package x39.wallet.android

import android.os.Build
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import x39.wallet.R

/**
 * Crear una billetera nueva (12 palabras mostradas UNA vez, confirmación de copia), restaurar
 * desde 12 palabras, o RECUPERAR la clave del Keystore tras una invalidación (sin tocar el
 * estado de la billetera). FLAG_SECURE. Las palabras se pintan con `setText(char[], 0, n)`,
 * nunca como String, y se ponen a cero en `onStop` SIEMPRE: al volver hay que revelar de nuevo.
 */
@RequiresApi(Build.VERSION_CODES.M) // Keystore con autenticación por operación
class CrearRestaurarActivity : BaseActivity() {

    private lateinit var col: LinearLayout
    private var semilla: SemillaNueva? = null
    private var palabras: CharArray? = null
    private var campoActivo: CampoSinPortapapeles? = null
    private var recuperarClave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.protegerContraCapturas()
        recuperarClave = intent.getBooleanExtra(EXTRA_RECUPERAR_CLAVE, false)
        col = Estilo.columna(this)
        setContentView(Estilo.envolver(this, col))
    }

    override fun onStart() {
        super.onStart()
        // Siempre se empieza desde el principio: lo revelado antes ya se puso a cero.
        if (recuperarClave) recuperacion() else inicio()
    }

    override fun onStop() {
        // Al salir de la pantalla, SIEMPRE: palabras, semilla y campo a cero.
        limpiar()
        super.onStop()
    }

    override fun onDestroy() {
        limpiar()
        super.onDestroy()
    }

    private fun inicio() {
        limpiar()
        col.removeAllViews()
        col.addView(cabecera(getString(R.string.x39w_crear_titulo)))
        col.addView(Estilo.texto(this, getString(R.string.x39w_crear_red, repo.ajustes.red.name.lowercase()), 12f, Estilo.gris))
        if (repo.nodo.config == null) col.addView(Estilo.texto(this, getString(R.string.x39w_crear_sin_nodo), 12f, Estilo.aviso))
        col.addView(Estilo.boton(this, getString(R.string.x39w_crear_nueva)) { crear() })
        col.addView(Estilo.boton(this, getString(R.string.x39w_crear_restaurar), principal = false) { restaurar() })
        col.addView(Estilo.boton(this, getString(R.string.x39w_ajustes), principal = false) { startActivity(android.content.Intent(this, AjustesActivity::class.java)) })
    }

    private fun crear() {
        lanzar {
            val s = repo.semillaNueva()
            semilla = s
            val chars = s.palabras()
            palabras = chars
            col.removeAllViews()
            col.addView(cabecera(getString(R.string.x39w_crear_tus_12)))
            col.addView(Estilo.texto(this, getString(R.string.x39w_crear_copialas), 13f, Estilo.gris))
            val caja = Estilo.tarjeta(this)
            val tv = TextView(this).apply {
                textSize = 18f; setTextColor(Estilo.claro); setLineSpacing(0f, 1.4f)
                setTextIsSelectable(false); isLongClickable = false
                @Suppress("SetTextI18n") // son las palabras de la semilla, no un texto traducible
                setText(chars, 0, chars.size) // nunca String
            }
            caja.addView(tv)
            col.addView(caja)
            val copiado = CheckBox(this).apply { text = getString(R.string.x39w_crear_he_copiado); setTextColor(Estilo.claro) }
            col.addView(copiado)
            val k = (1..12).random()
            col.addView(Estilo.texto(this, getString(R.string.x39w_crear_comprobacion, k.toString()), 13f, Estilo.gris))
            val campo = campoMnemonico(getString(R.string.x39w_crear_palabra_k, k.toString()))
            col.addView(campo)
            col.addView(Estilo.boton(this, getString(R.string.x39w_crear_proteger_crear)) {
                if (!copiado.isChecked) { aviso(getString(R.string.x39w_crear_confirma_copia)); return@boton }
                val escrita = campo.tomarChars()
                val coincide = palabraK(chars, k, escrita)
                escrita.fill('\u0000')
                if (!coincide) { aviso(getString(R.string.x39w_crear_palabra_no_coincide, k.toString())); return@boton }
                val m = s.comoMnemonico() // guardarSemilla toma la propiedad y lo cierra
                guardarSemilla(m) {
                    repo.crear(m, restaurar = false)
                    limpiar()
                    aviso(getString(R.string.x39w_crear_creada))
                    finish()
                }
            })
            col.addView(Estilo.boton(this, getString(R.string.x39w_cancelar), principal = false) { inicio() })
        }
    }

    /** ¿`escrita` es la palabra `k` (1..12) de `chars`? Sin pasar por String. */
    private fun palabraK(chars: CharArray, k: Int, escrita: CharArray): Boolean {
        var ini = 0; var n = 1
        while (n < k) { while (ini < chars.size && chars[ini] != ' ') ini++; ini++; n++ }
        var fin = ini
        while (fin < chars.size && chars[fin] != ' ') fin++
        var e0 = 0; var e1 = escrita.size
        while (e0 < e1 && escrita[e0].isWhitespace()) e0++
        while (e1 > e0 && escrita[e1 - 1].isWhitespace()) e1--
        if (fin - ini != e1 - e0) return false
        for (i in 0 until fin - ini) if (chars[ini + i] != escrita[e0 + i].lowercaseChar()) return false
        return true
    }

    private fun campoMnemonico(pista: String): CampoSinPortapapeles = CampoSinPortapapeles(this).apply {
        setTextColor(Estilo.claro); hint = pista; setHintTextColor(Estilo.tenue)
        campoActivo = this
    }

    private fun restaurar() {
        limpiar()
        col.removeAllViews()
        col.addView(cabecera(getString(R.string.x39w_restaurar_titulo)))
        col.addView(Estilo.texto(this, getString(R.string.x39w_restaurar_texto), 13f, Estilo.gris))
        val campo = campoMnemonico(getString(R.string.x39w_restaurar_pista)).apply { minLines = 3 }
        col.addView(campo)
        col.addView(Estilo.boton(this, getString(R.string.x39w_restaurar_proteger)) {
            val chars = campo.tomarChars()
            val m = try { Mnemonico.deChars(chars) } catch (e: IllegalArgumentException) { aviso(getString(R.string.x39w_texto_no_valido)); return@boton }
            guardarSemilla(m) {
                val escaneada = repo.crear(m, restaurar = true)
                aviso(getString(if (escaneada) R.string.x39w_restaurada_escaneada else R.string.x39w_restaurada_sin_escaneo))
                finish()
            }
        })
        col.addView(Estilo.boton(this, getString(R.string.x39w_cancelar), principal = false) { campo.tomarChars().fill('\u0000'); inicio() })
    }

    /** Clave del Keystore invalidada: palabras → comprobación en el motor → clave nueva → recifrar. Nada se borra. */
    private fun recuperacion() {
        limpiar()
        col.removeAllViews()
        col.addView(cabecera(getString(R.string.x39w_recuperar_titulo)))
        col.addView(Estilo.texto(this, getString(R.string.x39w_recuperar_texto), 13f, Estilo.gris))
        val campo = campoMnemonico(getString(R.string.x39w_restaurar_pista)).apply { minLines = 3 }
        col.addView(campo)
        col.addView(Estilo.boton(this, getString(R.string.x39w_recuperar_comprobar)) {
            val chars = campo.tomarChars()
            val m = try { Mnemonico.deChars(chars) } catch (e: IllegalArgumentException) { aviso(getString(R.string.x39w_texto_no_valido)); return@boton }
            lanzar(alFallar = { e -> m.close(); aviso(e.message?.let { textoPantalla(it) } ?: getString(R.string.x39w_error)) }) {
                val coincide = repo.coincideSemilla(m)
                if (!coincide) { m.close(); aviso(getString(R.string.x39w_recuperar_no_coinciden)); return@lanzar }
                guardarSemilla(m, claveNueva = true) {
                    aviso(getString(R.string.x39w_recuperar_protegida))
                    finish()
                }
            }
        })
        col.addView(Estilo.boton(this, getString(R.string.x39w_cancelar), principal = false) { campo.tomarChars().fill('\u0000'); finish() })
    }

    private fun limpiar() {
        palabras?.fill('\u0000'); palabras = null
        semilla?.close(); semilla = null
        campoActivo?.tomarChars()?.fill('\u0000'); campoActivo = null
    }

    companion object {
        const val EXTRA_RECUPERAR_CLAVE = "recuperar_clave"
    }
}
