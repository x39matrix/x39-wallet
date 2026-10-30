package x39.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Bundle
import android.widget.LinearLayout
import x39.wallet.PsbtPendiente
import x39.wallet.android.WalletRepository.Companion.texto

/** Enviar: revisión → desbloqueo (prompt) → firma → difusión. FLAG_SECURE. */
@RequiresApi(Build.VERSION_CODES.M) // Keystore con autenticación por operación
class EnviarActivity : BaseActivity() {

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.protegerContraCapturas()
        col = Estilo.columna(this)
        setContentView(Estilo.envolver(this, col))
        formulario()
    }

    private fun formulario() {
        col.removeAllViews()
        col.addView(Estilo.titulo(this, "Enviar"))
        val destino = Estilo.campo(this, "Dirección de destino")
        val importe = Estilo.campo(this, "Importe en sat", numerico = true)
        val tasa = Estilo.campo(this, "Comisión en sat/vB", numerico = true)
        val maximo = Estilo.campo(this, "Comisión máxima aceptada en sat (por defecto 10000)", numerico = true)
        col.addView(destino); col.addView(importe); col.addView(tasa); col.addView(maximo)
        col.addView(Estilo.boton(this, "Estimar comisión (2 bloques)", principal = false) {
            lanzar { tasa.setText(Estilo.dec(repo.estimarComision())) }
        })
        col.addView(Estilo.boton(this, "Revisar") {
            val d = destino.text.toString().trim()
            val sat = importe.text.toString().trim().toULongOrNull()
            val t = tasa.text.toString().trim().replace(',', '.').toDoubleOrNull()
            val max = maximo.text.toString().trim().toULongOrNull() ?: 10_000uL
            if (d.isEmpty() || sat == null || t == null) { aviso("Faltan datos"); return@boton }
            lanzar { revision(repo.prepararEnvio(d, sat, t, max)) }
        })
    }

    private fun revision(p: PsbtPendiente) {
        col.removeAllViews()
        col.addView(Estilo.titulo(this, "Revisa antes de firmar"))
        val t = Estilo.tarjeta(this)
        t.addView(Estilo.texto(this, "Destino\n${p.destino}", 13f))
        t.addView(Estilo.texto(this, "Importe: ${Estilo.sat(p.importeSat)}"))
        t.addView(Estilo.texto(this, "Comisión: ${Estilo.sat(p.comisionSat)} (≈${p.vbytes} vB, ${Estilo.dec(p.satVb)} sat/vB; máximo ${Estilo.sat(p.maxComisionSat)})"))
        t.addView(Estilo.texto(this, if (p.cambio.isEmpty()) "Sin cambio" else "Cambio a una dirección propia: ${Estilo.sat(p.cambioSat)}", 13f, Estilo.gris))
        t.addView(Estilo.texto(this, "Red: ${p.red.name.lowercase()} · la revisión caduca en ${p.caducaEnS} s", 12f, Estilo.tenue))
        col.addView(t)
        col.addView(Estilo.texto(this, "Al firmar, el motor vuelve a comprobar que lo que firma es exactamente esto.", 12f, Estilo.gris))
        col.addView(Estilo.boton(this, "Firmar y enviar") {
            conSemilla("Firmar el envío", "${Estilo.sat(p.importeSat)} a ${p.destino.take(12)}…") { m ->
                val firmada = repo.firmar(p.id, m)
                resultado(firmada.txid)
            }
        })
        col.addView(Estilo.boton(this, "Descartar", principal = false) { formulario() })
    }

    /** Tras firmar: la columna ya está en pantalla (no se vuelve a envolver); difunde y muestra el estado. */
    internal fun resultado(txid: String) {
        col.removeAllViews()
        col.addView(Estilo.titulo(this, "Firmada"))
        col.addView(Estilo.texto(this, "txid $txid", 12f, Estilo.gris))
        col.addView(Estilo.texto(this, "Las monedas ya quedan reservadas en esta billetera. Difundiendo…", 13f))
        lanzar(alFallar = { e ->
            col.addView(Estilo.texto(this, "⚠ ${e.message}. La transacción sigue firmada: puedes reintentar desde la pantalla principal o el historial.", 13f, Estilo.aviso))
            col.addView(Estilo.boton(this, "Volver", principal = false) { finish() })
        }) {
            val e = repo.difundir(txid)
            col.addView(Estilo.texto(this, "Estado: ${e.estado.texto()}${if (e.motivo.isNotEmpty()) " — ${e.motivo}" else ""}", 14f, if (e.estado == x39.wallet.EstadoEnvio.ENVIADA) Estilo.ok else Estilo.aviso))
            col.addView(Estilo.boton(this, "Volver") { finish() })
        }
    }
}
