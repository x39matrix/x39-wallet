package x39.wallet.android

import android.content.Context
import android.content.SharedPreferences
import x39.wallet.Red

/**
 * Ajustes de la billetera (SharedPreferences `x39-wallet`, excluidas de las copias de
 * seguridad). El servidor Electrum lo elige el usuario; vacío por defecto: sin servidor no
 * hay red. Nada del mnemónico pasa por aquí.
 */
class Ajustes(context: Context) : AlmacenConfigNodo {

    override fun leer(): ConfigNodo? = configNodo()
    override fun guardar(c: ConfigNodo) = guardarConfigNodo(c)
    override fun borrar() = borrarConfigNodo()

    private val prefs: SharedPreferences = context.getSharedPreferences("x39-wallet", Context.MODE_PRIVATE)

    /** Tal como lo escribió el usuario: `host:puerto`, opcionalmente con `ssl://` o `tcp://`. */
    var servidor: String
        get() = prefs.getString(CLAVE_SERVIDOR, "") ?: ""
        set(v) = prefs.edit().putString(CLAVE_SERVIDOR, v.trim()).apply()

    /** Red de la billetera; solo cambia antes de crearla. */
    var red: Red
        get() = Red.valueOf(prefs.getString(CLAVE_RED, Red.SIGNET.name) ?: Red.SIGNET.name)
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

    companion object {
        private const val CLAVE_SERVIDOR = "servidor_electrum"
        private const val CLAVE_RED = "red"
        private const val CLAVE_HUELLA = "huella_servidor"
        private const val CLAVE_TUNEL = "tunel"
        private const val CLAVE_NOMBRE_NODO = "nombre_nodo"
    }
}

// ---- NodeBridge: configuración del nodo del usuario (persistida en las mismas preferencias) ----

fun Ajustes.configNodo(): ConfigNodo? {
    val electrum = urlServidor()
    if (electrum.isEmpty()) return null
    return ConfigNodo(
        red = red,
        electrum = electrum,
        huellaServidor = huellaServidor.ifBlank { null },
        tunel = tunel,
        nombre = nombreNodo,
    )
}

fun Ajustes.guardarConfigNodo(c: ConfigNodo) {
    servidor = c.electrum
    huellaServidor = c.huellaServidor ?: ""
    tunel = c.tunel
    nombreNodo = c.nombre
}

fun Ajustes.borrarConfigNodo() {
    servidor = ""
    huellaServidor = ""
    tunel = ModoTunel.NINGUNO
    nombreNodo = ""
}
