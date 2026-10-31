package x39.wallet.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Lee UN código QR con la cámara trasera (CameraX + zxing) y devuelve su texto a quien la
 * llamó. No interpreta nada: la validación la hace la pantalla de emparejamiento.
 */
@RequiresApi(Build.VERSION_CODES.M) // permiso de cámara en tiempo de ejecución (BaseActivity exige M)
class EscanearQrActivity : BaseActivity() {

    companion object {
        const val EXTRA_RESULTADO = "x39.wallet.QR"
    }

    private val ejecutor = Executors.newSingleThreadExecutor()
    private val lector = QRCodeReader()
    @Volatile private var entregado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.protegerContraCapturas()
        val col = Estilo.columna(this)
        col.addView(cabecera("Escanear el QR del paquete"))
        col.addView(Estilo.texto(this, "Apunta al QR generado en tu nodo. Lo leído se valida en la pantalla anterior; no se guarda nada hasta que lo confirmes.", 12f, Estilo.gris))
        val vista = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Estilo.dp(this@EscanearQrActivity, 360))
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        col.addView(vista)
        setContentView(col)
        arrancarCamara(vista)
    }

    private fun arrancarCamara(vista: PreviewView) {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            try {
                val proveedor = futuro.get()
                val previa = Preview.Builder().build().also { it.setSurfaceProvider(vista.surfaceProvider) }
                val analisis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(ejecutor) { img -> analizar(img) } }
                proveedor.unbindAll()
                proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previa, analisis)
            } catch (e: Exception) {
                aviso("No se pudo abrir la cámara: ${e.message ?: e.javaClass.simpleName}")
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Luminancia (plano Y) → zxing. Un solo resultado: el primero que decodifica. */
    private fun analizar(img: ImageProxy) {
        try {
            if (entregado) return
            val plano = img.planes[0]
            val buf = plano.buffer
            val datos = ByteArray(buf.remaining())
            buf.get(datos)
            val fuente = PlanarYUVLuminanceSource(datos, plano.rowStride, img.height, 0, 0, img.width, img.height, false)
            val texto = try {
                lector.decode(BinaryBitmap(HybridBinarizer(fuente))).text
            } catch (_: ReaderException) {
                null
            } finally {
                lector.reset()
            }
            if (texto != null) {
                entregado = true
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULTADO, texto))
                    finish()
                }
            }
        } finally {
            img.close()
        }
    }

    override fun onDestroy() {
        ejecutor.shutdown()
        super.onDestroy()
    }
}
