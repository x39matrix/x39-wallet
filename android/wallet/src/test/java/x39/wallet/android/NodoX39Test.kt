package x39.wallet.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import x39.wallet.Red

/** Pruebas JVM del nodo X-39 y del selector de modo (lógica pura, sin Android). */
class NodoX39Test {

    @Test
    fun soloElPuertoElectrumPorRed_yNadaParaRegtestNiTestnet() {
        assertEquals("tcp://10.39.0.3:50001", NodoX39.servidor(Red.MAINNET))
        assertEquals("tcp://10.39.0.3:60601", NodoX39.servidor(Red.SIGNET))
        assertNull(NodoX39.servidor(Red.REGTEST))
        assertNull(NodoX39.servidor(Red.TESTNET))
        assertTrue(NodoX39.disponible(Red.MAINNET))
        assertFalse(NodoX39.disponible(Red.REGTEST))
        val c = NodoX39.config(Red.MAINNET)!!
        assertEquals(Red.MAINNET, c.red)
        assertEquals(ModoTunel.X39, c.tunel)
        assertNull("tcp dentro del túnel: sin huella", c.huellaServidor)
        assertNull("sin proxy", c.proxySocks5)
        assertEquals(NodoX39.NOMBRE, c.nombre)
        assertNull("configuración válida", c.invalida())
        assertNull(NodoX39.config(Red.REGTEST))
    }

    @Test
    fun modoPredeterminado_x39EnInstalacionLimpia_yMigracionDeLoYaGuardado() {
        // Instalación limpia (nada guardado): nodo X-39.
        assertEquals(ModoNodo.X39, NodoX39.resolverModo(null, "", Red.MAINNET))
        assertEquals(ModoNodo.X39, NodoX39.resolverModo(null, "", Red.SIGNET))
        // Instalación anterior a este ajuste con el servidor de X-39 ya escrito a mano: X-39.
        assertEquals(ModoNodo.X39, NodoX39.resolverModo(null, "tcp://10.39.0.3:50001", Red.MAINNET))
        assertEquals(ModoNodo.X39, NodoX39.resolverModo(null, " tcp://10.39.0.3:60601 ", Red.SIGNET))
        // El de X-39 pero de OTRA red no es el de esta: «Mi nodo» (y el génesis lo rechazará).
        assertEquals(ModoNodo.PROPIO, NodoX39.resolverModo(null, "tcp://10.39.0.3:60601", Red.MAINNET))
        // Instalación anterior con otro servidor: se respeta, «Mi nodo».
        assertEquals(ModoNodo.PROPIO, NodoX39.resolverModo(null, "ssl://mempool.space:60602", Red.SIGNET))
        assertEquals(ModoNodo.PROPIO, NodoX39.resolverModo(null, "nodo.local:50002", Red.MAINNET))
        // Lo guardado manda.
        assertEquals(ModoNodo.PROPIO, NodoX39.resolverModo("PROPIO", "", Red.MAINNET))
        assertEquals(ModoNodo.X39, NodoX39.resolverModo("X39", "ssl://otro:1", Red.MAINNET))
        // Un valor desconocido se trata como ausente.
        assertEquals(ModoNodo.X39, NodoX39.resolverModo("basura", "", Red.MAINNET))
        // Sin nodo X-39 para la red, siempre «Mi nodo», diga lo que diga lo guardado.
        assertEquals(ModoNodo.PROPIO, NodoX39.resolverModo("X39", "", Red.REGTEST))
        assertEquals(ModoNodo.PROPIO, NodoX39.resolverModo(null, "", Red.REGTEST))
    }

    @Test
    fun descripcionDelNodoParaSeguridad() {
        assertEquals("sin configurar", (null as ConfigNodo?).descripcionNodo())
        assertEquals("X-39 por túnel", NodoX39.config(Red.MAINNET).descripcionNodo())
        val propio = ConfigNodo(Red.MAINNET, "ssl://nodo.ejemplo.local:50002", null, ModoTunel.NINGUNO, "mi nodo")
        assertEquals("el tuyo (nodo.ejemplo.local)", propio.descripcionNodo())
        assertEquals("el tuyo (abcdefghijklmnop.onion)", propio.copy(electrum = "tcp://abcdefghijklmnop.onion:50001", proxySocks5 = "127.0.0.1:9050").descripcionNodo())
        assertEquals("el tuyo ([2001:db8::1])", propio.copy(electrum = "tcp://[2001:db8::1]:50001").descripcionNodo())
    }

