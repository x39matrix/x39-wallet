package x39.wallet.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import x39.wallet.EnvioInfo
import x39.wallet.EstadoEnvio
import x39.wallet.PsbtPendiente
import x39.wallet.Red

/** Repositorio falso para probar las pantallas sin red ni Keystore. */
class FakeRepositorio(ctx: Context) : Repositorio {
    override val ajustes = Ajustes(ctx)
    override val almacen = AlmacenSemilla(ctx, "x39-wallet-fake-${System.nanoTime()}")
    override val vista: StateFlow<WalletRepository.Vista> = MutableStateFlow(WalletRepository.Vista())
    override val nodo: NodeBridge = NodeBridgeImpl(ajustes, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), hayVpnActiva = { false })
    val difundidas = mutableListOf<String>()
    var fallarDifusion: String? = null

    fun envio(txid: String, estado: EstadoEnvio) = EnvioInfo(
        id = "id", txid = txid, red = Red.SIGNET, destino = "tb1qdestino", importeSat = 1000uL, comisionSat = 141uL,
        vbytes = 141uL, satVb = 1.0, cambio = "", cambioSat = 0uL, reemplazaA = "", estado = estado, motivo = "",
        intentosDifusion = if (estado == EstadoEnvio.FIRMADA) 0u else 1u, firmadaEn = 0uL, actualizadaEn = 0uL,
        puedeReintentar = false, puedeCancelar = false, puedeReemplazar = false,
    )

    override fun estado() = WalletRepository.Estado.LISTA
    override fun escaneoPendiente() = false
    override suspend fun semillaNueva() = throw UnsupportedOperationException()
    override suspend fun crear(mnemonico: Mnemonico, restaurar: Boolean) = false
    override suspend fun abrir() {}
    override suspend fun coincideSemilla(mnemonico: Mnemonico) = false
    override suspend fun sincronizar(completo: Boolean) {}
    override suspend fun nuevaDireccion() = "tb1qdireccion"
    override suspend fun estimarComision(bloques: UInt) = 1.0
    override suspend fun prepararEnvio(destino: String, cantidadSat: ULong, satVb: Double, maxComisionSat: ULong): PsbtPendiente =
        throw UnsupportedOperationException()
    override suspend fun prepararCancelacion(txid: String, satVb: Double): PsbtPendiente = throw UnsupportedOperationException()
    override suspend fun firmar(id: String, mnemonico: Mnemonico) = envio("txfirmada", EstadoEnvio.FIRMADA)
    override suspend fun difundir(txid: String): EnvioInfo {
        fallarDifusion?.let { throw ErrorBilletera(it) }
        difundidas += txid
        return envio(txid, EstadoEnvio.ENVIADA)
    }
    override suspend fun cancelar(txid: String) = envio(txid, EstadoEnvio.CANCELADA)
}
