package x39.wallet.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import x39.wallet.NodoInfo
import x39.wallet.Red

/** Las dos carreras de la revisión, en el aparato y con el almacén real (`Ajustes`, SharedPreferences). */
@RunWith(AndroidJUnit4::class)
class NodeBridgeCarrerasInstrumentadoTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val ajustes = Ajustes(ctx).also { it.red = Red.SIGNET; it.borrarConfigNodo() }
    private val a = ConfigNodo(Red.SIGNET, "ssl://nodo.ejemplo.local:50002", "ab".repeat(32), ModoTunel.NINGUNO, "A")
    private val b = a.copy(electrum = "ssl://candidato.ejemplo.local:50002", nombre = "B")
    private val paqueteB = PaqueteEmparejamiento(Red.SIGNET, b.electrum, "ab".repeat(32), DatosTunel("A".repeat(43) + "=", "nodo.ejemplo.local:51820", listOf("127.0.0.1/32"), null), "B", 0L, 86_400L)

    private fun info(c: ConfigNodo, altura: UInt) = NodoInfo(c.electrum, "simulado", "1.4", altura, 10uL, true, "ab".repeat(32), Red.SIGNET)

    @After
    fun fin() = ajustes.borrarConfigNodo()

    /** Almacén cuyo commit() «devuelve false»: Ajustes.guardar lanza en ese caso, y aquí se reproduce. */
    private class AlmacenQueNoPersiste(private val real: Ajustes) : AlmacenConfigNodo {
        override fun leer() = real.leer()
        override fun guardar(c: ConfigNodo, paquete: PaqueteEmparejamiento?) {
            throw IllegalStateException("no se pudo guardar la configuración del nodo en las preferencias")
        }
        override fun borrar() = real.borrar()
    }

    @Test
    fun siCommitDevuelveFalse_noSeAdoptaNiSePublicaSano_yNoQuedaNadaAMedias() = runBlocking {
        val nb = NodeBridgeImpl(AlmacenQueNoPersiste(ajustes), CoroutineScope(SupervisorJob()), { false }, { }, { c, _ -> info(c, 5u) })
        val e = nb.adoptarSiSano(b, paqueteB)
        assertTrue("$e", e is EstadoNodo.Caido && e.ultimoError.contains("no se pudo guardar"))
        assertEquals(EstadoNodo.SinConfigurar, nb.estado.value)
        assertNull(ajustes.configNodo())
        assertEquals("", ajustes.tunelEndpoint)
        assertEquals(0L, ajustes.emparejadoEn)
        assertNull(nb.conexionSana())
    }

    /**
     * Persistencia real: A en disco; el directorio de las preferencias se vuelve de solo lectura y
     * se adopta B. SharedPreferences cambia su memoria y luego falla el disco (`commit()` false):
     * Ajustes restaura A en su memoria y lanza; NodeBridge sigue en A (comprobar, conexionSana) y
     * el fichero en disco (relectura en frío) también dice A.
     */
    @Test
    fun siCommitFallaDespuesDeTocarLaMemoria_todoSigueEnA_tambienEnFrio() = runBlocking {
        val nombre = "x39-wallet-prueba-disco-${System.nanoTime()}"
        val prefsDir = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs")
        val fichero = java.io.File(prefsDir, "$nombre.xml")
        val aj = Ajustes(ctx, nombre).also { it.red = Red.SIGNET }
        try {
            val nb = NodeBridgeImpl(aj, CoroutineScope(SupervisorJob()), { false }, { }, { c, _ -> info(c, 7u) })
            assertTrue(nb.configurar(a).sano)
            assertTrue("A en disco", fichero.exists() && fichero.readText().contains(a.electrum))
            // Disco de solo lectura: la escritura temporal de SharedPreferences falla → commit() false.
            android.system.Os.chmod(prefsDir.absolutePath, 0b101_000_000 /* 0500 */)
            val e = try { nb.adoptarSiSano(b, paqueteB) } finally { android.system.Os.chmod(prefsDir.absolutePath, 0b111_000_000 /* 0700 */) }
            assertTrue("la adopción falla: $e", e is EstadoNodo.Caido && e.ultimoError.contains("no se pudo guardar"))
            assertEquals("activa sigue A", a, nb.config)
            val c = nb.comprobar()
            assertTrue("$c", c is EstadoNodo.Sano && c.info.servidor == a.electrum)
            assertEquals(a.electrum, nb.conexionSana()!!.url)
            assertEquals("la memoria de las preferencias se restauró a A", a, aj.configNodo())
            assertEquals("", aj.tunelEndpoint)
            val xml = fichero.readText()
            assertTrue("relectura en frío (fichero): A y no B", xml.contains(a.electrum) && !xml.contains(b.electrum))
        } finally {
            android.system.Os.chmod(prefsDir.absolutePath, 0b111_000_000)
            aj.borrarConfigNodo()
            fichero.delete()
        }
    }

    @Test
    fun olvidarDuranteUnaAdopcionLenta_noQuedaNadaGuardado() = runBlocking {
        val entrado = CountDownLatch(1)
        val soltar = CountDownLatch(1)
        val nb = NodeBridgeImpl(ajustes, CoroutineScope(SupervisorJob()), { false }, { }, { c, _ -> entrado.countDown(); soltar.await(); info(c, 5u) })
        val adopcion = launch(Dispatchers.Default) { nb.adoptarSiSano(b, paqueteB) }
        entrado.await()
        nb.olvidar() // termina antes que el guardado pendiente
        assertEquals(EstadoNodo.SinConfigurar, nb.estado.value)
        soltar.countDown()
        adopcion.join()
        assertNull("el guardado tardío no pisa el olvido", ajustes.configNodo())
        assertEquals("", ajustes.tunelEndpoint)
        assertEquals(0L, ajustes.emparejadoEn)
        assertEquals(EstadoNodo.SinConfigurar, nb.estado.value)
    }

    @Test
    fun descartarCandidatoLento_elNodoVigenteSigueSanoASuAltura_yAdoptarGuardaConfigYMetadatosJuntos() = runBlocking {
        val entrado = CountDownLatch(1)
        val soltar = CountDownLatch(1)
        val nb = NodeBridgeImpl(ajustes, CoroutineScope(SupervisorJob()), { false }, { }, { c, min ->
            if (c.electrum == b.electrum) { entrado.countDown(); soltar.await(); info(c, 500u) }
            else { if (100u < min) throw RuntimeException("altura 100 inferior a la vista $min"); info(c, 100u) }
        })
        assertTrue(nb.configurar(a).sano)
        val adopcion = launch(Dispatchers.Default) { nb.adoptarSiSano(b, paqueteB) }
        entrado.await()
        adopcion.cancel()
        soltar.countDown()
        adopcion.join()
        assertEquals(a, ajustes.configNodo())
        val e = nb.comprobar()
        assertTrue("A sigue sano a 100: $e", e is EstadoNodo.Sano && e.info.altura == 100u)
        // Adopción completa de B: configuración y metadatos del paquete en la misma escritura.
        val ok = nb.adoptarSiSano(b, paqueteB)
        assertTrue("$ok", ok.sano)
        assertEquals(b, ajustes.configNodo())
        assertEquals("nodo.ejemplo.local:51820", ajustes.tunelEndpoint)
        assertEquals(listOf("127.0.0.1/32"), ajustes.tunelIpsPermitidas)
        assertTrue(ajustes.emparejadoEn > 0)
    }
}
