package x39.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val volver = android.widget.TextView(this).apply {
            text = "\u2190"; textSize = 24f; setTextColor(Estilo.claro)
            contentDescription = "Volver"
            setPadding(0, Estilo.dp(this@BaseActivity, 4), Estilo.dp(this@BaseActivity, 16), Estilo.dp(this@BaseActivity, 8))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        fila.addView(volver)
        fila.addView(Estilo.titulo(this, titulo))
        return fila
    }

    /** Ejecuta trabajo suspendido; los errores salen como aviso. */
    protected fun lanzar(alFallar: ((Throwable) -> Unit)? = null, bloque: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                bloque()
            } catch (e: Throwable) {
                if (alFallar != null) alFallar(e) else aviso(e.message ?: e.javaClass.simpleName)
            }
        }
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
            aviso(e.message ?: "no se pudo preparar el descifrado"); return
        }
        Autenticacion.pedir(
            this, cipher, titulo, subtitulo, "Cancelar",
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
            alAbortar = { _, msg -> aviso("Operación cancelada: $msg") },
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
            mnemonico.close(); aviso("Hace falta una pantalla de bloqueo configurada en el aparato"); return
        } catch (e: Exception) {
            mnemonico.close(); aviso(e.message ?: "no se pudo preparar el cifrado"); return
        }
        Autenticacion.pedir(
            this, cipher, "Proteger la semilla", "Se cifra con la clave de este aparato", "Cancelar",
            alAutenticar = { c ->
                lanzar(alFallar = { e -> mnemonico.close(); aviso(e.message ?: e.javaClass.simpleName) }) {
                    try {
                        withContext(NonCancellable) { withContext(Dispatchers.IO) { repo.almacen.guardar(c, mnemonico) } }
                        despues()
                    } finally {
                        mnemonico.close()
                    }
                }
            },
            alAbortar = { _, msg -> mnemonico.close(); aviso("Operación cancelada: $msg") },
        )
    }

    /** La clave del Keystore ya no sirve: NO se borra nada; se recupera con las 12 palabras. */
    protected fun claveInvalidada() {
        AlertDialog.Builder(this)
            .setTitle("Clave invalidada")
            .setMessage(
                "La clave que protegía la semilla ya no sirve (se perdió o se reinició el almacén de claves del aparato). " +
                    "La billetera y sus envíos se conservan: escribe las 12 palabras para volver a protegerla con una clave nueva.",
            )
            .setPositiveButton("Escribir las 12 palabras") { _, _ ->
                startActivity(
                    android.content.Intent(this@BaseActivity, CrearRestaurarActivity::class.java)
                        .putExtra(CrearRestaurarActivity.EXTRA_RECUPERAR_CLAVE, true),
                )
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }
}
