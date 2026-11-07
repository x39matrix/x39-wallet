package x39.wallet.android

import android.content.Context
import android.content.SharedPreferences
import x39.wallet.Red

/**
 * Ajustes de la billetera, separados por edición y excluidos de las copias de seguridad.
 * Dos modos de nodo (Ajustes → Nodo): «Nodo X-39» (predeterminado: el servidor Electrum de
 * X-39 por el túnel de la app, sin escribir nada) y «Mi nodo» (el servidor Electrum que escribe
 * el usuario, que se conserva aunque vuelva al nodo X-39). Nada del mnemónico pasa por aquí.
 * La dirección del nodo propio no se escribe en ningún registro (`Log`): solo en estas
 * preferencias y en pantalla.
 */
class Ajustes(context: Context, nombrePrefs: String = PerfilBilletera.actual.preferencias) : AlmacenConfigNodo {

    /** Configuración activa según el modo: el nodo X-39 de la red, o «Mi nodo» (null si está vacío). */
    override fun leer(): ConfigNodo? = when (modoNodo) {
        ModoNodo.X39 -> NodoX39.config(red)
        ModoNodo.PROPIO -> configNodo()
    }
    private val CLAVES_NODO = listOf(CLAVE_MODO, CLAVE_SERVIDOR, CLAVE_HUELLA, CLAVE_TUNEL, CLAVE_NOMBRE_NODO, CLAVE_PROXY, CLAVE_PROXY_DIRECCION, CLAVE_TUNEL_CLAVE_NODO, CLAVE_TUNEL_ENDPOINT, CLAVE_TUNEL_IPS, CLAVE_TUNEL_MOVIL)

    /**
     * Configuración y metadatos del paquete en UNA escritura: `commit()` a propósito (síncrono),
     * para que lo guardado esté en disco antes de que NodeBridge publique el estado Sano.
     * SharedPreferences cambia PRIMERO su memoria y LUEGO el disco: si `commit()` devuelve
     * `false`, el disco conserva lo anterior pero la memoria ya tiene lo nuevo y no se revierte
     * sola. Por eso, en ese caso, se restaura en las preferencias la configuración anterior y se
     * lanza; NodeBridge mantiene además su configuración activa en memoria y solo la cambia si
     * esta función vuelve sin lanzar.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    override fun guardar(c: ConfigNodo, paquete: PaqueteEmparejamiento?) {
        val anteriores = CLAVES_NODO.associateWith { prefs.getString(it, null) }
        val emparejadoAntes = if (prefs.contains(CLAVE_EMPAREJADO_EN)) prefs.getLong(CLAVE_EMPAREJADO_EN, 0L) else null
        val e = prefs.edit()
        if (c.tunel == ModoTunel.X39) {
            // Nodo X-39: solo cambia el modo. «Mi nodo» y su emparejamiento se conservan tal cual,
            // para volver a ellos sin escribir nada (y un paquete no tiene sentido aquí).
            e.putString(CLAVE_MODO, ModoNodo.X39.name)
        } else {
            e.putString(CLAVE_MODO, ModoNodo.PROPIO.name)
                .putString(CLAVE_SERVIDOR, c.electrum.trim())
                .putString(CLAVE_HUELLA, (c.huellaServidor ?: "").trim().lowercase().filter { it.isLetterOrDigit() })
                .putString(CLAVE_TUNEL, c.tunel.name)
                .putString(CLAVE_NOMBRE_NODO, c.nombre.trim())
                .putString(CLAVE_PROXY, (c.proxySocks5 ?: "").trim())
            if (!c.proxySocks5.isNullOrBlank()) e.putString(CLAVE_PROXY_DIRECCION, c.proxySocks5.trim())
        }
        if (paquete != null && c.tunel != ModoTunel.X39) {
            val tn = paquete.tunel
            e.putString(CLAVE_TUNEL_CLAVE_NODO, tn?.clavePublicaNodo ?: "")
                .putString(CLAVE_TUNEL_ENDPOINT, tn?.endpoint ?: "")
                .putString(CLAVE_TUNEL_IPS, (tn?.ipsPermitidas ?: emptyList()).joinToString(","))
                .putString(CLAVE_TUNEL_MOVIL, tn?.direccionMovil ?: "")
                .putLong(CLAVE_EMPAREJADO_EN, System.currentTimeMillis() / 1000)
        }
        // commit() es atómico (todo o nada) y síncrono; si devuelve false NO se ha guardado nada y
        // quien llama no debe dar el nodo por adoptado ni publicar Sano.
        if (!e.commit()) {
            // Deshacer en la memoria de las preferencias lo que el disco no aceptó.
            val r = prefs.edit()
            for ((k, v) in anteriores) if (v == null) r.remove(k) else r.putString(k, v)
            if (emparejadoAntes == null) r.remove(CLAVE_EMPAREJADO_EN) else r.putLong(CLAVE_EMPAREJADO_EN, emparejadoAntes)
            r.commit()
            throw IllegalStateException("no se pudo guardar la configuración del nodo en las preferencias (el disco no aceptó la escritura; se conserva la anterior)")
        }
    }
    override fun borrar() = borrarConfigNodo()

    private val prefs: SharedPreferences = context.getSharedPreferences(nombrePrefs, Context.MODE_PRIVATE)

    /** Tal como lo escribió el usuario: `host:puerto`, opcionalmente con `ssl://` o `tcp://`. */
    var servidor: String
        get() = prefs.getString(CLAVE_SERVIDOR, "") ?: ""
        set(v) = prefs.edit().putString(CLAVE_SERVIDOR, v.trim()).apply()

