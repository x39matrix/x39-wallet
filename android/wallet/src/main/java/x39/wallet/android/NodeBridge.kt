package x39.wallet.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import x39.wallet.NodoInfo
import x39.wallet.Red
import x39.wallet.comprobarNodo

/** Cómo se llega al nodo. Android solo permite una VPN activa: ver docs/nodebridge.md §6. */
enum class ModoTunel {
    /** El nodo es alcanzable directamente (red local, TLS público…). */
    NINGUNO,
    /**
     * Hay una VPN ya activa en el aparato (p. ej. la de la app). NodeBridge no la toca y EXIGE que
     * esté activa; Android permite rutas por destino, así que detectarla NO demuestra que el
     * tráfico hacia ESE nodo viaje por ella.
     */
    EXTERNO,
    /** Perfil WireGuard propio de NodeBridge (E-3: solo definido, no implementado). */
    PROPIO,
    /**
     * El X39 Túnel (la VPN WireGuard de la propia app). Solo lo lleva la configuración del «Nodo
     * X-39» ([NodoX39.config]): antes de conectar se consulta la detección real del túnel
     * ([DetectorTunel]); sin túnel no se abre socket ni se conecta por ninguna otra vía.
     */
    X39,
}

/**
 * Diagnóstico del X39 Túnel por la detección REAL de redes del sistema (redes con transporte VPN
 * cuyas rutas cubran 10.39.0.3), nunca por el estado en memoria del túnel de la app. Desde la
 * orden 7 se consulta ANTES de conectar en modo X-39: [SIN_TUNEL] con certeza → no se abre
 * ningún socket; [CON_RUTA] o [DESCONOCIDO] → se intenta (nunca se bloquea por una duda) y, si
 * la conexión falla, se vuelve a consultar para elegir el texto.
 */
enum class EstadoTunel {
    /** Hay una red VPN del sistema con ruta a 10.39.0.3 (o dirección en 10.39.0.0/24). */
    CON_RUTA,
    /** Se consultaron las redes y NO hay ninguna VPN con ruta al nodo: el túnel está apagado. */
    SIN_TUNEL,
    /** No se pudo consultar las redes del aparato (sin ConnectivityManager, excepción…). */
    DESCONOCIDO,
}

/** Resultado del diagnóstico: el estado y un detalle para el registro (sin direcciones de billetera). */
data class DiagnosticoTunel(val estado: EstadoTunel, val detalle: String)

/** Detección del túnel, inyectable (la real recorre las redes del sistema; en las pruebas, un doble). */
fun interface DetectorTunel {
    fun diagnosticar(): DiagnosticoTunel
}

/**
 * Punto de conexión con el nodo, inyectable: el real es `comprobarNodo` del motor (la firma del
 * motor no cambia); en las pruebas, un doble que cuenta intentos y anota el destino sin abrir red.
 */
fun interface ConexionNodo {
    fun comprobar(c: ConfigNodo, alturaMin: UInt): NodoInfo
}

/**
 * Señal de cambio de red (orden 8, H2): aparece o desaparece una red VPN, o cambian sus rutas.
 * `escuchar` registra `alCambiar` y devuelve la función para dejar de escuchar. La señal solo
 * DESPIERTA a la vigilancia ([NodeBridge.vigilar]); la decisión de abrir socket o no sigue siendo
 * de la puerta y del diagnóstico del túnel ([DetectorTunel]), la única fuente de verdad. La real
 * es un `ConnectivityManager.NetworkCallback` de transporte VPN; en las pruebas, un doble. Si
 * `escuchar` lanza (permiso, demasiados callbacks…), la vigilancia sigue con la espera creciente.
 */
fun interface SenalRed {
    fun escuchar(alCambiar: () -> Unit): () -> Unit
}

/**
 * Configuración del nodo con el que sincroniza la billetera: el nodo X-39 por el túnel
 * ([NodoX39.config], predeterminado) o el nodo del usuario («Mi nodo»).
 */