    @Test
    fun laFraseFijaYElMensajeDelTunelNoCambian() {
        assertEquals("El servidor Electrum que uses ve las direcciones de tu billetera. Con tu propio nodo no las ve nadie más.", NodoX39.FRASE_PRIVACIDAD)
        assertEquals("Túnel apagado. Activa el X39 Túnel; la billetera no se conecta por otra vía.", NodoX39.MENSAJE_TUNEL)
        assertTrue(NodoX39.MENSAJE_NODO_NO_RESPONDE.startsWith("túnel activo pero"))
        // Tres textos fijos distintos, uno por diagnóstico, sin motivo técnico ni direcciones (G6).
        val textos = EstadoTunel.values().map { NodoX39.mensajeFallo(it) }
        assertEquals(3, textos.toSet().size)
        assertEquals(NodoX39.MENSAJE_TUNEL, NodoX39.mensajeFallo(EstadoTunel.SIN_TUNEL))
        assertEquals(NodoX39.MENSAJE_NODO_NO_RESPONDE, NodoX39.mensajeFallo(EstadoTunel.CON_RUTA))
        assertEquals(NodoX39.MENSAJE_TUNEL_DESCONOCIDO, NodoX39.mensajeFallo(EstadoTunel.DESCONOCIDO))
        for (t in textos) assertTrue(t, !t.contains("10.39") && !t.contains("(") && !t.lowercase().contains("time") && !t.contains("50001"))
        assertEquals("127.0.0.1:9050", NodoX39.PROXY_POR_DEFECTO)
    }

    /** G3 (d): la comprobación de ruta, por bits y no por texto. */
    @Test
    fun laRutaCubreElNodo_porPrefijo_noPorTexto() {
        fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertTrue("10.39.0.0/24 cubre", NodoX39.rutaCubreNodo(ip(10, 39, 0, 0), 24))
        assertTrue("0.0.0.0/0 (ruta por defecto por la VPN) cubre", NodoX39.rutaCubreNodo(ip(0, 0, 0, 0), 0))
        assertFalse("192.168.1.0/24 no cubre", NodoX39.rutaCubreNodo(ip(192, 168, 1, 0), 24))
        assertTrue("10.39.0.3/32 cubre", NodoX39.rutaCubreNodo(ip(10, 39, 0, 3), 32))
        assertFalse("10.39.0.2/32 no cubre", NodoX39.rutaCubreNodo(ip(10, 39, 0, 2), 32))
        assertTrue("10.0.0.0/8 cubre", NodoX39.rutaCubreNodo(ip(10, 0, 0, 0), 8))
        assertTrue("10.39.0.0/25 cubre (bits sueltos)", NodoX39.rutaCubreNodo(ip(10, 39, 0, 0), 25))
        assertFalse("10.39.0.128/25 no cubre", NodoX39.rutaCubreNodo(ip(10, 39, 0, 128), 25))
        assertFalse("10.39.1.0/24 no cubre", NodoX39.rutaCubreNodo(ip(10, 39, 1, 0), 24))
        assertFalse("11.39.0.0/8 no cubre", NodoX39.rutaCubreNodo(ip(11, 39, 0, 0), 8))
        assertFalse("::/0 (IPv6) no cubre una IPv4", NodoX39.rutaCubreNodo(ByteArray(16), 0))
        assertFalse("prefijo inválido", NodoX39.rutaCubreNodo(ip(10, 39, 0, 0), 33))
        assertFalse("prefijo inválido", NodoX39.rutaCubreNodo(ip(10, 39, 0, 0), -1))
        // Dirección de interfaz dentro de la red del túnel.
        assertTrue(NodoX39.direccionEnRedTunel(ip(10, 39, 0, 2)))
        assertFalse(NodoX39.direccionEnRedTunel(ip(10, 39, 1, 2)))
        assertFalse(NodoX39.direccionEnRedTunel(ip(192, 168, 1, 44)))
        assertEquals(listOf<Byte>(10, 39, 0, 3), NodoX39.IP_NODO.toList())
        assertEquals("10.39.0.3", NodoX39.IP_NODO.joinToString(".") { (it.toInt() and 0xFF).toString() })
    }
}