    /** Red de la billetera; solo cambia antes de crearla. */
    var red: Red
        get() {
            val defecto = Redes.porDefecto()
            val guardada = prefs.getString(CLAVE_RED, defecto.name)?.let { runCatching { Red.valueOf(it) }.getOrNull() }
            return if (guardada != null && Redes.permitida(guardada)) guardada else defecto
        }
        set(v) {
            Redes.exigir(v)
            prefs.edit().putString(CLAVE_RED, v.name).apply()
        }

    /** URL para el motor: sin esquema se asume `ssl://`. Vacío si no hay servidor. */
    fun urlServidor(): String {
        val s = servidor
        if (s.isEmpty()) return ""
        return if ("://" in s) s else "ssl://$s"
    }

    /**
     * Modo del selector de nodo. Sin valor guardado (instalación nueva o anterior a este ajuste)
     * lo decide [NodoX39.resolverModo]: X-39 si no había servidor propio o si el guardado era el
     * de X-39; «Mi nodo» en otro caso; y siempre «Mi nodo» en una red sin nodo X-39.
     */
    var modoNodo: ModoNodo
        get() = NodoX39.resolverModo(prefs.getString(CLAVE_MODO, null), servidor, red)
        set(v) = prefs.edit().putString(CLAVE_MODO, v.name).apply()

    /** Proxy SOCKS5 `host:puerto` en uso para «Mi nodo»; vacío = sin proxy. */
    var proxySocks5: String
        get() = prefs.getString(CLAVE_PROXY, "") ?: ""
        set(v) = prefs.edit().putString(CLAVE_PROXY, v.trim()).apply()

    /** Última dirección de proxy escrita, para rellenar el campo (por defecto, Orbot). */
    val proxyDireccion: String
        get() = (prefs.getString(CLAVE_PROXY_DIRECCION, null) ?: "").ifBlank { NodoX39.PROXY_POR_DEFECTO }

    /** Huella SPKI (SHA-256, hex) del certificado del nodo; vacía si no se fijó. */
    var huellaServidor: String
        get() = prefs.getString(CLAVE_HUELLA, "") ?: ""
        set(v) = prefs.edit().putString(CLAVE_HUELLA, v.trim().lowercase().filter { it.isLetterOrDigit() }).apply()

    var tunel: ModoTunel
        get() = ModoTunel.valueOf(prefs.getString(CLAVE_TUNEL, ModoTunel.NINGUNO.name) ?: ModoTunel.NINGUNO.name)
        set(v) = prefs.edit().putString(CLAVE_TUNEL, v.name).apply()

    var nombreNodo: String
        get() = prefs.getString(CLAVE_NOMBRE_NODO, "") ?: ""
        set(v) = prefs.edit().putString(CLAVE_NOMBRE_NODO, v.trim()).apply()

    /** `tcp://` va sin cifrar: se avisa (válido en una red local propia o un túnel). */
    fun servidorSinCifrar(): Boolean = urlServidor().startsWith("tcp://")

    fun hayServidor(): Boolean = servidor.isNotEmpty()

