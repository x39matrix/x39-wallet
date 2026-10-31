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
        col.addView(cabecera("Historial"))
        col.addView(Estilo.seccion(this, "ENVÍOS FIRMADOS"))
        if (v.envios.isEmpty()) col.addView(Estilo.texto(this, "Ninguno", 13f, Estilo.tenue))
        for (e in v.envios.asReversed()) col.addView(tarjetaEnvio(e))
        col.addView(Estilo.seccion(this, "MOVIMIENTOS"))
        if (v.movimientos.isEmpty()) col.addView(Estilo.texto(this, "Sin movimientos todavía", 13f, Estilo.tenue))
        for (m in v.movimientos) {
            val t = Estilo.tarjeta(this)
            val neto = m.recibidoSat.toLong() - m.enviadoSat.toLong()
            t.addView(Estilo.texto(this, (if (neto >= 0) "+" else "") + String.format(java.util.Locale.ROOT, "%,d sat", neto).replace(',', '.'), 16f, if (neto >= 0) Estilo.ok else Estilo.claro))
            t.addView(Estilo.texto(this, "${m.txid.take(20)}… · ${m.altura?.let { "bloque $it" } ?: "en la mempool"}", 12f, Estilo.gris))
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
        t.addView(Estilo.texto(this, e.estado.texto().uppercase(), 12f, color))
        t.addView(Estilo.texto(this, "${Estilo.sat(e.importeSat)} a ${e.destino.take(18)}… · comisión ${Estilo.sat(e.comisionSat)}", 13f))
        if (e.reemplazaA.isNotEmpty()) t.addView(Estilo.texto(this, "reemplazo (cancelación) de ${e.reemplazaA.take(16)}…", 12f, Estilo.gris))
        t.addView(Estilo.texto(this, "${e.txid.take(20)}… · intentos ${e.intentosDifusion}${if (e.motivo.isNotEmpty()) "\n${e.motivo}" else ""}", 12f, Estilo.gris))
        if (e.puedeReintentar) t.addView(Estilo.boton(this, "Reintentar la misma", principal = false) { lanzar { repo.difundir(e.txid) } })
        if (e.puedeCancelar) t.addView(Estilo.boton(this, "Cancelar (libera las monedas)", principal = false) { lanzar { repo.cancelar(e.txid) } })
        if (e.puedeReemplazar) t.addView(Estilo.boton(this, "Cancelar por reemplazo", principal = false) { pedirTasaReemplazo(e) })
        return t
    }

    private fun pedirTasaReemplazo(e: EnvioInfo) {
        val campo = Estilo.campo(this, "sat/vB del reemplazo (más que la original: ${Estilo.dec(e.satVb)})", numerico = true)
        AlertDialog.Builder(this)
            .setTitle("Cancelar por reemplazo")
            .setMessage("Se construye otra transacción que gasta las MISMAS monedas y las devuelve a una dirección propia, con más comisión. La red confirmará una de las dos.")
            .setView(campo)
            .setPositiveButton("Revisar") { _, _ ->
                val tasa = campo.text.toString().replace(',', '.').toDoubleOrNull() ?: run { aviso("Tasa inválida"); return@setPositiveButton }
                lanzar { revisarReemplazo(repo.prepararCancelacion(e.txid, tasa)) }
            }
            .setNegativeButton("Atrás", null)
            .show()
    }

    private fun revisarReemplazo(p: PsbtPendiente) {
        AlertDialog.Builder(this)
            .setTitle("Revisa el reemplazo")
            .setMessage(
                "Reemplaza a ${p.reemplazaA.take(16)}…\nTodo a una dirección propia: ${p.destino}\n" +
                    "Importe ${Estilo.sat(p.importeSat)} · comisión ${Estilo.sat(p.comisionSat)} (${Estilo.dec(p.satVb)} sat/vB)",
            )
            .setPositiveButton("Firmar y difundir") { _, _ ->
                conSemilla("Firmar el reemplazo", "cancelación de ${p.reemplazaA.take(12)}…") { m ->
                    val firmada = repo.firmar(p.id, m)
                    val d = repo.difundir(firmada.txid)
                    aviso("Reemplazo: ${d.estado.texto()}")
                }
            }
            .setNegativeButton("Descartar", null)
            .show()
    }
}
