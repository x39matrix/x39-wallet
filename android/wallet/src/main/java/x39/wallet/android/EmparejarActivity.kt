package x39.wallet.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import x39.wallet.R

/**
 * «Emparejar con mi nodo»: importa un paquete de emparejamiento (fichero JSON o QR con la
 * cámara), lo valida ENTERO antes de tocar nada, lo muestra y, solo tras confirmación explícita,
 * lo vuelve a validar (vigencia incluida) y lo prueba con NodeBridge; se guarda únicamente si el
 * nodo da Sano. Si no, se muestra el motivo y no queda rastro. «Descartar» cancela de verdad:
 * si la comprobación estaba en marcha, su resultado no se guarda.
 */
@RequiresApi(Build.VERSION_CODES.M) // Keystore con autenticación por operación (BaseActivity)
class EmparejarActivity : BaseActivity() {

    companion object {
        /** Texto del paquete ya en mano (lo entrega el escáner; también lo usan las pruebas). */
        const val EXTRA_TEXTO = "x39.wallet.PAQUETE"
    }

    private lateinit var zona: LinearLayout
    private var textoActual: String? = null
    private var trabajo: Job? = null
    /** Reloj de la validación (segundos UTC); sustituible en las pruebas. */
    @VisibleForTesting
    var reloj: () -> Long = { System.currentTimeMillis() / 1000 }

