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
import x39.wallet.R
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
        col.addView(cabecera(getString(R.string.x39w_billetera_titulo, repo.ajustes.red.name.lowercase())))
        val tarjeta = Estilo.tarjeta(this)
        saldo = Estilo.texto(this, "—", 30f, Estilo.aviso).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        detalle = Estilo.texto(this, "", 12f, Estilo.gris)
        redTxt = Estilo.texto(this, "", 11f, Estilo.tenue)
        tarjeta.addView(saldo); tarjeta.addView(detalle); tarjeta.addView(redTxt)
        col.addView(tarjeta)
        banner = Estilo.tarjeta(this).apply { visibility = View.GONE }
        col.addView(banner)

        val fila = Estilo.fila(this)
        fila.addView(Estilo.boton(this, getString(R.string.x39w_recibir)) { startActivity(Intent(this, RecibirActivity::class.java)) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Estilo.dp(this@BilleteraActivity, 5) }
        })
        fila.addView(Estilo.boton(this, getString(R.string.x39w_enviar), principal = false) { startActivity(Intent(this, EnviarActivity::class.java)) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Estilo.dp(this@BilleteraActivity, 5) }
        })
        col.addView(fila)
        col.addView(Estilo.boton(this, getString(R.string.x39w_historial_envios), principal = false) { startActivity(Intent(this, HistorialActivity::class.java)) })
        col.addView(Estilo.boton(this, getString(R.string.x39w_sincronizar), principal = false) { lanzarSinRepetirElNodo { repo.sincronizar() } })
        col.addView(Estilo.boton(this, getString(R.string.x39w_emparejar_boton), principal = false) { startActivity(Intent(this, EmparejarActivity::class.java)) })
        col.addView(Estilo.boton(this, getString(R.string.x39w_ajustes_nodo_boton), principal = false) { startActivity(Intent(this, AjustesActivity::class.java)) })
        col.addView(Estilo.texto(this, getString(R.string.x39w_autocustodia), 11f, Estilo.tenue))
        setContentView(Estilo.envolver(this, col))

        lifecycleScope.launch { repo.vista.collect { pintar(it) } }
        lifecycleScope.launch { repo.nodo.estado.collect { pintar(repo.vista.value) } }
    }

    override fun onPause() {
        repo.nodo.vigilar(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!Redes.androidCompatible()) { aviso(getString(R.string.x39w_android6)); finish(); return }
        when (repo.estado()) {
            WalletRepository.Estado.SIN_BILLETERA -> { startActivity(Intent(this, CrearRestaurarActivity::class.java)); return }
            WalletRepository.Estado.CLAVE_INVALIDADA -> { claveInvalidada(); return }
            WalletRepository.Estado.LISTA -> {}
        }
        lanzar {
            repo.abrir() // reanuda desde lo persistido (envíos firmados o inciertos)
            if (repo.nodo.config != null) {
                repo.nodo.vigilar(true) // reconexión con espera creciente mientras la pantalla esté delante
                try { repo.sincronizar() } catch (_: Exception) {}
            }
        }
    }

    /**
     * H1 (orden 8): si la operación falla porque el nodo no está disponible y la línea de estado
     * del nodo de esta pantalla ya muestra ese motivo, no se repite en un toast. Cualquier otro
     * fallo se avisa una vez, como siempre.
     */
    private fun lanzarSinRepetirElNodo(bloque: suspend () -> Unit) = lanzar(alFallar = { e ->
        if (!WalletRepository.loMuestraElNodo(e, repo.nodo.estado.value)) aviso(textoDeFallo(e))
    }) { bloque() }

    private fun pintar(v: WalletRepository.Vista) {
        val s = v.saldo
        saldo.text = if (s == null) "—" else Estilo.sat(s.total)
        detalle.text = if (s == null) "" else getString(R.string.x39w_saldo_detalle, Estilo.sat(s.confirmado), Estilo.sat(s.pendiente))
        redTxt.text = buildString {
            val cfg = repo.nodo.config
            append(if (cfg == null) getString(R.string.x39w_sin_nodo) else getString(R.string.x39w_nodo_en_uso, textoPantalla(cfg.descripcionNodo())))
            // tcp:// dentro del X39 Túnel ya va cifrado por el túnel; fuera de él se avisa.
            if (cfg != null && !cfg.cifrado() && cfg.tunel != ModoTunel.X39) append("  ").append(getString(R.string.x39w_tcp_sin_cifrar_corto))
            if (cfg?.proxySocks5 != null) append("  ").append(getString(R.string.x39w_por_proxy))
            when (val n = repo.nodo.estado.value) {
                is EstadoNodo.Sano -> append("\n").append(getString(R.string.x39w_nodo_sano_linea, n.info.altura.toString(), n.info.latenciaMs.toString()) + if (n.info.huellaVista.isNotEmpty()) " " + getString(R.string.x39w_huella_comprobada_corto) else "")
                is EstadoNodo.Caido -> append("\n").append(getString(R.string.x39w_nodo_caido_linea, textoPantalla(n.ultimoError)) + if (n.proximoIntentoEnS > 0) " " + getString(R.string.x39w_reintento_en, n.proximoIntentoEnS.toString()) else "")
                is EstadoNodo.RedIncorrecta -> append("\n").append(getString(R.string.x39w_nodo_otra_red_linea, n.detalle))
                is EstadoNodo.HuellaIncorrecta -> append("\n").append(getString(R.string.x39w_huella_incorrecta_linea))
                is EstadoNodo.Conectando -> append("\n").append(getString(R.string.x39w_nodo_conectando_linea))
                is EstadoNodo.SinConfigurar -> {}
            }
            append("\n").append(getString(R.string.x39w_altura_linea, v.altura.toString()))
            if (v.sincronizando) append(" ").append(getString(R.string.x39w_sincronizando))
            if (v.escaneoPendiente) append("\n").append(getString(R.string.x39w_escaneo_pendiente_aviso))
            v.ultimoError?.let { append("\n⚠ ").append(textoPantalla(it)) }
        }
        val e = v.envioEnCurso
        banner.removeAllViews()
        if (e == null) { banner.visibility = View.GONE; return }
        banner.visibility = View.VISIBLE
        banner.addView(Estilo.texto(this, getString(R.string.x39w_envio_sin_resolver), 12f, Estilo.aviso))
        banner.addView(Estilo.texto(this, getString(R.string.x39w_txid_estado, e.txid.take(16), e.estado.texto(this)) + if (e.motivo.isNotEmpty()) " · ${e.motivo}" else "", 12f, Estilo.gris))
        banner.addView(Estilo.texto(this, getString(R.string.x39w_envio_resumen, Estilo.sat(e.importeSat), e.destino.take(14), Estilo.sat(e.comisionSat), e.intentosDifusion.toString()), 12f, Estilo.gris))
        if (e.puedeReintentar) banner.addView(Estilo.boton(this, getString(R.string.x39w_reintentar_misma_tx)) { lanzarSinRepetirElNodo { repo.difundir(e.txid) } })
        if (e.puedeCancelar) banner.addView(Estilo.boton(this, getString(R.string.x39w_cancelar_libera), principal = false) { lanzar { repo.cancelar(e.txid) } })
        if (e.puedeReemplazar) banner.addView(Estilo.boton(this, getString(R.string.x39w_cancelar_reemplazo_historial), principal = false) { startActivity(Intent(this, HistorialActivity::class.java)) })
        if (!e.puedeReintentar && !e.puedeCancelar && !e.puedeReemplazar) banner.addView(Estilo.texto(this, getString(R.string.x39w_esperando_confirmacion), 12f, Estilo.tenue))
    }
}