    // ---- Emparejamiento (E-2): datos del túnel propio del paquete; solo claves PÚBLICAS ----
    var tunelClavePublicaNodo: String
        get() = prefs.getString(CLAVE_TUNEL_CLAVE_NODO, "") ?: ""
        set(v) = prefs.edit().putString(CLAVE_TUNEL_CLAVE_NODO, v.trim()).apply()
    var tunelEndpoint: String
        get() = prefs.getString(CLAVE_TUNEL_ENDPOINT, "") ?: ""
        set(v) = prefs.edit().putString(CLAVE_TUNEL_ENDPOINT, v.trim()).apply()
    var tunelIpsPermitidas: List<String>
        get() = (prefs.getString(CLAVE_TUNEL_IPS, "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString(CLAVE_TUNEL_IPS, v.joinToString(",")).apply()
    var tunelDireccionMovil: String
        get() = prefs.getString(CLAVE_TUNEL_MOVIL, "") ?: ""
        set(v) = prefs.edit().putString(CLAVE_TUNEL_MOVIL, v.trim()).apply()
    /** Segundos UTC en que se importó el paquete; 0 si el nodo no vino por emparejamiento. */
    var emparejadoEn: Long
        get() = prefs.getLong(CLAVE_EMPAREJADO_EN, 0L)
        set(v) = prefs.edit().putLong(CLAVE_EMPAREJADO_EN, v).apply()

    fun datosTunel(): DatosTunel? {
        if (tunelClavePublicaNodo.isEmpty() || tunelEndpoint.isEmpty()) return null
        return DatosTunel(tunelClavePublicaNodo, tunelEndpoint, tunelIpsPermitidas, tunelDireccionMovil.ifEmpty { null })
    }

    fun borrarEmparejamiento() {
        prefs.edit().remove(CLAVE_TUNEL_CLAVE_NODO).remove(CLAVE_TUNEL_ENDPOINT).remove(CLAVE_TUNEL_IPS)
            .remove(CLAVE_TUNEL_MOVIL).remove(CLAVE_EMPAREJADO_EN).apply()
    }

    companion object {
        private const val CLAVE_MODO = "modo_nodo"
        private const val CLAVE_PROXY = "proxy_socks5"
        private const val CLAVE_PROXY_DIRECCION = "proxy_socks5_direccion"
        private const val CLAVE_SERVIDOR = "servidor_electrum"
        private const val CLAVE_RED = "red"
        private const val CLAVE_HUELLA = "huella_servidor"
        private const val CLAVE_TUNEL = "tunel"
        private const val CLAVE_NOMBRE_NODO = "nombre_nodo"
        private const val CLAVE_TUNEL_CLAVE_NODO = "tunel_clave_publica_nodo"
        private const val CLAVE_TUNEL_ENDPOINT = "tunel_endpoint"
        private const val CLAVE_TUNEL_IPS = "tunel_ips_permitidas"
        private const val CLAVE_TUNEL_MOVIL = "tunel_direccion_movil"
        private const val CLAVE_EMPAREJADO_EN = "emparejado_en"
    }
}

// ---- NodeBridge: «Mi nodo», el nodo del usuario (persistido en las mismas preferencias) ----

/** La configuración de «Mi nodo» tal como está guardada, esté o no en uso; `null` si está vacía. */
fun Ajustes.configNodo(): ConfigNodo? {
    val electrum = urlServidor()
    if (electrum.isEmpty()) return null
    return ConfigNodo(
        red = red,
        electrum = electrum,
        huellaServidor = huellaServidor.ifBlank { null },
        tunel = tunel,
        nombre = nombreNodo,
        proxySocks5 = proxySocks5.ifBlank { null },
    )
}

fun Ajustes.guardarConfigNodo(c: ConfigNodo) {
    servidor = c.electrum
    huellaServidor = c.huellaServidor ?: ""
    tunel = c.tunel
    nombreNodo = c.nombre
    proxySocks5 = c.proxySocks5 ?: ""
}

/**
 * Olvidar el nodo propio: borra «Mi nodo» y lo que vino en el paquete de emparejamiento
 * (revocación local, §5.3) y deja el modo en «Mi nodo» vacío, es decir, SIN nodo (la pantalla
 * principal y Ajustes → Nodo lo dicen; el usuario elige después el nodo X-39 o escribe el suyo).
 */
fun Ajustes.borrarConfigNodo() {
    modoNodo = ModoNodo.PROPIO
    servidor = ""
    huellaServidor = ""
    tunel = ModoTunel.NINGUNO
    nombreNodo = ""
    proxySocks5 = ""
    borrarEmparejamiento()
}
