package x39.wallet.android

import android.os.Build
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi

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
        col.addView(cabecera("Billetera Bitcoin"))
        col.addView(Estilo.texto(this, "Red: ${repo.ajustes.red.name.lowercase()} (cámbiala en Ajustes antes de crear).", 12f, Estilo.gris))
        if (!repo.ajustes.hayServidor()) col.addView(Estilo.texto(this, "⚠ Sin servidor Electrum configurado: podrás crear la billetera, pero no sincronizar hasta ponerlo en Ajustes.", 12f, Estilo.aviso))
        col.addView(Estilo.boton(this, "Crear billetera nueva") { crear() })
        col.addView(Estilo.boton(this, "Restaurar desde 12 palabras", principal = false) { restaurar() })
        col.addView(Estilo.boton(this, "Ajustes", principal = false) { startActivity(android.content.Intent(this, AjustesActivity::class.java)) })
    }

    private fun crear() {
        lanzar {
            val s = repo.semillaNueva()
            semilla = s
            val chars = s.palabras()
            palabras = chars
            col.removeAllViews()
            col.addView(cabecera("Tus 12 palabras"))
            col.addView(Estilo.texto(this, "Cópialas a mano, en orden, en un lugar seguro. Se muestran UNA sola vez: si sales de esta pantalla desaparecen. Sin ellas no hay forma de recuperar los fondos. No se pueden copiar al portapapeles.", 13f, Estilo.gris))
            val caja = Estilo.tarjeta(this)
            val tv = TextView(this).apply {
                textSize = 18f; setTextColor(Estilo.claro); setLineSpacing(0f, 1.4f)
                setTextIsSelectable(false); isLongClickable = false
                @Suppress("SetTextI18n") // son las palabras de la semilla, no un texto traducible
                setText(chars, 0, chars.size) // nunca String
            }
            caja.addView(tv)
            col.addView(caja)
            val copiado = CheckBox(this).apply { text = "He copiado las 12 palabras a mano"; setTextColor(Estilo.claro) }
            col.addView(copiado)
            val k = (1..12).random()
            col.addView(Estilo.texto(this, "Comprobación: escribe la palabra número $k", 13f, Estilo.gris))
            val campo = campoMnemonico("palabra $k")
            col.addView(campo)
            col.addView(Estilo.boton(this, "Proteger y crear la billetera") {
                if (!copiado.isChecked) { aviso("Confirma que has copiado las palabras"); return@boton }
                val escrita = campo.tomarChars()
                val coincide = palabraK(chars, k, escrita)
                escrita.fill('\u0000')
                if (!coincide) { aviso("La palabra $k no coincide"); return@boton }
                val m = s.comoMnemonico() // guardarSemilla toma la propiedad y lo cierra
                guardarSemilla(m) {
                    repo.crear(m, restaurar = false)
                    limpiar()
                    aviso("Billetera creada")
                    finish()
                }
            })
            col.addView(Estilo.boton(this, "Cancelar", principal = false) { inicio() })
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
        col.addView(cabecera("Restaurar"))
        col.addView(Estilo.texto(this, "Escribe las 12 palabras separadas por espacios. No se puede pegar desde el portapapeles. Tras restaurar hace falta un escaneo completo con tu servidor antes de poder enviar: lo que el servidor muestre volverá como movimientos, pero los envíos firmados o inciertos anteriores no se recuperan desde la semilla.", 13f, Estilo.gris))
        val campo = campoMnemonico("palabra1 palabra2 … palabra12").apply { minLines = 3 }
        col.addView(campo)
        col.addView(Estilo.boton(this, "Proteger y restaurar") {
            val chars = campo.tomarChars()
            val m = try { Mnemonico.deChars(chars) } catch (e: IllegalArgumentException) { aviso("Texto no válido"); return@boton }
            guardarSemilla(m) {
                val escaneada = repo.crear(m, restaurar = true)
                aviso(if (escaneada) "Billetera restaurada y escaneada" else "Billetera restaurada; configura el servidor y sincroniza: hará el escaneo completo")
                finish()
            }
        })
        col.addView(Estilo.boton(this, "Cancelar", principal = false) { campo.tomarChars().fill('\u0000'); inicio() })
    }

    /** Clave del Keystore invalidada: palabras → comprobación en el motor → clave nueva → recifrar. Nada se borra. */
    private fun recuperacion() {
        limpiar()
        col.removeAllViews()
        col.addView(cabecera("Recuperar la clave"))
        col.addView(Estilo.texto(this, "La billetera y sus envíos se conservan. Escribe las 12 palabras de ESTA billetera: el motor comprueba que coinciden (sin firmar nada) y solo entonces se crea una clave nueva y se vuelve a cifrar la semilla. Si no coinciden o cancelas, todo queda como estaba.", 13f, Estilo.gris))
        val campo = campoMnemonico("palabra1 palabra2 … palabra12").apply { minLines = 3 }
        col.addView(campo)
        col.addView(Estilo.boton(this, "Comprobar y proteger") {
            val chars = campo.tomarChars()
            val m = try { Mnemonico.deChars(chars) } catch (e: IllegalArgumentException) { aviso("Texto no válido"); return@boton }
            lanzar(alFallar = { e -> m.close(); aviso(e.message ?: "error") }) {
                val coincide = repo.coincideSemilla(m)
                if (!coincide) { m.close(); aviso("Esas palabras no son las de esta billetera; nada ha cambiado"); return@lanzar }
                guardarSemilla(m, claveNueva = true) {
                    aviso("Semilla protegida con una clave nueva")
                    finish()
                }
            }
        })
        col.addView(Estilo.boton(this, "Cancelar", principal = false) { campo.tomarChars().fill('\u0000'); finish() })
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
