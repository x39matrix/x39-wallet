package x39.wallet.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regresión: tras firmar, `resultado()` no vuelve a envolver la columna (antes lanzaba IllegalStateException). */
@RunWith(AndroidJUnit4::class)
class EnviarActivityInstrumentadoTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun fin() = WalletRepository.fijarParaPruebas(null)

    private fun textos(v: View, acc: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView) acc += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) textos(v.getChildAt(i), acc)
        return acc
    }

    @Test
    fun firmarResultadoDifundir_sinExcepcion_yMuestraElEstado() {
        val fake = FakeRepositorio(ctx)
        WalletRepository.fijarParaPruebas(fake)
        ActivityScenario.launch<EnviarActivity>(Intent(ctx, EnviarActivity::class.java)).use { escenario ->
            escenario.onActivity { it.resultado("txfirmada") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(300) // la difusión corre en una corrutina de IO
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            escenario.onActivity { a ->
                val t = textos(a.window.decorView)
                assertTrue("muestra el txid", t.any { it.contains("txfirmada") })
                assertTrue("muestra el estado enviada: $t", t.any { it.contains("enviada") })
            }
            assertEquals(listOf("txfirmada"), fake.difundidas)
        }
    }

    @Test
    fun siLaDifusionFalla_loDiceYNoRompe() {
        val fake = FakeRepositorio(ctx).apply { fallarDifusion = "sin respuesta del servidor" }
        WalletRepository.fijarParaPruebas(fake)
        ActivityScenario.launch<EnviarActivity>(Intent(ctx, EnviarActivity::class.java)).use { escenario ->
            escenario.onActivity { it.resultado("txfirmada") }
            Thread.sleep(300)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            escenario.onActivity { a ->
                val t = textos(a.window.decorView)
                assertTrue("avisa del fallo y de que sigue firmada: $t", t.any { it.contains("sin respuesta") && it.contains("sigue firmada") })
            }
        }
    }
}
