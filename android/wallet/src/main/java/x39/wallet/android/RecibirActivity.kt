package x39.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView

/** Dirección nueva (segwit nativo) con QR. La dirección es pública: sí se puede copiar. */
@RequiresApi(Build.VERSION_CODES.M) // Keystore con autenticación por operación
class RecibirActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Estilo.columna(this)
        col.addView(Estilo.titulo(this, "Recibir"))
        val qr = ImageView(this).apply { adjustViewBounds = true; setPadding(0, Estilo.dp(this@RecibirActivity, 8), 0, Estilo.dp(this@RecibirActivity, 8)) }
        val dir = TextView(this).apply { textSize = 13f; setTextColor(Estilo.claro); setTextIsSelectable(true) }
        col.addView(qr); col.addView(dir)
        var actual = ""
        fun nueva() = lanzar {
            actual = repo.nuevaDireccion()
            dir.text = actual
            qr.setImageBitmap(Qr.deDireccion(actual, Estilo.dp(this, 240)))
        }
        col.addView(Estilo.texto(this, "Cada vez se da una dirección nueva; las anteriores siguen valiendo.", 12f, Estilo.gris))
        col.addView(Estilo.boton(this, "Copiar dirección") {
            if (actual.isNotEmpty()) {
                androidx.core.content.ContextCompat.getSystemService(this, ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("dirección", actual))
                aviso("Dirección copiada")
            }
        })
        col.addView(Estilo.boton(this, "Otra dirección", principal = false) { nueva() })
        setContentView(Estilo.envolver(this, col))
        nueva()
    }
}