    private val elegirFichero = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { leerFichero(it) } }
    private val escanear = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(EscanearQrActivity.EXTRA_RESULTADO)?.let { procesar(it) }
    }
    private val permisoCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) escanear.launch(Intent(this, EscanearQrActivity::class.java))
        else aviso(getString(R.string.x39w_emp_sin_camara))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Estilo.columna(this)
        col.addView(cabecera(getString(R.string.x39w_emp_titulo)))
        col.addView(Estilo.texto(this, getString(R.string.x39w_emp_texto), 12f, Estilo.gris))
        val fila = Estilo.fila(this)
        fila.addView(Estilo.boton(this, getString(R.string.x39w_emp_importar), principal = false) {
            elegirFichero.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "text/*"))
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Estilo.dp(this@EmparejarActivity, 5) } })
        fila.addView(Estilo.boton(this, getString(R.string.x39w_emp_escanear)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                escanear.launch(Intent(this, EscanearQrActivity::class.java))
            } else {
                permisoCamara.launch(Manifest.permission.CAMERA)
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Estilo.dp(this@EmparejarActivity, 5) } })
        col.addView(fila)
        zona = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(zona)
        setContentView(Estilo.envolver(this, col))
        intent.getStringExtra(EXTRA_TEXTO)?.let { procesar(it) }
    }

    /** Lee como máximo MAX_BYTES + 1: si hay más, no es un paquete y se rechaza sin leer el resto. */
    private fun leerAcotado(s: InputStream): String {
        val tope = Emparejamiento.MAX_BYTES + 1
        val buf = ByteArray(tope)
        var total = 0
        while (total < tope) {
            val n = s.read(buf, total, tope - total)
            if (n < 0) break
            total += n
        }
        if (total > Emparejamiento.MAX_BYTES) throw ErrorEmparejamiento("el fichero es demasiado grande para ser un paquete (más de ${Emparejamiento.MAX_BYTES / 1024} KB)")
        return String(buf, 0, total, Charsets.UTF_8)
    }

    private fun leerFichero(uri: Uri) {
        lanzar(alFallar = { e -> pintarRechazo(e.message ?: "no se pudo leer el fichero") }) {
            val texto = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { leerAcotado(it) } ?: throw ErrorEmparejamiento("no se pudo abrir el fichero")
            }
            procesar(texto)
        }
    }

    /** Valida el texto entero; rechazo o vista previa, nunca se guarda nada aquí. */
    fun procesar(texto: String) {
        descartar()
        val p = try {
            Emparejamiento.analizar(texto, repo.ajustes.red, reloj())
        } catch (e: ErrorEmparejamiento) {
            pintarRechazo(e.message ?: "paquete inválido"); return
        }
        textoActual = texto
        pintarVistaPrevia(p)
    }

    /** Cancela cualquier comprobación en marcha y quita la vista previa: lo que llegue después no se guarda. */
    private fun descartar() {
        trabajo?.cancel()
        trabajo = null
        textoActual = null
        zona.removeAllViews()
    }

    /** `motivo` es el mensaje interno (castellano) de `Emparejamiento`; se traduce al pintar. */
    private fun pintarRechazo(motivo: String) {
        zona.removeAllViews()
        val t = Estilo.tarjeta(this).apply { contentDescription = "rechazo" }
        t.addView(Estilo.texto(this, getString(R.string.x39w_emp_rechazado), 12f, Estilo.peligro))
        t.addView(Estilo.texto(this, textoPantalla(motivo), 14f))
        t.addView(Estilo.texto(this, getString(R.string.x39w_emp_nada_guardado), 12f, Estilo.tenue))
        zona.addView(t)
    }

    private fun fila(etiqueta: String, valor: String): TextView = Estilo.texto(this, getString(R.string.x39w_emp_fila, etiqueta, valor), 13f)

    private fun pintarVistaPrevia(p: PaqueteEmparejamiento) {
        val t = Estilo.tarjeta(this).apply { contentDescription = "vista_previa" }
        t.addView(Estilo.texto(this, getString(R.string.x39w_emp_trae), 12f, Estilo.gris))
        t.addView(fila(getString(R.string.x39w_emp_nombre), p.nombre))
        t.addView(fila(getString(R.string.x39w_emp_red), p.red.name.lowercase()))
        t.addView(fila(getString(R.string.x39w_emp_servidor), p.electrum + if (p.electrum.startsWith("tcp://")) "  " + getString(R.string.x39w_emp_sin_cifrar) else ""))
        val huella = fila(getString(R.string.x39w_emp_huella), textoPantalla(p.huellaAbreviada()))
        t.addView(huella)
        if (p.huellaSpki.isNotEmpty()) {
            t.addView(Estilo.boton(this, getString(R.string.x39w_emp_ver_huella), principal = false) {
                huella.text = getString(R.string.x39w_emp_fila, getString(R.string.x39w_emp_huella), p.huellaSpki)
            })
        }
        t.addView(fila(getString(R.string.x39w_emp_emitido), Emparejamiento.formatearUtc(p.emitidoS)))
        t.addView(fila(getString(R.string.x39w_emp_caduca), Emparejamiento.formatearUtc(p.caducaS)))
        val tun = p.tunel
        if (tun == null) {
            t.addView(fila(getString(R.string.x39w_emp_tunel), getString(R.string.x39w_emp_no_trae)))
        } else {
            t.addView(fila(getString(R.string.x39w_emp_tunel_endpoint), tun.endpoint))
            t.addView(fila(getString(R.string.x39w_emp_tunel_clave), "${tun.clavePublicaNodo.take(6)}…${tun.clavePublicaNodo.takeLast(6)}"))
            t.addView(fila(getString(R.string.x39w_emp_tunel_redes), tun.ipsPermitidas.joinToString(", ")))
            tun.direccionMovil?.let { t.addView(fila(getString(R.string.x39w_emp_tunel_movil), it)) }
            t.addView(Estilo.texto(this, getString(R.string.x39w_emp_tunel_aviso), 12f, Estilo.aviso))
        }
        val estado = Estilo.texto(this, "", 13f, Estilo.aviso).apply { contentDescription = "estado_emparejamiento" }
        t.addView(estado)
        val confirmar = Estilo.boton(this, getString(R.string.x39w_emp_confirmar)) {}
        confirmar.contentDescription = "confirmar_emparejamiento"
        confirmar.setOnClickListener {
            val texto = textoActual ?: return@setOnClickListener
            // Se vuelve a validar AHORA (la vigencia puede haber vencido desde que se abrió).
            val vigente = try {
                Emparejamiento.analizar(texto, repo.ajustes.red, reloj())
            } catch (e: ErrorEmparejamiento) {
                descartar(); pintarRechazo(e.message ?: "paquete inválido"); return@setOnClickListener
            }
            confirmar.isEnabled = false
            estado.text = getString(R.string.x39w_emp_comprobando)
            trabajo?.cancel()
            trabajo = lifecycleScope.launch {
                try {
                    // Configuración y metadatos del paquete se guardan JUNTOS dentro de NodeBridge, o nada.
                    val e = repo.nodo.adoptarSiSano(vigente.aConfig(), vigente)
                    if (e is EstadoNodo.Sano) {
                        aviso(getString(R.string.x39w_emp_sano, vigente.nombre))
                        finish()
                    } else {
                        estado.text = getString(R.string.x39w_emp_no_guardado_motivo, textoPantalla(e.describir()))
                        confirmar.isEnabled = true
                    }
                } catch (_: CancellationException) {
                    // Descartado o pantalla cerrada mientras se comprobaba: NodeBridge no guardó nada.
                } catch (e: Exception) {
                    estado.text = getString(R.string.x39w_emp_no_guardado_motivo, e.message?.let { textoPantalla(it) } ?: e.javaClass.simpleName)
                    confirmar.isEnabled = true
                }
            }
        }
        t.addView(confirmar)
        t.addView(Estilo.boton(this, getString(R.string.x39w_descartar), principal = false) { descartar() }.apply { contentDescription = "descartar_emparejamiento" })
        zona.addView(t)
    }
}
