package x39.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import x39.wallet.EnvioInfo
import x39.wallet.EstadoEnvio
import x39.wallet.Movimiento
import x39.wallet.PsbtPendiente
import x39.wallet.Saldo
import x39.wallet.estimarComision
import x39.wallet.nuevaSemilla

/** Error con mensaje para la pantalla; envuelve el del motor sin perderlo. */
open class ErrorBilletera(mensaje: String, causa: Throwable? = null) : Exception(mensaje, causa)

/**
 * Una operación de red no se ha hecho porque el nodo no está sano AHORA (orden 8, H1).
 * `estadoNodo` es el texto que ya muestra la línea de estado del nodo ([EstadoNodo.describir]):
 * quien lo reciba puede comprobar con [WalletRepository.loMuestraElNodo] si repetirlo o no.
 */
class NodoNoDisponible(val estadoNodo: String) : ErrorBilletera("el nodo no está disponible: $estadoNodo")

/** Lo que las pantallas usan del repositorio; una implementación falsa sirve para probarlas. */
@RequiresApi(Build.VERSION_CODES.M)
interface Repositorio {
    val ajustes: Ajustes
    val almacen: AlmacenSemilla
    val nodo: NodeBridge
    val vista: StateFlow<WalletRepository.Vista>
    fun estado(): WalletRepository.Estado
    fun escaneoPendiente(): Boolean
    suspend fun semillaNueva(): SemillaNueva
    suspend fun crear(mnemonico: Mnemonico, restaurar: Boolean): Boolean
    suspend fun abrir()
    suspend fun coincideSemilla(mnemonico: Mnemonico): Boolean
    suspend fun sincronizar(completo: Boolean = false)
    suspend fun nuevaDireccion(): String
    suspend fun estimarComision(bloques: UInt = 2u): Double
    suspend fun prepararEnvio(destino: String, cantidadSat: ULong, satVb: Double, maxComisionSat: ULong): PsbtPendiente
    suspend fun prepararCancelacion(txid: String, satVb: Double): PsbtPendiente
    suspend fun firmar(id: String, mnemonico: Mnemonico): EnvioInfo
    suspend fun difundir(txid: String): EnvioInfo
    suspend fun cancelar(txid: String): EnvioInfo
    /**
     * Huella SPKI del certificado que presenta un servidor `ssl://`, SIN validarlo y sin hablar
     * Electrum: para mostrársela al usuario antes de que decida fijarla («certificado propio»).
     */
    suspend fun huellaServidor(electrum: String, proxySocks5: String?): String
}

/**
 * Acceso único a la billetera desde la app: toda la red en `Dispatchers.IO`, un mutex para
 * que haya UNA operación de envío a la vez, y un `StateFlow` con lo que pintan las pantallas.
 * Ninguna función recibe ni devuelve el mnemónico como texto: entra como [Mnemonico]
 * (ByteBuffer directo) y lo cierra quien lo creó.
 */
@RequiresApi(Build.VERSION_CODES.M) // Keystore con autenticación por operación
class WalletRepository(context: Context) : Repositorio {

    private val ctx = context.applicationContext
    override val ajustes = Ajustes(ctx)
    override val almacen: AlmacenSemilla by lazy {
        Redes.exigirAndroid() // API 23+
        AlmacenSemilla(ctx)
    }
    private val fichero = PerfilBilletera.actual.ficheroEstado(ctx.noBackupFilesDir)
    /** Marca persistida: restaurada sin escanear; sobrevive al cierre del proceso. */
    private val marcaEscaneo = File(fichero.path + ".escaneo-pendiente")
    private val ambito = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var abierta: BilleteraAbierta? = null
    /** El nodo del usuario: la billetera no sincroniza ni difunde si no está sano. */
    override val nodo: NodeBridge = NodeBridgeImpl(
        ajustes,
        ambito,
        hayVpnActiva = { hayVpnActiva(ctx) },
        // Nodo X-39: ANTES de conectar, esta detección (redes VPN del sistema con ruta a 10.39.0.3)
        // decide si se abre socket; sin túnel con certeza, no. Si falla la conexión, elige el texto.
        diagnosticoTunel = { diagnosticoTunel(ctx) },
        // H2: la aparición o desaparición de una red VPN despierta la vigilancia enseguida; la
        // decisión sigue siendo de la puerta y de diagnosticoTunel.
        senalRed = senalRedVpn(ctx),
        // Suelo de altura: la de la billetera abierta si es de la misma red; 0 si no la hay.
        // No toma mutexRed, mutexBilletera ni mutexEnvio: lee `abierta` (@Volatile), compara una
        // red inmutable y solo toma el mutex hoja de Billetera en Rust. Si la billetera se cierra
        // justo mientras NodeBridge consulta el suelo, la llamada puede lanzar y la comprobación
        // acaba en Caído (falla cerrado, dirección segura por decisión expresa).
        alturaSuelo = { red -> abierta?.takeIf { it.red == red }?.motor?.altura() ?: 0u },
    )
    private val mutexEnvio = Mutex()
    private val mutexBilletera = Mutex()
    /** Una operación de red a la vez: fijar la huella y usar la conexión son indivisibles. */
    private val mutexRed = Mutex()

