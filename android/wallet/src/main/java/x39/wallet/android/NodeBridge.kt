package x39.wallet.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
}

/** Configuración del nodo del usuario. Vacía por defecto: sin nodo no hay red. */
data class ConfigNodo(
    val red: Red,
    /** `ssl://host:puerto` o `tcp://host:puerto`. */
    val electrum: String,
    /** Huella SPKI (SHA-256, hex) del certificado; obligatoria con `ssl://` salvo el servidor público de signet. */
    val huellaServidor: String?,
    val tunel: ModoTunel,
    val nombre: String,
) {
    fun cifrado(): Boolean = electrum.startsWith("ssl://")
    fun esPublicoDeSignet(): Boolean = red == Red.SIGNET && electrum == Redes.SERVIDOR_PUBLICO_SIGNET

    /** Reglas de la configuración; `null` si es válida, si no el motivo. */
    fun invalida(): String? = when {
        electrum.isBlank() -> "sin servidor Electrum"
        !electrum.startsWith("ssl://") && !electrum.startsWith("tcp://") -> "el servidor debe empezar por ssl:// o tcp://"
        cifrado() && huellaServidor.isNullOrBlank() && !esPublicoDeSignet() -> "con ssl:// hace falta la huella del certificado del nodo (salvo el servidor público de signet)"
        !cifrado() && !huellaServidor.isNullOrBlank() -> "la huella solo se comprueba con ssl://"
        tunel == ModoTunel.PROPIO -> "el túnel propio todavía no está disponible"
        else -> null
    }
}

/** Lo que la billetera necesita para una operación de red, atado a UNA generación de configuración. */
data class ConexionMotor(val url: String, val huella: String?, val generacion: Int)

sealed class EstadoNodo {
    object SinConfigurar : EstadoNodo()
    object Conectando : EstadoNodo()
    data class Sano(val info: NodoInfo, val sinCifrar: Boolean) : EstadoNodo()
    data class Caido(val desdeHaceS: Long, val ultimoError: String, val proximoIntentoEnS: Long) : EstadoNodo()
    data class RedIncorrecta(val esperada: Red, val detalle: String) : EstadoNodo()
    data class HuellaIncorrecta(val detalle: String) : EstadoNodo()

    val sano: Boolean get() = this is Sano
}

/** Dónde vive la configuración del nodo (Ajustes en la app; memoria en las pruebas). */
interface AlmacenConfigNodo {
    fun leer(): ConfigNodo?
    fun guardar(c: ConfigNodo)
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
    fun vigilar(activo: Boolean)
    /**
     * Comprueba el nodo AHORA y, solo si está sano, devuelve URL y huella de la MISMA
     * generación de configuración (si entretanto se configuró u olvidó otra, `null`).
     */
    suspend fun conexionSana(): ConexionMotor?
}

/**
 * Implementación con el motor Rust (`comprobarNodo`): comprobaciones en orden (génesis,
 * huella, altura, protocolo, latencia), reconexión con espera creciente y azar, modo de
 * túnel externo (solo comprueba que hay una VPN activa y que el nodo responde a través de
 * ella). Toda la red en `Dispatchers.IO`.
 */
