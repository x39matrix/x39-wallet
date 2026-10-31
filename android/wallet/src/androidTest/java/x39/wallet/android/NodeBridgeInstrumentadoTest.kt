package x39.wallet.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
}
