package x39.wallet.android

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** La clave del Keystore ya no sirve (cambió la biometría o se borró): hay que restaurar desde la semilla. */
class ClaveInvalidadaException(causa: Throwable? = null) :
    Exception("la clave de la billetera ha quedado invalidada; restaura desde la semilla", causa)

/**
 * Mnemónico cifrado en disco con una clave del Android Keystore.
 *
 *  - AES-256-GCM, clave generada en el Keystore (StrongBox si lo hay, si no TEE).
 *  - `setUserAuthenticationRequired(true)` con autenticación POR OPERACIÓN: API 30+
 *    `setUserAuthenticationParameters(0, BIOMETRIC_STRONG | DEVICE_CREDENTIAL)`; antes,
 *    `setUserAuthenticationValidityDurationSeconds(-1)`. Cada cifrado o descifrado exige un
 *    `BiometricPrompt` con `CryptoObject(cipher)`: ver [Autenticacion]. Cancelar = abortar.
 *  - Invalidación: con la credencial del aparato admitida (API 30+, decisión del proyecto), un
 *    cambio de biometría NO invalida la clave (`setInvalidatedByBiometricEnrollment` solo
 *    actúa en claves de solo biometría). La invalidación que se detecta
 *    ([ClaveInvalidadaException] o clave desaparecida) cubre la pérdida o el reinicio del
 *    Keystore (p. ej. quitar el bloqueo de pantalla, restablecer el aparato). Opción futura
 *    anotada: ajuste «solo biometría», que sí invalidaría al cambiar la huella o la cara.
 *  - Recuperación tras invalidación: NO se borra nada del estado de la billetera; se piden
 *    las 12 palabras, el motor comprueba que son de esta billetera (`coincideSemilla`), y
 *    solo entonces [regenerarClave] crea una clave nueva y se vuelve a cifrar.
 *  - `setUnlockedDeviceRequired(true)` en API 28+: la clave solo sirve con el aparato
 *    desbloqueado. (Si diera problemas al salir del bloqueo en algún aparato, se documenta y
 *    se quita; primero se prueba.)
 *  - La cabecera del fichero (versión y longitud del IV) va ligada al cifrado como AAD de
 *    GCM: una cabecera manipulada no descifra.
 *  - El fichero cifrado vive en el directorio de su edición bajo `noBackupFilesDir`, excluido
 *    de las copias de seguridad; las reglas de `res/xml` lo excluyen también si la app las referencia. Tras
 *    el renombrado atómico se hace fsync del directorio (igual que en el motor Rust).
 *  - El texto en claro solo existe en memoria nativa directa ([Mnemonico]); el cifrado y el
 *    descifrado trabajan sobre `ByteBuffer` directos. Lo que el proveedor del Keystore copie
 *    internamente no se puede borrar desde aquí.
 *
 * Flujo: `prepararCifrado()` → prompt con el cipher → `guardar(cipherAutenticado, mnemonico)`;
 *        `prepararDescifrado()` → prompt con el cipher → `leer(cipherAutenticado)`.
 */
@RequiresApi(Build.VERSION_CODES.M) // el Keystore con autenticación por operación exige Android 6
class AlmacenSemilla(context: Context, private val alias: String = PerfilBilletera.actual.aliasSemilla) {

    private val dir = PerfilBilletera.actual.directorio(context.noBackupFilesDir)
    private val fichero = File(dir, "semilla.enc")
    private val almacen: KeyStore = KeyStore.getInstance(PROVEEDOR).apply { load(null) }

    enum class Estado { SIN_SEMILLA, LISTA, CLAVE_INVALIDADA }

    fun estado(): Estado = when {
        !fichero.exists() -> Estado.SIN_SEMILLA
        !almacen.containsAlias(alias) -> Estado.CLAVE_INVALIDADA // hay fichero pero la clave desapareció
        else -> Estado.LISTA
    }

