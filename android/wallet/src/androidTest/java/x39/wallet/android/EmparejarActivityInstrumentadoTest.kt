package x39.wallet.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import x39.wallet.NodoInfo
import x39.wallet.Red

/** Flujo de «Emparejar con mi nodo» con repositorio falso: vista previa, confirmación y «solo se guarda tras Sano». */
@RunWith(AndroidJUnit4::class)
class EmparejarActivityInstrumentadoTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val huella = "d69cfd0cee1cb723598e98e240ec62f14cc0ef834f47872dd6c29d4da6e230de"
    private lateinit var fake: FakeRepositorio

    private fun fecha(desplazamientoS: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format((System.currentTimeMillis() / 1000 + desplazamientoS) * 1000)
    }

    private fun paquete(extra: String = "") = """{"v": 1, "red": "signet", "electrum": "ssl://nodo.ejemplo.local:50002",
        "huella_spki_sha256": "$huella", "tunel": {"clave_publica_nodo": "${"B".repeat(43)}=", "endpoint": "nodo.ejemplo.local:51820",
        "ips_permitidas": ["127.0.0.1/32"]}, "nombre": "nodo de prueba", "emitido": "${fecha(-60)}", "caduca": "${fecha(3600)}"$extra}"""

    @Before
    fun inicio() {
        // Los paquetes de estas pruebas son de signet: en la edición mainnet quedan como «no aplicables».
        assumeTrue("solo en la edición de prueba (signet); en mainnet no aplica", Redes.permitida(Red.SIGNET))
        // Sin nodo ANTES de crear el repositorio: con el modo predeterminado (nodo X-39) NodeBridge
        // arrancaría ya configurado y estas pruebas comprueban que no queda nada guardado.
        Ajustes(ctx).apply { red = Red.SIGNET; borrarConfigNodo() }
        fake = FakeRepositorio(ctx)
        WalletRepository.fijarParaPruebas(fake)
    }

    @After
    fun fin() {
        if (::fake.isInitialized) fake.ajustes.borrarConfigNodo() // sin inicializar si la suposición de @Before no se cumplió
        WalletRepository.fijarParaPruebas(null)
    }

    private fun textos(v: View, acc: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView) acc += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) textos(v.getChildAt(i), acc)
        return acc
    }

    private fun buscar(v: View, desc: String): View? {
        if (v.contentDescription == desc) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) buscar(v.getChildAt(i), desc)?.let { return it }
        return null
    }

    private fun lanzar(texto: String) = ActivityScenario.launch<EmparejarActivity>(
        Intent(ctx, EmparejarActivity::class.java).putExtra(EmparejarActivity.EXTRA_TEXTO, texto),
    )

    private fun esperar(cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < fin && !cond()) Thread.sleep(100)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun paqueteValido_vistaPrevia_yConfirmarGuardaSoloTrasSano() {
        fake.respuestaNodo = { c -> NodoInfo(c.electrum, "simulado", "1.4", 400_000u, 15uL, true, huella, Red.SIGNET) }
        lanzar(paquete()).use { esc ->
            esc.onActivity { a ->
                val t = textos(a.window.decorView)
                assertTrue("servidor: $t", t.any { it.contains("ssl://nodo.ejemplo.local:50002") })
                assertTrue("huella abreviada", t.any { it.contains("d69cfd0c…a6e230de") })
                assertTrue("nombre", t.any { it.contains("nodo de prueba") })
                assertTrue("túnel", t.any { it.contains("nodo.ejemplo.local:51820") })
                assertTrue("aviso de túnel no disponible", t.any { it.contains("aún no está disponible") })
                assertEquals("nada guardado antes de confirmar", "", fake.ajustes.servidor)
                (buscar(a.window.decorView, "confirmar_emparejamiento") ?: throw AssertionError("sin botón confirmar")).performClick()
            }
            esperar { fake.ajustes.servidor.isNotEmpty() }
            assertEquals("ssl://nodo.ejemplo.local:50002", fake.ajustes.servidor)
            assertEquals(huella, fake.ajustes.huellaServidor)
            assertEquals("nodo de prueba", fake.ajustes.nombreNodo)
            assertEquals(ModoTunel.NINGUNO, fake.ajustes.tunel)
            assertEquals("nodo.ejemplo.local:51820", fake.ajustes.tunelEndpoint)
            assertEquals(listOf("127.0.0.1/32"), fake.ajustes.tunelIpsPermitidas)
            assertTrue(fake.ajustes.emparejadoEn > 0)
            assertTrue("NodeBridge publica Sano", fake.nodo.estado.value is EstadoNodo.Sano)
            esperar { esc.state == Lifecycle.State.DESTROYED }
            assertEquals("la pantalla se cierra al emparejar", Lifecycle.State.DESTROYED, esc.state)
        }
    }

    @Test
    fun nodoCaido_noGuardaNada_yDiceElMotivo() {
        fake.respuestaNodo = { throw RuntimeException("conexión rechazada por el nodo") }
        lanzar(paquete()).use { esc ->
            esc.onActivity { a -> buscar(a.window.decorView, "confirmar_emparejamiento")!!.performClick() }
            esperar { var ok = false; esc.onActivity { a -> ok = (buscar(a.window.decorView, "estado_emparejamiento") as TextView).text.contains("No se ha guardado nada") }; ok }
            esc.onActivity { a ->
                val estado = (buscar(a.window.decorView, "estado_emparejamiento") as TextView).text.toString()
                assertTrue(estado, estado.contains("No se ha guardado nada") && estado.contains("conexión rechazada por el nodo"))
            }
            assertEquals("", fake.ajustes.servidor)
            assertEquals("", fake.ajustes.huellaServidor)
            assertEquals("", fake.ajustes.tunelEndpoint)
            assertEquals(0L, fake.ajustes.emparejadoEn)
            assertNull(fake.ajustes.configNodo())
            assertEquals(EstadoNodo.SinConfigurar, fake.nodo.estado.value)
        }
    }

    @Test
    fun descartarDuranteLaComprobacion_cancelaDeVerdad_yElSanoTardioNoSeGuarda() {
        val entrado = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        fake.respuestaNodo = { c -> entrado.countDown(); soltar.await(); NodoInfo(c.electrum, "simulado", "1.4", 400_000u, 15uL, true, huella, Red.SIGNET) }
        lanzar(paquete()).use { esc ->
            esc.onActivity { a -> buscar(a.window.decorView, "confirmar_emparejamiento")!!.performClick() }
            assertTrue("el servidor lento recibió la comprobación", entrado.await(8, java.util.concurrent.TimeUnit.SECONDS))
            // El usuario descarta mientras el servidor sigue pensando…
            esc.onActivity { a -> buscar(a.window.decorView, "descartar_emparejamiento")!!.performClick() }
            // …y entonces llega el Sano: no se guarda nada.
            soltar.countDown()
            Thread.sleep(800)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals("", fake.ajustes.servidor)
            assertEquals("", fake.ajustes.tunelEndpoint)
            assertEquals(0L, fake.ajustes.emparejadoEn)
            assertEquals(EstadoNodo.SinConfigurar, fake.nodo.estado.value)
            esc.onActivity { a ->
                assertNull("sin vista previa tras descartar", buscar(a.window.decorView, "vista_previa"))
            }
            assertTrue("la pantalla sigue abierta", esc.state != Lifecycle.State.DESTROYED)
        }
    }

    @Test
    fun alConfirmarSeRevalida_unPaqueteCaducadoEntreMediasSeRechazaSinGuardar() {
        fake.respuestaNodo = { c -> NodoInfo(c.electrum, "simulado", "1.4", 400_000u, 15uL, true, huella, Red.SIGNET) }
        lanzar(paquete()).use { esc ->
            esc.onActivity { a ->
                assertNotNull("vista previa con el paquete vigente", buscar(a.window.decorView, "vista_previa"))
                // Pasa el tiempo: el reloj de la pantalla se adelanta más allá de «caduca».
                a.reloj = { System.currentTimeMillis() / 1000 + 2 * 3600 }
                buscar(a.window.decorView, "confirmar_emparejamiento")!!.performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            esc.onActivity { a ->
                val t = textos(a.window.decorView)
                assertTrue("rechazo por caducidad: $t", t.any { it == "PAQUETE RECHAZADO" } && t.any { it.contains("caducó") })
                assertNull(buscar(a.window.decorView, "confirmar_emparejamiento"))
            }
            assertEquals("", fake.ajustes.servidor)
            assertEquals(EstadoNodo.SinConfigurar, fake.nodo.estado.value)
        }
    }

    @Test
    fun paqueteConClavePrivada_seRechazaEntero_sinBotonDeConfirmar() {
        lanzar(paquete(extra = ", \"clave_privada_tunel\": \"${"C".repeat(43)}=\"")).use { esc ->
            esc.onActivity { a ->
                val t = textos(a.window.decorView)
                assertTrue("rechazo: $t", t.any { it == "PAQUETE RECHAZADO" })
                assertTrue(t.any { it.contains("clave privada") && it.contains("se rechaza entero") })
                assertNull("no hay vista previa", buscar(a.window.decorView, "vista_previa"))
                assertNull("no hay confirmar", buscar(a.window.decorView, "confirmar_emparejamiento"))
            }
            assertEquals("", fake.ajustes.servidor)
        }
    }
}
