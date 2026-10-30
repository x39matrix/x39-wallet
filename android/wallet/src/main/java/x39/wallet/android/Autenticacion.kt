package x39.wallet.android

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * Autenticación POR OPERACIÓN con `BiometricPrompt.CryptoObject`: cada firma y cada lectura
 * de la semilla pasan por aquí con su propio `Cipher`. Cancelar o fallar = abortar: el
 * cipher no queda autorizado y la operación no ocurre.
 *
 * API 30+: biometría fuerte o credencial del dispositivo. Antes de API 30 el `CryptoObject`
 * solo admite biometría fuerte (limitación de la plataforma): en esos aparatos hace falta
 * una huella o cara registrada.
 */
object Autenticacion {

    private fun autenticadores(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG

    /** `BiometricManager.BIOMETRIC_SUCCESS` si se puede autenticar; si no, el código del motivo. */
    fun disponible(context: Context): Int = BiometricManager.from(context).canAuthenticate(autenticadores())

    fun pedir(
        actividad: FragmentActivity,
        cipher: Cipher,
        titulo: String,
        subtitulo: String,
        textoCancelar: String,
        alAutenticar: (Cipher) -> Unit,
        alAbortar: (codigo: Int, mensaje: CharSequence) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            actividad,
            ContextCompat.getMainExecutor(actividad),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c == null) alAbortar(BiometricPrompt.ERROR_VENDOR, "sin CryptoObject") else alAutenticar(c)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    alAbortar(errorCode, errString)
                }
                // onAuthenticationFailed: intento fallido, el prompt sigue abierto.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(titulo)
            .setSubtitle(subtitulo)
            .setConfirmationRequired(true)
            .setAllowedAuthenticators(autenticadores())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) info.setNegativeButtonText(textoCancelar)
        prompt.authenticate(info.build(), BiometricPrompt.CryptoObject(cipher))
    }
}
