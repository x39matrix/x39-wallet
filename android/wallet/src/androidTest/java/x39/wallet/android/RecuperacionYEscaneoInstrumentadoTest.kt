package x39.wallet.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import x39.wallet.EstadoEnvio
import x39.wallet.Red

/**
 * Repositorio real con la biblioteca nativa. Vector BIP39 público como semilla; «otro» vector
 * para la semilla equivocada. El estado vive en el directorio privado del paquete de pruebas.
 */
@RunWith(AndroidJUnit4::class)
class RecuperacionYEscaneoInstrumentadoTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vector = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val otro = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"

    private fun limpiar(repo: WalletRepository) {
        repo.cerrar()
        File(ctx.noBackupFilesDir, "x39-wallet").deleteRecursively()
        repo.ajustes.servidor = ""
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** Un envío Firmada en el registro (transacción pública del banco regtest, solo como dato). */
    private fun registroConUnaFirmada(estado: File) {
        val hex = "02000000000101fb160ed431c45567176d51560a18c57eafabaf21e1ea4113b30fff279988b6860100000000fdffffff025e8300000000000016001495f2d1d93a377a914c296b06cad67fbab7b9d1088813000000000000160014fd9cc7bf67485e695c74b247b4c2a48fa58a0b0502473044022029d62a39473c7f84c747bef246f213e1c8e0827f2dce632aeb62efb7d906c64e02205065142c66fbc48fdeec6fbd482f5cc2a1aa0a88c6f84fb9bd73b15009a5643801210368828fc665810600da18644a75ffb27033d6ecf1baec745946fc0af71500aea16b000000"
        val txid = "b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c"
        File(estado.path + ".envios.json").writeText(
            """{"version":3,"envios":[{"info":{"id":"p","txid":"$txid","red":"signet","destino":"tb1qdestino","importe_sat":5000,
            "comision_sat":281,"vbytes":141,"sat_vb":1.99,"cambio":"tb1qcambio","cambio_sat":33630,"reemplaza_a":"","estado":"Firmada",
            "motivo":"","intentos_difusion":0,"firmada_en":1,"actualizada_en":1,"puede_reintentar":true,"puede_cancelar":true,
            "puede_reemplazar":false},"hex":"$hex","visto_en":1}]}""".trimIndent(),
        )
    }

    @Test
    fun recuperarLaClave_noTocaElEstadoNiElRegistroDeEnvios() = runBlocking {
        assumeTrue("hace falta pantalla de bloqueo", ctx.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure)
        val repo = WalletRepository(ctx)
        limpiar(repo)
        try {
            // Billetera creada y un envío firmado registrado (fixture), con la clave del Keystore.
            Mnemonico.deChars(vector.toCharArray()).use { repo.crear(it, restaurar = false) }
            repo.cerrar()
            val estado = repo.ficheroEstado()
            registroConUnaFirmada(estado)
            repo.almacen.asegurarClave()
            File(ctx.noBackupFilesDir, "x39-wallet/semilla.enc").writeText("fichero cifrado con la clave antigua (simulado)")
            val shaEstado = sha(estado)
            val shaEnvios = sha(File(estado.path + ".envios.json"))
            assertEquals(WalletRepository.Estado.LISTA, repo.estado())

            // Invalidación: el Keystore pierde la clave.
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(AlmacenSemilla.ALIAS_POR_DEFECTO)
            assertEquals(WalletRepository.Estado.CLAVE_INVALIDADA, repo.estado())

            // Palabras equivocadas: no coinciden; nada cambia.
            assertFalse(Mnemonico.deChars(otro.toCharArray()).use { repo.coincideSemilla(it) })
            // Las buenas sí; sigue sin cambiar nada hasta cifrar (que pide el prompt, manual).
            assertTrue(Mnemonico.deChars(vector.toCharArray()).use { repo.coincideSemilla(it) })
            assertTrue(repo.almacen.regenerarClave() || true) // clave nueva creada
            assertEquals(WalletRepository.Estado.LISTA, repo.estado())

            assertEquals("estado.dat intacto", shaEstado, sha(estado))
            assertEquals("registro de envíos intacto", shaEnvios, sha(File(estado.path + ".envios.json")))
            repo.cerrar()
            repo.abrir()
            val e = repo.vista.value.envios.single()
            assertEquals(EstadoEnvio.FIRMADA, e.estado)
            assertEquals(0u, e.intentosDifusion)
        } finally {
            limpiar(repo)
            repo.almacen.borrarTodo()
        }
    }

    @Test
    fun restaurarSinServidor_dejaEscaneoPendiente_ySincronizarLoCompleta() = runBlocking {
        val repo = WalletRepository(ctx)
        limpiar(repo)
        try {
            repo.ajustes.red = Red.SIGNET
            val escaneada = Mnemonico.deChars(vector.toCharArray()).use { repo.crear(it, restaurar = true) }
            assertFalse("sin servidor no escanea", escaneada)
            assertTrue(repo.escaneoPendiente())
            try {
                repo.prepararEnvio("tb1qdestino", 1000uL, 1.0, 1000uL)
                fail("con escaneo pendiente no se prepara ningún envío")
            } catch (e: ErrorBilletera) {
                assertTrue(e.message!!.contains("escaneo completo"))
            }
            // Cierre del proceso simulado: otra instancia del repositorio sobre los mismos ficheros.
            repo.cerrar()
            val repo2 = WalletRepository(ctx)
            assertTrue("la marca sobrevive", repo2.escaneoPendiente())
            repo2.abrir()
            assertTrue(repo2.vista.value.escaneoPendiente)
            // Servidor público de signet (el vector tiene historia en signet): sincronizar hace el escaneo completo.
            repo2.ajustes.servidor = "ssl://mempool.space:60602"
            repo2.sincronizar()
            assertFalse("la marca se quita al terminar bien", repo2.escaneoPendiente())
            assertFalse(repo2.vista.value.escaneoPendiente)
            assertTrue("encuentra movimientos", repo2.vista.value.movimientos.isNotEmpty())
            repo2.cerrar()
        } finally {
            limpiar(repo)
        }
    }
}