@RequiresApi(Build.VERSION_CODES.M)
class NodeBridgeImpl(
    private val ajustes: AlmacenConfigNodo,
    private val ambito: CoroutineScope,
    /** ¿Hay una VPN activa en el aparato? Inyectable para las pruebas. */
    private val hayVpnActiva: () -> Boolean,
    /** Espera entre reintentos; inyectable para que las pruebas no esperen de verdad. */
    private val esperar: suspend (ms: Long) -> Unit = { delay(it) },
    private val comprobador: (ConfigNodo, UInt) -> NodoInfo = { c, alturaMin -> comprobarNodo(c.red, c.electrum, c.huellaServidor, alturaMin) },
    /** Despachador para la red; `Dispatchers.IO` en la app, `Unconfined` en las pruebas JVM. */
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : NodeBridge {

    private val _estado = MutableStateFlow<EstadoNodo>(if (ajustes.leer() == null) EstadoNodo.SinConfigurar else EstadoNodo.Conectando)
    override val estado: StateFlow<EstadoNodo> = _estado
    override val config: ConfigNodo? get() = ajustes.leer()

    private var vigilancia: Job? = null
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

    override suspend fun configurar(config: ConfigNodo): EstadoNodo {
        config.invalida()?.let { return EstadoNodo.Caido(0, it, 0).also { e -> _estado.value = e } }
        ajustes.guardar(config)
        generacion.incrementAndGet()
        alturaVista = 0u
        return comprobar()
    }

    override suspend fun olvidar() {
        vigilar(false)
        ajustes.borrar()
        generacion.incrementAndGet()
        _estado.value = EstadoNodo.SinConfigurar
    }

    override suspend fun probar(config: ConfigNodo): EstadoNodo = withContext(io) { comprobarUnaVez(config) }

    override suspend fun comprobar(): EstadoNodo = withContext(io) {
        val gen = generacion.get()
        val c = ajustes.leer() ?: return@withContext EstadoNodo.SinConfigurar.also { _estado.value = it }
        _estado.value = EstadoNodo.Conectando
        val e = comprobarUnaVez(c)
        if (gen != generacion.get()) {
            // Resultado de una configuración vieja: no se publica.
            return@withContext _estado.value
        }
        generacionSano = if (e.sano) gen else -1
        _estado.value = e
        e
    }

    override suspend fun conexionSana(): ConexionMotor? {
        val gen = generacion.get()
        val c = ajustes.leer() ?: return null
        val e = comprobar()
        if (!e.sano || gen != generacion.get() || generacionSano != gen) return null
        return ConexionMotor(c.electrum, c.huellaServidor?.takeIf { it.isNotBlank() }, gen)
    }

    private fun comprobarUnaVez(c: ConfigNodo): EstadoNodo {
        c.invalida()?.let { return caido(it) }
        if (c.tunel == ModoTunel.EXTERNO && !hayVpnActiva()) {
            return caido("túnel externo no activo: se exige una VPN activa en el aparato")
        }
        return try {
            val info = comprobador(c, alturaVista)
            alturaVista = maxOf(alturaVista, info.altura)
            caidoDesde = 0
            EstadoNodo.Sano(info, sinCifrar = !info.cifrado)
        } catch (e: x39.wallet.Exception.RedServidor) {
            EstadoNodo.RedIncorrecta(c.red, e.message ?: "")
        } catch (e: x39.wallet.Exception.HuellaServidor) {
            EstadoNodo.HuellaIncorrecta(e.message ?: "")
        } catch (e: x39.wallet.Exception.RedProhibida) {
            caido("red no permitida en esta edición")
        } catch (e: Exception) {
            caido(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun caido(motivo: String): EstadoNodo.Caido {
        val ahora = System.currentTimeMillis() / 1000
        if (caidoDesde == 0L) caidoDesde = ahora
        return EstadoNodo.Caido(desdeHaceS = ahora - caidoDesde, ultimoError = motivo, proximoIntentoEnS = 0)
    }

    override fun vigilar(activo: Boolean) {
        vigilancia?.cancel()
        vigilancia = null
        if (!activo) return
        // Arranque perezoso: el Job queda guardado ANTES de que corra el cuerpo, para que
        // vigilar(false) lo cancele aunque el despachador ejecute en el mismo hilo.
        val trabajo = ambito.launch(io, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var intento = 0
            while (isActive) {
                val e = comprobar()
                val espera = if (e.sano) { intento = 0; 60_000L } else { esperaMs(intento).also { intento++ } }
                if (e is EstadoNodo.Caido) _estado.value = e.copy(proximoIntentoEnS = espera / 1000)
                esperar(espera)
            }
        }
        vigilancia = trabajo
        trabajo.start()
    }

}
