package x39.wallet.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import x39.wallet.NodoInfo
import x39.wallet.Red

/** Pruebas JVM de la lógica de NodeBridge con el motor sustituido por una función. */
class NodeBridgeTest {

    private class Memoria : AlmacenConfigNodo {
        var c: ConfigNodo? = null
        override fun leer() = c
        override fun guardar(c: ConfigNodo) { this.c = c }
        override fun borrar() { c = null }
    }

    private fun info(altura: UInt, cifrado: Boolean = true) =
        NodoInfo("ssl://nodo.ejemplo.local:50002", "simulado", "1.4", altura, 12uL, cifrado, if (cifrado) "ab".repeat(32) else "", Red.SIGNET)

    private val configOk = ConfigNodo(Red.SIGNET, "ssl://nodo.ejemplo.local:50002", "ab".repeat(32), ModoTunel.NINGUNO, "mi nodo")

    @Test
    fun configuracionInvalida() {
        assertEquals("sin servidor Electrum", configOk.copy(electrum = "").invalida())
        assertEquals("el servidor debe empezar por ssl:// o tcp://", configOk.copy(electrum = "nodo:50002").invalida())
        assertTrue(configOk.copy(huellaServidor = null).invalida()!!.contains("hace falta la huella"))
        assertNull(configOk.copy(electrum = AjustesActivity.SERVIDOR_PUBLICO_SIGNET, huellaServidor = null).invalida())
        assertTrue(configOk.copy(electrum = "tcp://nodo:50001").invalida()!!.contains("solo se comprueba con ssl://"))
        assertNull(configOk.copy(electrum = "tcp://nodo:50001", huellaServidor = null).invalida())
        assertTrue(configOk.copy(tunel = ModoTunel.PROPIO).invalida()!!.contains("todavía"))
    }

