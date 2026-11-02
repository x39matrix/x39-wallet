package x39.wallet.android

import x39.wallet.BuildConfig
import x39.wallet.Red
import x39.wallet.redesPermitidas

/**
 * Redes que acepta la capa Android (buildConfigField) Y la biblioteca nativa (marcador de
 * compilación). Las dos tienen que estar de acuerdo antes de abrir nada.
 */
object Redes {
    /** Servidor Electrum público de terceros para SIGNET (certificado reconocido, sin huella); solo se ofrece en esa red. */
    const val SERVIDOR_PUBLICO_SIGNET = "ssl://mempool.space:60602"

    private val permitidas: Set<String> = BuildConfig.REDES_PERMITIDAS.split(',').map { it.trim() }.toSet()

    fun permitida(red: Red): Boolean = red.name.lowercase() in permitidas

    fun disponibles(): List<Red> = listOf(Red.MAINNET, Red.SIGNET, Red.REGTEST).filter { permitida(it) }

    fun porDefecto(): Red = if (permitida(Red.MAINNET)) Red.MAINNET else Red.SIGNET

    /** Lanza si las redes de la biblioteca nativa cargada no coinciden con BuildConfig.MARCADOR_REDES_ESPERADO. */
    fun comprobarBiblioteca() {
        val marcador = redesPermitidas()
        check(marcador == BuildConfig.MARCADOR_REDES_ESPERADO) {
            "biblioteca nativa con redes no permitidas: $marcador"
        }
    }

    fun exigir(red: Red) {
        check(permitida(red)) { "red no permitida en esta edición: ${red.name.lowercase()}" }
        comprobarBiblioteca()
    }

    /** La semilla cifrada en el Keystore con autenticación por operación exige Android 6 (API 23). */
    @androidx.annotation.ChecksSdkIntAtLeast(api = android.os.Build.VERSION_CODES.M)
    fun androidCompatible(): Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M

    fun exigirAndroid() {
        check(androidCompatible()) { "la billetera exige Android 6 o superior" }
    }
}
