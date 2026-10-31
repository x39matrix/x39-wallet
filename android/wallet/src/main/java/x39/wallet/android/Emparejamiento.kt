package x39.wallet.android

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import x39.wallet.Red

/**
 * Datos del túnel propio que trae el paquete. En E-2 solo se validan, se muestran y se guardan;
 * el túnel propio (E-3) los usará. Solo debe haber claves PÚBLICAS. Precisión: una clave privada
 * de WireGuard tiene la misma forma que una pública y NO se puede detectar; si llegara en
 * `clave_publica_nodo` se guardaría como pública. E-2 no la usa como privada ni la transmite.
 */
data class DatosTunel(
    val clavePublicaNodo: String,
    val endpoint: String,
    val ipsPermitidas: List<String>,
    val direccionMovil: String?,
)

/** Paquete de emparejamiento (versión 1) ya validado. Ver docs/nodebridge.md §5.1. */
data class PaqueteEmparejamiento(
    val red: Red,
    val electrum: String,
    /** Huella SPKI (SHA-256, hex en minúsculas); vacía solo con `tcp://`. */
    val huellaSpki: String,
    val tunel: DatosTunel?,
    val nombre: String,
    val emitidoS: Long,
    val caducaS: Long,
) {
    /**
     * Configuración de NodeBridge que resulta del paquete. El modo de túnel es NINGUNO aunque el
     * paquete traiga túnel: el túnel propio no está implementado (E-3); hasta entonces el nodo
     * debe ser alcanzable directamente o por la VPN del aparato (modo externo, en Ajustes).
     */
    fun aConfig(): ConfigNodo = ConfigNodo(
        red = red,
        electrum = electrum,
        huellaServidor = huellaSpki.ifBlank { null },
        tunel = ModoTunel.NINGUNO,
        nombre = nombre,
    )

    fun huellaAbreviada(): String = if (huellaSpki.isEmpty()) "(sin huella: tcp://)" else "${huellaSpki.take(8)}…${huellaSpki.takeLast(8)}"
}

class ErrorEmparejamiento(motivo: String) : Exception(motivo)

/**
 * Analizador y validador del paquete de emparejamiento. Todo se valida ANTES de tocar nada; un
 * paquete con cualquier cosa que parezca una clave privada se rechaza entero. No resuelve
 * nombres ni toca la red: solo texto.
 */
object Emparejamiento {
    const val VERSION = 1
    /** Tamaño máximo del texto (un QR cabe de sobra; un fichero mayor no es un paquete). */
    const val MAX_BYTES = 16 * 1024
    /** Vigencia máxima: `caduca` no puede estar a más de 24 h de `emitido`. */
    const val VIGENCIA_MAX_S = 24 * 3600L
    /** Tolerancia al desajuste de relojes entre nodo y móvil: `emitido` puede estar hasta 5 min en el futuro. */
    const val ADELANTO_MAX_S = 5 * 60L
    const val NOMBRE_MAX = 64

    private val CAMPOS = setOf("v", "red", "electrum", "huella_spki_sha256", "tunel", "nombre", "emitido", "caduca")
    private val CAMPOS_TUNEL = setOf("clave_publica_nodo", "endpoint", "ips_permitidas", "direccion_movil")

    /** Nombres de campo que delatan una clave privada o una semilla, en cualquier nivel. */
    private val NOMBRE_PRIVADO = Regex("(?i)priv|secret|secreto|semilla|seed|mnemo|xprv|tprv|vprv|wif|passphrase|contrase|interface")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    /** 32 bytes en base64: 43 caracteres y un «=». */
    private val BASE64_32 = Regex("^[A-Za-z0-9+/]{43}=$")
    private val NOMBRE_HOST = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val PUERTO = Regex("^[0-9]{1,5}$")

    // Detección DENTRO de los valores (containsMatchIn), con límites de alfabeto base58 o de palabra.
    private const val B58 = "[1-9A-HJ-NP-Za-km-z]"
    private val PEM_PRIVADA = Regex("(?i)PRIVATE[ _-]?KEY")
    private val XPRV = Regex("(?<!$B58)[xtyzuvYZUV]prv$B58{100,120}(?!$B58)")
    private val WIF = Regex("(?<!$B58)[5KLc9]$B58{50,51}(?!$B58)")
    /** Doce o más palabras alfabéticas seguidas = semilla. Se cuenta con un recorrido acotado, sin regex (ReDoS). */
    private const val PALABRAS_SEMILLA = 12
    private val FECHA_FORMA = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")

