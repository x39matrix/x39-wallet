package x39.wallet.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import x39.wallet.Billetera
import x39.wallet.Red
import x39.wallet.nuevaSemilla
import x39.wallet.redesPermitidas

/** Con la biblioteca nativa real (arm64-v8a). Sin red: solo signet en local. */
@RunWith(AndroidJUnit4::class)
class BilleterasInstrumentadoTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun ficheroNuevo(): File {
        val d = File(ctx.filesDir, "prueba-${System.nanoTime()}").apply { mkdirs() }
        return File(d, "estado.dat")
    }

    @Test
    fun bibliotecaSoloRedesDePrueba() {
        val esMainnet = Redes.permitida(Red.MAINNET)
        val esperado = if (esMainnet) "x39wallet-redes=mainnet" else "x39wallet-redes=solo-prueba"
        assertEquals(esperado, redesPermitidas())
        Redes.comprobarBiblioteca()
        assertEquals(!esMainnet, Redes.permitida(Red.SIGNET))
        assertEquals(!esMainnet, Redes.permitida(Red.REGTEST))
    }

    @Test
    fun nuevaSemilla_docePalabras_yCreaBilleteraConByteBufferDirecto() {
        val f = ficheroNuevo()
        SemillaNueva(nuevaSemilla()).use { s ->
            val palabras = s.palabras()
            assertEquals(12, String(palabras).trim().split(' ').size) // solo en la prueba se pasa a String
            palabras.fill('\u0000')
            s.comoMnemonico().use { m ->
                Billeteras.crear(m, Red.SIGNET, f).use { b ->
                    val d = b.motor.nuevaDireccion()
                    assertEquals(0u, d.indice)
                    assertTrue(d.direccion.startsWith("tb1q"))
                    assertEquals(0uL, b.motor.saldo().total)
                }
            }
        }
        assertTrue(f.exists())
    }

    @Test
    fun semillaNativa_borradoExplicitoImpideLeerMasBytes() {
        nuevaSemilla().use { nativa ->
            assertTrue(nativa.longitud() > 0u)
            nativa.borrar()
            nativa.borrar()
            assertEquals(0u, nativa.longitud())
            try {
                nativa.byteEn(0u)
                fail("una semilla borrada no puede leerse")
            } catch (_: x39.wallet.Exception.Semilla) {
            }
        }
    }

    @Test
    fun semillaNueva_cierraElObjetoNativoAntesDeUsarlaEnAndroid() {
        val nativa = nuevaSemilla()
        SemillaNueva(nativa).use { semilla ->
            assertTrue(nativa.uniffiIsDestroyed)
            semilla.comoMnemonico().use { m -> assertTrue(m.longitud > 0) }
        }
    }

    @Test
    fun unaSolaBilleteraPorFichero_tambienDesdeAndroid() {
        val f = ficheroNuevo()
        val primera = SemillaNueva(nuevaSemilla()).use { s -> s.comoMnemonico().use { Billeteras.crear(it, Red.SIGNET, f) } }
        assertEquals("la primera revela el índice 0", 0u, primera.motor.nuevaDireccion().indice)
        try {
            Billeteras.abrir(f, Red.SIGNET)
            fail("la segunda apertura debe fallar")
        } catch (e: IllegalStateException) {
            assertEquals("ya abierta en este proceso", e.message)
        }
        // Saltándose el registro Kotlin, el motor también se niega.
        try {
            Billetera.abrir(f.path, Red.SIGNET)
            fail("el motor debe negarse")
        } catch (e: x39.wallet.Exception.Fichero) {
            assertTrue(e.message!!.contains("ya abierta"))
        }
        primera.close()
        Billeteras.abrir(f, Red.SIGNET).use { b -> assertEquals("al reabrir sigue por el 1", 1u, b.motor.nuevaDireccion().indice) }
    }

    @Test
    fun mainnetProhibidaPorElMotor() {
        org.junit.Assume.assumeFalse(Redes.permitida(Red.MAINNET))
        val f = ficheroNuevo()
        try {
            SemillaNueva(nuevaSemilla()).use { s -> s.comoMnemonico().use { Billeteras.crear(it, Red.MAINNET, f) } }
            fail("mainnet debe fallar")
        } catch (_: x39.wallet.Exception.RedProhibida) {
        }
    }
}
