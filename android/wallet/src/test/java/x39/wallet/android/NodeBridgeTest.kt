package x39.wallet.android

import kotlinx.coroutines.CancellationException
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
        /** Cuántas veces se ha INTENTADO escribir (aunque el commit fallara). */
        var escrituras = 0
        override fun leer() = c
        override fun guardar(c: ConfigNodo, paquete: PaqueteEmparejamiento?) {
            escrituras++
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
        // ssl:// sin huella: el motor valida el certificado contra las autoridades públicas (cualquier nodo con certificado reconocido).
        assertNull(configOk.copy(huellaServidor = null).invalida())
        assertNull(configOk.copy(electrum = AjustesActivity.SERVIDOR_PUBLICO_SIGNET, huellaServidor = null).invalida())
        assertTrue(configOk.copy(electrum = "tcp://nodo:50001").invalida()!!.contains("solo se comprueba con ssl://"))
        assertNull(configOk.copy(electrum = "tcp://nodo:50001", huellaServidor = null).invalida())
        assertTrue(configOk.copy(tunel = ModoTunel.PROPIO).invalida()!!.contains("todavía"))
        // Puerto y host.
        assertTrue(configOk.copy(electrum = "tcp://nodo", huellaServidor = null).invalida()!!.contains("host:puerto"))
        assertTrue(configOk.copy(electrum = "tcp://nodo:0", huellaServidor = null).invalida()!!.contains("host:puerto"))
        assertTrue(configOk.copy(electrum = "tcp://nodo:70000", huellaServidor = null).invalida()!!.contains("host:puerto"))
        assertTrue(configOk.copy(electrum = "tcp://:50001", huellaServidor = null).invalida()!!.contains("host:puerto"))
        assertNull(configOk.copy(electrum = "tcp://[::1]:50001", huellaServidor = null).invalida())
        assertEquals("[::1]", configOk.copy(electrum = "tcp://[::1]:50001").host())
        assertEquals("nodo.ejemplo.local", configOk.host())
        // .onion exige proxy SOCKS5; con proxy bien formado vale; proxy mal formado se rechaza.
        val onion = configOk.copy(electrum = "tcp://abcdefghijklmnop.onion:50001", huellaServidor = null)
        assertTrue(onion.esOnion())
        assertTrue(onion.invalida()!!.contains("proxy SOCKS5"))
        assertNull(onion.copy(proxySocks5 = "127.0.0.1:9050").invalida())
        assertNull(onion.copy(proxySocks5 = "[::1]:9050").invalida())
        assertTrue(onion.copy(proxySocks5 = "127.0.0.1").invalida()!!.contains("IP:puerto"))
        assertTrue(onion.copy(proxySocks5 = "localhost:0").invalida()!!.contains("IP:puerto"))
        assertTrue(onion.copy(proxySocks5 = ":9050").invalida()!!.contains("IP:puerto"))
        assertTrue(onion.copy(proxySocks5 = "   ").invalida()!!.contains("proxy SOCKS5"))
        // El proxy es una IP literal, nunca un nombre: un nombre habría que resolverlo AQUÍ por DNS.
        for (nombre in listOf("localhost:9050", "orbot.local:9050", "proxy.ejemplo.onion:9050", "[localhost]:9050", "1.2.3:9050", "256.1.1.1:9050", "[::1]9050", "[::1]:9050:1", "[]:9050", "[:::]:9050")) {
            assertTrue(nombre, onion.copy(proxySocks5 = nombre).invalida()!!.contains("IP:puerto"))
        }
        for (ip in listOf("10.0.0.1:9050", "192.168.1.1:1", "[fe80::1]:9050", "[2001:db8::1]:65535", "[::ffff:127.0.0.1]:9050", " 127.0.0.1:9050 ")) {
            assertNull(ip, onion.copy(proxySocks5 = ip).invalida())
        }
        assertTrue(ConfigNodo.esIpv4Literal("127.0.0.1")); assertTrue(!ConfigNodo.esIpv4Literal("127.0.0.256")); assertTrue(!ConfigNodo.esIpv4Literal("localhost"))
        assertTrue(ConfigNodo.esIpv6Literal("::1")); assertTrue(ConfigNodo.esIpv6Literal("1:2:3:4:5:6:7:8")); assertTrue(!ConfigNodo.esIpv6Literal("1::2::3")); assertTrue(!ConfigNodo.esIpv6Literal(":::")); assertTrue(!ConfigNodo.esIpv6Literal("orbot"))
        assertTrue(configOk.copy(electrum = "ssl://x.ONION:1").esOnion())
        assertTrue(!configOk.copy(electrum = "ssl://onion.ejemplo.local:1").esOnion())
        // Un nodo normal también puede ir por proxy (opcional).
        assertNull(configOk.copy(proxySocks5 = "127.0.0.1:9050").invalida())
    }

    /** Doble del punto de conexión (G3): cuenta intentos, anota el destino y nunca abre red. */
    private class ConexionContada(var respuesta: () -> NodoInfo) : ConexionNodo {
        var intentos = 0
        val destinos = mutableListOf<String>()
        override fun comprobar(c: ConfigNodo, alturaMin: UInt): NodoInfo { intentos++; destinos += c.electrum; return respuesta() }
    }

    /** Doble de la detección del túnel (G3): devuelve lo que se le diga y cuenta consultas. */
    private class DetectorFijo(var diagnostico: DiagnosticoTunel) : DetectorTunel {
        var consultas = 0
        override fun diagnosticar(): DiagnosticoTunel { consultas++; return diagnostico }
    }

    private val sinVpn = DiagnosticoTunel(EstadoTunel.SIN_TUNEL, "redes VPN: 0, ninguna con ruta a 10.39.0.0/24")
    private val conRuta = DiagnosticoTunel(EstadoTunel.CON_RUTA, "redes VPN: 1, con ruta a 10.39.0.0/24: 1")
    private val noConcluyente = DiagnosticoTunel(EstadoTunel.DESCONOCIDO, "sin ConnectivityManager")
    private val caidaDeRed: () -> NodoInfo = { throw x39.wallet.Exception.Red("no se pudo conectar con 10.39.0.3:50001: Network is unreachable (os error 101)") }

    /**
     * Orden 7, G1/G3 (a): detección «sin VPN» → CERO intentos de conexión, texto fijo de inmediato y
     * sin pasar por «conectando…». Vale para configurar, comprobar, conexionSana, probar y adoptar.
     */
    @Test
    fun g3a_sinVpn_ceroIntentosDeConexion_textoInmediato_sinConectando() = runBlocking {
        val conexion = ConexionContada(caidaDeRed)
        val detector = DetectorFijo(sinVpn)
        val registro = mutableListOf<String>()
        val memoria = Memoria()
        val nb = NodeBridgeImpl(memoria, CoroutineScope(Dispatchers.Unconfined), { true /* otra VPN activa: irrelevante */ }, { }, conexion, Dispatchers.Unconfined,
            diagnosticoTunel = detector, registrar = { registro += it })
        val estados = mutableListOf<EstadoNodo>()
        val vigia = CoroutineScope(Dispatchers.Unconfined).launch { nb.estado.collect { estados += it } }
        val cfg = NodoX39.config(Red.MAINNET)!!
        val apagado = nb.configurar(cfg) as EstadoNodo.Caido
        assertEquals("sin túnel no se abre socket ni se llama al motor", 0, conexion.intentos)
        assertEquals("la detección se consultó antes de conectar", 1, detector.consultas)
        assertEquals("Túnel apagado. Activa el X39 Túnel; la billetera no se conecta por otra vía.", apagado.ultimoError)
        assertTrue("$estados", estados.none { it is EstadoNodo.Conectando })
        assertTrue("$estados", nb.estado.value is EstadoNodo.Caido)
        assertEquals("se guarda igual: es el nodo predeterminado", cfg, memoria.c)
        assertNull(nb.conexionSana()); assertEquals(0, conexion.intentos)
        assertTrue(nb.comprobar() is EstadoNodo.Caido); assertEquals(0, conexion.intentos)
        assertTrue(nb.probar(cfg) is EstadoNodo.Caido); assertEquals(0, conexion.intentos)
        assertTrue(nb.adoptarSiSano(cfg) is EstadoNodo.Caido); assertEquals(0, conexion.intentos)
        assertTrue("$estados", estados.none { it is EstadoNodo.Conectando })
        vigia.cancel()
        assertTrue("$registro", registro.any { it.contains("túnel apagado, no se abre socket") && it.contains("SIN_TUNEL") })
        for (l in registro) assertTrue(l, !l.contains("10.39.0.3") && !l.contains("50001") && !l.contains("tcp://"))
        // En «Mi nodo» no hay puerta: el mismo detector «sin VPN» no impide nada.
        val propio = ConexionContada { info(5u) }
        val nbPropio = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, propio, Dispatchers.Unconfined, diagnosticoTunel = detector)
        assertTrue(nbPropio.configurar(configOk).sano)
        assertEquals(1, propio.intentos)
    }

    /** G3 (b): detección «VPN con ruta» → exactamente UN intento, y solo a 10.39.0.3:50001. */
    @Test
    fun g3b_vpnConRuta_unSoloIntento_soloAlNodoX39() = runBlocking {
        val conexion = ConexionContada(caidaDeRed)
        val detector = DetectorFijo(conRuta)
        val registro = mutableListOf<String>()
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { true }, { }, conexion, Dispatchers.Unconfined,
            diagnosticoTunel = detector, registrar = { registro += it })
        val cfg = NodoX39.config(Red.MAINNET)!!
        val noResponde = nb.configurar(cfg) as EstadoNodo.Caido
        assertEquals(1, conexion.intentos)
        assertEquals(listOf("tcp://10.39.0.3:50001"), conexion.destinos)
        // G6: texto fijo, sin el motivo ni la dirección; el motivo está en el registro.
        assertEquals(NodoX39.MENSAJE_NODO_NO_RESPONDE, noResponde.ultimoError)
        assertTrue("$registro", registro.any { it.contains("conexión fallida") && it.contains("red inalcanzable") && it.contains("diagnóstico del túnel: CON_RUTA") })
        // Nodo que responde: Sano, y el texto del túnel no aparece.
        conexion.respuesta = { info(5u, cifrado = false) }
        val sano = nb.comprobar()
        assertTrue("$sano", sano is EstadoNodo.Sano && sano.sinCifrar)
        assertEquals(2, conexion.intentos)
        assertEquals(listOf("tcp://10.39.0.3:50001", "tcp://10.39.0.3:50001"), conexion.destinos)
        assertEquals(cfg.electrum, nb.conexionSana()!!.url)
        assertEquals(3, conexion.intentos)
        // Otra red: rechazo del nodo, no del túnel.
        conexion.respuesta = { throw x39.wallet.Exception.RedServidor("se esperaba bitcoin, el servidor anuncia génesis 00000008819873…") }
        assertTrue(nb.comprobar() is EstadoNodo.RedIncorrecta)
        assertTrue("$registro", registro.any { it.contains("nodo sano por el túnel") } && registro.any { it.contains("otra red") })
        for (l in registro) assertTrue(l, !l.contains("10.39.0.3") && !l.contains("50001") && !l.contains("tcp://"))
    }

    /** G3 (c): detección «no concluyente» (DESCONOCIDO) → CERO intentos en modo X-39: exige ruta verificada. */
    @Test
    fun g3c_deteccionNoConcluyente_unIntento_nuncaSeBloqueaPorUnaDuda() = runBlocking {
        val conexion = ConexionContada(caidaDeRed)
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, conexion, Dispatchers.Unconfined,
            diagnosticoTunel = DetectorFijo(noConcluyente), registrar = { })
        val cfg = NodoX39.config(Red.MAINNET)!!
        val e = nb.configurar(cfg) as EstadoNodo.Caido
        assertEquals(0, conexion.intentos)
        assertTrue(conexion.destinos.isEmpty())
        assertEquals(NodoX39.MENSAJE_TUNEL_DESCONOCIDO, e.ultimoError)
        // Un detector que LANZA cuenta como DESCONOCIDO y tampoco abre socket en modo X-39.
        val conexion2 = ConexionContada { info(9u, cifrado = false) }
        val nb2 = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, conexion2, Dispatchers.Unconfined,
            diagnosticoTunel = { throw IllegalStateException("sin permiso") }, registrar = { })
        val e2 = nb2.probar(cfg) as EstadoNodo.Caido
        assertEquals(0, conexion2.intentos)
        assertEquals(NodoX39.MENSAJE_TUNEL_DESCONOCIDO, e2.ultimoError)
        // Sin detector inyectado (DESCONOCIDO por defecto) tampoco abre socket en modo X-39.
        val conexion3 = ConexionContada { info(9u, cifrado = false) }
        val nb3 = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, conexion3, Dispatchers.Unconfined, registrar = { })
        val e3 = nb3.probar(cfg) as EstadoNodo.Caido
        assertEquals(0, conexion3.intentos)
        assertEquals(NodoX39.MENSAJE_TUNEL_DESCONOCIDO, e3.ultimoError)
        // Los tres textos del modo X-39 son distintos entre sí.
        val textos = listOf(sinVpn, conRuta, noConcluyente).map { d ->
            val c = ConexionContada(caidaDeRed)
            (NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, c, Dispatchers.Unconfined, diagnosticoTunel = DetectorFijo(d), registrar = { }).probar(cfg) as EstadoNodo.Caido).ultimoError
        }
        assertEquals(3, textos.toSet().size)
    }

    /** G6: una cancelación en mitad de la comprobación no produce NINGÚN texto de error. */
    @Test
    fun g6_unaCancelacionEnMitadDeLaComprobacion_noProduceTextoDeError() = runBlocking {
        // 1. El punto de conexión se cancela (CancellationException): se relanza, no se traduce.
        val registro = mutableListOf<String>()
        val conexion = ConexionContada { throw CancellationException("Job was cancelled") }
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, conexion, Dispatchers.Unconfined,
            diagnosticoTunel = DetectorFijo(conRuta), registrar = { registro += it })
        val cfg = NodoX39.config(Red.MAINNET)!!
        var relanzada = false
        try { nb.configurar(cfg) } catch (e: CancellationException) { relanzada = true }
        assertTrue("la cancelación se relanza tal cual", relanzada)
        assertTrue("no se publica ningún texto: ${nb.estado.value}", nb.estado.value !is EstadoNodo.Caido)
        assertTrue("$registro", registro.none { it.lowercase().contains("cancel") })
        relanzada = false
        try { nb.probar(configOk) } catch (e: CancellationException) { relanzada = true }
        assertTrue(relanzada)
        // 2. Cancelación real de la corrutina mientras el servidor tarda: el estado vigente no
        // recibe ningún texto de error, ni «Job was cancelled» ni ningún otro.
        val entrado = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        val lenta = ConexionContada { entrado.countDown(); soltar.await(); info(5u) }
        val nb2 = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, lenta, Dispatchers.IO, diagnosticoTunel = DetectorFijo(conRuta), registrar = { registro += it })
        soltar.countDown()
        assertTrue(nb2.configurar(cfg).sano)
        assertTrue(entrado.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val entrado2 = java.util.concurrent.CountDownLatch(1)
        val soltar2 = java.util.concurrent.CountDownLatch(1)
        lenta.respuesta = { entrado2.countDown(); soltar2.await(); info(6u) }
        val trabajo = launch(Dispatchers.Default) { nb2.comprobar() }
        entrado2.await()
        trabajo.cancel()
        soltar2.countDown()
        trabajo.join()
        val e = nb2.estado.value
        assertTrue("$e", e !is EstadoNodo.Caido)
        assertTrue("$registro", registro.none { it.lowercase().contains("cancel") })
    }

    /** G6: una excepción genérica produce el texto fijo, y nunca su mensaje. */
    @Test
    fun g6_unaExcepcionGenericaProduceElTextoFijo_nuncaSuMensaje() = runBlocking {
        val secreto = "fallo interno 203.0.113.7:50001 ruta /data/x"
        val registro = mutableListOf<String>()
        val cfg = NodoX39.config(Red.MAINNET)!!
        for ((d, esperado) in listOf(conRuta to NodoX39.MENSAJE_NODO_NO_RESPONDE, noConcluyente to NodoX39.MENSAJE_TUNEL_DESCONOCIDO)) {
            for (ex in listOf<Exception>(RuntimeException(secreto), IllegalStateException(secreto), x39.wallet.Exception.Red(secreto), java.io.IOException(secreto), NullPointerException())) {
                val conexion = ConexionContada { throw ex }
                val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, conexion, Dispatchers.Unconfined, diagnosticoTunel = DetectorFijo(d), registrar = { registro += it })
                val e = nb.configurar(cfg) as EstadoNodo.Caido
                assertEquals("${ex.javaClass.simpleName} con ${d.estado}", esperado, e.ultimoError)
                assertTrue(e.ultimoError, !e.ultimoError.contains("fallo interno") && !e.ultimoError.contains("203.0.113") && !e.ultimoError.contains("NullPointer"))
                assertEquals(if (d == conRuta) 1 else 0, conexion.intentos)
            }
        }
        // El detalle está en el registro, sin direcciones.
        assertTrue("$registro", registro.any { it.contains("fallo interno") && it.contains("<ip>") })
        for (l in registro) assertTrue(l, !l.contains("203.0.113") && !l.contains("10.39.0.3") && !l.contains("50001"))
        // Y la pantalla principal no añade el mensaje: `describir()` devuelve el mismo texto fijo.
        assertEquals(NodoX39.MENSAJE_NODO_NO_RESPONDE, EstadoNodo.Caido(0, NodoX39.MENSAJE_NODO_NO_RESPONDE, 0).describir())
        // Con el túnel apagado, el mismo texto fijo de siempre, también sin motivo.
        val conexion = ConexionContada { throw RuntimeException(secreto) }
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, conexion, Dispatchers.Unconfined, diagnosticoTunel = DetectorFijo(sinVpn), registrar = { })
        assertEquals(NodoX39.MENSAJE_TUNEL, (nb.configurar(cfg) as EstadoNodo.Caido).ultimoError)
        assertEquals(0, conexion.intentos)
        // El rechazo por altura es un texto propio de la app (el servidor respondió): se conserva.
        val atras = ConexionContada { throw x39.wallet.Exception.Red("servidor Electrum: el servidor va por detrás: altura 100, ya se había visto 200") }
        val nb2 = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, atras, Dispatchers.Unconfined, diagnosticoTunel = DetectorFijo(conRuta), registrar = { })
        assertEquals("ese nodo va por detrás de lo ya visto: bloque 100 frente a 200", (nb2.probar(cfg) as EstadoNodo.Caido).ultimoError)
    }

    /** Orden 6: el registro no lleva la dirección del servidor del usuario, ni su proxy, ni ninguna IP ni .onion. */
    @Test
    fun elRegistroNoLlevaDireccionesDelNodoDelUsuario() = runBlocking {
        val registro = mutableListOf<String>()
        val onion = ConfigNodo(Red.SIGNET, "tcp://abcdefghijklmnopqrstuvwxyz234567.onion:50001", null, ModoTunel.NINGUNO, "mi nodo", "127.0.0.1:9050")
        var respuesta: () -> NodoInfo = { throw x39.wallet.Exception.Red("proxy SOCKS5 127.0.0.1:9050: no pudo conectar con abcdefghijklmnopqrstuvwxyz234567.onion:50001: servidor inalcanzable") }
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> respuesta() }, Dispatchers.Unconfined, registrar = { registro += it })
        assertTrue(nb.probar(onion) is EstadoNodo.Caido)
        respuesta = { throw x39.wallet.Exception.HuellaServidor("el servidor presenta la huella ab…, no la esperada") }
        assertTrue(nb.probar(configOk) is EstadoNodo.HuellaIncorrecta)
        respuesta = { throw x39.wallet.Exception.RedServidor("se esperaba signet, el servidor nodo.ejemplo.local:50002 anuncia génesis …") }
        assertTrue(nb.probar(configOk) is EstadoNodo.RedIncorrecta)
        // El motor solo nombra el host configurado (y, si acaso, IP sueltas): ambos se limpian.
        respuesta = { throw x39.wallet.Exception.Red("resolviendo nodo.ejemplo.local:50002: failed to lookup address; también 192.0.2.46:50002") }
        assertTrue(nb.probar(configOk) is EstadoNodo.Caido)
        respuesta = { throw x39.wallet.Exception.Red("no se pudo conectar con [2001:db8::1]:50002: Network is unreachable") }
        assertTrue(nb.probar(configOk.copy(electrum = "ssl://[2001:db8::1]:50002")) is EstadoNodo.Caido)
        assertEquals(5, registro.size)
        for (l in registro) {
            assertTrue(l, l.startsWith("Mi nodo: "))
            for (prohibido in listOf(".onion", "127.0.0.1", "9050", "nodo.ejemplo.local", "50002", "2001:db8", "192.0.2.46", "tcp://", "ssl://")) {
                assertTrue("«$prohibido» en: $l", !l.contains(prohibido))
            }
        }
        assertTrue("$registro", registro[0].contains("<proxy>") && registro[0].contains("<servidor>"))
        // El nodo sano en modo «Mi nodo» no se registra (nada que diagnosticar).
        respuesta = { info(7u) }
        assertTrue(nb.probar(configOk).sano)
        assertEquals(5, registro.size)
        // sinDirecciones también limpia IP:puerto sueltas y [IPv6]:puerto.
        assertEquals("a <ip> y <ip> y <servidor>", sinDirecciones("a 10.39.0.3:50001 y [fe80::1]:1 y tcp://x.onion:1", configOk))
    }

    @Test
    fun conexionSanaLlevaElProxyDeLaMismaConfiguracion() = runBlocking {
        var vista: ConfigNodo? = null
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { c, _ -> vista = c; info(5u, cifrado = false) }, Dispatchers.Unconfined)
        val onion = ConfigNodo(Red.SIGNET, "tcp://abcdefghijklmnop.onion:50001", null, ModoTunel.NINGUNO, "tor", "127.0.0.1:9050")
        assertTrue(nb.configurar(onion).sano)
        assertEquals("el motor recibe la configuración entera, proxy incluido", onion, vista)
        val cm = nb.conexionSana()!!
        assertEquals("127.0.0.1:9050", cm.proxy)
        assertNull(cm.huella)
        // Sin proxy configurado, la conexión no lleva ninguno.
        assertTrue(nb.configurar(configOk).sano)
        assertNull(nb.conexionSana()!!.proxy)
        // Un .onion sin proxy no llega al motor: se rechaza antes.
        var sondas = 0
        val nb2 = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> sondas++; info(5u) }, Dispatchers.Unconfined)
        val e = nb2.probar(onion.copy(proxySocks5 = null))
        assertTrue("$e", e is EstadoNodo.Caido && e.ultimoError.contains("proxy SOCKS5"))
        assertEquals(0, sondas)
    }

    @Test
    fun olvidarDejaLoQueDigaElAlmacen() = runBlocking {
        // Almacén que, al borrar, vuelve a un nodo por defecto (como Ajustes con el nodo X-39 elegido).
        class ConDefecto(val defecto: ConfigNodo) : AlmacenConfigNodo {
            var c: ConfigNodo? = null
            override fun leer() = c ?: defecto
            override fun guardar(c: ConfigNodo, paquete: PaqueteEmparejamiento?) { this.c = c }
            override fun borrar() { c = null }
        }
        val x39 = NodoX39.config(Red.SIGNET)!!
        val nb = NodeBridgeImpl(ConDefecto(x39), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> info(5u, cifrado = false) }, Dispatchers.Unconfined, diagnosticoTunel = { DiagnosticoTunel(EstadoTunel.CON_RUTA, "") })
        assertTrue(nb.configurar(configOk).sano)
        nb.olvidar()
        assertEquals("tras olvidar queda el nodo por defecto, pendiente de comprobar", EstadoNodo.Conectando, nb.estado.value)
        assertEquals(x39, nb.config)
        assertTrue(nb.comprobar().sano)
        // Y con el almacén vacío (Memoria), olvidar sigue dejando SinConfigurar.
        val nb2 = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> info(5u) }, Dispatchers.Unconfined)
        assertTrue(nb2.configurar(configOk).sano)
        nb2.olvidar()
        assertEquals(EstadoNodo.SinConfigurar, nb2.estado.value)
    }

    @Test
    fun losErroresDeRedSalenEnLenguajeLlano_conservandoElOriginal() {
        assertEquals("nadie responde en esa dirección y puerto (no se pudo conectar con 10.0.0.1:50001: Connection refused)",
            mensajeLlano("no se pudo conectar con 10.0.0.1:50001: Connection refused (os error 111)"))
        assertTrue(mensajeLlano("tls: invalid peer certificate: UnknownIssuer").startsWith("el certificado del servidor no lo reconoce ninguna autoridad"))
        assertTrue(mensajeLlano("resolviendo nodo.inexistente:50001: failed to lookup address information").startsWith("no se encuentra ese nombre de servidor"))
        assertTrue(mensajeLlano("no se pudo conectar con 1.2.3.4:1: timed out").startsWith("sin respuesta a tiempo"))
        assertTrue(mensajeLlano("Network is unreachable (os error 101)").startsWith("red inalcanzable"))
        // Lo que ya dice el motor sobre el proxy se deja tal cual; lo desconocido, también.
        assertEquals("proxy SOCKS5: no pudo conectar con x.onion:1: servidor inalcanzable", mensajeLlano("proxy SOCKS5: no pudo conectar con x.onion:1: servidor inalcanzable"))
        assertEquals("protocolo Electrum 1.2 no admitido (hace falta 1.4)", mensajeLlano("protocolo Electrum 1.2 no admitido (hace falta 1.4)"))
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

    /**
     * Génesis (auditoría del cierre): NADA se escribe antes de que el motor haya validado el nodo
     * entero. Un servidor que contesta a `server.version` pero falla en `server.features` (el que
     * trae el `genesis_hash`) llega aquí como una excepción del motor, igual que un génesis de otra
     * red o una huella distinta: `adoptarSiSano` no toca el almacén (cero intentos de escritura), ni
     * el estado publicado, ni la configuración activa. Solo con `Sano` hay UNA escritura, y después.
     */
    @Test
    fun adoptarSiSano_servidorQueRespondeVersionPeroFallaEnFeatures_noEscribeNada() = runBlocking {
        val memoria = Memoria()
        var llamadasAlMotor = 0
        var respuesta: () -> NodoInfo = { throw x39.wallet.Exception.Red("servidor Electrum: server.features: fallo interno del servidor") }
        val nb = NodeBridgeImpl(memoria, CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> llamadasAlMotor++; respuesta() }, Dispatchers.Unconfined)
        // server.version bien, server.features mal → el motor lanza; aquí: Caido, y cero escrituras.
        val e = nb.adoptarSiSano(configOk)
        assertTrue("$e", e is EstadoNodo.Caido && (e as EstadoNodo.Caido).ultimoError.contains("server.features"))
        assertEquals(0, memoria.escrituras)
        assertNull(memoria.c)
        assertNull(nb.config)
        assertEquals(EstadoNodo.SinConfigurar, nb.estado.value)
        assertNull(nb.conexionSana())
        // Génesis de otra red (RedServidor): igual.
        respuesta = { throw x39.wallet.Exception.RedServidor("se esperaba signet, el servidor anuncia génesis 000000000019d6…") }
        val r = nb.adoptarSiSano(configOk)
        assertTrue("$r", r is EstadoNodo.RedIncorrecta)
        assertEquals(0, memoria.escrituras)
        assertNull(nb.config)
        assertEquals(EstadoNodo.SinConfigurar, nb.estado.value)
        // `probar` tampoco escribe nunca, ni con el nodo sano.
        respuesta = { info(42u) }
        assertTrue(nb.probar(configOk).sano)
        assertEquals(0, memoria.escrituras)
        assertNull(nb.config)
        // Sano por `adoptarSiSano`: una escritura, y solo entonces cambia la configuración activa.
        assertTrue(nb.adoptarSiSano(configOk).sano)
        assertEquals(1, memoria.escrituras)
        assertEquals(configOk, nb.config)
        assertEquals(4, llamadasAlMotor)
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

    /** Doble de la señal de red (H2): guarda el oyente, cuenta si se escucha y permite emitir. */
    private class SenalSimulada : SenalRed {
        var alCambiar: (() -> Unit)? = null
        var escuchando = false
        var registros = 0
        override fun escuchar(alCambiar: () -> Unit): () -> Unit {
            this.alCambiar = alCambiar; escuchando = true; registros++
            return { escuchando = false; this.alCambiar = null }
        }
        fun senal() { alCambiar?.invoke() }
    }

    /**
     * Doble de la espera de la vigilancia (H2): anota cada espera; una espera larga (la de la
     * reconexión o los 60 s) no termina nunca por sí sola —solo una señal de red puede
     * interrumpirla—; el rebote espera a que la prueba lo suelte con `soltarRebote()`.
     */
    private class EsperaControlada {
        val esperas = mutableListOf<Long>()
        @Volatile var rebote = kotlinx.coroutines.CompletableDeferred<Unit>()
        val esperar: suspend (Long) -> Unit = { ms ->
            esperas += ms
            if (ms == NodeBridgeImpl.REBOTE_MS) rebote.await() else kotlinx.coroutines.awaitCancellation()
        }
        fun soltarRebote() { val r = rebote; rebote = kotlinx.coroutines.CompletableDeferred(); r.complete(Unit) }
        fun largas() = esperas.filter { it != NodeBridgeImpl.REBOTE_MS }
    }

    /**
     * Orden 8, H2 (a)(b)(c): con el túnel apagado y la vigilancia en espera larga, una señal de red
     * provoca la comprobación ENSEGUIDA (sin esperar la espera creciente, que vuelve a empezar);
     * esa comprobación pasa por la puerta (si sigue sin túnel, sigue sin sockets); cuando el
     * detector ve la ruta, el siguiente despertar conecta y el nodo pasa a sano; al desactivar la
     * vigilancia se deja de escuchar y una señal tardía no hace nada.
     */
    @Test
    fun h2_unaSenalDeRedDespiertaLaVigilancia_pasaPorLaPuerta_ySeRetiraAlDesactivar() = runBlocking {
        val conexion = ConexionContada(caidaDeRed)
        val detector = DetectorFijo(sinVpn)
        val senal = SenalSimulada()
        val espera = EsperaControlada()
        val registro = mutableListOf<String>()
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, espera.esperar, conexion, Dispatchers.Unconfined,
            diagnosticoTunel = detector, registrar = { registro += it }, senalRed = senal)
        val cfg = NodoX39.config(Red.MAINNET)!!
        assertTrue(nb.configurar(cfg) is EstadoNodo.Caido)
        assertTrue("antes de vigilar no se escucha la red", !senal.escuchando)
        nb.vigilar(true)
        assertTrue("al activar la vigilancia se escucha la red", senal.escuchando)
        assertEquals("primera comprobación de la vigilancia", 2, detector.consultas)
        assertEquals(0, conexion.intentos)
        assertEquals("la vigilancia está en su espera creciente (≈2 s)", 1, espera.largas().size)
        assertTrue("${espera.largas()}", espera.largas()[0] in 2_000..2_400)
        // (a)+(b): señal con el túnel todavía apagado → comprobación inmediata por la puerta: 0 sockets.
        senal.senal(); espera.soltarRebote()
        assertEquals("comprobación inmediata tras la señal", 3, detector.consultas)
        assertEquals("sigue sin túnel: sigue sin socket", 0, conexion.intentos)
        assertEquals("la espera creciente vuelve a empezar (≈2 s, no 4 s)", 2, espera.largas().size)
        assertTrue("${espera.largas()}", espera.largas()[1] in 2_000..2_400)
        assertTrue("$registro", registro.any { it.contains("cambio de red: se comprueba el nodo ahora") })
        assertEquals(NodoX39.MENSAJE_TUNEL, (nb.estado.value as EstadoNodo.Caido).ultimoError)
        // (a): aparece la VPN con ruta y el nodo responde → al despertar, UN intento y nodo sano.
        detector.diagnostico = conRuta
        conexion.respuesta = { info(5u, cifrado = false) }
        senal.senal(); espera.soltarRebote()
        assertEquals(1, conexion.intentos)
        assertEquals(listOf("tcp://10.39.0.3:50001"), conexion.destinos)
        assertTrue("${nb.estado.value}", nb.estado.value.sano)
        assertEquals("sano: la siguiente espera es la de 60 s", 60_000L, espera.largas().last())
        // Se apaga el túnel con la app abierta: la señal llega, la puerta corta sin socket.
        detector.diagnostico = sinVpn
        senal.senal(); espera.soltarRebote()
        assertEquals("sin túnel: ni un socket más", 1, conexion.intentos)
        assertEquals(NodoX39.MENSAJE_TUNEL, (nb.estado.value as EstadoNodo.Caido).ultimoError)
        // (c): al desactivar se deja de escuchar; una señal tardía no comprueba nada.
        val consultas = detector.consultas
        nb.vigilar(false)
        assertTrue("vigilar(false) retira el callback", !senal.escuchando)
        senal.senal()
        assertEquals(consultas, detector.consultas)
        assertEquals(1, conexion.intentos)
        // Y al reactivar, se vuelve a escuchar (un registro por activación).
        nb.vigilar(true)
        assertTrue(senal.escuchando); assertEquals(2, senal.registros)
        nb.vigilar(false)
        assertTrue(!senal.escuchando)
        for (l in registro) assertTrue(l, !l.contains("10.39.0.3") && !l.contains("50001"))
    }

    /** H2 (d): varias señales seguidas no producen más de una comprobación por rebote. */
    @Test
    fun h2_variasSenalesSeguidas_unaSolaComprobacionPorRebote() = runBlocking {
        val conexion = ConexionContada(caidaDeRed)
        val detector = DetectorFijo(sinVpn)
        val senal = SenalSimulada()
        val espera = EsperaControlada()
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, espera.esperar, conexion, Dispatchers.Unconfined,
            diagnosticoTunel = detector, registrar = { }, senalRed = senal)
        nb.configurar(NodoX39.config(Red.MAINNET)!!)
        nb.vigilar(true)
        val antes = detector.consultas
        // Ráfaga: onAvailable, onLinkPropertiesChanged ×3, onLost… dentro del mismo rebote.
        repeat(5) { senal.senal() }
        assertEquals("durante el rebote no se comprueba", antes, detector.consultas)
        assertEquals("un rebote en marcha", 1, espera.esperas.count { it == NodeBridgeImpl.REBOTE_MS })
        espera.soltarRebote()
        assertEquals("una sola comprobación para toda la ráfaga", antes + 1, detector.consultas)
        assertEquals("y después, la vigilancia vuelve a su espera", 2, espera.largas().size)
        assertEquals(0, conexion.intentos)
        // Otra ráfaga: otra única comprobación.
        repeat(3) { senal.senal() }
        espera.soltarRebote()
        assertEquals(antes + 2, detector.consultas)
        nb.vigilar(false)
    }

    /**
     * H2: una señal con el nodo sano y el túnel todavía con ruta no abre un socket más (es el caso
     * del `onAvailable` inicial del sistema al registrar el callback con la VPN ya levantada); la
     * vigilancia sigue con su espera. Si la señal no se puede escuchar, la vigilancia sigue como
     * antes, con la espera creciente.
     */
    @Test
    fun h2_senalConNodoSanoYTunelConRuta_noAbreOtroSocket_ySinSenalLaVigilanciaSigue() = runBlocking {
        val conexion = ConexionContada { info(5u, cifrado = false) }
        val detector = DetectorFijo(conRuta)
        val senal = SenalSimulada()
        val espera = EsperaControlada()
        val registro = mutableListOf<String>()
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, espera.esperar, conexion, Dispatchers.Unconfined,
            diagnosticoTunel = detector, registrar = { registro += it }, senalRed = senal)
        assertTrue(nb.configurar(NodoX39.config(Red.MAINNET)!!).sano)
        nb.vigilar(true)
        assertEquals(2, conexion.intentos)
        senal.senal(); espera.soltarRebote()
        assertEquals("nodo sano y túnel con ruta: nada que comprobar", 2, conexion.intentos)
        assertTrue("$registro", registro.any { it.contains("cambio de red sin efecto") })
        assertEquals(listOf(60_000L, 60_000L), espera.largas())
        // El túnel se cae: la misma señal ahora sí comprueba, por la puerta, sin socket.
        detector.diagnostico = sinVpn
        senal.senal(); espera.soltarRebote()
        assertEquals(2, conexion.intentos)
        assertEquals(NodoX39.MENSAJE_TUNEL, (nb.estado.value as EstadoNodo.Caido).ultimoError)
        nb.vigilar(false)
        // Señal que no se puede registrar (permiso): la vigilancia arranca igual y espera como antes.
        val esperas2 = mutableListOf<Long>()
        val registro2 = mutableListOf<String>()
        lateinit var nb2: NodeBridgeImpl
        nb2 = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { ms -> esperas2 += ms; if (esperas2.size >= 2) nb2.vigilar(false) },
            ConexionContada(caidaDeRed), Dispatchers.Unconfined, diagnosticoTunel = DetectorFijo(sinVpn), registrar = { registro2 += it },
            senalRed = { throw SecurityException("sin permiso") })
        nb2.configurar(NodoX39.config(Red.MAINNET)!!)
        nb2.vigilar(true)
        assertEquals(2, esperas2.size)
        assertTrue("${esperas2}", esperas2[0] in 2_000..2_400 && esperas2[1] in 4_000..4_800)
        assertTrue("$registro2", registro2.any { it.contains("sin señal de red") && it.contains("SecurityException") })
    }

    /** Motor simulado con la puerta de la altura: lanza el error del motor si el servidor va por detrás. */
    private fun motorConAltura(altura: () -> UInt): ConexionNodo = ConexionNodo { _, min ->
        val h = altura()
        if (h < min) throw x39.wallet.Exception.Red("servidor Electrum: el servidor va por detrás: altura $h, ya se había visto $min")
        info(h)
    }

    @Test
    fun sueloPersistido_rechazaUnNodoPorDebajo_enComprobarProbarYAdoptar_ySoloParaSuRed() = runBlocking {
        val mem = Memoria()
        // Suelo 200.000 para signet (la red de la billetera abierta); 0 para cualquier otra red.
        val suelo: (Red) -> UInt = { red -> if (red == Red.SIGNET) 200_000u else 0u }
        val nb = NodeBridgeImpl(mem, CoroutineScope(Dispatchers.Unconfined), { false }, { }, motorConAltura { 150_000u }, Dispatchers.Unconfined, alturaSuelo = suelo)
        val esperado = "ese nodo va por detrás de lo ya visto: bloque 150000 frente a 200000"
        val c = nb.configurar(configOk)
        assertTrue("$c", c is EstadoNodo.Caido && c.ultimoError == esperado)
        val p = nb.probar(configOk)
        assertTrue("$p", p is EstadoNodo.Caido && p.ultimoError == esperado)
        nb.olvidar()
        val a = nb.adoptarSiSano(configOk, paqueteOk)
        assertTrue("$a", a is EstadoNodo.Caido && a.ultimoError == esperado)
        assertNull("no se adopta un nodo por debajo del suelo", mem.c)
        // Un nodo de OTRA red no tiene suelo (0): 150.000 es aceptable.
        val otraRed = nb.probar(configOk.copy(red = Red.REGTEST))
        assertTrue("$otraRed", otraRed is EstadoNodo.Sano && otraRed.info.altura == 150_000u)
    }

    @Test
    fun laMarcaMaximaDeLaSesionSigueVivaPorEncimaDelSuelo() = runBlocking {
        var altura = 200_050u
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, motorConAltura { altura }, Dispatchers.Unconfined, alturaSuelo = { 200_000u })
        val sano = nb.configurar(configOk)
        assertTrue("$sano", sano is EstadoNodo.Sano && sano.info.altura == 200_050u)
        // El servidor retrocede por debajo de lo que ya anunció, aunque siga por encima del suelo.
        altura = 200_010u
        val e = nb.comprobar()
        assertTrue("$e", e is EstadoNodo.Caido && e.ultimoError == "ese nodo va por detrás de lo ya visto: bloque 200010 frente a 200050")
    }

    @Test
    fun adoptarSiSano_revisaElSueloOtraVezAntesDeAdoptar() = runBlocking {
        val mem = Memoria()
        var sueloActual = 100_000u
        // El candidato responde 150.000 (>= suelo inicial 100.000), pero mientras dura la sonda
        // la billetera sincroniza y sube el suelo a 200.000: no debe adoptarse ni guardarse.
        val comprobador: (ConfigNodo, UInt) -> NodoInfo = { _, min ->
            val h = 150_000u
            if (h < min) throw x39.wallet.Exception.Red("servidor Electrum: el servidor va por detrás: altura $h, ya se había visto $min")
            sueloActual = 200_000u
            info(h)
        }
        val nb = NodeBridgeImpl(mem, CoroutineScope(Dispatchers.Unconfined), { false }, { }, comprobador, Dispatchers.Unconfined, alturaSuelo = { sueloActual })
        val a = nb.adoptarSiSano(configOk, paqueteOk)
        assertTrue("$a", a is EstadoNodo.Caido && a.ultimoError == "ese nodo va por detrás de lo ya visto: bloque 150000 frente a 200000")
        assertNull("no se adopta si el suelo subió durante la sonda", mem.c)
    }
}
