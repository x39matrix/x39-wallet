package x39.wallet.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import x39.wallet.NodoInfo
import x39.wallet.Red

/**
 * Pruebas JVM de la parte pura de WalletRepository: qué texto va a `Vista.ultimoError` (y al
 * toast) cuando una operación falla. El repositorio entero exige un `Context` y una billetera
 * en disco, así que `sincronizar` llama a `errorParaLaVista` y aquí se prueba esa decisión con
 * la misma excepción que construye `conRed` (`NodoNoDisponible(nodo.estado.value.describir())`).
 */
class WalletRepositoryTest {

    private class Memoria : AlmacenConfigNodo {
        var c: ConfigNodo? = null
        override fun leer() = c
        override fun guardar(c: ConfigNodo, paquete: PaqueteEmparejamiento?) { this.c = c }
        override fun borrar() { c = null }
    }

    private fun info(altura: UInt) = NodoInfo("tcp://10.39.0.3:50001", "simulado", "1.4", altura, 12uL, false, "", Red.MAINNET)

    /** Lo que hace `conRed` cuando `conexionSana()` devuelve `null`. */
    private fun falloDeConRed(nb: NodeBridge) = NodoNoDisponible(nb.estado.value.describir())

    /**
     * Orden 8, H1: con el nodo en `Caido(texto fijo)` y una sincronización que falla por ello,
     * `ultimoError` queda `null` (la línea de estado ya lo dice) y la pantalla no hace toast.
     */
    @Test
    fun h1_siElNodoYaMuestraElMotivo_laSincronizacionNoLoRepite() = runBlocking {
        var intentos = 0
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> intentos++; info(1u) }, Dispatchers.Unconfined,
            diagnosticoTunel = { DiagnosticoTunel(EstadoTunel.SIN_TUNEL, "redes VPN: 0") }, registrar = { })
        val cfg = NodoX39.config(Red.MAINNET)!!
        assertTrue(nb.configurar(cfg) is EstadoNodo.Caido)
        assertEquals(NodoX39.MENSAJE_TUNEL, nb.estado.value.describir())
        assertNull("sin nodo sano no hay conexión para el motor", nb.conexionSana())
        assertEquals(0, intentos)
        val e = falloDeConRed(nb)
        assertEquals("el texto sigue existiendo para quien lo necesite (una sola vez)", "el nodo no está disponible: ${NodoX39.MENSAJE_TUNEL}", e.message)
        assertTrue("la línea de estado del nodo ya lo muestra", WalletRepository.loMuestraElNodo(e, nb.estado.value))
        assertNull("ultimoError queda vacío: ni segunda línea ni toast", WalletRepository.errorParaLaVista(e, nb.estado.value))
        // También con la vigilancia en marcha (el estado lleva «reintento en N s»): el texto es el mismo.
        val conReintento = (nb.estado.value as EstadoNodo.Caido).copy(proximoIntentoEnS = 4)
        assertTrue(WalletRepository.loMuestraElNodo(e, conReintento))
        assertNull(WalletRepository.errorParaLaVista(e, conReintento))
        // Lo mismo con los otros estados que la línea del nodo ya enseña.
        for (estado in listOf<EstadoNodo>(EstadoNodo.Conectando, EstadoNodo.RedIncorrecta(Red.MAINNET, "génesis de signet"), EstadoNodo.HuellaIncorrecta("otra"), EstadoNodo.Caido(3, NodoX39.MENSAJE_NODO_NO_RESPONDE, 8))) {
            val f = NodoNoDisponible(estado.describir())
            assertTrue("$estado", WalletRepository.loMuestraElNodo(f, estado))
            assertNull("$estado", WalletRepository.errorParaLaVista(f, estado))
        }
    }

    /** H1: un fallo de OTRO tipo (motor, fondos, red con el nodo sano) se muestra una vez, como hasta ahora. */
    @Test
    fun h1_unFalloDeOtroTipo_seMuestraUnaVez() = runBlocking {
        val nb = NodeBridgeImpl(Memoria(), CoroutineScope(Dispatchers.Unconfined), { false }, { }, { _, _ -> info(1u) }, Dispatchers.Unconfined,
            diagnosticoTunel = { DiagnosticoTunel(EstadoTunel.CON_RUTA, "redes VPN: 1") }, registrar = { })
        assertTrue(nb.configurar(NodoX39.config(Red.MAINNET)!!).sano)
        val sano = nb.estado.value
        for ((fallo, esperado) in listOf(
            ErrorBilletera("la billetera no está abierta") to "la billetera no está abierta",
            x39.wallet.Exception.Fondos("") to "fondos insuficientes para el envío y la comisión",
            x39.wallet.Exception.Red("no se pudo conectar: timed out (os error 110)") to "sin respuesta del servidor: sin respuesta a tiempo: comprueba la dirección, el túnel o el proxy (no se pudo conectar: timed out)",
            IllegalStateException("fallo del motor") to "fallo del motor",
        )) {
            assertFalse(fallo.javaClass.simpleName, WalletRepository.loMuestraElNodo(fallo, sano))
            assertEquals(esperado, WalletRepository.errorParaLaVista(fallo, sano))
        }
        // «Nodo no disponible» cuyo motivo YA NO es el que muestra la línea del nodo (el nodo
        // volvió a sano entre medias): no es una repetición, se muestra una vez.
        val viejo = NodoNoDisponible(NodoX39.MENSAJE_TUNEL)
        assertFalse(WalletRepository.loMuestraElNodo(viejo, sano))
        assertEquals("el nodo no está disponible: ${NodoX39.MENSAJE_TUNEL}", WalletRepository.errorParaLaVista(viejo, sano))
        // Un ErrorBilletera cualquiera con el nodo caído tampoco se calla: no es el fallo del nodo.
        val caido = EstadoNodo.Caido(0, NodoX39.MENSAJE_TUNEL, 2)
        assertEquals("falta el escaneo completo", WalletRepository.errorParaLaVista(ErrorBilletera("falta el escaneo completo"), caido))
    }
}
