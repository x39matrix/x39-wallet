package x39.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import android.content.Intent
import android.os.Bundle
import android.view.View
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
    private lateinit var panelPi5: LinearLayout
    private lateinit var txtPi5: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = Estilo.columna(this)
        col.addView(cabecera("Billetera Bitcoin (${repo.ajustes.red.name.lowercase()})"))
        val tarjeta = Estilo.tarjeta(this)
        saldo = Estilo.texto(this, "—", 30f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        detalle = Estilo.texto(this, "", 12f, Estilo.gris)
        redTxt = Estilo.texto(this, "", 11f, Estilo.tenue)
        tarjeta.addView(saldo); tarjeta.addView(detalle); tarjeta.addView(redTxt)
        col.addView(tarjeta)
        banner = Estilo.tarjeta(this).apply { visibility = View.GONE }
        col.addView(banner)

        // Panel desplegable de Telemetría y Control Pi 5
        panelPi5 = Estilo.tarjeta(this).apply { visibility = View.GONE }
        txtPi5 = Estilo.texto(this, "Consultando Pi 5…", 12f, Estilo.gris)
        panelPi5.addView(txtPi5)
        val filaPi5 = Estilo.fila(this)
        filaPi5.addView(Estilo.boton(this, "Refrescar", principal = false) { cargarTelemetriaPi5() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Estilo.dp(this@BilleteraActivity, 4) }
        })
        filaPi5.addView(Estilo.boton(this, "Reiniciar Pool", principal = false) {
            lanzar {
                txtPi5.text = "Reiniciando CKPool en Pi 5…"
                val msg = TelemetriaPi5.reiniciarPool()
                aviso(msg)
                cargarTelemetriaPi5()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Estilo.dp(this@BilleteraActivity, 4) }
        })
        panelPi5.addView(filaPi5)
        // Panel web de la Pi 5 (:3000) en el navegador, en la IP que responda (túnel o LAN).
        panelPi5.addView(Estilo.boton(this, "Abrir panel web de la Pi 5 (:3000)", principal = false) { abrirPanelPi5() })
        col.addView(panelPi5)

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
        col.addView(Estilo.boton(this, "Minería y Nodo Pi 5 (Pool / Avisos)", principal = false) {
            if (panelPi5.visibility == View.VISIBLE) {
                panelPi5.visibility = View.GONE
            } else {
                panelPi5.visibility = View.VISIBLE
                cargarTelemetriaPi5()
            }
        })
        col.addView(Estilo.boton(this, "Emparejar con mi nodo (QR o fichero)", principal = false) { startActivity(Intent(this, EmparejarActivity::class.java)) })
        col.addView(Estilo.boton(this, "Ajustes (servidor Electrum)", principal = false) { startActivity(Intent(this, AjustesActivity::class.java)) })
        col.addView(Estilo.texto(this, "Autocustodia: la semilla vive solo en este aparato, cifrada con su clave. Cada firma pide tu autenticación.", 11f, Estilo.tenue))
        setContentView(Estilo.envolver(this, col))

        lifecycleScope.launch { repo.vista.collect { pintar(it) } }
        lifecycleScope.launch { repo.nodo.estado.collect { pintar(repo.vista.value) } }
    }

    /**
     * Abre el panel web de la Pi 5 en el navegador con la IP que responde de verdad (sonda TCP).
     * Si ninguna contesta o no hay navegador: aviso, nunca una pestaña en blanco ni una caída.
     */
    private fun abrirPanelPi5() {
        lanzar {
            val url = TelemetriaPi5.urlPanel(this@BilleteraActivity)
            if (url == null) {
                aviso("La Pi 5 no responde en :${TelemetriaPi5.PUERTO_PANEL} ni por el túnel (${TelemetriaPi5.IP_VPN}) ni por la LAN (${TelemetriaPi5.IP_LAN})")
                return@lanzar
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (_: android.content.ActivityNotFoundException) {
                aviso("No hay navegador para abrir $url")
            }
        }
    }

    private fun cargarTelemetriaPi5() {
        lanzar {
            txtPi5.text = "Conectando con Raspberry Pi 5…"
            try {
                txtPi5.text = TelemetriaPi5.consultar()
            } catch (e: Exception) {
                txtPi5.text = "⚠ ${e.message ?: "Error al conectar con Pi 5"}"
            }
        }
    }

    override fun onPause() {
        repo.nodo.vigilar(false)
        super.onPause()
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
            if (repo.ajustes.hayServidor()) {
                repo.nodo.vigilar(true) // reconexión con espera creciente mientras la pantalla esté delante
                try { repo.sincronizar() } catch (_: Exception) {}
            }
        }
    }

    private fun pintar(v: WalletRepository.Vista) {
        val s = v.saldo
        saldo.text = if (s == null) "—" else Estilo.sat(s.total)
        detalle.text = if (s == null) "" else "confirmado ${Estilo.sat(s.confirmado)} · pendiente ${Estilo.sat(s.pendiente)}"
        redTxt.text = buildString {
            append(if (repo.ajustes.hayServidor()) "nodo: ${repo.ajustes.servidor}" else "sin nodo: configúralo en Ajustes")
            if (repo.ajustes.servidorSinCifrar()) append("  ⚠ tcp:// sin cifrar")
            when (val n = repo.nodo.estado.value) {
                is EstadoNodo.Sano -> append("\nnodo sano · altura ${n.info.altura} · ${n.info.latenciaMs} ms" + if (n.info.huellaVista.isNotEmpty()) " · huella comprobada" else "")
                is EstadoNodo.Caido -> append("\n⚠ nodo caído: ${n.ultimoError}" + if (n.proximoIntentoEnS > 0) " · reintento en ${n.proximoIntentoEnS} s" else "")
                is EstadoNodo.RedIncorrecta -> append("\n⚠ el nodo es de otra red: ${n.detalle}")
                is EstadoNodo.HuellaIncorrecta -> append("\n⚠ huella del certificado incorrecta: no se habla con ese servidor")
                is EstadoNodo.Conectando -> append("\nnodo: conectando…")
                is EstadoNodo.SinConfigurar -> {}
            }
            append("\naltura ${v.altura}")
            if (v.sincronizando) append(" · sincronizando…")
            if (v.escaneoPendiente) append("\n⚠ Restaurada sin escaneo completo: no se puede enviar hasta sincronizar con tu servidor")
            v.ultimoError?.let { append("\n⚠ $it") }
        }
        val e = v.envioEnCurso
        banner.removeAllViews()
        if (e == null) { banner.visibility = View.GONE; return }
        banner.visibility = View.VISIBLE
        banner.addView(Estilo.texto(this, "ENVÍO SIN RESOLVER", 12f, Estilo.aviso))
        banner.addView(Estilo.texto(this, "${e.txid.take(16)}… · ${e.estado.texto()}${if (e.motivo.isNotEmpty()) " · ${e.motivo}" else ""}", 12f, Estilo.gris))
        banner.addView(Estilo.texto(this, "${Estilo.sat(e.importeSat)} a ${e.destino.take(14)}… · comisión ${Estilo.sat(e.comisionSat)} · intentos ${e.intentosDifusion}", 12f, Estilo.gris))
        if (e.puedeReintentar) banner.addView(Estilo.boton(this, "Reintentar la misma transacción") { lanzar { repo.difundir(e.txid) } })
        if (e.puedeCancelar) banner.addView(Estilo.boton(this, "Cancelar (nunca se difundió: libera las monedas)", principal = false) { lanzar { repo.cancelar(e.txid) } })
        if (e.puedeReemplazar) banner.addView(Estilo.boton(this, "Cancelar por reemplazo (en Historial)", principal = false) { startActivity(Intent(this, HistorialActivity::class.java)) })
        if (!e.puedeReintentar && !e.puedeCancelar && !e.puedeReemplazar) banner.addView(Estilo.texto(this, "esperando a que la red confirme la original o su reemplazo", 12f, Estilo.tenue))
    }
}
