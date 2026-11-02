package x39.wallet.android

import java.io.File
import x39.wallet.BuildConfig

/**
 * Almacenamiento de la edición compilada. No depende de una preferencia que pueda cambiar
 * al actualizar: mainnet nunca abre el estado ni sobrescribe la semilla del laboratorio.
 * El perfil de pruebas conserva los nombres históricos, sin migrar ni borrar sus datos.
 */
internal enum class PerfilBilletera(
    val nombreDirectorio: String,
    val preferencias: String,
    val aliasSemilla: String,
) {
    PRUEBAS("x39-wallet", "x39-wallet", "x39-wallet-semilla"),
    MAINNET("x39-wallet-mainnet", "x39-wallet-mainnet", "x39-wallet-semilla-mainnet"),
    ;

    fun directorio(noBackup: File): File = File(noBackup, nombreDirectorio)

    fun ficheroEstado(noBackup: File): File = File(directorio(noBackup), "estado.dat")

    companion object {
        val actual: PerfilBilletera
            get() = deMarcador(BuildConfig.MARCADOR_REDES_ESPERADO)

        fun deMarcador(marcador: String): PerfilBilletera = when (marcador) {
            "x39wallet-redes=solo-prueba" -> PRUEBAS
            "x39wallet-redes=mainnet" -> MAINNET
            else -> error("marcador de redes desconocido: $marcador")
        }
    }
}