    data class Vista(
        val saldo: Saldo? = null,
        val altura: UInt = 0u,
        val movimientos: List<Movimiento> = emptyList(),
        val envios: List<EnvioInfo> = emptyList(),
        val envioEnCurso: EnvioInfo? = null,
        val sincronizando: Boolean = false,
        val ultimoError: String? = null,
        /** Restaurada sin escaneo completo: no se da por libre ninguna moneda ni se envía. */
        val escaneoPendiente: Boolean = false,
    )

    private val _vista = MutableStateFlow(Vista())
    override val vista: StateFlow<Vista> = _vista

    enum class Estado { SIN_BILLETERA, LISTA, CLAVE_INVALIDADA }

    override fun escaneoPendiente(): Boolean = marcaEscaneo.exists()

    override fun estado(): Estado = when {
        !Redes.androidCompatible() -> throw ErrorBilletera("la billetera exige Android 6 o superior")
        !fichero.exists() -> Estado.SIN_BILLETERA
        almacen.estado() == AlmacenSemilla.Estado.CLAVE_INVALIDADA -> Estado.CLAVE_INVALIDADA
        almacen.estado() == AlmacenSemilla.Estado.SIN_SEMILLA -> Estado.CLAVE_INVALIDADA // hay estado pero no semilla cifrada
        else -> Estado.LISTA
    }

    fun ficheroEstado(): File = fichero

    /**
     * Semilla nueva del motor, obtenida de forma NO cancelable para que nunca quede una copia
     * sin dueño. Quien la recibe la muestra, la cifra, crea la billetera y la cierra.
     */
    override suspend fun semillaNueva(): SemillaNueva =
        withContext(NonCancellable) { withContext(Dispatchers.IO) { SemillaNueva(nuevaSemilla()) } }

    /**
     * Crea la billetera con un mnemónico (nuevo o restaurado). Si es restauración y hay
     * servidor, escanea con gap limit; devuelve `true` si escaneó. Una restaurada sin
     * escanear no debe dar por libre ninguna moneda hasta sincronizar en modo completo.
     */
    override suspend fun crear(mnemonico: Mnemonico, restaurar: Boolean): Boolean = withContext(Dispatchers.IO) {
        var escaneada = false
        mutexBilletera.withLock {
            Redes.exigir(ajustes.red)
            fichero.parentFile?.mkdirs()
            abierta?.close()
            abierta = motor { Billeteras.crear(mnemonico, ajustes.red, fichero) }
            if (restaurar) {
                // La marca va a disco ANTES de intentar escanear: si el proceso muere, al
                // reabrir sigue pendiente.
                marcaEscaneo.writeText("restaurada; escaneo completo pendiente\n")
                if (nodo.config != null) {
                    try {
                        conRed { cm -> motor { abierta!!.motor.escanear(cm.url) } }
                        marcaEscaneo.delete()
                        escaneada = true
                    } catch (_: ErrorBilletera) {
                        // sin nodo sano: queda pendiente
                    }
                }
            }
        }
        refrescar()
        escaneada
    }

    /** ¿Estas palabras son la semilla de esta billetera? Sin firmar ni guardar nada. */
    override suspend fun coincideSemilla(mnemonico: Mnemonico): Boolean = withContext(Dispatchers.IO) {
        mutexBilletera.withLock {
            if (abierta == null) abierta = motor { Billeteras.abrir(fichero, ajustes.red) }
            motor { mnemonico.usar { abierta!!.motor.coincideSemilla(it) } }
        }
    }

    /** Abre la billetera existente (sin semilla) y reanuda los envíos sin resolver. */
    override suspend fun abrir() = withContext(Dispatchers.IO) {
        mutexBilletera.withLock {
            if (abierta == null) {
                Redes.exigir(ajustes.red)
                abierta = motor { Billeteras.abrir(fichero, ajustes.red) }
            }
        }
        refrescarLocal()
    }

