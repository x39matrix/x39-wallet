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
}
