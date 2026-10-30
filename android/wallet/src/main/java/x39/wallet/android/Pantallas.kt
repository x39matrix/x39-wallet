package x39.wallet.android

import android.view.Window
import android.view.WindowManager

/** Pantallas con mnemónico o firma: sin capturas ni vista previa en el selector de apps. */
fun Window.protegerContraCapturas() {
    addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
