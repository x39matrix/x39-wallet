package x39.wallet.android

import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.AppCompatEditText

/**
 * Campo para teclear el mnemónico: sin portapapeles (ni copiar, ni cortar, ni pegar, ni
 * compartir), sin sugerencias ni autocompletado, sin autofill. Los caracteres se sacan con
 * [tomarChars] a un `CharArray` y el campo se vacía.
 */
class CampoSinPortapapeles(context: Context) : AppCompatEditText(context) {

    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        isLongClickable = false
        setTextIsSelectable(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        val nada = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        customSelectionActionModeCallback = nada
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) customInsertionActionModeCallback = nada
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        // Bloquea pegar / copiar / cortar / compartir por cualquier vía (teclado incluido).
        return when (id) {
            android.R.id.paste, android.R.id.pasteAsPlainText, android.R.id.copy, android.R.id.cut,
            android.R.id.shareText, android.R.id.selectAll -> true
            else -> super.onTextContextMenuItem(id)
        }
    }

    /** Copia los caracteres a un `CharArray` (sin `String`), vacía el campo y lo devuelve. */
    fun tomarChars(): CharArray {
        val e = text ?: return CharArray(0)
        val chars = CharArray(e.length)
        e.getChars(0, e.length, chars, 0)
        // Vaciar el Editable sobreescribiendo primero.
        for (i in 0 until e.length) e.replace(i, i + 1, "\u0000")
        e.clear()
        return chars
    }
}
