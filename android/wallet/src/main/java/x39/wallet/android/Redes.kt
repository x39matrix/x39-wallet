package x39.wallet.android

import x39.wallet.BuildConfig
import x39.wallet.Red
import x39.wallet.redesPermitidas

/**
 * Redes que acepta la capa Android (buildConfigField) Y la biblioteca nativa (marcador de
 * compilación). Las dos tienen que estar de acuerdo antes de abrir nada.
 */
object Redes {
    private val permitidas: Set<String> = BuildConfig.REDES_PERMITIDAS.split(',').map { it.trim() }.toSet()

    fun permitida(red: Red): Boolean = red.name.lowercase() in permitidas

    /** Lanza si la biblioteca nativa cargada no es la de solo redes de prueba. */
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