    @Test
    fun esperaCrecienteConTopeYAzar() {
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> info(1u) }, Dispatchers.Unconfined)
        var anterior = 0L
        for (i in 0..6) {
            val e = nb.esperaMs(i)
            val base = 2_000L shl i
            assertTrue("intento $i: $e ≥ $base", e >= base)
            assertTrue("intento $i: $e ≤ base + 20 %", e <= base + base / 5)
            assertTrue("crece", e > anterior); anterior = e
        }
        assertTrue("tope de 5 min", nb.esperaMs(20) <= 300_000L)
        assertTrue(nb.esperaMs(20) >= 250_000L)
    }

    @Test
    fun sanoRedIncorrectaHuellaIncorrectaYCaido() = runBlocking {
        var respuesta: () -> NodoInfo = { info(10u) }
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> respuesta() }, Dispatchers.Unconfined)
        assertEquals(EstadoNodo.SinConfigurar, nb.comprobar())
        val e = nb.configurar(configOk)
        assertTrue(e is EstadoNodo.Sano)
        val cm = nb.conexionSana()!!
        assertEquals("ssl://nodo.ejemplo.local:50002", cm.url)
        assertEquals("ab".repeat(32), cm.huella)
        respuesta = { throw x39.wallet.Exception.RedServidor("se esperaba signet, el servidor anuncia génesis …") }
        assertTrue(nb.comprobar() is EstadoNodo.RedIncorrecta)
        assertNull("sin estado sano no hay conexión para el motor", nb.conexionSana())
        respuesta = { throw x39.wallet.Exception.HuellaServidor("el servidor presenta la huella …") }
        assertTrue(nb.comprobar() is EstadoNodo.HuellaIncorrecta)
        respuesta = { throw RuntimeException("connection refused") }
        val c = nb.comprobar()
        assertTrue(c is EstadoNodo.Caido && c.ultimoError.contains("connection refused"))
        respuesta = { info(11u) }
        assertTrue(nb.comprobar().sano)
        nb.olvidar()
        assertEquals(EstadoNodo.SinConfigurar, nb.estado.value)
    }

    @Test
    fun unResultadoDeUnaConfiguracionViejaNoSePublica_yConexionSanaVaAtadaALaGeneracion() = runBlocking {
        val memoria = Memoria()
        lateinit var nb: NodeBridgeImpl
        var cambiarEnMedio = true
        nb = NodeBridgeImpl(memoria, CoroutineScope(Dispatchers.Unconfined), { false }, { }, { c, _ ->
            if (cambiarEnMedio && c.nombre == "A") {
                cambiarEnMedio = false
                // Mientras se comprueba A, el usuario olvida el nodo: la generación cambia.
                runBlocking { nb.olvidar() }
            }
            info(7u)
        }, Dispatchers.Unconfined)
        val e = nb.configurar(configOk.copy(nombre = "A"))
        assertTrue("el Sano(A) viejo no se publica: $e", e !is EstadoNodo.Sano)
        assertEquals(EstadoNodo.SinConfigurar, nb.estado.value)
        assertNull(nb.conexionSana())
        // Con B configurada y sana, conexionSana devuelve URL y huella de B, y su generación.
        val b = configOk.copy(nombre = "B", electrum = "ssl://otro.ejemplo.local:50002", huellaServidor = "cd".repeat(32))
        assertTrue(nb.configurar(b).sano)
        val cm = nb.conexionSana()!!
        assertEquals("ssl://otro.ejemplo.local:50002", cm.url)
        assertEquals("cd".repeat(32), cm.huella)
        nb.olvidar()
        assertNull("tras olvidar, nada para el motor", nb.conexionSana())
    }

    @Test
    fun probar_aplicaLasMismasReglasSinGuardar() = runBlocking {
        val memoria = Memoria()
        val nb = NodeBridgeImpl(memoria, CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> info(3u) }, Dispatchers.Unconfined)
        val ext = nb.probar(configOk.copy(tunel = ModoTunel.EXTERNO))
        assertTrue("sin VPN activa, probar también dice caído: $ext", ext is EstadoNodo.Caido && ext.ultimoError.contains("túnel externo"))
        assertTrue(nb.probar(configOk).sano)
        assertNull("probar no guarda", memoria.c)
        assertEquals("ni publica", EstadoNodo.SinConfigurar, nb.estado.value)
    }

    @Test
    fun alturaMinimaEsLaUltimaVista() = runBlocking {
        val alturas = mutableListOf<UInt>()
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, min -> alturas += min; info(100u) }, Dispatchers.Unconfined)
        nb.configurar(configOk)
        nb.comprobar()
        assertEquals(listOf(0u, 100u), alturas)
    }

    @Test
    fun tunelExternoSinVpnActivaEsCaido_yConVpnSano() = runBlocking {
        var vpn = false
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { vpn }, { }, { _, _ -> info(5u) }, Dispatchers.Unconfined)
        val e = nb.configurar(configOk.copy(tunel = ModoTunel.EXTERNO))
        assertTrue(e is EstadoNodo.Caido && e.ultimoError.contains("túnel externo no activo"))
        vpn = true
        assertTrue(nb.comprobar().sano)
    }

    @Test
    fun tcpSeMarcaComoSinCifrar() = runBlocking {
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> info(5u, cifrado = false) }, Dispatchers.Unconfined)
        val e = nb.configurar(configOk.copy(electrum = "tcp://nodo.ejemplo.local:50001", huellaServidor = null))
        assertTrue(e is EstadoNodo.Sano && e.sinCifrar)
    }

    @Test
    fun vigilanciaReintentaConEsperasCrecientesYVuelveA60sAlSanar() = runBlocking {
        val esperas = mutableListOf<Long>()
        var fallos = 4 // configurar() consume el primero; la vigilancia ve tres fallos y luego sano
        val ambito = CoroutineScope(Dispatchers.Unconfined)
        lateinit var nb: NodeBridgeImpl
        nb = NodeBridgeImpl(Memoria(), ambito, { false }, { ms ->
            esperas += ms
            if (esperas.size >= 5) nb.vigilar(false)
        }, { _, _ -> if (fallos-- > 0) throw RuntimeException("caído") else info(1u) }, Dispatchers.Unconfined)
        nb.configurar(configOk)
        nb.vigilar(true)
        assertEquals(5, esperas.size)
        assertTrue("1.º fallo ≈2 s", esperas[0] in 2_000..2_400)
        assertTrue("2.º fallo ≈4 s", esperas[1] in 4_000..4_800)
        assertTrue("3.º fallo ≈8 s", esperas[2] in 8_000..9_600)
        assertEquals("sano: 60 s", 60_000L, esperas[3])
        assertEquals(60_000L, esperas[4])
    }
}