data class ConfigNodo(
    val red: Red,
    /** `ssl://host:puerto` o `tcp://host:puerto`; el host puede ser un servicio `.onion`. */
    val electrum: String,
    /**
     * Huella SPKI (SHA-256, hex) del certificado, solo con `ssl://`. Con huella la app acepta
     * únicamente ese certificado («certificado propio»); sin ella el motor valida el certificado
     * contra las autoridades públicas reconocidas, como cualquier cliente TLS.
     */
    val huellaServidor: String?,
    val tunel: ModoTunel,
    val nombre: String,
    /**
     * Proxy SOCKS5 `IP:puerto` (Tor/Orbot, por defecto `127.0.0.1:9050`) por el que va TODA la
     * conexión; obligatorio con `.onion`. Solo una IP literal: un nombre obligaría a resolverlo en
     * este aparato (DNS) antes de llegar al proxy, y eso es justo lo que el proxy evita.
     */
    val proxySocks5: String? = null,
) {
    fun cifrado(): Boolean = electrum.startsWith("ssl://")
    fun esPublicoDeSignet(): Boolean = red == Red.SIGNET && electrum == Redes.SERVIDOR_PUBLICO_SIGNET

    /** `host` sin esquema ni puerto (`[IPv6]` con sus corchetes). */
    fun host(): String {
        val sinEsquema = electrum.substringAfter("://")
        return if (sinEsquema.startsWith("[")) sinEsquema.substringBefore("]") + "]" else sinEsquema.substringBeforeLast(':')
    }

    /** Servicio oculto de Tor: solo se alcanza por un proxy SOCKS5. */
    fun esOnion(): Boolean = host().lowercase().endsWith(".onion")

    /** Reglas de la configuración; `null` si es válida, si no el motivo. */
    fun invalida(): String? = when {
        electrum.isBlank() -> "sin servidor Electrum"
        !electrum.startsWith("ssl://") && !electrum.startsWith("tcp://") -> "el servidor debe empezar por ssl:// o tcp://"
        host().isBlank() || !electrum.substringAfterLast(':').all { it.isDigit() } || electrum.substringAfterLast(':').toIntOrNull() !in 1..65535 -> "el servidor debe ser host:puerto (puerto entre 1 y 65535)"
        !cifrado() && !huellaServidor.isNullOrBlank() -> "la huella solo se comprueba con ssl://"
        esOnion() && proxySocks5.isNullOrBlank() -> "un servidor .onion solo se alcanza por un proxy SOCKS5 (Orbot): activa el proxy"
        proxySocks5 != null && !proxyBienFormado(proxySocks5) -> "el proxy SOCKS5 debe ser IP:puerto (por ejemplo 127.0.0.1:9050), no un nombre: un nombre habría que resolverlo en este aparato"
        tunel == ModoTunel.PROPIO -> "el túnel propio todavía no está disponible"
        else -> null
    }

    companion object {
        private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
        private val GRUPO_IPV6 = Regex("^[0-9a-fA-F]{1,4}$")

        /** IPv4 literal `A.B.C.D` (cada octeto 0–255). */
        fun esIpv4Literal(h: String): Boolean = IPV4.matches(h)

        /**
         * IPv6 literal SIN corchetes: grupos hexadecimales de 1 a 4 cifras separados por `:`, con
         * `::` como mucho una vez y, opcionalmente, una IPv4 incrustada al final. Basta para
         * distinguir una dirección de un nombre (que nunca lleva dos `:`); la validez exacta la
         * comprueba el sistema al conectar.
         */
        fun esIpv6Literal(h: String): Boolean {
            if (h.length !in 2..45 || h.count { it == ':' } < 2 || h.contains(":::") || h.split("::").size > 2) return false
            val grupos = h.split(':')
            return grupos.withIndex().all { (i, g) -> g.isEmpty() || GRUPO_IPV6.matches(g) || (i == grupos.lastIndex && IPV4.matches(g)) }
        }

        /**
         * `IP:puerto` o `[IPv6]:puerto`, con puerto entre 1 y 65535 y la dirección como IP literal.
         * Un nombre (`localhost`, `orbot.local`…) NO vale: resolverlo sería una consulta DNS desde
         * este aparato, antes de que nada pase por el proxy.
         */
        fun proxyBienFormado(p: String): Boolean {
            val s = p.trim()
            val puerto = s.substringAfterLast(':', "").toIntOrNull() ?: return false
            if (puerto !in 1..65535) return false
            return if (s.startsWith("[")) {
                val h = s.substringAfter("[").substringBefore("]", "")
                h.isNotEmpty() && s.indexOf("]:") == h.length + 1 && s.indexOf(':', h.length + 3) == -1 && esIpv6Literal(h)
            } else {
                esIpv4Literal(s.substringBeforeLast(':', ""))
            }
        }
    }
}

/** Lo que la billetera necesita para una operación de red, atado a UNA generación de configuración. */
data class ConexionMotor(val url: String, val huella: String?, val generacion: Int, val proxy: String? = null)