    private fun fechaUtc() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC"); isLenient = false
    }

    fun formatearUtc(s: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(s * 1000)

    private fun esLetra(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in "áéíóúñÁÉÍÓÚÑ"

    private fun esDigito(c: Char) = c in '0'..'9'

    /**
     * ¿Hay [PALABRAS_SEMILLA] o más palabras alfabéticas seguidas? Una palabra es una secuencia
     * máxima de letras y dígitos; cuenta si es solo letras, y una con dígitos corta la racha. La
     * puntuación y los blancos son separadores, así que «copia:zoo», «wrong.» o «(zoo … wrong)»
     * no esconden la semilla. Recorrido lineal y acotado, sin expresiones regulares.
     */
    internal fun tieneRachaDePalabras(valor: String, tope: Int = PALABRAS_SEMILLA): Boolean {
        var racha = 0
        var i = 0
        val n = valor.length
        while (i < n) {
            while (i < n && !esLetra(valor[i]) && !esDigito(valor[i])) i++
            if (i >= n) break
            var soloLetras = true
            while (i < n && (esLetra(valor[i]) || esDigito(valor[i]))) { if (esDigito(valor[i])) soloLetras = false; i++ }
            racha = if (soloLetras) racha + 1 else 0
            if (racha >= tope) return true
        }
        return false
    }

    /** ¿El valor CONTIENE algo que parezca una clave privada, una semilla o una clave extendida privada? */
    fun pareceClavePrivada(valor: String): Boolean =
        PEM_PRIVADA.containsMatchIn(valor) || XPRV.containsMatchIn(valor) || WIF.containsMatchIn(valor) || tieneRachaDePalabras(valor)

    private fun rechazo(motivo: String): Nothing = throw ErrorEmparejamiento(motivo)

    /** Recorre todo el árbol: nombres y valores. La primera señal de clave privada rechaza el paquete entero. */
    private fun buscarClavesPrivadas(nodo: Any?, ruta: String) {
        when (nodo) {
            is JSONObject -> for (k in nodo.keys()) {
                if (NOMBRE_PRIVADO.containsMatchIn(k)) rechazo("el paquete trae un campo que parece una clave privada («$k»): se rechaza entero")
                buscarClavesPrivadas(nodo.opt(k), "$ruta/$k")
            }
            is JSONArray -> for (i in 0 until nodo.length()) buscarClavesPrivadas(nodo.opt(i), "$ruta[$i]")
            is String -> if (pareceClavePrivada(nodo)) rechazo("el paquete trae un valor que parece una clave privada o una semilla (en $ruta): se rechaza entero")
        }
    }

    private fun JSONObject.cadena(campo: String, obligatorio: Boolean = true): String? {
        if (!has(campo) || isNull(campo)) { if (obligatorio) rechazo("falta el campo «$campo»"); return null }
        val v = opt(campo)
        if (v !is String) rechazo("el campo «$campo» debe ser texto")
        return v
    }

    /**
     * Fecha UTC estricta: anchuras y dígitos exactos (nada de «2026-1-1T22:0:0Z»), el formato debe
     * consumir TODA la cadena (nada de «…Z+09:00») y el calendario debe ser válido (no lenient).
     */
    private fun fecha(texto: String, campo: String): Long {
        if (!FECHA_FORMA.matches(texto)) rechazo("la fecha «$campo» debe tener exactamente la forma 2026-01-31T12:00:00Z (UTC)")
        val pos = ParsePosition(0)
        val d = fechaUtc().parse(texto, pos)
        if (d == null || pos.index != texto.length) rechazo("la fecha «$campo» no es una fecha válida del calendario")
        return d.time / 1000
    }

    // ---- direcciones: solo literales, nunca una resolución de nombres desde aquí ----------------

    internal fun ipv4Valida(s: String): Boolean {
        val m = IPV4.matchEntire(s) ?: return false
        return (1..4).all { m.groupValues[it].toInt() in 0..255 }
    }

    /** Literal IPv6: una sola «::», grupos de 1 a 4 hex, 8 grupos (o menos con «::»), IPv4 incrustada solo al final. */
    internal fun ipv6Valida(s: String): Boolean {
        if (s.length < 2 || s.contains(":::") || s.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return false
        val dobles = s.windowed(2).count { it == "::" }
        if (dobles > 1) return false
        fun grupos(x: String): Int? {
            if (x.isEmpty()) return 0
            val gs = x.split(':')
            var n = 0
            for ((i, g) in gs.withIndex()) {
                if (g.isEmpty()) return null
                if (g.contains('.')) {
                    if (i != gs.lastIndex || !ipv4Valida(g)) return null
                    n += 2
                } else {
                    if (g.length !in 1..4) return null
                    n += 1
                }
            }
            return n
        }
        if (dobles == 0) return grupos(s) == 8
        val partes = s.split("::", limit = 2)
        if (partes[0].contains('.')) return false
        val izq = grupos(partes[0]) ?: return false
        val der = grupos(partes[1]) ?: return false
        return izq + der <= 7
    }

    internal fun cidrValido(s: String): Boolean {
        val barra = s.lastIndexOf('/')
        if (barra <= 0 || barra == s.lastIndex) return false
        val dir = s.substring(0, barra)
        val pref = s.substring(barra + 1)
        if (!pref.all { it in '0'..'9' } || pref.length > 3) return false
        return when {
            dir.contains(':') -> ipv6Valida(dir) && pref.toInt() in 0..128
            else -> ipv4Valida(dir) && pref.toInt() in 0..32
        }
    }

    /** `host:puerto`: nombre de host, IPv4 literal o `[IPv6]`; puerto 1..65535. */
    internal fun hostPuerto(s: String, campo: String) {
        val host: String
        val puerto: String
        if (s.startsWith("[")) {
            val cierre = s.indexOf(']')
            if (cierre < 0 || cierre + 1 >= s.length || s[cierre + 1] != ':') rechazo("«$campo» debe ser [IPv6]:puerto")
            host = s.substring(1, cierre)
            puerto = s.substring(cierre + 2)
            if (!ipv6Valida(host)) rechazo("«$campo»: «$host» no es una dirección IPv6 válida")
        } else {
            val dosPuntos = s.lastIndexOf(':')
            if (dosPuntos <= 0 || dosPuntos == s.lastIndex) rechazo("«$campo» debe ser host:puerto")
            host = s.substring(0, dosPuntos)
            puerto = s.substring(dosPuntos + 1)
            if (host.contains(':')) rechazo("«$campo»: una dirección IPv6 va entre corchetes: [IPv6]:puerto")
            val numerico = host.all { it in '0'..'9' || it == '.' }
            if (numerico) { if (!ipv4Valida(host)) rechazo("«$campo»: «$host» no es una dirección IPv4 válida") }
            else if (host.length > 253 || !NOMBRE_HOST.matches(host)) rechazo("«$campo»: «$host» no es un nombre de host válido")
        }
        if (pareceClavePrivada(host)) rechazo("«$campo»: el host contiene algo que parece una clave privada: se rechaza entero")
        if (!PUERTO.matches(puerto) || puerto.toInt() !in 1..65535) rechazo("«$campo»: el puerto debe estar entre 1 y 65535")
    }

    private fun tunel(o: JSONObject): DatosTunel {
        for (k in o.keys()) if (k !in CAMPOS_TUNEL) rechazo("campo desconocido en «tunel»: «$k»")
        val clave = o.cadena("clave_publica_nodo")!!.trim()
        if (!BASE64_32.matches(clave)) rechazo("«tunel.clave_publica_nodo» debe ser una clave pública de 32 bytes en base64")
        val endpoint = o.cadena("endpoint")!!.trim()
        hostPuerto(endpoint, "tunel.endpoint")
        val ips = o.opt("ips_permitidas") as? JSONArray ?: rechazo("«tunel.ips_permitidas» debe ser una lista")
        if (ips.length() == 0) rechazo("«tunel.ips_permitidas» está vacía")
        val lista = (0 until ips.length()).map { i ->
            val s = ips.opt(i) as? String ?: rechazo("«tunel.ips_permitidas» debe contener textos")
            if (!cidrValido(s.trim())) rechazo("«tunel.ips_permitidas»: «$s» no es una red válida (forma A.B.C.D/n o IPv6/n)")
            s.trim()
        }
        val movil = o.cadena("direccion_movil", obligatorio = false)?.trim()
        if (movil != null && !cidrValido(movil)) rechazo("«tunel.direccion_movil» no es una dirección válida (forma A.B.C.D/32)")
        return DatosTunel(clave, endpoint, lista, movil)
    }

    /**
     * Analiza y valida el texto de un paquete para una billetera de red [redBilletera].
     * Devuelve el paquete o lanza [ErrorEmparejamiento] con el motivo (ya en lenguaje de pantalla).
     * [ahoraS] es el instante de la comprobación: hay que volver a llamar al confirmar, no vale la
     * validación de cuando se abrió.
     */
    fun analizar(texto: String, redBilletera: Red, ahoraS: Long = System.currentTimeMillis() / 1000): PaqueteEmparejamiento {
        if (texto.toByteArray().size > MAX_BYTES) rechazo("el paquete es demasiado grande (más de ${MAX_BYTES / 1024} KB)")

        // 0. Texto crudo, estricto y completo: sin sobrante, sin claves duplicadas, sin JSON permisivo.
        try { JsonEstricto.comprobar(texto) } catch (e: JsonEstricto.Invalido) { rechazo("no es un paquete de emparejamiento (JSON inválido: ${e.message})") }
        val o = try {
            val tk = JSONTokener(texto)
            val v = tk.nextValue() as? JSONObject ?: rechazo("no es un paquete de emparejamiento (JSON inválido: no es un objeto)")
            if (tk.nextClean() != '\u0000') rechazo("no es un paquete de emparejamiento (JSON inválido: texto sobrante)")
            v
        } catch (_: JSONException) { rechazo("no es un paquete de emparejamiento (JSON inválido)") }

        // 1. Antes que nada: nada que parezca una clave privada, en ningún sitio (nombres y valores).
        buscarClavesPrivadas(o, "")

        // 2. Esquema cerrado: versión conocida y solo los campos de esa versión.
        for (k in o.keys()) if (k !in CAMPOS) rechazo("campo desconocido: «$k»")
        val v = o.opt("v")
        if (v !is Int) rechazo("falta la versión del paquete («v»)")
        if (v != VERSION) rechazo("versión del paquete desconocida ($v); esta billetera entiende la $VERSION")

        // 3. Red: permitida y la misma que la billetera.
        val redTexto = o.cadena("red")!!.trim().lowercase()
        val red = Red.values().firstOrNull { it.name.lowercase() == redTexto } ?: rechazo("red desconocida: «$redTexto»")
        if (!Redes.permitida(red)) rechazo("red no permitida en esta edición: $redTexto")
        if (red != redBilletera) rechazo("el paquete es de $redTexto y la billetera es de ${redBilletera.name.lowercase()}")

        // 4. Servidor Electrum (host:puerto literal; no se resuelve nada).
        val electrum = o.cadena("electrum")!!.trim()
        if (!electrum.startsWith("ssl://") && !electrum.startsWith("tcp://")) rechazo("«electrum» debe empezar por ssl:// o tcp://")
        hostPuerto(electrum.substringAfter("://"), "electrum")

        // 5. Huella SPKI: obligatoria con ssl://, prohibida con tcp:// (no habría qué comprobar).
        val huella = o.cadena("huella_spki_sha256", obligatorio = false)?.trim()?.lowercase() ?: ""
        if (electrum.startsWith("ssl://")) {
            if (huella.isEmpty()) rechazo("falta la huella SPKI del certificado (obligatoria con ssl://)")
            if (!HEX64.matches(huella)) rechazo("la huella SPKI debe tener 64 caracteres hexadecimales")
        } else if (huella.isNotEmpty()) {
            rechazo("con tcp:// no hay certificado: el paquete no debe traer huella")
        }

        // 6. Túnel opcional.
        val tunel = when (val t = o.opt("tunel")) {
            null, JSONObject.NULL -> null
            is JSONObject -> tunel(t)
            else -> rechazo("«tunel» debe ser un objeto")
        }

        // 7. Nombre: una línea, sin caracteres de control.
        val nombre = o.cadena("nombre")!!.trim()
        if (nombre.isEmpty()) rechazo("el nombre del nodo está vacío")
        if (nombre.any { it == '\n' || it == '\r' || it.isISOControl() }) rechazo("el nombre del nodo no puede tener saltos de línea ni caracteres de control")
        if (nombre.length > NOMBRE_MAX) rechazo("el nombre del nodo es demasiado largo (máximo $NOMBRE_MAX caracteres)")

        // 8. Fechas: vigencia de 24 h como máximo, no caducado (estricto), emitido no más de 5 min en el futuro.
        val emitido = fecha(o.cadena("emitido")!!, "emitido")
        val caduca = fecha(o.cadena("caduca")!!, "caduca")
        if (caduca <= emitido) rechazo("«caduca» debe ser posterior a «emitido»")
        if (caduca - emitido > VIGENCIA_MAX_S) rechazo("el paquete pretende valer más de 24 h: no se acepta")
        if (emitido > ahoraS + ADELANTO_MAX_S) rechazo("el paquete está emitido más de 5 min en el futuro (${formatearUtc(emitido)}; se toleran 5 min de desajuste de reloj): revisa el reloj del nodo o del móvil")
        if (ahoraS >= caduca) rechazo("el paquete caducó el ${formatearUtc(caduca)}: pide uno nuevo en el nodo")

        return PaqueteEmparejamiento(red, electrum, huella, tunel, nombre, emitido, caduca)
    }
}
