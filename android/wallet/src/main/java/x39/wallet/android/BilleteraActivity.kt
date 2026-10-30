package x39.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import x39.wallet.android.WalletRepository.Companion.texto

/** Pantalla principal: saldo, estado de la red y accesos. Reanuda el envío sin resolver al arrancar. */
@RequiresApi(Build.VERSION_CODES.M) // Keystore con autenticación por operación
class BilleteraActivity : BaseActivity() {

    private lateinit var col: LinearLayout
    private lateinit var saldo: TextView
    private lateinit var detalle: TextView
    private lateinit var redTxt: TextView
    private lateinit var banner: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = Estilo.columna(this)
        col.addView(Estilo.titulo(this, "Billetera Bitcoin (${repo.ajustes.red.name.lowercase()})"))
        val tarjeta = Estilo.tarjeta(this)
        saldo = Estilo.texto(this, "—", 30f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        detalle = Estilo.texto(this, "", 12f, Estilo.gris)
        redTxt = Estilo.texto(this, "", 11f, Estilo.tenue)
        tarjeta.addView(saldo); tarjeta.addView(detalle); tarjeta.addView(redTxt)
        col.addView(tarjeta)
        banner = Estilo.tarjeta(this).apply { visibility = android.view.View.GONE }
        col.addView(banner)
        val fila = Estilo.fila(this)
        fila.addView(Estilo.boton(this, "Recibir") { startActivity(Intent(this, RecibirActivity::class.java)) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Estilo.dp(this@BilleteraActivity, 5) }
        })
        fila.addView(Estilo.boton(this, "Enviar", principal = false) { startActivity(Intent(this, EnviarActivity::class.java)) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Estilo.dp(this@BilleteraActivity, 5) }
        })
        col.addView(fila)
        col.addView(Estilo.boton(this, "Historial y envíos", principal = false) { startActivity(Intent(this, HistorialActivity::class.java)) })
        col.addView(Estilo.boton(this, "Sincronizar", principal = false) { lanzar { repo.sincronizar() } })
        col.addView(Estilo.boton(this, "Ajustes (servidor Electrum)", principal = false) { startActivity(Intent(this, AjustesActivity::class.java)) })
        col.addView(Estilo.texto(this, "Autocustodia: la semilla vive solo en este aparato, cifrada con su clave. Cada firma pide tu autenticación.", 11f, Estilo.tenue))
        setContentView(Estilo.envolver(this, col))

        lifecycleScope.launch { repo.vista.collect { pintar(it) } }
    }

    override fun onResume() {
        super.onResume()
        if (!Redes.androidCompatible()) { aviso("La billetera exige Android 6 o superior"); finish(); return }
        when (repo.estado()) {
            WalletRepository.Estado.SIN_BILLETERA -> { startActivity(Intent(this, CrearRestaurarActivity::class.java)); return }
            WalletRepository.Estado.CLAVE_INVALIDADA -> { claveInvalidada(); return }
            WalletRepository.Estado.LISTA -> {}
        }
        lanzar {
            repo.abrir() // reanuda desde lo persistido (envíos firmados o inciertos)
            if (repo.ajustes.hayServidor()) try { repo.sincronizar() } catch (_: Exception) {}
        }
    }

    private fun pintar(v: WalletRepository.Vista) {
        val s = v.saldo
        saldo.text = if (s == null) "—" else Estilo.sat(s.total)
        detalle.text = if (s == null) "" else "confirmado ${Estilo.sat(s.confirmado)} · pendiente ${Estilo.sat(s.pendiente)}"
        redTxt.text = buildString {
            append(if (repo.ajustes.hayServidor()) "servidor: ${repo.ajustes.servidor}" else "sin servidor Electrum: configúralo en Ajustes")
            if (repo.ajustes.servidorSinCifrar()) append("  ⚠ tcp:// sin cifrar")
            append("\naltura ${v.altura}")
            if (v.sincronizando) append(" · sincronizando…")
            if (v.escaneoPendiente) append("\n⚠ Restaurada sin escaneo completo: no se puede enviar hasta sincronizar con tu servidor")
            v.ultimoError?.let { append("\n⚠ $it") }
        }
        val e = v.envioEnCurso
        banner.removeAllViews()
        if (e == null) { banner.visibility = android.view.View.GONE; return }
        banner.visibility = android.view.View.VISIBLE
        banner.addView(Estilo.texto(this, "ENVÍO SIN RESOLVER", 12f, Estilo.aviso))
        banner.addView(Estilo.texto(this, "${e.txid.take(16)}… · ${e.estado.texto()}${if (e.motivo.isNotEmpty()) " · ${e.motivo}" else ""}", 12f, Estilo.gris))
        banner.addView(Estilo.texto(this, "${Estilo.sat(e.importeSat)} a ${e.destino.take(14)}… · comisión ${Estilo.sat(e.comisionSat)} · intentos ${e.intentosDifusion}", 12f, Estilo.gris))
        if (e.puedeReintentar) banner.addView(Estilo.boton(this, "Reintentar la misma transacción") { lanzar { repo.difundir(e.txid) } })
        if (e.puedeCancelar) banner.addView(Estilo.boton(this, "Cancelar (nunca se difundió: libera las monedas)", principal = false) { lanzar { repo.cancelar(e.txid) } })
        if (e.puedeReemplazar) banner.addView(Estilo.boton(this, "Cancelar por reemplazo (en Historial)", principal = false) { startActivity(Intent(this, HistorialActivity::class.java)) })
        if (!e.puedeReintentar && !e.puedeCancelar && !e.puedeReemplazar) banner.addView(Estilo.texto(this, "esperando a que la red confirme la original o su reemplazo", 12f, Estilo.tenue))
    }
}
