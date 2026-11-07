package x39.wallet.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.RequiresApi
import x39.wallet.R
import x39.wallet.Red

/**
 * Ajustes → Nodo: con qué servidor Electrum sincroniza la billetera. Dos modos:
 *
 * - «Nodo X-39» (predeterminado): el servidor Electrum de X-39, solo por el X39 Túnel de la app.
 *   Nada que escribir; sin túnel activo no se conecta por otra vía.
 * - «Mi nodo»: el servidor Electrum del usuario (electrs, Fulcrum… sobre su nodo) por `tcp://`,
 *   `ssl://` o `.onion` (con proxy SOCKS5). Un servidor solo se adopta tras comprobar que responde
 *   y que su génesis es el de la red de la billetera (`adoptarSiSano`); si no, no se toca nada.
 *   `ssl://` se valida contra las autoridades públicas salvo que el usuario active expresamente
 *   «certificado propio»: entonces se lee la huella del certificado al probar y se fija.
 *
 * Cambiar de servidor no borra nada de la billetera: la cadena es la misma, basta sincronizar; si
 * había un escaneo completo pendiente, se hace al sincronizar y se dice. La dirección escrita se
 * guarda (sobrevive al reinicio y a volver al nodo X-39) y no pasa por ningún registro.
 */
@RequiresApi(Build.VERSION_CODES.M) // Keystore con autenticación por operación
class AjustesActivity : BaseActivity() {

