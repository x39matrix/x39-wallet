package x39.wallet.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import x39.wallet.Red

@RunWith(AndroidJUnit4::class)
class AjustesActivityInstrumentadoTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun fin() = WalletRepository.fijarParaPruebas(null)

    private fun buscar(v: View, desc: String): View? {
        if (v.contentDescription == desc) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) buscar(v.getChildAt(i), desc)?.let { return it }
        return null
    }

    private fun campos(v: View, acc: MutableList<EditText> = mutableListOf()): List<EditText> {
        if (v is EditText) acc += v
        if (v is ViewGroup) for (i in 0 until v.childCount) campos(v.getChildAt(i), acc)
        return acc
    }

    @Test
    fun elBotonDelServidorPublicoRellenaElCampo_soloEnSignet() {
        val fake = FakeRepositorio(ctx)
        fake.ajustes.red = Red.SIGNET
        fake.ajustes.servidor = ""
        WalletRepository.fijarParaPruebas(fake)
        ActivityScenario.launch<AjustesActivity>(Intent(ctx, AjustesActivity::class.java)).use { escenario ->
            escenario.onActivity { a ->
                val boton = buscar(a.window.decorView, "servidor_publico_signet")
                assertNotNull("en signet hay botón de servidor público", boton)
                boton!!.performClick()
                assertEquals(AjustesActivity.SERVIDOR_PUBLICO_SIGNET, campos(a.window.decorView).first().text.toString())
            }
        }
        fake.ajustes.red = Red.REGTEST
        ActivityScenario.launch<AjustesActivity>(Intent(ctx, AjustesActivity::class.java)).use { escenario ->
            escenario.onActivity { a -> assertEquals("en regtest no se ofrece", null, buscar(a.window.decorView, "servidor_publico_signet")) }
        }
        fake.ajustes.red = Red.SIGNET
    }
}
