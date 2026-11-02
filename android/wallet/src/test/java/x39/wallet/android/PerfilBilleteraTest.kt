package x39.wallet.android

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Compatibilidad con los datos existentes y separación del almacenamiento mainnet. */
class PerfilBilleteraTest {
    @get:Rule val temporal = TemporaryFolder()

    @Test
    fun edicionDePruebas_conservaLasRutasYLaClaveHistoricas() {
        val perfil = PerfilBilletera.deMarcador("x39wallet-redes=solo-prueba")
        val legado = File(temporal.root, "x39-wallet/estado.dat")
        legado.parentFile!!.mkdirs()
        legado.writeText("estado signet ficticio")

        assertEquals(legado, perfil.ficheroEstado(temporal.root))
        assertEquals("estado signet ficticio", perfil.ficheroEstado(temporal.root).readText())
        assertEquals("x39-wallet", perfil.preferencias)
        assertEquals("x39-wallet-semilla", perfil.aliasSemilla)
    }

    @Test
    fun mainnetEmpiezaVacia_yCrearOBorrarSusArchivosConservaElLegado() {
        val pruebas = PerfilBilletera.deMarcador("x39wallet-redes=solo-prueba")
        val mainnet = PerfilBilletera.deMarcador("x39wallet-redes=mainnet")
        val nombres = listOf("estado.dat", "estado.dat.envios.json", "estado.dat.escaneo-pendiente", "semilla.enc", "semilla.enc.tmp")
        val legado = pruebas.directorio(temporal.root).apply { mkdirs() }
        val originales = nombres.associateWith { nombre ->
            "fixture de pruebas: $nombre".toByteArray().also { File(legado, nombre).writeBytes(it) }
        }

        assertFalse(mainnet.ficheroEstado(temporal.root).exists())
        assertNotEquals(pruebas.preferencias, mainnet.preferencias)
        assertNotEquals(pruebas.aliasSemilla, mainnet.aliasSemilla)
        val nuevo = mainnet.directorio(temporal.root).apply { mkdirs() }
        for (nombre in nombres) File(nuevo, nombre).writeText("fixture mainnet: $nombre")
        assertTrue(mainnet.ficheroEstado(temporal.root).exists())
        assertTrue(nuevo.deleteRecursively())
        for ((nombre, bytes) in originales) assertArrayEquals(nombre, bytes, File(legado, nombre).readBytes())
    }

    @Test(expected = IllegalStateException::class)
    fun marcadorDesconocido_noEligeUnaBilleteraPorDefecto() {
        PerfilBilletera.deMarcador("x39wallet-redes=desconocida")
    }
}
