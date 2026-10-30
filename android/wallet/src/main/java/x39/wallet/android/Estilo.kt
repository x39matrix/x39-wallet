package x39.wallet.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText

/** Paleta y piezas de interfaz (vistas programáticas, misma estética oscura que la app). */
object Estilo {
    val fondo = Color.parseColor("#0E0E10")
    val grafito = Color.parseColor("#141414")
    val sup2 = Color.parseColor("#26262B")
    val claro = Color.parseColor("#F2F2F2")
    val gris = Color.parseColor("#8A8A8A")
    val tenue = Color.parseColor("#5F5F66")
    val aviso = Color.parseColor("#E0A030")
    val peligro = Color.parseColor("#D85050")
    val ok = Color.parseColor("#4CAF50")

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun columna(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 24))
        setBackgroundColor(fondo)
    }

    fun envolver(c: Context, v: View): View = ScrollView(c).apply {
        setBackgroundColor(fondo)
        isFillViewport = true
        addView(v)
    }

    fun titulo(c: Context, t: String): TextView = TextView(c).apply {
        text = t; textSize = 20f; setTextColor(claro); setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(c, 8), 0, dp(c, 12))
    }

    fun seccion(c: Context, t: String): TextView = TextView(c).apply {
        text = t; textSize = 12f; setTextColor(gris); letterSpacing = 0.08f
        setPadding(0, dp(c, 16), 0, dp(c, 6))
    }

    fun texto(c: Context, t: CharSequence, tam: Float = 14f, color: Int = claro): TextView = TextView(c).apply {
        text = t; textSize = tam; setTextColor(color); setPadding(0, dp(c, 4), 0, dp(c, 4))
    }

    fun tarjeta(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        background = GradientDrawable().apply { setColor(grafito); cornerRadius = dp(c, 12).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(c, 10)
        }
    }

    fun boton(c: Context, t: String, principal: Boolean = true, onClick: () -> Unit): TextView = TextView(c).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER
        setTextColor(if (principal) grafito else claro)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13))
        background = GradientDrawable().apply { setColor(if (principal) claro else sup2); cornerRadius = dp(c, 10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(c, 8)
        }
        setOnClickListener { onClick() }
    }

    fun campo(c: Context, pista: String, numerico: Boolean = false): EditText = AppCompatEditText(c).apply {
        hint = pista; setHintTextColor(tenue); setTextColor(claro); textSize = 15f
        inputType = if (numerico) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12))
        background = GradientDrawable().apply { setColor(sup2); cornerRadius = dp(c, 10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(c, 6)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    fun fila(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }

    fun separador(c: Context, h: Int = 8): View = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, h))
    }

    fun sat(v: ULong): String = String.format(java.util.Locale.ROOT, "%,d sat", v.toLong()).replace(',', '.')

    fun dec(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v)
}
