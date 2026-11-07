package x39.wallet.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import x39.wallet.Red

/**
 * Análisis ADVERSARIO del paquete con el org.json DE ANDROID (el de la JVM se comporta distinto:
 * rechaza claves duplicadas y el de Android las sobrescribe; el veredicto no puede depender de eso).
 */
@RunWith(AndroidJUnit4::class)
class EmparejamientoInstrumentadoTest {

    private val ahora = 1_767_225_600L // 2026-01-01T00:00:00Z
    private val huella = "d69cfd0cee1cb723598e98e240ec62f14cc0ef834f47872dd6c29d4da6e230de"
    private val clavePublica = "A".repeat(43) + "="
    private val semilla = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val wif = "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ"

    private fun paquete(
        electrum: String = "\"ssl://nodo.ejemplo.local:50002\"",
        tunel: String? = null,
        nombre: String = "\"mi nodo\"",
        emitido: String = "\"2025-12-31T23:00:00Z\"",
        caduca: String = "\"2026-01-01T22:00:00Z\"",
        extra: String = "",
    ) = """{"v": 1, "red": "signet", "electrum": $electrum, "huella_spki_sha256": "$huella"${if (tunel != null) ", \"tunel\": $tunel" else ""}, "nombre": $nombre, "emitido": $emitido, "caduca": $caduca$extra}"""

    private fun rechazo(texto: String): String {
        try { Emparejamiento.analizar(texto, Red.SIGNET, ahora) } catch (e: ErrorEmparejamiento) { return e.message!! }
        fail("debía rechazarse: $texto"); return ""
    }

    @Test
    fun elPaqueteValidoSeAceptaEnAndroid() {
        assumeTrue("solo en la edición de prueba (signet); en mainnet no aplica", Redes.permitida(Red.SIGNET))
        val p = Emparejamiento.analizar(paquete(), Red.SIGNET, ahora)
        assertEquals("mi nodo", p.nombre)
        assertEquals(huella, p.huellaSpki)
    }

    @Test
    fun textoSobranteTrasElJson_seRechaza() {
        assertTrue(rechazo(paquete() + " PRIVATE KEY=synthetic").contains("texto sobrante"))
        assertTrue(rechazo(paquete() + "{}").contains("texto sobrante"))
    }

    @Test
    fun clavesDuplicadas_seRechazanAunqueAndroidLasSobrescribiria() {
        // En Android, org.json dejaría «tunel»: null y la clave privada desaparecería sin dejar rastro.
        val m = rechazo(paquete(tunel = """{"private_key": "x"}""", extra = ", \"tunel\": null"))
        assertTrue(m, m.contains("clave duplicada: «tunel»"))
        assertTrue(rechazo(paquete(tunel = """{"clave_publica_nodo": "$clavePublica", "endpoint": "n:1", "ips_permitidas": ["127.0.0.1/32"], "endpoint": "m:2"}""")).contains("clave duplicada: «endpoint»"))
        assertTrue(rechazo(paquete(extra = ", \"nom\\u0062re\": \"otro\"")).contains("clave duplicada: «nombre»"))
    }

    @Test
    fun jsonPermisivoDeAndroid_seRechaza() {
        assertTrue(rechazo(paquete().replace("\"v\": 1", "v: 1")).contains("JSON inválido"))
        assertTrue(rechazo(paquete().replace("\"signet\"", "'signet'")).contains("JSON inválido"))
        assertTrue(rechazo(paquete().replace("\"v\": 1", "/* x */ \"v\": 1")).contains("JSON inválido"))
        assertTrue(rechazo(paquete().replace("\"v\": 1", "# x\n\"v\": 1")).contains("JSON inválido"))
    }

    @Test
    fun secretosDentroDeLosValores() {
        assertTrue(rechazo(paquete(nombre = "\"copia: $semilla\"")).contains("clave privada"))
        assertTrue(rechazo(paquete(nombre = "\"nodo $wif\"")).contains("clave privada"))
        assertTrue(rechazo(paquete(electrum = "\"ssl://$wif.ejemplo.local:50002\"")).contains("clave privada"))
        assertTrue(rechazo(paquete(nombre = "\"-----BEGIN PRIVATE KEY-----\"")).contains("clave privada"))
        assertTrue(rechazo(paquete(extra = ", \"clave_privada\": \"x\"")).contains("clave privada"))
    }

    @Test
    fun fechasConAnchurasExactas_yRobustezAnteMilesDePalabras() {
        assumeTrue("solo en la edición de prueba (signet); en mainnet no aplica", Redes.permitida(Red.SIGNET))
        assertTrue(rechazo(paquete(caduca = "\"2026-1-1T22:0:0Z\"")).contains("exactamente la forma"))
        assertTrue(rechazo(paquete(emitido = "\"2025-02-30T23:00:00Z\"", caduca = "\"2025-03-01T22:00:00Z\"")).contains("calendario"))
        val m = rechazo(paquete(nombre = "\"${"a ".repeat(7000)}\""))
        assertTrue(m, m.contains("clave privada o una semilla"))
        rechazo(paquete(nombre = "\"${"x".repeat(16_000)}\""))
    }

    @Test
    fun semillaPegadaAPuntuacion_seRechaza_yMilesDePalabrasNoDesbordan() {
        assumeTrue("solo en la edición de prueba (signet); en mainnet no aplica", Redes.permitida(Red.SIGNET))
        val zoo = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
        for (n in listOf("copia:$zoo", "$zoo.", "($zoo)", "copia: $zoo", "nodo $wif.")) {
            val m = rechazo(paquete(nombre = "\"$n\""))
            assertTrue("$n → $m", m.contains("clave privada"))
        }
        Emparejamiento.analizar(paquete(nombre = "\"nodo de casa, en el salón (2.º piso)\""), Red.SIGNET, ahora)
        rechazo(paquete(nombre = "\"(${"a ".repeat(7000)})\""))
        assertTrue(rechazo(paquete(electrum = "\"ssl://[fd00::1:]:50002\"")).contains("no es una dirección IPv6"))
    }

    @Test
    fun fechasEstrictas_eIpv6Literal() {
        assumeTrue("solo en la edición de prueba (signet); en mainnet no aplica", Redes.permitida(Red.SIGNET))
        assertTrue(rechazo(paquete(emitido = "\"2025-12-31T23:00:00Z+09:00\"")).contains("exactamente la forma"))
        assertTrue(rechazo(paquete(caduca = "\"2026-01-01T00:00:00Z\"")).contains("caducó")) // caduca == ahora
        Emparejamiento.analizar(paquete(caduca = "\"2026-01-01T00:00:01Z\""), Red.SIGNET, ahora)
        assertTrue(rechazo(paquete(emitido = "\"2026-01-01T00:06:00Z\"", caduca = "\"2026-01-01T12:00:00Z\"")).contains("5 min"))
        assertTrue(rechazo(paquete(electrum = "\"ssl://[::::]:50002\"")).contains("no es una dirección IPv6"))
        Emparejamiento.analizar(paquete(tunel = """{"clave_publica_nodo": "$clavePublica", "endpoint": "[fd00::2]:51820", "ips_permitidas": ["::ffff:192.0.2.1/128"]}"""), Red.SIGNET, ahora)
    }
}
