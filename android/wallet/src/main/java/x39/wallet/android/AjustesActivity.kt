package x39.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import x39.wallet.Red
import x39.wallet.alturaRed

/** Servidor Electrum (host:puerto, vacío por defecto; aviso si es tcp://) y red (solo antes de crear). */
@RequiresApi(Build.VERSION_CODES.M) // Keystore con autenticación por operación
class AjustesActivity : BaseActivity() {

    companion object {
        /** Servidor Electrum público de terceros para SIGNET; solo se ofrece en esa red. */
        const val SERVIDOR_PUBLICO_SIGNET = "ssl://mempool.space:60602"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Estilo.columna(this)
        col.addView(cabecera("Ajustes"))
        col.addView(Estilo.seccion(this, "SERVIDOR ELECTRUM (host:puerto)"))
        col.addView(Estilo.texto(this, "Tu propio nodo (Electrum/electrs) o, para pruebas en signet, uno público. Sin esquema se usa ssl://. Con tcp:// va sin cifrar: solo en tu red local o por tu túnel.", 12f, Estilo.gris))
        val campo = Estilo.campo(this, "host:puerto").apply { setText(repo.ajustes.servidor) }
        col.addView(campo)
        val avisoTcp = Estilo.texto(this, "", 12f, Estilo.aviso)
        col.addView(avisoTcp)
        fun refrescarAviso() { avisoTcp.text = if (campo.text.toString().trim().startsWith("tcp://")) "⚠ tcp:// sin cifrar" else "" }
        refrescarAviso()
        if (repo.ajustes.red == Red.SIGNET) {
            col.addView(Estilo.texto(this, "Para pruebas en signet puedes usar un servidor PÚBLICO de terceros (ves lo que consultas y a quién). Lo propio es tu nodo.", 12f, Estilo.gris))
            col.addView(Estilo.boton(this, "Usar servidor público de signet (terceros)", principal = false) {
                campo.setText(SERVIDOR_PUBLICO_SIGNET)
                refrescarAviso()
            }.apply { contentDescription = "servidor_publico_signet" })
        }

        col.addView(Estilo.seccion(this, "RED"))
        val redes = listOf(Red.SIGNET, Red.REGTEST).filter { Redes.permitida(it) }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@AjustesActivity, android.R.layout.simple_spinner_dropdown_item, redes.map { it.name.lowercase() })
            setSelection(redes.indexOf(repo.ajustes.red).coerceAtLeast(0))
            isEnabled = repo.estado() == WalletRepository.Estado.SIN_BILLETERA
        }
        col.addView(spinner)
        if (!spinner.isEnabled) col.addView(Estilo.texto(this, "La red no se cambia con una billetera creada (${repo.ajustes.red.name.lowercase()}).", 12f, Estilo.tenue))

        col.addView(Estilo.boton(this, "Probar conexión", principal = false) {
            val s = campo.text.toString().trim()
            if (s.isEmpty()) { aviso("Escribe el servidor"); return@boton }
            val url = if ("://" in s) s else "ssl://$s"
            val red = redes[spinner.selectedItemPosition]
            lanzar {
                val h = withContext(Dispatchers.IO) { alturaRed(red, url) }
                aviso("Conecta: ${red.name.lowercase()}, altura $h")
            }
        })
        col.addView(Estilo.boton(this, "Guardar") {
            repo.ajustes.servidor = campo.text.toString()
            if (spinner.isEnabled) repo.ajustes.red = redes[spinner.selectedItemPosition]
            refrescarAviso()
            aviso("Guardado")
            finish()
        })
        setContentView(Estilo.envolver(this, col))
    }
}