    /**
     * Crea la clave si no existe. Devuelve `true` si está respaldada por StrongBox.
     * Exige pantalla de bloqueo configurada (si no, el Keystore lanza IllegalStateException).
     */
    fun asegurarClave(): Boolean {
        if (almacen.containsAlias(alias)) return esStrongBox()
        val generar = { strongBox: Boolean ->
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                spec.setUserAuthenticationParameters(
                    0, // por operación
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                spec.setUserAuthenticationValidityDurationSeconds(-1) // por operación
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) spec.setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) spec.setUnlockedDeviceRequired(true)
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) spec.setIsStrongBoxBacked(true)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEEDOR).apply { init(spec.build()) }.generateKey()
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generar(true); true
            } catch (_: StrongBoxUnavailableException) {
                generar(false); false
            }
        } else {
            generar(false); false
        }
    }

    private fun clave(): SecretKey =
        (almacen.getKey(alias, null) as? SecretKey) ?: throw ClaveInvalidadaException()

    /** Cipher listo para cifrar; hay que autenticarlo con `BiometricPrompt.CryptoObject(cipher)` antes de [guardar]. */
    fun prepararCifrado(): Cipher = try {
        Cipher.getInstance(TRANSFORMACION).apply { init(Cipher.ENCRYPT_MODE, clave()) }
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw ClaveInvalidadaException(e)
    }

    /** Cifra y guarda el mnemónico (escritura atómica, 0600). El mnemónico NO se borra aquí. */
    fun guardar(cipherAutenticado: Cipher, mnemonico: Mnemonico) {
        val iv = cipherAutenticado.iv
        require(iv != null && iv.size == 12) { "IV de GCM inesperado" }
        val cabecera = byteArrayOf(VERSION, iv.size.toByte())
        val cifrado = mnemonico.usar { claro ->
            cipherAutenticado.updateAAD(cabecera) // la cabecera queda ligada al cifrado
            val salida = ByteBuffer.allocate(cipherAutenticado.getOutputSize(claro.remaining()))
            cipherAutenticado.doFinal(claro, salida)
            salida.flip()
            ByteArray(salida.remaining()).also { salida.get(it) }
        }
        dir.mkdirs()
        val tmp = File(dir, "semilla.enc.tmp")
        FileOutputStream(tmp).use { f ->
            f.write(cabecera)
            f.write(iv)
            f.write(cifrado)
            f.fd.sync()
        }
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        tmp.setWritable(false, false); tmp.setWritable(true, true)
        if (!tmp.renameTo(fichero)) {
            tmp.delete()
            throw java.io.IOException("no se pudo guardar la semilla cifrada")
        }
        fsyncDirectorio(dir)
    }

    /** Durabilidad del renombrado: fsync del directorio que contiene el fichero. */
    private fun fsyncDirectorio(d: File) {
        val fd = Os.open(d.path, OsConstants.O_RDONLY, 0) // abrir el directorio (solo lectura) para fsync
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    /** Cipher listo para descifrar lo guardado; autenticar con `CryptoObject(cipher)` antes de [leer]. */
    fun prepararDescifrado(): Cipher {
        val (iv, _) = leerFichero()
        return try {
            Cipher.getInstance(TRANSFORMACION).apply { init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw ClaveInvalidadaException(e)
        }
    }

    /** Descifra a memoria nativa directa. El resultado se borra con `close()` / `use { }`. */
    fun leer(cipherAutenticado: Cipher): Mnemonico {
        val (iv, cifrado) = leerFichero()
        val salida = ByteBuffer.allocateDirect(cipherAutenticado.getOutputSize(cifrado.size))
        try {
            // La misma cabecera que se escribió: si alguien la cambió, GCM no descifra.
            cipherAutenticado.updateAAD(byteArrayOf(VERSION, iv.size.toByte()))
            cipherAutenticado.doFinal(ByteBuffer.wrap(cifrado), salida)
        } catch (e: Exception) {
            Mnemonico.borrar(salida)
            throw e
        }
        salida.flip()
        return Mnemonico.deBufferDirecto(salida)
    }

    /**
     * Clave NUEVA para volver a cifrar la semilla tras una invalidación. Solo toca el Keystore;
     * el fichero cifrado antiguo (ya inútil) se sobrescribe atómicamente al [guardar].
     */
    fun regenerarClave(): Boolean {
        if (almacen.containsAlias(alias)) almacen.deleteEntry(alias)
        return asegurarClave()
    }

    /** Borra el fichero cifrado y la clave (para empezar de cero). No toca el estado de la billetera. */
    fun borrarTodo() {
        fichero.delete()
        File(dir, "semilla.enc.tmp").delete()
        if (almacen.containsAlias(alias)) almacen.deleteEntry(alias)
    }

    private fun esStrongBox(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            val k = clave()
            val fabrica = javax.crypto.SecretKeyFactory.getInstance(k.algorithm, PROVEEDOR)
            val info = fabrica.getKeySpec(k, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                @Suppress("DEPRECATION")
                info.isInsideSecureHardware
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun leerFichero(): Pair<ByteArray, ByteArray> {
        if (!fichero.exists()) throw IllegalStateException("no hay semilla guardada")
        val todo = fichero.readBytes()
        require(todo.size > 2 && todo[0] == VERSION) { "fichero de semilla ilegible" }
        val ivLen = todo[1].toInt()
        require(ivLen == 12 && todo.size > 2 + ivLen) { "fichero de semilla ilegible" }
        val iv = todo.copyOfRange(2, 2 + ivLen)
        val cifrado = todo.copyOfRange(2 + ivLen, todo.size)
        return iv to cifrado
    }

    companion object {
        /** Alias histórico de la edición de pruebas; el alias efectivo depende del perfil. */
        const val ALIAS_POR_DEFECTO = "x39-wallet-semilla"
        private const val PROVEEDOR = "AndroidKeyStore"
        private const val TRANSFORMACION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
    }
}
