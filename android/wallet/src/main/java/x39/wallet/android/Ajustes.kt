package x39.wallet.android

import android.content.Context
import android.content.SharedPreferences
import x39.wallet.Red

/**
 * Ajustes de la billetera (SharedPreferences `x39-wallet`, excluidas de las copias de
 * seguridad). El servidor Electrum lo elige el usuario; vacío por defecto: sin servidor no
 * hay red. Nada del mnemónico pasa por aquí.
 */
class Ajustes(context: Context) {

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

    /** `tcp://` va sin cifrar: se avisa (válido en una red local propia o un túnel). */
    fun servidorSinCifrar(): Boolean = urlServidor().startsWith("tcp://")

    fun hayServidor(): Boolean = servidor.isNotEmpty()

    companion object {
        private const val CLAVE_SERVIDOR = "servidor_electrum"
        private const val CLAVE_RED = "red"
    }
}
