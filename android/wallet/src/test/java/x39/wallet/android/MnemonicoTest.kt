package x39.wallet.android

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import x39.wallet.NoHandle
import x39.wallet.SemillaGenerada

/** Pruebas JVM (sin dispositivo) del contrato del mnemónico. */
class MnemonicoTest {

    private val texto = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun deChars_bufferDirectoPosicionCeroLimiteExacto_yBorraLaEntrada() {
        val chars = texto.toCharArray()
        val esperado = texto.toByteArray(StandardCharsets.UTF_8)
        val m = Mnemonico.deChars(chars)
        assertTrue("la entrada se borra", chars.all { it == '\u0000' })
        assertEquals(esperado.size, m.longitud)
        m.usar { buf ->
            assertTrue("directo", buf.isDirect)
            assertEquals(0, buf.position())
            assertEquals(esperado.size, buf.limit())
            assertEquals(esperado.size, buf.remaining())
            val copia = ByteArray(buf.remaining())
            buf.duplicate().get(copia)
            assertArrayEquals(esperado, copia)
        }
        m.close()
    }

    @Test
    fun deChars_conAcentos_utf8Exacto() {
        val t = "ábaco ñandú über"
        val m = Mnemonico.deChars(t.toCharArray())
        assertEquals(t.toByteArray(StandardCharsets.UTF_8).size, m.longitud)
        m.close()
    }

    @Test
    fun close_poneACeroElBuferYLoInutiliza() {
        val bytes = texto.toByteArray(StandardCharsets.UTF_8)
        val m = Mnemonico.deBytes(bytes)
        assertTrue("la entrada se borra", bytes.all { it == 0.toByte() })
        val vista = m.usar { it.duplicate() } // misma memoria nativa
        assertTrue(vista.isDirect)
        m.close()
        m.close() // idempotente
        vista.clear()
        while (vista.hasRemaining()) assertEquals(0.toByte(), vista.get())
        try {
            m.usar { }
            fail("usar tras close debe fallar")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun usar_dejaLaPosicionEnCeroAunqueLaLlamadaLaMueva() {
        val m = Mnemonico.deBytes(texto.toByteArray(StandardCharsets.UTF_8))
        m.usar { it.position(it.limit()) }
        m.usar { assertEquals(0, it.position()) }
        m.close()
    }

    @Test
    fun deChars_entradaNoCodificable_falla_yBorra() {
        val chars = charArrayOf('a', '\uD800', 'b') // suplente suelto: no es UTF-8 válido
        try {
            Mnemonico.deChars(chars)
            fail("debe fallar")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(chars.all { it == '\u0000' })
    }

    @Test
    fun semillaNueva_palabrasComoChars_yBorrado() {
        val bytes = texto.toByteArray(StandardCharsets.UTF_8)
        val s = SemillaNueva(bytes.copyOf())
        val palabras = s.palabras()
        assertArrayEquals(texto.toCharArray(), palabras)
        val m = s.comoMnemonico()
        assertEquals(bytes.size, m.longitud)
        m.close()
        s.close()
        try {
            s.palabras()
            fail("tras close no hay palabras")
        } catch (_: IllegalStateException) {
        }
        assertFalse(palabras.isEmpty())
        palabras.fill('\u0000')
    }
    private class FuenteNativaSimulada(
        private val falloLectura: Int? = null,
        private val falloBorrado: Boolean = false,
        private val falloCierre: Boolean = false,
    ) : SemillaGenerada(NoHandle) {
        val original = byteArrayOf(97, 98, 99)
        var borrada = false
        var cerrada = false
        override fun longitud(): UInt = original.size.toUInt()
        override fun byteEn(indice: UInt): UByte {
            if (indice.toInt() == falloLectura) throw IllegalStateException("lectura interrumpida")
            return original[indice.toInt()].toUByte()
        }
        override fun borrar() {
            original.fill(0)
            borrada = true
            if (falloBorrado) throw IllegalStateException("fallo al borrar")
        }
        override fun close() {
            cerrada = true
            if (falloCierre) throw IllegalStateException("fallo al cerrar")
        }
    }

    @Test
    fun semillaNueva_nativaSeBorraYCierraAntesDeEntregarLaCopia() {
        val origen = FuenteNativaSimulada()
        SemillaNueva(origen).use { semilla ->
            assertTrue(origen.borrada)
            assertTrue(origen.cerrada)
            assertTrue(origen.original.all { it == 0.toByte() })
            val palabras = semilla.palabras()
            assertArrayEquals(charArrayOf('a', 'b', 'c'), palabras)
            palabras.fill('\u0000')
        }
    }

    @Test
    fun semillaNueva_siFallaLaLecturaParcial_borraYCierraLaNativa() {
        val origen = FuenteNativaSimulada(falloLectura = 1)
        var intermedio: ByteArray? = null
        try {
            SemillaNueva.extraerYBorrar(origen) { n -> ByteArray(n).also { intermedio = it } }
            fail("la lectura interrumpida debe fallar")
        } catch (e: IllegalStateException) {
            assertEquals("lectura interrumpida", e.message)
        }
        assertTrue(origen.borrada)
        assertTrue(origen.cerrada)
        assertTrue(origen.original.all { it == 0.toByte() })
        assertTrue(intermedio != null && intermedio!!.all { it == 0.toByte() })
    }

    @Test
    fun semillaNueva_siFallaLaLimpieza_noDevuelveSemillaYCierraSiempre() {
        for (origen in listOf(
            FuenteNativaSimulada(falloBorrado = true),
            FuenteNativaSimulada(falloCierre = true),
        )) {
            try {
                SemillaNueva(origen)
                fail("el fallo de limpieza debe impedir entregar la semilla")
            } catch (_: IllegalStateException) {
            }
            assertTrue(origen.borrada)
            assertTrue(origen.cerrada)
        }
    }

}