    fun cerrar() {
        abierta?.close()
        abierta = null
    }

    /**
     * Sincroniza. Con la marca de escaneo pendiente hace el escaneo COMPLETO y, si termina
     * bien, quita la marca.
     */
    override suspend fun sincronizar(completo: Boolean) = withContext(Dispatchers.IO) {
        _vista.value = _vista.value.copy(sincronizando = true, ultimoError = null)
        try {
            val b = billetera()
            val pendiente = marcaEscaneo.exists()
            conRed { cm -> motor { if (completo || pendiente) b.escanear(cm.url) else b.sincronizar(cm.url) } }
            if (pendiente) marcaEscaneo.delete()
            refrescarLocal()
        } catch (e: Exception) {
            // H1: si el nodo no está disponible y su línea de estado ya muestra el motivo, la
            // vista no lo repite en `ultimoError`; cualquier otro fallo se muestra una vez.
            _vista.value = _vista.value.copy(sincronizando = false, ultimoError = errorParaLaVista(e, nodo.estado.value))
            throw if (e is NodoNoDisponible) e else ErrorBilletera(mensaje(e), e)
        }
    }

    override suspend fun nuevaDireccion(): String = withContext(Dispatchers.IO) {
        motor { billetera().nuevaDireccion().direccion }
    }

    override suspend fun estimarComision(bloques: UInt): Double = withContext(Dispatchers.IO) {
        // Altura vista: la altura hasta la que la billetera abierta ya está sincronizada (persistida
        // en estado.dat, así es suelo entre sesiones; el motor añade la marca máxima dentro de la
        // sesión). Es la misma que usa el motor en sincronizar, escanear y difundir.
        // No se pasa un 0 fijo: sale de estado.dat y solo es 0 antes de la primera sincronización.
        val b = billetera()
        conRed { cm -> motor { estimarComision(ajustes.red, cm.url, bloques, cm.huella, b.altura(), cm.proxy) } }
    }

    override suspend fun huellaServidor(electrum: String, proxySocks5: String?): String = withContext(Dispatchers.IO) {
        motor { x39.wallet.huellaServidor(electrum, proxySocks5) }
    }

    /** Paso 1 de enviar: la revisión. Mientras se revisa, nadie más envía (mutex). */
    override suspend fun prepararEnvio(destino: String, cantidadSat: ULong, satVb: Double, maxComisionSat: ULong): PsbtPendiente =
        withContext(Dispatchers.IO) {
            if (marcaEscaneo.exists()) throw ErrorBilletera("falta el escaneo completo tras restaurar: configura el servidor y sincroniza antes de enviar")
            mutexEnvio.withLock { motor { billetera().prepararPsbt(destino, cantidadSat, satVb, maxComisionSat) } }
        }

    override suspend fun prepararCancelacion(txid: String, satVb: Double): PsbtPendiente = withContext(Dispatchers.IO) {
        mutexEnvio.withLock { motor { billetera().prepararCancelacion(txid, satVb) } }
    }

    /** Paso 2: firma con el mnemónico descifrado (ByteBuffer directo). No lo cierra: lo hace quien lo abrió. */
    override suspend fun firmar(id: String, mnemonico: Mnemonico): EnvioInfo = withContext(Dispatchers.IO) {
        mutexEnvio.withLock { motor { mnemonico.usar { billetera().firmarYExtraer(id, it) } } }.also { refrescarLocal() }
    }

    /** Difunde o reintenta LA MISMA transacción. */
    override suspend fun difundir(txid: String): EnvioInfo = withContext(Dispatchers.IO) {
        mutexEnvio.withLock { conRed { cm -> motor { billetera().difundir(cm.url, txid) } } }.also { refrescarLocal() }
    }

    /** Cancela liberando monedas (solo firmada y nunca difundida). */
    override suspend fun cancelar(txid: String): EnvioInfo = withContext(Dispatchers.IO) {
        mutexEnvio.withLock { motor { billetera().cancelar(txid) } }.also { refrescarLocal() }
    }

    suspend fun envios(): List<EnvioInfo> = withContext(Dispatchers.IO) { motor { billetera().envios() } }

    private fun billetera() = abierta?.motor ?: throw ErrorBilletera("la billetera no está abierta")

