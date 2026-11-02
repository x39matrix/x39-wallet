package x39.wallet.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import x39.wallet.BuildConfig
import x39.wallet.Red

/** Datos ficticios en el paquete de pruebas; no abre datos de la app ni usa el Keystore. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 23)
class PerfilMainnetInstrumentadoTest {
    @Test
    fun legadoSignet_noBloqueaCrearYReabrirMainnet_niSeModifica() = runBlocking {
        assumeTrue(BuildConfig.MARCADOR_REDES_ESPERADO == "x39wallet-redes=mainnet")
        val instrumentacion = InstrumentationRegistry.getInstrumentation()
        val base = instrumentacion.context
        check(base.packageName == "x39.wallet.test")
        val id = "perfil-mainnet-${System.nanoTime()}"
        val raiz = File(base.cacheDir, id)
        check(raiz.mkdir())
        val contexto = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = raiz
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$id-$name", mode)
        }
        val pruebas = PerfilBilletera.PRUEBAS
        val mainnet = PerfilBilletera.MAINNET
        val legado = pruebas.directorio(raiz).apply { check(mkdir()) }
        val originales = listOf("estado.dat", "estado.dat.envios.json", "estado.dat.escaneo-pendiente", "semilla.enc").associateWith { nombre ->
            "fixture signet: $nombre".toByteArray().also { File(legado, nombre).writeBytes(it) }
        }
        val prefsLegado = contexto.getSharedPreferences(pruebas.preferencias, Context.MODE_PRIVATE)
        check(prefsLegado.edit().putString("red", "SIGNET").putString("servidor_electrum", "tcp://192.0.2.1:60601").commit())
        val repo = WalletRepository(contexto)
        try {
            assertEquals(WalletRepository.Estado.SIN_BILLETERA, repo.estado())
            assertEquals(Red.MAINNET, repo.ajustes.red)
            assertEquals("", repo.ajustes.servidor)
            assertEquals(mainnet.ficheroEstado(raiz), repo.ficheroEstado())

            // Vector público BIP39, sin fondos ni llamadas de red; no se guarda una semilla cifrada.
            Mnemonico.deChars("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".toCharArray()).use { m ->
                repo.crear(m, restaurar = false)
            }
            val primera = repo.nuevaDireccion()
            assertTrue(primera.startsWith("bc1q"))
            repo.cerrar()
            Billeteras.abrir(mainnet.ficheroEstado(raiz), Red.MAINNET).use { abierta ->
                val segunda = abierta.motor.nuevaDireccion().direccion
                assertTrue(segunda.startsWith("bc1q"))
                assertTrue(primera != segunda)
            }
            for ((nombre, bytes) in originales) assertArrayEquals(nombre, bytes, File(legado, nombre).readBytes())
            assertEquals("SIGNET", prefsLegado.getString("red", null))
            assertEquals("tcp://192.0.2.1:60601", prefsLegado.getString("servidor_electrum", null))
        } finally {
            repo.cerrar()
            for (perfil in listOf(pruebas, mainnet)) {
                contexto.getSharedPreferences(perfil.preferencias, Context.MODE_PRIVATE).edit().clear().commit()
            }
            check(raiz.deleteRecursively())
        }
    }
}
