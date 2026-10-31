package x39.wallet.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import x39.wallet.Red

/** NodeBridge con el motor real desde el aparato, contra el servidor público de signet. */
@RunWith(AndroidJUnit4::class)
class NodeBridgeInstrumentadoTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun servidorPublicoDeSignet_sano_yHuellaIncorrecta_rechazada() = runBlocking {
        val repo = WalletRepository(ctx)
        repo.ajustes.borrarConfigNodo()
        try {
            // Sin huella: certificado público reconocido → sano.
            val sano = repo.nodo.configurar(ConfigNodo(Red.SIGNET, AjustesActivity.SERVIDOR_PUBLICO_SIGNET, null, ModoTunel.NINGUNO, "público"))
            assertTrue("$sano", sano is EstadoNodo.Sano && sano.info.altura > 300_000u && sano.info.protocolo.startsWith("1.4"))
            // Con una huella que no es la suya: el apretón de manos TLS falla y no se habla con él.
            val mala = repo.nodo.configurar(ConfigNodo(Red.SIGNET, AjustesActivity.SERVIDOR_PUBLICO_SIGNET, "00".repeat(32), ModoTunel.NINGUNO, "público"))
            assertTrue("$mala", mala is EstadoNodo.HuellaIncorrecta)
            // Túnel externo sin VPN activa: caído con motivo claro (si el aparato tiene una VPN activa, la prueba lo dice).
            val ext = repo.nodo.configurar(ConfigNodo(Red.SIGNET, AjustesActivity.SERVIDOR_PUBLICO_SIGNET, null, ModoTunel.EXTERNO, "público"))
            if (WalletRepository.hayVpnActiva(ctx)) assertTrue("$ext", ext.sano) else assertTrue("$ext", ext is EstadoNodo.Caido && ext.ultimoError.contains("túnel externo"))
        } finally {
            repo.nodo.olvidar()
            repo.cerrar()
        }
    }

    /**
     * Puerta de la altura con el MOTOR NATIVO REAL (`comprobarNodo` de libx39wallet.so) contra un
     * servidor Electrum falso en el propio aparato, por `tcp://` y en regtest. Fija la cadena
     * exacta que emite `rust/src/nodo.rs` («el servidor va por detrás: altura N, ya se había visto
     * M») tal como la traduce `mensajeAlturaSiAplica`: si alguien cambia esa cadena en el motor,
     * esta prueba se rompe.
     */
    @Test
    fun puertaDeAltura_conElMotorReal_formateaElRechazoYRespetaSueloYMarca() = runBlocking {
        val n = 1_000u
        ServidorElectrumFalso(altura = n).use { srv ->
            var suelo = n + 20u
            val nb = NodeBridgeImpl(AlmacenEnMemoria(), CoroutineScope(Dispatchers.IO), { false }, alturaSuelo = { suelo })
            val cfg = ConfigNodo(Red.REGTEST, srv.url, null, ModoTunel.NINGUNO, "falso")
            // Billetera (suelo) en N+20 y servidor en N: rechazado ya en la primera sonda.
            val porDebajo = nb.probar(cfg)
            assertTrue("$porDebajo", porDebajo is EstadoNodo.Caido)
            porDebajo as EstadoNodo.Caido
            assertTrue(porDebajo.ultimoError, porDebajo.ultimoError.contains("ese nodo va por detrás de lo ya visto: bloque "))
            assertEquals("ese nodo va por detrás de lo ya visto: bloque 1000 frente a 1020", porDebajo.ultimoError)
            // Marca en la sesión: suelo N, el servidor anuncia N+50 (sano) y después retrocede a
            // N+10: se exige N+50 aunque N+10 siga por encima del suelo.
            suelo = n
            srv.altura = n + 50u
            val sano = nb.configurar(cfg)
            assertTrue("$sano", sano is EstadoNodo.Sano && sano.info.altura == n + 50u)
            srv.altura = n + 10u
            val atras = nb.comprobar()
            assertTrue("$atras", atras is EstadoNodo.Caido)
            assertEquals("ese nodo va por detrás de lo ya visto: bloque 1010 frente a 1050", (atras as EstadoNodo.Caido).ultimoError)
            nb.olvidar()
        }
    }

    private class AlmacenEnMemoria : AlmacenConfigNodo {
        private var c: ConfigNodo? = null
        override fun leer() = c
        override fun guardar(c: ConfigNodo, paquete: PaqueteEmparejamiento?) { this.c = c }
        override fun borrar() { c = null }
    }

    /**
     * Servidor Electrum mínimo (JSON-RPC por líneas, `tcp://` en el bucle local del aparato) con
     * el génesis de regtest y una altura ajustable: lo justo para las puertas de `comprobarNodo`.
     */
    private class ServidorElectrumFalso(@Volatile var altura: UInt) : AutoCloseable {
        private val dir = InetAddress.getByName("127.0.0.1")
        private val escucha = ServerSocket(0, 5, dir)
        val url: String get() = "tcp://${dir.hostAddress}:${escucha.localPort}"

        init {
            thread(isDaemon = true) {
                while (!escucha.isClosed) {
                    val s = try { escucha.accept() } catch (e: Exception) { return@thread }
                    thread(isDaemon = true) { atender(s) }
                }
            }
        }

        private fun atender(s: Socket) = s.use { socket ->
            val lector = socket.getInputStream().bufferedReader()
            val escritor = socket.getOutputStream().bufferedWriter()
            while (true) {
                val linea = lector.readLine() ?: return
                val peticion = JSONObject(linea)
                val id = peticion.get("id")
                val respuesta = JSONObject().put("jsonrpc", "2.0").put("id", id)
                when (peticion.getString("method")) {
                    "server.version" -> respuesta.put("result", JSONArray().put("falso 0.1").put("1.4"))
                    "server.features" -> respuesta.put("result", JSONObject()
                        .put("genesis_hash", GENESIS_REGTEST)
                        .put("hash_function", "sha256")
                        .put("server_version", "falso 0.1")
                        .put("protocol_min", "1.4")
                        .put("protocol_max", "1.4")
                        .put("pruning", JSONObject.NULL))
                    "blockchain.headers.subscribe" -> respuesta.put("result", JSONObject()
                        .put("height", altura.toLong())
                        .put("hex", CABECERA_GENESIS_REGTEST))
                    else -> respuesta.put("error", JSONObject().put("code", 1).put("message", "método no soportado por el falso"))
                }
                escritor.write(respuesta.toString())
                escritor.newLine()
                escritor.flush()
            }
        }

        override fun close() = escucha.close()

        companion object {
            const val GENESIS_REGTEST = "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206"
            /** Cabecera (80 bytes) del génesis de regtest; su doble SHA-256 es `GENESIS_REGTEST`. */
            const val CABECERA_GENESIS_REGTEST =
                "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4adae5494dffff7f2002000000"
        }
    }
}
