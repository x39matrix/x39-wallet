package x39.wallet.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
        var paquete: PaqueteEmparejamiento? = null
        /** Gancho de las pruebas de carrera: corre DENTRO de guardar (es decir, dentro del cerrojo). */
        var alGuardar: (() -> Unit)? = null
        /** Simula un commit() que devuelve false: Ajustes.guardar lanza y no persiste nada. */
        var commitFalla = false
        /** Peor caso: el almacén cambia su memoria y DESPUÉS falla el disco (como SharedPreferences). */
        var commitFallaTrasTocarMemoria = false
        override fun leer() = c
        override fun guardar(c: ConfigNodo, paquete: PaqueteEmparejamiento?) {
            alGuardar?.invoke()
            if (commitFalla) throw IllegalStateException("no se pudo guardar la configuración del nodo en las preferencias")
            if (commitFallaTrasTocarMemoria) { this.c = c; throw IllegalStateException("el disco no aceptó la escritura") }
            this.c = c
            if (paquete != null) this.paquete = paquete
        }
        override fun borrar() { c = null; paquete = null }
    }

    private val paqueteOk = PaqueteEmparejamiento(Red.SIGNET, "ssl://nodo.ejemplo.local:50002", "ab".repeat(32), DatosTunel("A".repeat(43) + "=", "nodo.ejemplo.local:51820", listOf("127.0.0.1/32"), null), "mi nodo", 0L, 86_400L)

    private fun info(altura: UInt, cifrado: Boolean = true) =
        NodoInfo("ssl://nodo.ejemplo.local:50002", "simulado", "1.4", altura, 12uL, cifrado, if (cifrado) "ab".repeat(32) else "", Red.SIGNET)

    private val configOkElectrum = "ssl://nodo.ejemplo.local:50002"
    private val configOk = ConfigNodo(Red.SIGNET, configOkElectrum, "ab".repeat(32), ModoTunel.NINGUNO, "mi nodo")

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
    fun adoptarSiSano_soloGuardaYPublicaSiElNodoEstaSano() = runBlocking {
        val memoria = Memoria()
        var respuesta: () -> NodoInfo = { throw RuntimeException("conexión rechazada") }
        val nb = NodeBridgeImpl(memoria, CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> respuesta() }, Dispatchers.Unconfined)
        // Nodo caído: nada en disco, el estado publicado no cambia, nada para el motor.
        val caido = nb.adoptarSiSano(configOk)
        assertTrue("$caido", caido is EstadoNodo.Caido && caido.ultimoError == "conexión rechazada")
        assertNull("no se guarda", memoria.c)
        assertEquals(EstadoNodo.SinConfigurar, nb.estado.value)
        assertNull(nb.conexionSana())
        // Huella incorrecta: igual.
        respuesta = { throw x39.wallet.Exception.HuellaServidor("otra huella") }
        assertTrue(nb.adoptarSiSano(configOk) is EstadoNodo.HuellaIncorrecta)
        assertNull(memoria.c)
        // Sano: se guarda, se publica y la conexión para el motor es la de esa configuración.
        respuesta = { info(42u) }
        val sano = nb.adoptarSiSano(configOk)
        assertTrue(sano is EstadoNodo.Sano)
        assertEquals(configOk, memoria.c)
        assertTrue(nb.estado.value is EstadoNodo.Sano)
        val cm = nb.conexionSana()!!
        assertEquals(configOk.electrum, cm.url)
        assertEquals(configOk.huellaServidor, cm.huella)
    }

    @Test
    fun adoptarSiSano_descartadoOCambiadoDuranteLaComprobacion_noGuardaNada() = runBlocking {
        val memoria = Memoria()
        val entrado = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        val nb = NodeBridgeImpl(memoria, CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> entrado.countDown(); soltar.await(); info(5u) }, Dispatchers.IO)
        // 1. Cancelación (el usuario descarta) mientras el servidor tarda: llega Sano y NO se guarda.
        val trabajo = launch(Dispatchers.Default) { nb.adoptarSiSano(configOk) }
        entrado.await()
        trabajo.cancel()
        soltar.countDown()
        trabajo.join()
        assertNull("descartado: nada en disco", memoria.c)
        assertEquals(EstadoNodo.SinConfigurar, nb.estado.value)
        // 2. La configuración cambia (olvidar) mientras se comprueba: tampoco se guarda.
        val entrado2 = java.util.concurrent.CountDownLatch(1)
        val soltar2 = java.util.concurrent.CountDownLatch(1)
        val nb2 = NodeBridgeImpl(memoria, CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> entrado2.countDown(); soltar2.await(); info(5u) }, Dispatchers.IO)
        val resultado = async(Dispatchers.Default) { nb2.adoptarSiSano(configOk) }
        entrado2.await()
        nb2.olvidar()
        soltar2.countDown()
        val e = resultado.await()
        assertTrue("$e", e is EstadoNodo.Caido && e.ultimoError.contains("cambió durante la comprobación"))
        assertNull(memoria.c)
    }

    @Test
    fun unOlvidoDuranteElGuardadoNoPuedeQuedarPisado_configYMetadatosJuntos() = runBlocking {
        // El cerrojo hace indivisibles comprobar-generación-y-guardar frente a olvidar(): si olvidar
        // llega mientras se está guardando, espera y gana (nada queda guardado); nunca el guardado
        // tardío pisa al olvido. El gancho corre dentro de guardar(), o sea, dentro del cerrojo.
        val memoria = Memoria()
        lateinit var nb: NodeBridgeImpl
        var olvido: kotlinx.coroutines.Job? = null
        memoria.alGuardar = {
            olvido = launch(Dispatchers.Default) { nb.olvidar() }
            Thread.sleep(300) // si el cerrojo no protegiera, olvidar terminaría aquí y luego se publicaría Sano
        }
        nb = NodeBridgeImpl(memoria, CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> info(5u) }, Dispatchers.IO)
        nb.adoptarSiSano(configOk, paqueteOk)
        olvido!!.join()
        assertNull("tras el olvido no queda configuración", memoria.c)
        assertNull("ni metadatos del paquete", memoria.paquete)
        assertEquals("y el estado publicado es SinConfigurar", EstadoNodo.SinConfigurar, nb.estado.value)
        assertNull(nb.conexionSana())
        // Sin olvido intermedio, la adopción guarda configuración y metadatos JUNTOS.
        memoria.alGuardar = null
        assertTrue(nb.adoptarSiSano(configOk, paqueteOk).sano)
        assertEquals(configOk, memoria.c)
        assertEquals(paqueteOk, memoria.paquete)
    }

    @Test
    fun siElAlmacenNoPersiste_noSeAdoptaNiSePublicaSano() = runBlocking {
        val memoria = Memoria().apply { commitFalla = true }
        val nb = NodeBridgeImpl(memoria, CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> info(5u) }, Dispatchers.Unconfined)
        val e = nb.adoptarSiSano(configOk, paqueteOk)
        assertTrue("$e", e is EstadoNodo.Caido && e.ultimoError.contains("no se pudo guardar"))
        assertNull(memoria.c); assertNull(memoria.paquete)
        assertEquals("no se publica Sano", EstadoNodo.SinConfigurar, nb.estado.value)
        assertNull(nb.conexionSana())
        // configurar() desde Ajustes tampoco da el nodo por configurado.
        val c = nb.configurar(configOk)
        assertTrue("$c", c is EstadoNodo.Caido && c.ultimoError.contains("no se pudo guardar"))
        assertNull(memoria.c)
        // Cuando el almacén vuelve a funcionar, adopta con normalidad.
        memoria.commitFalla = false
        assertTrue(nb.adoptarSiSano(configOk, paqueteOk).sano)
        assertEquals(configOk, memoria.c)
    }

    @Test
    fun siElDiscoFallaDespuesDeTocarLaMemoria_laActivaSigueSiendoLaAnterior() = runBlocking {
        // A persistida y activa; adoptar B con un almacén que cambia su memoria y luego falla:
        // comprobar() y conexionSana() tienen que seguir en A (la activa vive en NodeBridge, no en
        // una relectura del almacén).
        val memoria = Memoria()
        val b = configOk.copy(electrum = "ssl://candidato.ejemplo.local:50002", nombre = "B")
        val nb = NodeBridgeImpl(memoria, CoroutineScope(Dispatchers.Unconfined), { false }, { }, { c, _ -> info(7u).copy(servidor = c.electrum) }, Dispatchers.Unconfined)
        assertTrue(nb.configurar(configOk).sano)
        memoria.commitFallaTrasTocarMemoria = true
        val e = nb.adoptarSiSano(b, paqueteOk)
        assertTrue("$e", e is EstadoNodo.Caido && e.ultimoError.contains("no se pudo guardar"))
        assertEquals("la memoria del almacén quedó con B (eso es lo que hace SharedPreferences)…", b, memoria.c)
        assertEquals("…pero la activa es A", configOk, nb.config)
        val c = nb.comprobar()
        assertTrue("$c", c is EstadoNodo.Sano && c.info.servidor == configOk.electrum)
        assertEquals(configOk.electrum, nb.conexionSana()!!.url)
        // configurar() desde Ajustes, con el mismo fallo, tampoco mueve la activa.
        memoria.commitFallaTrasTocarMemoria = true
        assertTrue(nb.configurar(b) is EstadoNodo.Caido)
        assertEquals(configOk, nb.config)
    }

    @Test
    fun descartarUnCandidatoLentoNoRompeElNodoVigente() = runBlocking {
        // A sano a altura 100; B (candidato) responde lento con 500 y se descarta: A tiene que seguir
        // sano a su altura, porque el resultado del candidato no toca la altura vista del vigente.
        val memoria = Memoria()
        val entrado = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        val b = configOk.copy(electrum = "ssl://candidato.ejemplo.local:50002")
        val nb = NodeBridgeImpl(memoria, CoroutineScope(Dispatchers.Unconfined), { false }, { }, { c, min ->
            if (c.electrum == b.electrum) { entrado.countDown(); soltar.await(); info(500u) }
            else { if (100u < min) throw RuntimeException("altura 100 inferior a la vista $min"); info(100u) }
        }, Dispatchers.IO)
        assertTrue(nb.configurar(configOk).sano)
        val adopcion = launch(Dispatchers.Default) { nb.adoptarSiSano(b, null) }
        entrado.await()
        adopcion.cancel()
        soltar.countDown()
        adopcion.join()
        assertEquals("sigue A", configOk, memoria.c)
        val e = nb.comprobar()
        assertTrue("A sigue sano a su altura: $e", e is EstadoNodo.Sano && e.info.altura == 100u)
        // También si el candidato NO se cancela pero no se adopta (probar).
        assertTrue(nb.probar(b.copy(electrum = "ssl://otro.ejemplo.local:50002")).sano)
        assertTrue(nb.comprobar().sano)
    }

    @Test
    fun probarOtroNodoNoMueveLaAlturaVista() = runBlocking {
        var altura = 100u
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, min -> if (altura < min) throw RuntimeException("altura $altura inferior a la vista $min") else info(altura) }, Dispatchers.Unconfined)
        assertTrue(nb.configurar(configOk).sano)
        // Se prueba otro nodo muy adelantado…
        altura = 500u
        assertTrue(nb.probar(configOk.copy(electrum = "ssl://otro.ejemplo.local:50002")).sano)
        // …y el configurado, a su altura de antes, sigue siendo válido: la prueba no subió el mínimo.
        altura = 100u
        assertTrue("${nb.comprobar()}", nb.comprobar().sano)
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
