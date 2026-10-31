package x39.wallet.android

import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import x39.wallet.Red

/** Servidor Electrum (host:puerto, vacío por defecto; aviso si es tcp://) y red (solo antes de crear). */
@RequiresApi(Build.VERSION_CODES.M) // Keystore con autenticación por operación
class AjustesActivity : BaseActivity() {

    companion object {
        /** Alias de [Redes.SERVIDOR_PUBLICO_SIGNET]. */
        const val SERVIDOR_PUBLICO_SIGNET = Redes.SERVIDOR_PUBLICO_SIGNET
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Estilo.columna(this)
        col.addView(cabecera("Ajustes"))
        col.addView(Estilo.seccion(this, "SERVIDOR ELECTRUM (host:puerto)"))
        col.addView(Estilo.texto(this, "Tu propio nodo (Electrum/electrs) o, para pruebas en signet, uno público. Sin esquema se usa ssl://. Con tcp:// va sin cifrar: solo en tu red local o por tu túnel.", 12f, Estilo.gris))
        val campo = Estilo.campo(this, "host:puerto").apply { setText(repo.ajustes.servidor) }
        val campoHuella = Estilo.campo(this, "64 caracteres hexadecimales").apply { setText(repo.ajustes.huellaServidor) }
        col.addView(campo)
        val avisoTcp = Estilo.texto(this, "", 12f, Estilo.aviso)
        col.addView(avisoTcp)
        fun refrescarAviso() { avisoTcp.text = if (campo.text.toString().trim().startsWith("tcp://")) "⚠ tcp:// sin cifrar" else "" }
        refrescarAviso()
        if (repo.ajustes.red == Red.SIGNET) {
            col.addView(Estilo.texto(this, "Para pruebas en signet puedes usar un servidor PÚBLICO de terceros (ves lo que consultas y a quién). Lo propio es tu nodo.", 12f, Estilo.gris))
            col.addView(Estilo.boton(this, "Usar servidor público de signet (terceros)", principal = false) {
                campo.setText(SERVIDOR_PUBLICO_SIGNET)
                campoHuella.setText("") // certificado público reconocido: sin huella fijada
                refrescarAviso()
            }.apply { contentDescription = "servidor_publico_signet" })
        }

        col.addView(Estilo.seccion(this, "HUELLA DEL CERTIFICADO DEL NODO (SPKI SHA-256, hex)"))
        col.addView(Estilo.texto(this, "Obligatoria con ssl:// salvo el servidor público de signet: el móvil solo hablará con el certificado que tenga esta huella. La da tu nodo (o la herramienta de emparejamiento).", 12f, Estilo.gris))
        col.addView(campoHuella)

        col.addView(Estilo.seccion(this, "TÚNEL"))
        col.addView(Estilo.texto(this, "Ninguno: el nodo se alcanza directamente. Externo: se exige que haya una VPN activa en el aparato (NodeBridge no la toca); ojo: detectarla no garantiza que el tráfico hacia este nodo vaya por ella, porque Android permite rutas por destino. El túnel propio llegará más adelante.", 12f, Estilo.gris))
        val modos = listOf(ModoTunel.NINGUNO, ModoTunel.EXTERNO)
        val spinnerTunel = Spinner(this).apply {
            adapter = ArrayAdapter(this@AjustesActivity, android.R.layout.simple_spinner_dropdown_item, listOf("ninguno", "externo (VPN ya activa)"))
            setSelection(modos.indexOf(repo.ajustes.tunel).coerceAtLeast(0))
        }
        col.addView(spinnerTunel)

        col.addView(Estilo.seccion(this, "RED"))
        val redes = listOf(Red.SIGNET, Red.REGTEST).filter { Redes.permitida(it) }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@AjustesActivity, android.R.layout.simple_spinner_dropdown_item, redes.map { it.name.lowercase() })
            setSelection(redes.indexOf(repo.ajustes.red).coerceAtLeast(0))
            isEnabled = repo.estado() == WalletRepository.Estado.SIN_BILLETERA
        }
        col.addView(spinner)
        if (!spinner.isEnabled) col.addView(Estilo.texto(this, "La red no se cambia con una billetera creada (${repo.ajustes.red.name.lowercase()}).", 12f, Estilo.tenue))

        val estadoNodo = Estilo.texto(this, "", 13f, Estilo.gris)
        col.addView(estadoNodo)
        fun configActual(): ConfigNodo {
            val s = campo.text.toString().trim()
            val url = if (s.isEmpty() || "://" in s) s else "ssl://$s"
            return ConfigNodo(
                red = redes[spinner.selectedItemPosition],
                electrum = url,
                huellaServidor = campoHuella.text.toString().trim().lowercase().filter { it.isLetterOrDigit() }.ifBlank { null },
                tunel = modos[spinnerTunel.selectedItemPosition],
                nombre = "mi nodo",
            )
        }
        fun pintarEstado(e: EstadoNodo) {
            estadoNodo.text = when (e) {
                is EstadoNodo.SinConfigurar -> "Nodo: sin configurar"
                is EstadoNodo.Conectando -> "Nodo: conectando…"
                is EstadoNodo.Sano -> "Nodo SANO: ${e.info.versionServidor}, protocolo ${e.info.protocolo}, altura ${e.info.altura}, ${e.info.latenciaMs} ms" +
                    (if (e.sinCifrar) " · ⚠ sin cifrar (tcp://)" else "") +
                    (if (e.info.huellaVista.isNotEmpty()) " · huella comprobada" else "")
                is EstadoNodo.Caido -> "Nodo CAÍDO: ${e.ultimoError}"
                is EstadoNodo.RedIncorrecta -> "Nodo de OTRA RED: ${e.detalle}"
                is EstadoNodo.HuellaIncorrecta -> "HUELLA INCORRECTA: ${e.detalle}"
            }
            estadoNodo.setTextColor(if (e.sano) Estilo.ok else Estilo.aviso)
        }
        pintarEstado(repo.nodo.estado.value)
        col.addView(Estilo.boton(this, "Probar conexión (comprueba red, huella, altura y protocolo)", principal = false) {
            val c = configActual()
            c.invalida()?.let { aviso(it); return@boton }
            lanzar {
                // Por NodeBridge, con las mismas condiciones que una conexión normal (VPN
                // externa incluida), SIN guardar: solo se guarda al pulsar Guardar.
                pintarEstado(repo.nodo.probar(c))
            }
        })
        col.addView(Estilo.boton(this, "Guardar") {
            val c = configActual()
            if (c.electrum.isNotEmpty()) c.invalida()?.let { aviso(it); return@boton }
            if (spinner.isEnabled) repo.ajustes.red = c.red
            lanzar {
                if (c.electrum.isEmpty()) {
                    repo.nodo.olvidar()
                    aviso("Nodo olvidado")
                } else {
                    val e = repo.nodo.configurar(c) // guarda y comprueba
                    pintarEstado(e)
                    aviso(if (e.sano) "Guardado: nodo sano" else "Guardado, pero el nodo no está sano")
                }
                refrescarAviso()
                finish()
            }
        })
        setContentView(Estilo.envolver(this, col))
    }
}