    /**
     * Toda operación de red pasa por aquí: comprueba el nodo AHORA (huella/TLS, génesis,
     * protocolo, altura) y, solo si está sano, entrega URL y huella de la MISMA generación de
     * configuración; fija la huella en el motor y ejecuta la operación bajo un mutex, de modo
     * que ninguna otra operación pueda cambiar la huella entre medias.
     */
    private suspend fun <T> conRed(f: (ConexionMotor) -> T): T = mutexRed.withLock {
        if (nodo.config == null) throw ErrorBilletera("no hay nodo configurado: elígelo en Ajustes → Nodo")
        val cm = nodo.conexionSana() ?: throw NodoNoDisponible(nodo.estado.value.describir())
        // Huella y proxy de la MISMA generación que la URL; nada puede cambiarlos entre medias.
        abierta?.motor?.let { it.fijarHuellaServidor(cm.huella); it.fijarProxySocks5(cm.proxy) }
        f(cm)
    }



    private suspend fun refrescar() = withContext(Dispatchers.IO) { refrescarLocal() }

    private fun refrescarLocal() {
        val b = abierta?.motor ?: return
        _vista.value = Vista(
            saldo = b.saldo(),
            altura = b.altura(),
            movimientos = b.movimientos(),
            envios = b.envios(),
            envioEnCurso = b.envioEnCurso(),
            sincronizando = false,
            ultimoError = null,
            escaneoPendiente = marcaEscaneo.exists(),
        )
    }

    /** Traduce las excepciones del motor a un texto para la pantalla. */
    private inline fun <T> motor(f: () -> T): T = try {
        f()
    } catch (e: x39.wallet.Exception) {
        throw ErrorBilletera(mensaje(e), e)
    } catch (e: IllegalStateException) {
        throw ErrorBilletera(e.message ?: "error", e)
    }

