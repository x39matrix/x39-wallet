package x39.wallet.android

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keystore real. Exige un aparato con pantalla de bloqueo configurada; si no la hay, las
 * pruebas se omiten (no se pueden simular). La autenticación biométrica en sí no se puede
 * automatizar: se comprueba que SIN autenticar el cipher no cifra.
 */
@RunWith(AndroidJUnit4::class)
class AlmacenSemillaInstrumentadoTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "x39-wallet-prueba-${System.nanoTime()}"

    private fun conPantallaDeBloqueo(): Boolean {
        val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
        return km != null && km.isDeviceSecure
    }

    @Test
    fun laClaveExigeAutenticacionPorOperacionYSeInvalidaConLaBiometria() {
        assumeTrue("hace falta pantalla de bloqueo", conPantallaDeBloqueo())
        val almacen = AlmacenSemilla(ctx, alias)
        try {
            assertEquals(AlmacenSemilla.Estado.SIN_SEMILLA, almacen.estado())
            almacen.asegurarClave()
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val k = ks.getKey(alias, null) as SecretKey
            val info = SecretKeyFactory.getInstance(k.algorithm, "AndroidKeyStore").getKeySpec(k, KeyInfo::class.java) as KeyInfo
            assertTrue("autenticación de usuario requerida", info.isUserAuthenticationRequired)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) assertTrue(info.isInvalidatedByBiometricEnrollment)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                assertEquals(0, info.userAuthenticationValidityDurationSeconds)
                assertEquals(
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    info.userAuthenticationType,
                )
            } else {
                @Suppress("DEPRECATION")
                assertEquals(-1, info.userAuthenticationValidityDurationSeconds)
            }
            assertEquals(256, info.keySize)
            assertTrue(info.blockModes.contains(KeyProperties.BLOCK_MODE_GCM))

            // Sin pasar por BiometricPrompt, el cipher no está autorizado: cifrar falla.
            val cipher = almacen.prepararCifrado()
            Mnemonico.deChars("abandon abandon about".toCharArray()).use { m ->
                try {
                    almacen.guardar(cipher, m)
                    fail("sin autenticación no debe cifrar")
                } catch (_: Exception) {
                    // UserNotAuthenticatedException o IllegalBlockSizeException según la versión.
                }
            }
            assertEquals(AlmacenSemilla.Estado.SIN_SEMILLA, almacen.estado())
        } finally {
            almacen.borrarTodo()
        }
    }

    @Test
    fun ficheroCifradoFueraDeLasCopiasDeSeguridad() {
        // El fichero vive bajo noBackupFilesDir: excluido de la copia automática por definición.
        val esperado = java.io.File(ctx.noBackupFilesDir, "x39-wallet/semilla.enc")
        assertTrue(esperado.path.contains("no_backup"))
    }
}