sealed class EstadoNodo {
    object SinConfigurar : EstadoNodo()
    object Conectando : EstadoNodo()
    data class Sano(val info: NodoInfo, val sinCifrar: Boolean) : EstadoNodo()
    data class Caido(val desdeHaceS: Long, val ultimoError: String, val proximoIntentoEnS: Long) : EstadoNodo()
    data class RedIncorrecta(val esperada: Red, val detalle: String) : EstadoNodo()
    data class HuellaIncorrecta(val detalle: String) : EstadoNodo()

    val sano: Boolean get() = this is Sano
}

/** Texto de pantalla de un estado del nodo. */
fun EstadoNodo.describir(): String = when (this) {
    is EstadoNodo.SinConfigurar -> "sin configurar"
    is EstadoNodo.Conectando -> "conectando"
    is EstadoNodo.Sano -> "sano"
    is EstadoNodo.Caido -> ultimoError
    is EstadoNodo.RedIncorrecta -> "es de otra red: ${detalle}"
    is EstadoNodo.HuellaIncorrecta -> "la huella del certificado no es la esperada: ${detalle}"
}

/** Dónde vive la configuración del nodo (Ajustes en la app; memoria en las pruebas). */
interface AlmacenConfigNodo {
    fun leer(): ConfigNodo?
    /**
     * Guarda la configuración y, si viene, los metadatos del paquete de emparejamiento (datos
     * públicos del túnel, instante de emparejamiento) en la MISMA escritura: o se guardan los dos
     * o ninguno. Con `paquete == null` los metadatos existentes no se tocan.
     */
    fun guardar(c: ConfigNodo, paquete: PaqueteEmparejamiento? = null)
    /** Borra configuración y metadatos del paquete (revocación local). */
    fun borrar()
}

/** Puente con el nodo del usuario (ver docs/nodebridge.md). */
interface NodeBridge {
    val estado: StateFlow<EstadoNodo>
    val config: ConfigNodo?
    suspend fun configurar(config: ConfigNodo): EstadoNodo
    suspend fun olvidar()
    suspend fun comprobar(): EstadoNodo
    /** Comprueba una configuración SIN guardarla ni publicarla, con las mismas reglas (VPN incluida). */
    suspend fun probar(config: ConfigNodo): EstadoNodo
    /**
     * Emparejamiento: comprueba `config` y SOLO si da `Sano` la guarda (junto con los metadatos
     * del `paquete`, en una sola escritura) y la publica. Si no, devuelve el estado con el motivo y
     * no deja rastro (ni en disco ni en `estado`). Es trabajo cancelable atado a la generación: la
     * comprobación corre fuera del cerrojo, pero comprobar la generación y la cancelación y guardar
     * ocurren DENTRO del mismo cerrojo que `configurar`/`olvidar`, así que un olvido o un cambio
     * intermedios no pueden quedar pisados por un guardado tardío. El resultado del candidato no
     * toca el estado del nodo vigente hasta adoptarlo.
     */
    suspend fun adoptarSiSano(config: ConfigNodo, paquete: PaqueteEmparejamiento? = null): EstadoNodo
    fun vigilar(activo: Boolean)
    /**
     * Comprueba el nodo AHORA y, solo si está sano, devuelve URL y huella de la MISMA
     * generación de configuración (si entretanto se configuró u olvidó otra, `null`).
     */
    suspend fun conexionSana(): ConexionMotor?
}

/** Texto exacto que emite el motor (`rust/src/nodo.rs`, puerta de la altura). */
private val REGEX_ALTURA_ATRASADA =
    Regex("""el servidor va por detrás: altura (\d+), ya se había visto (\d+)""")

private val REGEX_OS_ERROR = Regex("""\s*\(os error \d+\)""")
private val REGEX_URL = Regex("""(tcp|ssl)://\S+""")
private val REGEX_IP_PUERTO = Regex("""\b\d{1,3}(\.\d{1,3}){3}(:\d{1,5})?\b""")
private val REGEX_IPV6_PUERTO = Regex("""\[[0-9a-fA-F:.]+](:\d{1,5})?""")
private val REGEX_PUERTO_TRAS_MARCA = Regex("""<servidor>:\d{1,5}""")
private val REGEX_ONION = Regex("""[A-Za-z2-7.-]+\.onion(:\d{1,5})?""", RegexOption.IGNORE_CASE)

/**
 * Texto apto para el registro (logcat): sin la URL, el host ni el proxy de la configuración y sin
 * ninguna dirección IP, `[IPv6]` ni `.onion` que quede en el mensaje. El nodo X-39 se escribe
 * igual que cualquier otro: el registro nunca lleva direcciones.
 */
