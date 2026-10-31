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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    override suspend fun configurar(config: ConfigNodo): EstadoNodo {
        config.invalida()?.let { return EstadoNodo.Caido(0, it, 0).also { e -> _estado.value = e } }
        cerrojo.withLock {
            try {
                ajustes.guardar(config)
            } catch (ex: Exception) {
                // No persistido: la activa sigue siendo la anterior (el almacén restaura la suya).
                return EstadoNodo.Caido(0, "no se pudo guardar la configuración del nodo: ${ex.message ?: ex.javaClass.simpleName}", 0).also { e -> _estado.value = e }
            }
            activa = config
            generacion.incrementAndGet()
            alturaVista = 0u
            caidoDesde = 0
        }
        return comprobar()
    }

    override suspend fun olvidar() {
        vigilar(false)
        cerrojo.withLock {
            ajustes.borrar()
            activa = null
            generacion.incrementAndGet()
            generacionSano = -1
            _estado.value = EstadoNodo.SinConfigurar
        }
    }

    override suspend fun probar(config: ConfigNodo): EstadoNodo = withContext(io) {
        // Un candidato se comprueba desde cero (sin historial de altura) y no toca nada del vigente.
        comprobarUnaVez(config, 0u)
    }

    override suspend fun adoptarSiSano(config: ConfigNodo, paquete: PaqueteEmparejamiento?): EstadoNodo = withContext(io) {
        val gen = generacion.get()
        // Comprobación lenta FUERA del cerrojo; su resultado es del candidato, no del vigente.
        val e = comprobarUnaVez(config, 0u)
        if (e !is EstadoNodo.Sano) return@withContext e
        cerrojo.withLock {
            // Dentro del cerrojo: descartado (cancelación) o cambiado (otro configurar/olvidar
            // entretanto) → no se guarda ni se publica nada, aunque el resultado sea Sano.
            ensureActive()
            if (gen != generacion.get()) {
                return@withLock EstadoNodo.Caido(0, "la configuración del nodo cambió durante la comprobación: no se guarda", 0)
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
            alturaVista = e.info.altura
            caidoDesde = 0
            generacionSano = nueva
            _estado.value = e
            e
        }
    }

    override suspend fun comprobar(): EstadoNodo = withContext(io) {
        val (gen, c, alturaMin) = cerrojo.withLock { Triple(generacion.get(), activa, alturaVista) }
        if (c == null) return@withContext EstadoNodo.SinConfigurar.also { _estado.value = it }
        _estado.value = EstadoNodo.Conectando
        val e = comprobarUnaVez(c, alturaMin)
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
        return ConexionMotor(c.electrum, c.huellaServidor?.takeIf { it.isNotBlank() }, gen)
    }

    /**
     * Una comprobación PURA: no toca `alturaVista` ni `caidoDesde` (eso lo decide quien la llama,
     * bajo el cerrojo, y solo para el nodo vigente). `alturaMin` es la altura mínima exigida.
     */
    private fun comprobarUnaVez(c: ConfigNodo, alturaMin: UInt): EstadoNodo {
        c.invalida()?.let { return EstadoNodo.Caido(0, it, 0) }
        if (c.tunel == ModoTunel.EXTERNO && !hayVpnActiva()) {
            return EstadoNodo.Caido(0, "túnel externo no activo: se exige una VPN activa en el aparato", 0)
        }
        return try {
            val info = comprobador(c, alturaMin)
            EstadoNodo.Sano(info, sinCifrar = !info.cifrado)
        } catch (e: x39.wallet.Exception.RedServidor) {
            EstadoNodo.RedIncorrecta(c.red, e.message ?: "")
        } catch (e: x39.wallet.Exception.HuellaServidor) {
            EstadoNodo.HuellaIncorrecta(e.message ?: "")
        } catch (e: x39.wallet.Exception.RedProhibida) {
            EstadoNodo.Caido(0, "red no permitida en esta edición", 0)
        } catch (e: Exception) {
            EstadoNodo.Caido(0, e.message ?: e.javaClass.simpleName, 0)
        }
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