    companion object {
        /** Traduce las excepciones del motor a un texto para la pantalla. */
        internal fun mensaje(e: Throwable): String = when (e) {
            is ErrorBilletera -> e.message ?: "error"
            is x39.wallet.Exception.Fondos -> "fondos insuficientes para el envío y la comisión"
            is x39.wallet.Exception.Direccion -> "dirección inválida o de otra red"
            is x39.wallet.Exception.RedServidor -> "ese servidor es de otra red: ${e.message}"
            is x39.wallet.Exception.Red -> mensajeAlturaSiAplica(e.message) ?: "sin respuesta del servidor: ${mensajeLlano(e.message ?: "")}"
            is x39.wallet.Exception.EnvioEnCurso -> "hay un envío sin resolver; reintenta o cancela antes"
            is x39.wallet.Exception.Revision -> "la revisión no coincide con lo que se iba a firmar: ${e.message}"
            is x39.wallet.Exception.Pendiente -> "la revisión caducó; prepara el envío otra vez"
            is x39.wallet.Exception.Semilla -> "la semilla no es la de esta billetera"
            is x39.wallet.Exception.RedProhibida -> "red no permitida en esta edición"
            else -> e.message ?: e.javaClass.simpleName
        }

        /**
         * H1: ¿el fallo `e` es «el nodo no está disponible» y su motivo es exactamente el que ya
         * muestra la línea de estado del nodo (`nodo`)? Entonces la pantalla no lo repite (ni en
         * `ultimoError` ni en un toast). Función pura, probada en JVM.
         */
        fun loMuestraElNodo(e: Throwable, nodo: EstadoNodo): Boolean =
            e is NodoNoDisponible && e.estadoNodo == nodo.describir()

        /** H1: texto para `Vista.ultimoError` tras un fallo, o `null` si la línea del nodo ya lo dice. */
        internal fun errorParaLaVista(e: Throwable, nodo: EstadoNodo): String? =
            if (loMuestraElNodo(e, nodo)) null else mensaje(e)

        // Guarda el applicationContext, no una Activity: no hay fuga.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instancia: WalletRepository? = null

        @Volatile private var paraPruebas: Repositorio? = null

        /** Una sola instancia por proceso: una sola billetera abierta por fichero. */
        fun de(context: Context): Repositorio = paraPruebas ?: instancia ?: synchronized(this) {
            instancia ?: WalletRepository(context).also { instancia = it }
        }

        /** Solo pruebas: sustituye el repositorio que ven las pantallas. */
        fun fijarParaPruebas(r: Repositorio?) {
            paraPruebas = r
        }

        /**
         * Detección real del X39 Túnel (antes de conectar en modo X-39 y, si la conexión falla,
         * para elegir el texto): recorre TODAS las redes del sistema (no solo la activa: con rutas
         * parciales la VPN no es la red por defecto) y busca una con transporte VPN cuyas rutas
         * cubran 10.39.0.3 (vale una ruta 10.39.0.0/24 o una ruta por defecto 0.0.0.0/0 por la VPN;
         * comparación de prefijo por bits en [NodoX39.rutaCubreNodo], nunca por texto) o con una
         * dirección en 10.39.0.0/24. La pista de la app ([NodoX39.tunelActivo]) solo se anota en el
         * detalle. Sin DNS, sin sockets, sin `InetAddress`.
         */
        @Suppress("DEPRECATION") // allNetworks: la alternativa (NetworkCallback) exige registro asíncrono
        fun diagnosticoTunel(ctx: Context): DiagnosticoTunel {
            val pista = try { NodoX39.tunelActivo?.invoke() } catch (_: Exception) { null }
            return try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    ?: return DiagnosticoTunel(EstadoTunel.DESCONOCIDO, "sin ConnectivityManager; túnel de la app: $pista")
                var vpns = 0
                var conRuta = 0
                for (red in cm.allNetworks) {
                    val cap = cm.getNetworkCapabilities(red) ?: continue
                    if (!cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                    vpns++
                    val lp = cm.getLinkProperties(red) ?: continue
                    val porRuta = lp.routes.any { r -> NodoX39.rutaCubreNodo(r.destination.address.address, r.destination.prefixLength) }
                    val porDireccion = lp.linkAddresses.any { la -> NodoX39.direccionEnRedTunel(la.address.address) }
                    if (porRuta || porDireccion) conRuta++
                }
                if (conRuta > 0) DiagnosticoTunel(EstadoTunel.CON_RUTA, "redes VPN: $vpns, con ruta a 10.39.0.0/24: $conRuta; túnel de la app: $pista")
                else DiagnosticoTunel(EstadoTunel.SIN_TUNEL, "redes VPN: $vpns, ninguna con ruta a 10.39.0.0/24; túnel de la app: $pista")
            } catch (e: Exception) {
                DiagnosticoTunel(EstadoTunel.DESCONOCIDO, "fallo al consultar las redes: ${e.javaClass.simpleName}; túnel de la app: $pista")
            }
        }

        /**
         * Señal de cambio de red para la vigilancia (orden 8, H2): un `NetworkCallback` del sistema
         * para las redes con transporte VPN (aparece, cambian sus rutas o direcciones, desaparece).
         * Solo DESPIERTA a `vigilar`: la decisión de abrir socket sigue siendo de la puerta y de
         * [diagnosticoTunel]. El callback se registra al activar la vigilancia y se retira al
         * desactivarla con la función devuelta. Sin `ConnectivityManager` no hay señal (y no pasa
         * nada); si el registro lanza (permiso, demasiados callbacks…), la excepción sube y
         * NodeBridge sigue con la espera creciente. Nunca abre red ni resuelve nombres.
         */
        fun senalRedVpn(ctx: Context): SenalRed = object : SenalRed {
            override fun escuchar(alCambiar: () -> Unit): () -> Unit {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    ?: return { }
                val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) = alCambiar()
                    override fun onLinkPropertiesChanged(network: android.net.Network, linkProperties: android.net.LinkProperties) = alCambiar()
                    override fun onLost(network: android.net.Network) = alCambiar()
                }
                val peticion = android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
                    // Una petición lleva NOT_VPN por defecto: sin quitarla, nunca llegaría una VPN.
                    .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                cm.registerNetworkCallback(peticion, cb)
                return { try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) { } }
            }
        }

        /** ¿Hay una VPN activa en el aparato? (`TRANSPORT_VPN` en la red activa; para el modo EXTERNO). */
        fun hayVpnActiva(ctx: Context): Boolean {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
            val red = cm.activeNetwork ?: return false
            return cm.getNetworkCapabilities(red)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }

        /** Texto de pantalla del estado de un envío, en el idioma de la app. */
        fun EstadoEnvio.texto(c: Context): String = c.getString(
            when (this) {
                EstadoEnvio.FIRMADA -> x39.wallet.R.string.x39w_envio_firmada
                EstadoEnvio.DIFUNDIENDO -> x39.wallet.R.string.x39w_envio_difundiendo
                EstadoEnvio.ENVIADA -> x39.wallet.R.string.x39w_envio_enviada
                EstadoEnvio.FALLIDA -> x39.wallet.R.string.x39w_envio_fallida
                EstadoEnvio.INCIERTA -> x39.wallet.R.string.x39w_envio_incierta
                EstadoEnvio.CONFIRMADA -> x39.wallet.R.string.x39w_envio_confirmada
                EstadoEnvio.CANCELADA -> x39.wallet.R.string.x39w_envio_cancelada
            },
        )
    }
}
