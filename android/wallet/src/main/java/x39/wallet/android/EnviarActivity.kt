package x39.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Bundle
import android.widget.LinearLayout
import x39.wallet.PsbtPendiente
import x39.wallet.R
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
        col.addView(cabecera(getString(R.string.x39w_env_titulo)))
        val destino = Estilo.campo(this, getString(R.string.x39w_env_destino))
        val importe = Estilo.campo(this, getString(R.string.x39w_env_importe), numerico = true)
        val tasa = Estilo.campo(this, getString(R.string.x39w_env_tasa), numerico = true)
        val maximo = Estilo.campo(this, getString(R.string.x39w_env_maximo), numerico = true)
        col.addView(destino); col.addView(importe); col.addView(tasa); col.addView(maximo)
        col.addView(Estilo.boton(this, getString(R.string.x39w_env_estimar), principal = false) {
            lanzar { tasa.setText(Estilo.dec(repo.estimarComision())) }
        })
        col.addView(Estilo.boton(this, getString(R.string.x39w_revisar)) {
            val d = destino.text.toString().trim()
            val sat = importe.text.toString().trim().toULongOrNull()
            val t = tasa.text.toString().trim().replace(',', '.').toDoubleOrNull()
            val max = maximo.text.toString().trim().toULongOrNull() ?: 10_000uL
            if (d.isEmpty() || sat == null || t == null) { aviso(getString(R.string.x39w_env_faltan_datos)); return@boton }
            lanzar { revision(repo.prepararEnvio(d, sat, t, max)) }
        })
    }

    private fun revision(p: PsbtPendiente) {
        col.removeAllViews()
        col.addView(cabecera(getString(R.string.x39w_env_revisa)))
        val t = Estilo.tarjeta(this)
        t.addView(Estilo.texto(this, getString(R.string.x39w_env_destino_linea, p.destino), 13f))
        t.addView(Estilo.texto(this, getString(R.string.x39w_env_importe_linea, Estilo.sat(p.importeSat))))
        t.addView(Estilo.texto(this, getString(R.string.x39w_env_comision_linea, Estilo.sat(p.comisionSat), p.vbytes.toString(), Estilo.dec(p.satVb), Estilo.sat(p.maxComisionSat))))
        t.addView(Estilo.texto(this, if (p.cambio.isEmpty()) getString(R.string.x39w_env_sin_cambio) else getString(R.string.x39w_env_cambio, Estilo.sat(p.cambioSat)), 13f, Estilo.gris))
        t.addView(Estilo.texto(this, getString(R.string.x39w_env_red_caduca, p.red.name.lowercase(), p.caducaEnS.toString()), 12f, Estilo.tenue))
        col.addView(t)
        col.addView(Estilo.texto(this, getString(R.string.x39w_env_motor_recomprueba), 12f, Estilo.gris))
        col.addView(Estilo.boton(this, getString(R.string.x39w_env_firmar_enviar)) {
            conSemilla(getString(R.string.x39w_env_firmar_titulo), getString(R.string.x39w_env_firmar_sub, Estilo.sat(p.importeSat), p.destino.take(12))) { m ->
                val firmada = repo.firmar(p.id, m)
                resultado(firmada.txid)
            }
        })
        col.addView(Estilo.boton(this, getString(R.string.x39w_descartar), principal = false) { formulario() })
    }

    /** Tras firmar: la columna ya está en pantalla (no se vuelve a envolver); difunde y muestra el estado. */
    internal fun resultado(txid: String) {
        col.removeAllViews()
        col.addView(cabecera(getString(R.string.x39w_env_firmada)))
        col.addView(Estilo.texto(this, getString(R.string.x39w_env_txid, txid), 12f, Estilo.gris))
        col.addView(Estilo.texto(this, getString(R.string.x39w_env_difundiendo), 13f))
        lanzar(alFallar = { e ->
            col.addView(Estilo.texto(this, getString(R.string.x39w_env_fallo_difusion, textoDeFallo(e)), 13f, Estilo.aviso))
            col.addView(Estilo.boton(this, getString(R.string.x39w_env_volver), principal = false) { finish() })
        }) {
            val e = repo.difundir(txid)
            col.addView(Estilo.texto(this, getString(R.string.x39w_env_estado, e.estado.texto(this)) + if (e.motivo.isNotEmpty()) " — ${e.motivo}" else "", 14f, if (e.estado == x39.wallet.EstadoEnvio.ENVIADA) Estilo.ok else Estilo.aviso))
            col.addView(Estilo.boton(this, getString(R.string.x39w_env_volver)) { finish() })
        }
    }
}