internal fun sinDirecciones(msg: String, c: ConfigNodo): String {
    var t = msg
    if (c.electrum.isNotBlank()) t = t.replace(c.electrum, "<servidor>")
    val host = c.host()
    if (host.isNotBlank()) t = t.replace(host, "<servidor>")
    c.proxySocks5?.takeIf { it.isNotBlank() }?.let { t = t.replace(it.trim(), "<proxy>") }
    return t.replace(REGEX_URL, "<servidor>")
        .replace(REGEX_ONION, "<servidor>")
        .replace(REGEX_IPV6_PUERTO, "<ip>")
        .replace(REGEX_IP_PUERTO, "<ip>")
        .replace(REGEX_PUERTO_TRAS_MARCA, "<servidor>") // el puerto que quede pegado al host sustituido
}

/**
 * Error de red del motor o del sistema en lenguaje llano para la pantalla. Quita el «(os error N)»
 * y antepone una explicación; conserva el texto original entre paréntesis para poder buscarlo.
 */
internal fun mensajeLlano(msg: String): String {
    val limpio = msg.replace(REGEX_OS_ERROR, "").trim()
    val b = limpio.lowercase()
    val explicacion = when {
        "proxy socks5" in b -> return limpio
        "connection refused" in b -> "nadie responde en esa dirección y puerto"
        "timed out" in b || "timeout" in b -> "sin respuesta a tiempo: comprueba la dirección, el túnel o el proxy"
        "resolviendo" in b || "failed to lookup" in b || "name or service not known" in b || "no address associated" in b -> "no se encuentra ese nombre de servidor"
        "network is unreachable" in b || "no route to host" in b || "host is unreachable" in b -> "red inalcanzable: sin conexión, o el túnel no está activo"
        "connection reset" in b || "broken pipe" in b || "unexpected eof" in b -> "el servidor cortó la conexión: ¿seguro que ese puerto habla Electrum?"
        "unknownissuer" in b || "invalid peer certificate" in b || "certificate" in b -> "el certificado del servidor no lo reconoce ninguna autoridad: si es tu propio certificado, activa «certificado propio»"
        "tls:" in b -> "fallo del cifrado TLS: ¿seguro que ese puerto habla ssl://?"
        else -> return limpio
    }
    return "$explicacion ($limpio)"
}

/**
 * Si el error es el rechazo del motor por altura, lo traduce al texto de la app; si no, `null`.
 * Es el único sitio donde la app depende de la cadena del motor: la prueba instrumentada la
 * ejercita contra la biblioteca nativa real.
 */
internal fun mensajeAlturaSiAplica(msg: String?): String? {
    if (msg == null) return null
    val m = REGEX_ALTURA_ATRASADA.find(msg) ?: return null
    val (remota, exigida) = m.destructured
    return "ese nodo va por detrás de lo ya visto: bloque $remota frente a $exigida"
}

/**
 * Implementación con el motor Rust (`comprobarNodo`): las cuatro puertas de B4 en el orden del
 * motor (huella/TLS, protocolo 1.4 y latencia por `server.version` como primer mensaje,
 * génesis, altura), reconexión con espera creciente y azar, modo de túnel externo (solo
 * comprueba que hay una VPN activa y que el nodo responde a través de ella). La altura mínima
 * exigida es la mayor entre el suelo persistido de la billetera (`alturaSuelo`, por red) y la
 * marca máxima vista en esta sesión (`alturaVista`). Toda la red en `Dispatchers.IO`.
 */
