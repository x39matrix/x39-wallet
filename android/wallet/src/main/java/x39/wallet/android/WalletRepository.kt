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
class ErrorBilletera(mensaje: String, causa: Throwable? = null) : Exception(mensaje, causa)

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
                if (ajustes.hayServidor()) {
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
            _vista.value = _vista.value.copy(sincronizando = false, ultimoError = mensaje(e))
            throw ErrorBilletera(mensaje(e), e)
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
        conRed { cm -> motor { estimarComision(ajustes.red, cm.url, bloques, cm.huella, b.altura()) } }
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
        if (ajustes.urlServidor().isEmpty()) throw ErrorBilletera("no hay servidor Electrum configurado: ponlo en Ajustes")
        val cm = nodo.conexionSana() ?: throw ErrorBilletera("el nodo no está disponible: ${nodo.estado.value.describir()}")
        abierta?.motor?.fijarHuellaServidor(cm.huella)
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

    private fun mensaje(e: Throwable): String = when (e) {
        is ErrorBilletera -> e.message ?: "error"
        is x39.wallet.Exception.Fondos -> "fondos insuficientes para el envío y la comisión"
        is x39.wallet.Exception.Direccion -> "dirección inválida o de otra red"
        is x39.wallet.Exception.RedServidor -> "ese servidor es de otra red: ${e.message}"
        is x39.wallet.Exception.Red -> mensajeAlturaSiAplica(e.message) ?: "sin respuesta del servidor: ${e.message}"
        is x39.wallet.Exception.EnvioEnCurso -> "hay un envío sin resolver; reintenta o cancela antes"
        is x39.wallet.Exception.Revision -> "la revisión no coincide con lo que se iba a firmar: ${e.message}"
        is x39.wallet.Exception.Pendiente -> "la revisión caducó; prepara el envío otra vez"
        is x39.wallet.Exception.Semilla -> "la semilla no es la de esta billetera"
        is x39.wallet.Exception.RedProhibida -> "red no permitida en esta edición"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
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

        /** ¿Hay una VPN activa en el aparato? (`TRANSPORT_VPN` en alguna red). */
        fun hayVpnActiva(ctx: Context): Boolean {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
            val red = cm.activeNetwork ?: return false
            return cm.getNetworkCapabilities(red)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }

        fun EstadoEnvio.texto(): String = when (this) {
            EstadoEnvio.FIRMADA -> "firmada, sin difundir"
            EstadoEnvio.DIFUNDIENDO -> "difundiendo"
            EstadoEnvio.ENVIADA -> "enviada"
            EstadoEnvio.FALLIDA -> "reemplazo perdido"
            EstadoEnvio.INCIERTA -> "difusión incierta"
            EstadoEnvio.CONFIRMADA -> "confirmada"
            EstadoEnvio.CANCELADA -> "cancelada"
        }
    }
}
