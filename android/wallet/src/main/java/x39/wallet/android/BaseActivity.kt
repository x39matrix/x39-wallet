package x39.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import x39.wallet.R

/** Base de las pantallas de la billetera: repositorio, errores a la vista y acceso a la semilla. */
@RequiresApi(Build.VERSION_CODES.M) // Keystore con autenticación por operación
abstract class BaseActivity : AppCompatActivity() {

    protected lateinit var repo: Repositorio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = WalletRepository.de(this)
    }

    protected fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    /**
     * Cabecera de todas las pantallas de la billetera: flecha de volver arriba a la izquierda
     * (mismo comportamiento que el botón atrás del sistema) y el título.
     */
    protected fun cabecera(titulo: String): android.view.View {
        val fila = Estilo.fila(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        // De derecha a izquierda (árabe) la flecha apunta al otro lado y el hueco va al lado de lectura.
        val rtl = resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        val volver = android.widget.TextView(this).apply {
            text = if (rtl) "\u2192" else "\u2190"; textSize = 24f; setTextColor(Estilo.claro)
            contentDescription = getString(R.string.x39w_volver)
            setPaddingRelative(0, Estilo.dp(this@BaseActivity, 4), Estilo.dp(this@BaseActivity, 16), Estilo.dp(this@BaseActivity, 8))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        fila.addView(volver)
        fila.addView(Estilo.titulo(this, titulo))
        return fila
    }

    /**
     * Ejecuta trabajo suspendido; los errores salen como aviso. La cancelación de la corrutina (la
     * pantalla se va) se relanza y NUNCA se enseña (orden 7, G6: nada de «Job was cancelled»). Sin
     * `alFallar`, en pantalla solo va el texto de un [ErrorBilletera] (ya traducido para la
     * pantalla); cualquier otra excepción se muestra con un texto fijo y su clase (sin mensaje) va
     * al registro.
     */
    protected fun lanzar(alFallar: ((Throwable) -> Unit)? = null, bloque: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                bloque()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (alFallar != null) alFallar(e) else aviso(textoDeFallo(e))
            }
        }
    }

    /**
     * Texto de pantalla de un fallo: el de la billetera (mensaje interno traducido al pintar), o uno
     * fijo; nunca el mensaje crudo de otra excepción.
     */
    protected fun textoDeFallo(e: Throwable): String {
        if (e is ErrorBilletera) return e.message?.let { textoPantalla(it) } ?: getString(R.string.x39w_fallo_generico)
        try { android.util.Log.w(NodeBridgeImpl.ETIQUETA_REGISTRO, "operación fallida: ${e.javaClass.simpleName}") } catch (_: RuntimeException) { }
        return getString(R.string.x39w_fallo_generico)
    }

    /**
     * Descifra la semilla con un prompt POR OPERACIÓN y la entrega como [Mnemonico] en
     * memoria nativa. El mnemónico se obtiene de forma NO cancelable (nunca queda una copia
     * sin dueño) y se pone a cero al terminar `usar`, pase lo que pase, también si la
     * corrutina se cancela porque la actividad muere. Cancelar el prompt = no se hace nada.
     * Clave invalidada = recuperación con las 12 palabras.
     */
    protected fun conSemilla(titulo: String, subtitulo: String, usar: suspend (Mnemonico) -> Unit) {
        val cipher = try {
            repo.almacen.prepararDescifrado()
        } catch (e: ClaveInvalidadaException) {
            claveInvalidada(); return
        } catch (e: Exception) {
            aviso(e.message?.let { textoPantalla(it) } ?: getString(R.string.x39w_no_preparar_descifrado)); return
        }
        Autenticacion.pedir(
            this, cipher, titulo, subtitulo, getString(R.string.x39w_cancelar),
            alAutenticar = { c ->
                lanzar {
                    val m = withContext(NonCancellable) { withContext(Dispatchers.IO) { repo.almacen.leer(c) } }
                    try {
                        usar(m)
                    } finally {
                        m.close()
                    }
                }
            },
            alAbortar = { _, msg -> aviso(getString(R.string.x39w_operacion_cancelada, textoPantalla(msg.toString()))) },
        )
    }

    /**
     * Cifra y guarda un mnemónico con un prompt por operación y, si se guarda, ejecuta
     * `despues` (que todavía puede usarlo). TOMA LA PROPIEDAD del mnemónico: lo cierra en
     * todos los caminos (fallo al preparar, prompt cancelado, fallo al guardar, fin de
     * `despues`, cancelación de la corrutina). `claveNueva`: regenerar la clave antes
     * (recuperación tras invalidación).
     */
    protected fun guardarSemilla(mnemonico: Mnemonico, claveNueva: Boolean = false, despues: suspend () -> Unit) {
        val cipher = try {
            if (claveNueva) repo.almacen.regenerarClave() else repo.almacen.asegurarClave()
            repo.almacen.prepararCifrado()
        } catch (e: ClaveInvalidadaException) {
            mnemonico.close(); claveInvalidada(); return
        } catch (e: IllegalStateException) {
            mnemonico.close(); aviso(getString(R.string.x39w_pantalla_bloqueo)); return
        } catch (e: Exception) {
            mnemonico.close(); aviso(e.message?.let { textoPantalla(it) } ?: getString(R.string.x39w_no_preparar_cifrado)); return
        }
        Autenticacion.pedir(
            this, cipher, getString(R.string.x39w_proteger_semilla), getString(R.string.x39w_proteger_semilla_sub), getString(R.string.x39w_cancelar),
            alAutenticar = { c ->
                lanzar(alFallar = { e -> mnemonico.close(); aviso(e.message?.let { textoPantalla(it) } ?: e.javaClass.simpleName) }) {
                    try {
                        withContext(NonCancellable) { withContext(Dispatchers.IO) { repo.almacen.guardar(c, mnemonico) } }
                        despues()
                    } finally {
                        mnemonico.close()
                    }
                }
            },
            alAbortar = { _, msg -> mnemonico.close(); aviso(getString(R.string.x39w_operacion_cancelada, textoPantalla(msg.toString()))) },
        )
    }

    /** La clave del Keystore ya no sirve: NO se borra nada; se recupera con las 12 palabras. */
    protected fun claveInvalidada() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39w_clave_invalidada_titulo))
            .setMessage(getString(R.string.x39w_clave_invalidada_msg))
            .setPositiveButton(getString(R.string.x39w_escribir_12)) { _, _ ->
                startActivity(
                    android.content.Intent(this@BaseActivity, CrearRestaurarActivity::class.java)
                        .putExtra(CrearRestaurarActivity.EXTRA_RECUPERAR_CLAVE, true),
                )
            }
            .setNegativeButton(getString(R.string.x39w_ahora_no), null)
            .show()
    }
}