    companion object {
        /** Alias de [Redes.SERVIDOR_PUBLICO_SIGNET]. */
        const val SERVIDOR_PUBLICO_SIGNET = Redes.SERVIDOR_PUBLICO_SIGNET

        // El aviso de la casilla «certificado propio» (lenguaje llano: con ella la app confía en ESA
        // huella y en ninguna autoridad; sin ella, con ssl://, en las autoridades públicas reconocidas,
        // las raíces de Mozilla que lleva el motor) es el recurso x39w_cert_propio_aviso.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val aj = repo.ajustes
        val red = aj.red
        val col = Estilo.columna(this)
        col.addView(cabecera(getString(R.string.x39w_nodo_titulo)))
        col.addView(Estilo.texto(this, getString(R.string.x39w_nodo_intro), 12f, Estilo.gris))

        // ---- Selector de modo -------------------------------------------------------------------
        val x39Disponible = NodoX39.disponible(red)
        val grupo = RadioGroup(this)
        val rX39 = radio(getString(R.string.x39w_nodo_modo_x39), "modo_x39").apply { isEnabled = x39Disponible }
        val rPropio = radio(getString(R.string.x39w_nodo_modo_propio), "modo_propio")
        grupo.addView(rX39); grupo.addView(rPropio)
        col.addView(grupo)
        if (!x39Disponible) col.addView(Estilo.texto(this, getString(R.string.x39w_nodo_sin_x39_para_red, red.name.lowercase()), 12f, Estilo.tenue))

        val estado = Estilo.texto(this, "", 13f, Estilo.gris).apply { contentDescription = "estado_nodo" }
        fun pintarEstado(e: EstadoNodo) {
            estado.setTextColor(if (e.sano) Estilo.ok else Estilo.aviso) // antes del texto: el espejo copia el color al cambiar el texto
            estado.text = when (e) {
                is EstadoNodo.SinConfigurar -> getString(R.string.x39w_nodo_estado_sin_configurar)
                is EstadoNodo.Conectando -> getString(R.string.x39w_nodo_estado_conectando)
                is EstadoNodo.Sano -> buildString {
                    append(getString(R.string.x39w_nodo_estado_sano, e.info.versionServidor, e.info.red.name.lowercase(), e.info.altura.toString(), e.info.protocolo, e.info.latenciaMs.toString()))
                    when {
                        e.info.huellaVista.isNotEmpty() -> append(" ").append(getString(R.string.x39w_nodo_estado_sano_huella))
                        e.info.cifrado -> append(" ").append(getString(R.string.x39w_nodo_estado_sano_autoridad))
                        e.sinCifrar -> append(" ").append(getString(R.string.x39w_nodo_estado_sano_tcp))
                    }
                }
                is EstadoNodo.Caido -> getString(R.string.x39w_nodo_estado_caido, textoPantalla(e.ultimoError))
                is EstadoNodo.RedIncorrecta -> getString(R.string.x39w_nodo_estado_otra_red, e.detalle)
                is EstadoNodo.HuellaIncorrecta -> getString(R.string.x39w_nodo_estado_huella, e.detalle)
            }
        }

        // ---- Panel «Nodo X-39» ------------------------------------------------------------------
        val panelX39 = Estilo.tarjeta(this).apply { contentDescription = "panel_x39" }
        val cfgX39 = NodoX39.config(red)
        if (cfgX39 != null) {
            panelX39.addView(Estilo.texto(this, getString(R.string.x39w_nodo_x39_seccion), 12f, Estilo.gris))
            panelX39.addView(Estilo.texto(this, getString(R.string.x39w_nodo_x39_texto), 12f, Estilo.gris))
            panelX39.addView(Estilo.texto(this, getString(R.string.x39w_nodo_x39_servidor, cfgX39.electrum), 12f, Estilo.tenue))
            panelX39.addView(Estilo.boton(this, getString(R.string.x39w_nodo_probar), principal = false) {
                lanzar {
                    estado.text = getString(R.string.x39w_nodo_comprobando_x39)
                    pintarEstado(repo.nodo.probar(cfgX39))
                }
            }.apply { contentDescription = "probar_x39" })
            panelX39.addView(Estilo.boton(this, getString(R.string.x39w_nodo_usar_x39)) {
                lanzar {
                    // Es el nodo predeterminado y de confianza: se guarda aunque el túnel esté ahora
                    // apagado, y se comprueba (y se sincroniza) en cuanto vuelva.
                    val e = repo.nodo.configurar(cfgX39)
                    pintarEstado(e)
                    aviso(if (e.sano) getString(R.string.x39w_nodo_x39_en_uso_sano) else getString(R.string.x39w_nodo_x39_en_uso_ahora, textoPantalla(e.describir())))
                    finish()
                }
            }.apply { contentDescription = "usar_x39" })
        }
        col.addView(panelX39)

        // ---- Panel «Mi nodo» --------------------------------------------------------------------
        val panelPropio = Estilo.tarjeta(this).apply { contentDescription = "panel_propio" }
        panelPropio.addView(Estilo.texto(this, getString(R.string.x39w_nodo_propio_seccion), 12f, Estilo.gris))
        panelPropio.addView(Estilo.texto(this, getString(R.string.x39w_nodo_propio_texto), 12f, Estilo.gris))
        // El PRIMER campo de texto de la pantalla es el del servidor (lo usan las pruebas).
        val campo = Estilo.campo(this, getString(R.string.x39w_nodo_campo_servidor)).apply {
            setText(aj.servidor); contentDescription = "campo_servidor"
        }
        panelPropio.addView(campo)
        val avisoTcp = Estilo.texto(this, "", 12f, Estilo.aviso)
        panelPropio.addView(avisoTcp)

        val certPropio = casilla(getString(R.string.x39w_nodo_cert_propio), aj.huellaServidor.isNotEmpty(), "cert_propio")
        val campoHuella = Estilo.campo(this, getString(R.string.x39w_nodo_campo_huella)).apply {
            setText(aj.huellaServidor); contentDescription = "campo_huella"
        }
        val avisoCert = Estilo.texto(this, getString(R.string.x39w_cert_propio_aviso), 12f, Estilo.aviso)
        panelPropio.addView(certPropio); panelPropio.addView(avisoCert); panelPropio.addView(campoHuella)

        val conProxy = casilla(getString(R.string.x39w_nodo_con_proxy), aj.proxySocks5.isNotEmpty(), "con_proxy")
        val campoProxy = Estilo.campo(this, getString(R.string.x39w_nodo_campo_proxy)).apply {
            setText(aj.proxySocks5.ifBlank { aj.proxyDireccion }); contentDescription = "campo_proxy"
        }
        panelPropio.addView(conProxy); panelPropio.addView(campoProxy)
        panelPropio.addView(Estilo.texto(this, getString(R.string.x39w_nodo_proxy_texto), 12f, Estilo.tenue))

        if (red == Red.SIGNET) {
            panelPropio.addView(Estilo.texto(this, getString(R.string.x39w_nodo_signet_texto), 12f, Estilo.gris))
            panelPropio.addView(Estilo.boton(this, getString(R.string.x39w_nodo_signet_boton), principal = false) {
                campo.setText(SERVIDOR_PUBLICO_SIGNET)
                certPropio.isChecked = false // certificado público reconocido: sin huella fijada
                campoHuella.setText("")
                conProxy.isChecked = false
            }.apply { contentDescription = "servidor_publico_signet" })
        }
        if (aj.emparejadoEn > 0 && aj.nombreNodo.isNotEmpty()) {
            panelPropio.addView(Estilo.texto(this, getString(R.string.x39w_nodo_emparejado, aj.nombreNodo, Emparejamiento.formatearUtc(aj.emparejadoEn)), 12f, Estilo.tenue))
        }
        panelPropio.addView(Estilo.boton(this, getString(R.string.x39w_emparejar_boton), principal = false) {
            startActivity(Intent(this, EmparejarActivity::class.java))
        })

        fun refrescarCampos() {
            val s = campo.text.toString().trim()
            avisoTcp.text = when {
                s.startsWith("tcp://") && !s.lowercase().contains(".onion") -> getString(R.string.x39w_nodo_aviso_tcp)
                s.lowercase().contains(".onion") && !conProxy.isChecked -> getString(R.string.x39w_nodo_aviso_onion)
                else -> ""
            }
            val ssl = s.startsWith("ssl://") || (s.isNotEmpty() && "://" !in s)
            certPropio.visibility = if (ssl) View.VISIBLE else View.GONE
            avisoCert.visibility = if (ssl && certPropio.isChecked) View.VISIBLE else View.GONE
            campoHuella.visibility = if (ssl && certPropio.isChecked) View.VISIBLE else View.GONE
            campoProxy.visibility = if (conProxy.isChecked) View.VISIBLE else View.GONE
        }
        campo.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refrescarCampos()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        certPropio.setOnCheckedChangeListener { _: CompoundButton, _: Boolean -> refrescarCampos() }
        conProxy.setOnCheckedChangeListener { _: CompoundButton, _: Boolean -> refrescarCampos() }
        refrescarCampos()

        fun configPropio(): ConfigNodo {
            val s = campo.text.toString().trim()
            val url = if (s.isEmpty() || "://" in s) s else "ssl://$s"
            val huella = campoHuella.text.toString().trim().lowercase().filter { it.isLetterOrDigit() }
            return ConfigNodo(
                red = red,
                electrum = url,
                huellaServidor = if (certPropio.isChecked && url.startsWith("ssl://")) huella.ifBlank { null } else null,
                tunel = ModoTunel.NINGUNO,
                nombre = aj.nombreNodo.ifBlank { getString(R.string.x39w_nodo_mi_nodo) }.let { if (aj.servidor == url) it else getString(R.string.x39w_nodo_mi_nodo) },
                proxySocks5 = if (conProxy.isChecked) campoProxy.text.toString().trim() else null,
            )
        }

        /**
         * Configuración lista para comprobar: si el usuario pidió «certificado propio» y aún no hay
         * huella, se lee la del servidor AHORA (solo el apretón TLS, sin hablar Electrum) y se
         * muestra; es la que se fija. Devuelve `null` (con aviso) si la configuración no vale.
         */
        suspend fun configListaOAviso(): ConfigNodo? {
            var c = configPropio()
            c.invalida()?.let { aviso(textoPantalla(it)); return null }
            if (certPropio.isChecked && c.cifrado() && c.huellaServidor == null) {
                estado.text = getString(R.string.x39w_nodo_leyendo_cert)
                estado.setTextColor(Estilo.gris)
                val h = try {
                    repo.huellaServidor(c.electrum, c.proxySocks5)
                } catch (e: Exception) {
                    estado.setTextColor(Estilo.aviso)
                    estado.text = getString(R.string.x39w_nodo_no_leer_cert, textoPantalla(mensajeLlano(e.message ?: e.javaClass.simpleName)))
                    return null
                }
                campoHuella.setText(h)
                c = c.copy(huellaServidor = h)
                aviso(getString(R.string.x39w_nodo_huella_leida, h.take(8), h.takeLast(8)))
            }
            return c
        }

        panelPropio.addView(estado)
        panelPropio.addView(Estilo.boton(this, getString(R.string.x39w_nodo_probar_propio), principal = false) {
            lanzar {
                val c = configListaOAviso() ?: return@lanzar
                estado.text = getString(R.string.x39w_nodo_comprobando, c.host())
                estado.setTextColor(Estilo.gris)
                // Por NodeBridge, con las mismas reglas que una conexión normal, SIN guardar nada.
                pintarEstado(repo.nodo.probar(c))
            }
        }.apply { contentDescription = "probar_propio" })
        panelPropio.addView(Estilo.boton(this, getString(R.string.x39w_nodo_usar_propio)) {
            lanzar {
                val c = configListaOAviso() ?: return@lanzar
                estado.text = getString(R.string.x39w_nodo_comprobando_antes, c.host())
                estado.setTextColor(Estilo.gris)
                // Solo se guarda si da Sano (génesis de la red de la billetera, protocolo, altura
                // y, con huella, certificado). Si no, no se toca nada.
                val e = repo.nodo.adoptarSiSano(c)
                pintarEstado(e)
                if (e is EstadoNodo.Sano) {
                    aviso(getString(if (repo.escaneoPendiente()) R.string.x39w_nodo_guardado_escaneo else R.string.x39w_nodo_guardado))
                    finish()
                } else {
                    aviso(getString(R.string.x39w_nodo_no_cambiado, textoPantalla(e.describir())))
                }
            }
        }.apply { contentDescription = "usar_propio" })
        col.addView(panelPropio)

        // ---- Mostrar un panel u otro --------------------------------------------------------------
        fun mostrar(m: ModoNodo) {
            panelX39.visibility = if (m == ModoNodo.X39 && cfgX39 != null) View.VISIBLE else View.GONE
            panelPropio.visibility = if (m == ModoNodo.PROPIO || cfgX39 == null) View.VISIBLE else View.GONE
            val cfg = repo.nodo.config
            val esteModoEnUso = cfg != null && (cfg.tunel == ModoTunel.X39) == (m == ModoNodo.X39)
            when {
                esteModoEnUso -> pintarEstado(repo.nodo.estado.value)
                cfg == null -> pintarEstado(EstadoNodo.SinConfigurar)
                else -> {
                    // H3 (orden 8): el nodo en uso es el del OTRO modo; este no se ha probado. Texto
                    // verdadero y neutro, nunca «sin configurar» (el nodo activo sigue configurado).
                    estado.setTextColor(Estilo.gris)
                    estado.text = getString(R.string.x39w_nodo_sin_probar, textoPantalla(cfg.descripcionNodo()))
                }
            }
        }
        grupo.setOnCheckedChangeListener { _, id -> mostrar(if (id == rX39.id) ModoNodo.X39 else ModoNodo.PROPIO) }
        val inicial = if (x39Disponible) aj.modoNodo else ModoNodo.PROPIO
        (if (inicial == ModoNodo.X39) rX39 else rPropio).isChecked = true
        mostrar(inicial)
        // El estado va en el panel de «Mi nodo»; para el X-39 se muestra en su panel.
        if (cfgX39 != null) panelX39.addView(estadoEspejo(estado))

        // ---- Red (solo antes de crear la billetera) --------------------------------------------
        col.addView(Estilo.seccion(this, getString(R.string.x39w_nodo_red_seccion)))
        val redes = Redes.disponibles()
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@AjustesActivity, android.R.layout.simple_spinner_dropdown_item, redes.map { it.name.lowercase() })
            setSelection(redes.indexOf(red).coerceAtLeast(0))
            isEnabled = repo.estado() == WalletRepository.Estado.SIN_BILLETERA
        }
        col.addView(spinner)
        if (!spinner.isEnabled) {
            col.addView(Estilo.texto(this, getString(R.string.x39w_nodo_red_fija, red.name.lowercase()), 12f, Estilo.tenue))
        } else {
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val elegida = redes[pos]
                    if (elegida != aj.red) { aj.red = elegida; recreate() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        col.addView(Estilo.seccion(this, getString(R.string.x39w_nodo_privacidad_seccion)))
        col.addView(Estilo.texto(this, getString(R.string.x39w_frase_privacidad), 12f, Estilo.gris)) // = NodoX39.FRASE_PRIVACIDAD, traducida
        setContentView(Estilo.envolver(this, col))
    }

    private fun radio(texto: String, desc: String): RadioButton = RadioButton(this).apply {
        text = texto; textSize = 15f; setTextColor(Estilo.claro); contentDescription = desc
        setPadding(Estilo.dp(this@AjustesActivity, 6), Estilo.dp(this@AjustesActivity, 10), 0, Estilo.dp(this@AjustesActivity, 10))
    }

    private fun casilla(texto: String, marcada: Boolean, desc: String): CheckBox = CheckBox(this).apply {
        text = texto; textSize = 14f; setTextColor(Estilo.claro); isChecked = marcada; contentDescription = desc
        setPadding(Estilo.dp(this@AjustesActivity, 6), Estilo.dp(this@AjustesActivity, 8), 0, Estilo.dp(this@AjustesActivity, 8))
    }

    /** Un segundo TextView que repite el estado (una vista no puede tener dos padres). */
    private fun estadoEspejo(origen: TextView): TextView {
        val espejo = Estilo.texto(this, origen.text, 13f, Estilo.gris)
        origen.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { espejo.text = s; espejo.setTextColor(origen.currentTextColor) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        return espejo
    }
}
