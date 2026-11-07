package x39.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import x39.wallet.EnvioInfo
import x39.wallet.EstadoEnvio
import x39.wallet.PsbtPendiente
import x39.wallet.R
import x39.wallet.android.WalletRepository.Companion.texto

/** Movimientos y envíos firmados, con reintento, cancelación y cancelación por reemplazo. */
@RequiresApi(Build.VERSION_CODES.M) // Keystore con autenticación por operación
class HistorialActivity : BaseActivity() {

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.protegerContraCapturas()
        col = Estilo.columna(this)
        setContentView(Estilo.envolver(this, col))
        lifecycleScope.launch { repo.vista.collect { pintar(it) } }
    }

    private fun pintar(v: WalletRepository.Vista) {
        col.removeAllViews()
        col.addView(cabecera(getString(R.string.x39w_his_titulo)))
        col.addView(Estilo.seccion(this, getString(R.string.x39w_his_firmados)))
        if (v.envios.isEmpty()) col.addView(Estilo.texto(this, getString(R.string.x39w_his_ninguno), 13f, Estilo.tenue))
        for (e in v.envios.asReversed()) col.addView(tarjetaEnvio(e))
        col.addView(Estilo.seccion(this, getString(R.string.x39w_his_movimientos)))
        if (v.movimientos.isEmpty()) col.addView(Estilo.texto(this, getString(R.string.x39w_his_sin_movimientos), 13f, Estilo.tenue))
        for (m in v.movimientos) {
            val t = Estilo.tarjeta(this)
            val neto = m.recibidoSat.toLong() - m.enviadoSat.toLong()
            t.addView(Estilo.texto(this, (if (neto >= 0) "+" else "") + String.format(java.util.Locale.ROOT, "%,d sat", neto).replace(',', '.'), 16f, if (neto >= 0) Estilo.ok else Estilo.claro))
            t.addView(Estilo.texto(this, "${m.txid.take(20)}… · ${m.altura?.let { getString(R.string.x39w_his_bloque, it.toString()) } ?: getString(R.string.x39w_his_mempool)}", 12f, Estilo.gris))
            col.addView(t)
        }
    }

    private fun tarjetaEnvio(e: EnvioInfo): LinearLayout {
        val t = Estilo.tarjeta(this)
        val color = when (e.estado) {
            EstadoEnvio.CONFIRMADA -> Estilo.ok
            EstadoEnvio.INCIERTA, EstadoEnvio.FIRMADA, EstadoEnvio.DIFUNDIENDO -> Estilo.aviso
            EstadoEnvio.FALLIDA -> Estilo.peligro
            else -> Estilo.claro
        }
        t.addView(Estilo.texto(this, e.estado.texto(this).uppercase(), 12f, color))
        t.addView(Estilo.texto(this, getString(R.string.x39w_his_envio_linea, Estilo.sat(e.importeSat), e.destino.take(18), Estilo.sat(e.comisionSat)), 13f))
        if (e.reemplazaA.isNotEmpty()) t.addView(Estilo.texto(this, getString(R.string.x39w_his_reemplazo_de, e.reemplazaA.take(16)), 12f, Estilo.gris))
        t.addView(Estilo.texto(this, getString(R.string.x39w_his_txid_intentos, e.txid.take(20), e.intentosDifusion.toString()) + if (e.motivo.isNotEmpty()) "\n${e.motivo}" else "", 12f, Estilo.gris))
        if (e.puedeReintentar) t.addView(Estilo.boton(this, getString(R.string.x39w_his_reintentar), principal = false) { lanzar { repo.difundir(e.txid) } })
        if (e.puedeCancelar) t.addView(Estilo.boton(this, getString(R.string.x39w_his_cancelar_libera), principal = false) { lanzar { repo.cancelar(e.txid) } })
        if (e.puedeReemplazar) t.addView(Estilo.boton(this, getString(R.string.x39w_his_cancelar_reemplazo), principal = false) { pedirTasaReemplazo(e) })
        return t
    }

    private fun pedirTasaReemplazo(e: EnvioInfo) {
        val campo = Estilo.campo(this, getString(R.string.x39w_his_tasa_reemplazo, Estilo.dec(e.satVb)), numerico = true)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39w_his_cancelar_reemplazo))
            .setMessage(getString(R.string.x39w_his_reemplazo_texto))
            .setView(campo)
            .setPositiveButton(getString(R.string.x39w_revisar)) { _, _ ->
                val tasa = campo.text.toString().replace(',', '.').toDoubleOrNull() ?: run { aviso(getString(R.string.x39w_his_tasa_invalida)); return@setPositiveButton }
                lanzar { revisarReemplazo(repo.prepararCancelacion(e.txid, tasa)) }
            }
            .setNegativeButton(getString(R.string.x39w_atras), null)
            .show()
    }

    private fun revisarReemplazo(p: PsbtPendiente) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39w_his_revisa_reemplazo))
            .setMessage(getString(R.string.x39w_his_reemplazo_detalle, p.reemplazaA.take(16), p.destino, Estilo.sat(p.importeSat), Estilo.sat(p.comisionSat), Estilo.dec(p.satVb)))
            .setPositiveButton(getString(R.string.x39w_his_firmar_difundir)) { _, _ ->
                conSemilla(getString(R.string.x39w_his_firmar_reemplazo), getString(R.string.x39w_his_cancelacion_de, p.reemplazaA.take(12))) { m ->
                    val firmada = repo.firmar(p.id, m)
                    val d = repo.difundir(firmada.txid)
                    aviso(getString(R.string.x39w_his_reemplazo_estado, d.estado.texto(this)))
                }
            }
            .setNegativeButton(getString(R.string.x39w_descartar), null)
            .show()
    }
}