@RequiresApi(Build.VERSION_CODES.M)
class NodeBridgeImpl(
    private val ajustes: AlmacenConfigNodo,
    private val ambito: CoroutineScope,
    /** ¿Hay una VPN activa en el aparato? Inyectable para las pruebas. */
    private val hayVpnActiva: () -> Boolean,
    /** Espera entre reintentos; inyectable para que las pruebas no esperen de verdad. */
    private val esperar: suspend (ms: Long) -> Unit = { delay(it) },
    private val comprobador: ConexionNodo = ConexionNodo { c, alturaMin -> comprobarNodo(c.red, c.electrum, c.huellaServidor, alturaMin, c.proxySocks5) },
    /** Despachador para la red; `Dispatchers.IO` en la app, `Unconfined` en las pruebas JVM. */
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    /**
     * Suelo de altura por red: la altura hasta la que la billetera abierta ya está sincronizada
     * (persistida en estado.dat), o 0 si no hay billetera abierta de esa red. Un nodo por
     * debajo del suelo se rechaza aunque sea la primera sonda de la sesión.
     */
    private val alturaSuelo: (Red) -> UInt = { 0u },
    /**
     * Detección del X39 Túnel. En modo X-39 se consulta ANTES de conectar: si dice con certeza
     * `SIN_TUNEL`, no se abre ningún socket; si no, se intenta la conexión a `NodoX39.servidor(red)`
     * (y a ninguna otra dirección) y, si falla, se vuelve a consultar para elegir el texto. Por
     * defecto (sin detector real inyectado) no es concluyente: `DESCONOCIDO`, y se intenta.
     */
    private val diagnosticoTunel: DetectorTunel = DetectorTunel {
        DiagnosticoTunel(EstadoTunel.DESCONOCIDO, "sin detector de túnel inyectado")
    },
    /**
     * Registro (logcat, etiqueta `x39.nodo`): resultado del diagnóstico del túnel y motivo del
     * fallo de conexión, SIN direcciones de billetera ni del servidor del usuario ([sinDirecciones]).
     */
    private val registrar: (String) -> Unit = {
        // En las pruebas JVM android.util.Log es un esbozo que lanza; en el aparato nunca lanza.
        try { android.util.Log.i(ETIQUETA_REGISTRO, it) } catch (_: RuntimeException) { }
    },
    /**
     * Señal de cambio de red (H2): se escucha solo mientras la vigilancia está activa y despierta
     * la comprobación sin esperar la espera creciente. Por defecto no hay señal (nada cambia).
     */
    private val senalRed: SenalRed = SenalRed { { } },
    /** Rebote tras una señal de red: las que lleguen en ráfaga cuentan como una sola. */
    private val reboteMs: Long = REBOTE_MS,
) : NodeBridge {

    companion object {
        const val ETIQUETA_REGISTRO = "x39.nodo"
        const val REBOTE_MS = 500L
    }

    /**
     * Configuración ACTIVA, en memoria: solo cambia cuando el almacén ha persistido de verdad
     * (`guardar` ha vuelto sin lanzar, es decir, `commit() == true`). `comprobar()` y
     * `conexionSana()` leen esta variable y no una lectura fresca de las preferencias, porque
     * SharedPreferences cambia primero su memoria y luego el disco: si el disco falla, su memoria
     * se queda con lo nuevo y no se revierte sola.
     */
    @Volatile private var activa: ConfigNodo? = ajustes.leer()
    private val _estado = MutableStateFlow<EstadoNodo>(if (activa == null) EstadoNodo.SinConfigurar else EstadoNodo.Conectando)
    override val estado: StateFlow<EstadoNodo> = _estado
    override val config: ConfigNodo? get() = activa

    private var vigilancia: Job? = null
    /** Despertador de la vigilancia (H2): como mucho UNA señal de red pendiente (canal conflado). */
    private val despertador = Channel<Unit>(Channel.CONFLATED)
    /** Cómo dejar de escuchar la señal de red; solo mientras la vigilancia está activa. */
    private var dejarDeEscuchar: (() -> Unit)? = null
    private var caidoDesde: Long = 0
    private var alturaVista: UInt = 0u
    /** Generación de la configuración: cambia con cada configurar/olvidar; descarta resultados viejos. */
    private val generacion = java.util.concurrent.atomic.AtomicInteger(0)
    /** Generación a la que pertenece el último estado Sano publicado. */
    @Volatile private var generacionSano: Int = -1

    /** Esperas de la reconexión: 2, 4, 8… s hasta 5 min, con hasta un 20 % de azar. */
    internal fun esperaMs(intento: Int): Long {
        val base = (2_000L shl minOf(intento, 8)).coerceAtMost(300_000L)
        val azar = (base * Random.nextDouble(0.0, 0.2)).toLong()
        return (base + azar).coerceAtMost(300_000L)
    }

    /**
     * Cerrojo de la configuración vigente: configurar, olvidar, publicar un resultado y adoptar un
     * candidato son indivisibles entre sí. Las comprobaciones de red (lentas) corren fuera.
     */
    private val cerrojo = Mutex()

    override suspend fun configurar(config: ConfigNodo): EstadoNodo = withContext(io) {
        config.invalida()?.let { return@withContext EstadoNodo.Caido(0, it, 0).also { e -> _estado.value = e } }
        val suelo = alturaSuelo(config.red)
        cerrojo.withLock {
            try {
                ajustes.guardar(config)
            } catch (ex: Exception) {
                // No persistido: la activa sigue siendo la anterior (el almacén restaura la suya).
                return@withContext EstadoNodo.Caido(0, "no se pudo guardar la configuración del nodo: ${ex.message ?: ex.javaClass.simpleName}", 0).also { e -> _estado.value = e }
            }
            activa = config
            generacion.incrementAndGet()
            alturaVista = maxOf(suelo, alturaSuelo(config.red))
            caidoDesde = 0
        }
        comprobar()
    }

    override suspend fun olvidar() {
        vigilar(false)
        cerrojo.withLock {
            ajustes.borrar()
            // Lo que quede en el almacén tras borrar (en la app, nada: «Mi nodo» vacío).
            activa = ajustes.leer()
            generacion.incrementAndGet()
            generacionSano = -1
            _estado.value = if (activa == null) EstadoNodo.SinConfigurar else EstadoNodo.Conectando
        }
    }

    override suspend fun probar(config: ConfigNodo): EstadoNodo = withContext(io) {
        // Un candidato se comprueba sin el historial de la sesión, pero con el suelo persistido de
        // la billetera de su red; no toca nada del vigente.
        comprobarUnaVez(config, alturaSuelo(config.red))
    }

    override suspend fun adoptarSiSano(config: ConfigNodo, paquete: PaqueteEmparejamiento?): EstadoNodo = withContext(io) {
        val gen = generacion.get()
        val suelo = alturaSuelo(config.red)
        // Comprobación lenta FUERA del cerrojo; su resultado es del candidato, no del vigente.
        val e = comprobarUnaVez(config, suelo)
        if (e !is EstadoNodo.Sano) return@withContext e
        cerrojo.withLock {
            // Dentro del cerrojo: descartado (cancelación) o cambiado (otro configurar/olvidar
            // entretanto) → no se guarda ni se publica nada, aunque el resultado sea Sano.
            ensureActive()
            if (gen != generacion.get()) {
                return@withLock EstadoNodo.Caido(0, "la configuración del nodo cambió durante la comprobación: no se guarda", 0)
            }
            val sueloActual = maxOf(suelo, alturaSuelo(config.red))
            if (e.info.altura < sueloActual) {
                return@withLock EstadoNodo.Caido(
                    0,
                    "ese nodo va por detrás de lo ya visto: bloque ${e.info.altura} frente a $sueloActual",
                    0,
                )
            }
            try {
                ajustes.guardar(config, paquete)
            } catch (ex: Exception) {
                // El almacén no pudo persistir (commit() false u otro fallo): nada a medias, no se
                // publica Sano ni se cambia la generación; la pantalla no anuncia la adopción.
                return@withLock EstadoNodo.Caido(0, "no se pudo guardar la configuración del nodo: ${ex.message ?: ex.javaClass.simpleName}", 0)
            }
            activa = config
            val nueva = generacion.incrementAndGet()
            alturaVista = maxOf(sueloActual, e.info.altura)
            caidoDesde = 0
            generacionSano = nueva
            _estado.value = e
            e
        }
    }

    override suspend fun comprobar(): EstadoNodo = withContext(io) {
        val (gen, c, vista) = cerrojo.withLock { Triple(generacion.get(), activa, alturaVista) }
        if (c == null) return@withContext EstadoNodo.SinConfigurar.also { _estado.value = it }
        val alturaMin = maxOf(vista, alturaSuelo(c.red))
        // G1: con el túnel apagado (certeza) el resultado es inmediato, sin pasar por «conectando…»
        // y sin abrir ningún socket. Con duda o con ruta, se conecta como siempre.
        val puerta = if (c.invalida() == null) puertaTunel(c) else null
        val e = puerta ?: run {
            _estado.value = EstadoNodo.Conectando
            comprobarUnaVez(c, alturaMin, puertaHecha = true)
        }
        cerrojo.withLock {
            if (gen != generacion.get()) {
                // Resultado de una configuración vieja: no se publica ni toca la altura vista.
                return@withLock _estado.value
            }
            val publicado = when (e) {
                is EstadoNodo.Sano -> { alturaVista = maxOf(alturaVista, e.info.altura); caidoDesde = 0; e }
                is EstadoNodo.Caido -> conCaida(e)
                else -> e
            }
            generacionSano = if (publicado.sano) gen else -1
            _estado.value = publicado
            publicado
        }
    }

    override suspend fun conexionSana(): ConexionMotor? {
        val gen = generacion.get()
        val c = activa ?: return null
        val e = comprobar()
        if (!e.sano || gen != generacion.get() || generacionSano != gen) return null
        return ConexionMotor(c.electrum, c.huellaServidor?.takeIf { it.isNotBlank() }, gen, c.proxySocks5?.takeIf { it.isNotBlank() })
    }

    /**
     * Una comprobación PURA: no toca `alturaVista` ni `caidoDesde` (eso lo decide quien la llama,
     * bajo el cerrojo, y solo para el nodo vigente). `alturaMin` es la altura mínima exigida.
     */
    private fun comprobarUnaVez(c: ConfigNodo, alturaMin: UInt, puertaHecha: Boolean = false): EstadoNodo {
        c.invalida()?.let { return EstadoNodo.Caido(0, it, 0) }
        if (c.tunel == ModoTunel.EXTERNO && !hayVpnActiva()) {
            return EstadoNodo.Caido(0, "túnel externo no activo: se exige una VPN activa en el aparato", 0)
        }
        // G1: en modo X-39 la detección real del túnel va ANTES de abrir socket (salvo que quien
        // llama ya la haya hecho). Solo la certeza «sin túnel» corta; la duda no bloquea.
        if (!puertaHecha) puertaTunel(c)?.let { return it }
        // Modo X-39: la conexión va a NodoX39.servidor(red) (c.electrum es esa y no otra:
        // NodoX39.config); si falla, el diagnóstico se repite y elige uno de los textos fijos.
        val modo = if (c.tunel == ModoTunel.X39) "X-39" else "Mi nodo"
        return try {
            val info = comprobador.comprobar(c, alturaMin)
            if (c.tunel == ModoTunel.X39) registrar("$modo: nodo sano por el túnel (servidor ${info.versionServidor}, altura ${info.altura}, ${info.latenciaMs} ms)")
            EstadoNodo.Sano(info, sinCifrar = !info.cifrado)
        } catch (e: CancellationException) {
            // G6: la cancelación de la corrutina nunca es un texto de pantalla; se relanza tal cual.
            throw e
        } catch (e: x39.wallet.Exception.RedServidor) {
            registrar("$modo: el servidor respondió pero es de otra red: ${sinDirecciones(e.message ?: "", c)}")
            EstadoNodo.RedIncorrecta(c.red, e.message ?: "")
        } catch (e: x39.wallet.Exception.HuellaServidor) {
            registrar("$modo: certificado rechazado: ${sinDirecciones(e.message ?: "", c)}")
            EstadoNodo.HuellaIncorrecta(e.message ?: "")
        } catch (e: x39.wallet.Exception.RedProhibida) {
            EstadoNodo.Caido(0, "red no permitida en esta edición", 0)
        } catch (e: Exception) {
            val altura = mensajeAlturaSiAplica(e.message)
            val motivo = altura ?: mensajeLlano(e.message ?: e.javaClass.simpleName)
            if (altura != null) {
                // El servidor respondió (va por detrás): no es un fallo del túnel; texto fijo de la app.
                registrar("$modo: ${sinDirecciones(altura, c)}")
                EstadoNodo.Caido(0, altura, 0)
            } else if (c.tunel == ModoTunel.X39) {
                // G6: en pantalla solo uno de los tres textos fijos; el motivo, en el registro.
                val d = diagnosticar()
                registrar("$modo: conexión fallida (${sinDirecciones(motivo, c)}); diagnóstico del túnel: ${d.estado} (${sinDirecciones(d.detalle, c)})")
                EstadoNodo.Caido(0, NodoX39.mensajeFallo(d.estado), 0)
            } else {
                registrar("$modo: conexión fallida (${sinDirecciones(motivo, c)})")
                EstadoNodo.Caido(0, motivo, 0)
            }
        }
    }

    /** Diagnóstico que nunca lanza: un detector que falle cuenta como «no concluyente». */
    private fun diagnosticar(): DiagnosticoTunel = try {
        diagnosticoTunel.diagnosticar()
    } catch (e: CancellationException) {
        throw e
    } catch (ex: Exception) {
        DiagnosticoTunel(EstadoTunel.DESCONOCIDO, "el diagnóstico lanzó ${ex.javaClass.simpleName}")
    }

    /**
     * Puerta del modo X-39 (orden 7, G1): ANTES de abrir ningún socket, la detección real del
     * túnel. Solo si dice con certeza `SIN_TUNEL` se corta: texto fijo [NodoX39.MENSAJE_TUNEL],
     * cero conexiones, cero llamadas al motor. `CON_RUTA` o `DESCONOCIDO` → `null`: se intenta
     * (la orden 6 enseñó que una puerta equivocada deja la billetera sin nodo con el túnel activo).
     * En «Mi nodo» no hay puerta.
     */
    private fun puertaTunel(c: ConfigNodo): EstadoNodo.Caido? {
        if (c.tunel != ModoTunel.X39) return null
        val d = diagnosticar()
        if (d.estado == EstadoTunel.CON_RUTA) return null
        registrar("X-39: túnel apagado, no se abre socket; diagnóstico del túnel: ${d.estado} (${sinDirecciones(d.detalle, c)})")
        return EstadoNodo.Caido(0, NodoX39.mensajeFallo(d.estado), 0)
    }

    /** Caída del nodo VIGENTE: anota desde cuándo (bajo el cerrojo). */
    private fun conCaida(e: EstadoNodo.Caido): EstadoNodo.Caido {
        val ahora = System.currentTimeMillis() / 1000
        if (caidoDesde == 0L) caidoDesde = ahora
        return e.copy(desdeHaceS = ahora - caidoDesde)
    }

    override fun vigilar(activo: Boolean) {
        vigilancia?.cancel()
        vigilancia = null
        // H2: al desactivar la vigilancia se deja de escuchar la red (sin fugas).
        dejarDeEscuchar?.let { parar -> dejarDeEscuchar = null; try { parar() } catch (_: Exception) { } }
        if (!activo) return
        vaciarDespertador() // señales llegadas con la vigilancia parada: ya no dicen nada
        // Arranque perezoso: el Job queda guardado ANTES de que corra el cuerpo, para que
        // vigilar(false) lo cancele aunque el despachador ejecute en el mismo hilo.
        val trabajo = ambito.launch(io, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var intento = 0
            while (isActive) {
                val e = comprobar()
                val espera = if (e.sano) { intento = 0; 60_000L } else { esperaMs(intento).also { intento++ } }
                if (e is EstadoNodo.Caido) _estado.value = e.copy(proximoIntentoEnS = espera / 1000)
                // H2: la espera termina a su hora O con una señal de red. Tras la señal, un rebote
                // corto; las señales que lleguen en ráfaga se descartan (una sola comprobación) y
                // la espera creciente vuelve a empezar.
                var dormir = true
                while (dormir && esperarODespertar(espera)) {
                    esperar(reboteMs)
                    vaciarDespertador()
                    if (senalSinEfecto()) {
                        registrar("cambio de red sin efecto: el túnel sigue con ruta y el nodo sano")
                    } else {
                        registrar("cambio de red: se comprueba el nodo ahora")
                        intento = 0
                        dormir = false
                    }
                }
            }
        }
        vigilancia = trabajo
        // La señal solo despierta; si no se puede escuchar, la vigilancia sigue como antes.
        dejarDeEscuchar = try {
            senalRed.escuchar { despertador.trySend(Unit) }
        } catch (e: Exception) {
            registrar("sin señal de red (${e.javaClass.simpleName}): la vigilancia sigue con la espera creciente")
            null
        }
        trabajo.start()
    }

    private fun vaciarDespertador() {
        while (despertador.tryReceive().isSuccess) { /* descartar */ }
    }

    /**
     * Espera `ms` (con la función inyectada) o hasta la primera señal de red, lo que antes llegue.
     * `true` si fue la señal.
     */
    private suspend fun esperarODespertar(ms: Long): Boolean = coroutineScope {
        val senal = async { despertador.receive(); true }
        val plazo = async { esperar(ms); false }
        try {
            select<Boolean> { senal.onAwait { it }; plazo.onAwait { it } }
        } finally {
            senal.cancel()
            plazo.cancel()
        }
    }

    /**
     * Una señal de red no obliga a comprobar si NADA ha cambiado para el nodo: en modo X-39 con el
     * nodo sano y el túnel todavía con ruta (lo dice el diagnóstico, la única fuente de verdad), se
     * deja la vigilancia como está. Así, al activar la vigilancia con el túnel ya levantado, el
     * `onAvailable` inicial del sistema no abre un segundo socket. En cualquier otro caso (nodo
     * caído, túnel sin ruta, «Mi nodo») la comprobación es inmediata.
     */
    private fun senalSinEfecto(): Boolean {
        if (!_estado.value.sano || activa?.tunel != ModoTunel.X39) return false
        return diagnosticar().estado == EstadoTunel.CON_RUTA
    }

}
