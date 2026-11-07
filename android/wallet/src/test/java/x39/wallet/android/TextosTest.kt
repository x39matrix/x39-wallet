package x39.wallet.android

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import x39.wallet.R

/**
 * Orden 9a, I1: los cinco `strings.xml` del módulo tienen las MISMAS claves y los mismos huecos
 * (`%n$s`); cada entrada de [Textos.tabla] tiene recurso y en castellano la ida y vuelta devuelve
 * exactamente el mensaje interno original; con los otros cuatro idiomas la traducción no deja
 * huecos sin rellenar. Lee los ficheros del disco: no hace falta Android.
 */
class TextosTest {

    private val carpetas = listOf("values", "values-en", "values-zh-rCN", "values-ja", "values-ar")

    /** `src/main/res` desde el directorio de trabajo de las pruebas (el del módulo) o desde la raíz del repo. */
    private val res: File = listOf("src/main/res", "android/wallet/src/main/res", "wallet/src/main/res").map { File(it) }.first { it.isDirectory }

    private fun claves(carpeta: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$carpeta/strings.xml"))
        val nodos = doc.getElementsByTagName("string")
        return (0 until nodos.length).associate { i ->
            val n = nodos.item(i)
            n.attributes.getNamedItem("name").nodeValue to desescapar(n.textContent)
        }
    }

    /** Lo que hace Android con el texto de un recurso: \' \" \n y \\ . */
    private fun desescapar(t: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                when (val d = t[i + 1]) { 'n' -> sb.append('\n'); else -> sb.append(d) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun huecos(t: String): Set<String> = Regex("%(\\d+)\\\$s").findAll(t).map { it.groupValues[1] }.toSet()

    private val nombrePorId: Map<Int, String> = R.string::class.java.fields.filter { it.name.startsWith("x39w_") }.associate { it.getInt(null) to it.name }

    private fun resolver(textos: Map<String, String>): (Int, List<String>) -> String = { id, args ->
        val nombre = nombrePorId[id] ?: error("recurso sin nombre: $id")
        val plantilla = textos[nombre] ?: error("falta $nombre")
        String.format(Locale.ROOT, plantilla, *args.toTypedArray())
    }

    @Test
    fun lasCincoCarpetasTienenLasMismasClavesYLosMismosHuecos() {
        val es = claves("values")
        assertTrue("hay claves", es.size > 300)
        for (c in carpetas.drop(1)) {
            val otra = claves(c)
            assertEquals("faltan en $c: ${es.keys - otra.keys}", emptySet<String>(), es.keys - otra.keys)
            assertEquals("sobran en $c: ${otra.keys - es.keys}", emptySet<String>(), otra.keys - es.keys)
            for ((k, v) in es) {
                assertEquals("huecos de $k en $c", huecos(v), huecos(otra.getValue(k)))
                assertTrue("$k vacía en $c", otra.getValue(k).isNotBlank())
            }
        }
        // Todas las claves del módulo llevan el prefijo (no chocan con los recursos de la app).
        assertTrue(es.keys.all { it.startsWith("x39w_") })
        assertEquals("cada clave del XML existe en R.string y viceversa", es.keys, nombrePorId.values.toSet())
    }

    /** Un mensaje interno por entrada de la tabla (anidados incluidos), tal como los produce la lógica. */
    private val muestras = listOf(
        "sin servidor Electrum", "el servidor debe empezar por ssl:// o tcp://", "el servidor debe ser host:puerto (puerto entre 1 y 65535)",
        "la huella solo se comprueba con ssl://", "un servidor .onion solo se alcanza por un proxy SOCKS5 (Orbot): activa el proxy",
        "el proxy SOCKS5 debe ser IP:puerto (por ejemplo 127.0.0.1:9050), no un nombre: un nombre habría que resolverlo en este aparato",
        "el túnel propio todavía no está disponible", "sin configurar", "conectando", "sano",
        "es de otra red: se esperaba bitcoin, el servidor anuncia génesis 00000008…", "la huella del certificado no es la esperada: ab12…",
        "X-39 por túnel", "el tuyo (nodo.ejemplo.local)", "el tuyo ([2001:db8::1])",
        "nadie responde en esa dirección y puerto (Connection refused)", "sin respuesta a tiempo: comprueba la dirección, el túnel o el proxy (timed out)",
        "no se encuentra ese nombre de servidor (failed to lookup address)", "red inalcanzable: sin conexión, o el túnel no está activo (Network is unreachable)",
        "el servidor cortó la conexión: ¿seguro que ese puerto habla Electrum? (connection reset)",
        "el certificado del servidor no lo reconoce ninguna autoridad: si es tu propio certificado, activa «certificado propio» (tls: UnknownIssuer)",
        "fallo del cifrado TLS: ¿seguro que ese puerto habla ssl://? (tls: handshake)",
        "ese nodo va por detrás de lo ya visto: bloque 150000 frente a 200000",
        "túnel externo no activo: se exige una VPN activa en el aparato",
        "no se pudo guardar la configuración del nodo: no se pudo guardar la configuración del nodo en las preferencias (el disco no aceptó la escritura; se conserva la anterior)",
        "no se pudo guardar la configuración del nodo: IllegalStateException",
        "la configuración del nodo cambió durante la comprobación: no se guarda", "red no permitida en esta edición", "red no permitida en esta edición: signet",
        "no se pudo guardar la configuración del nodo en las preferencias (el disco no aceptó la escritura; se conserva la anterior)",
        NodoX39.MENSAJE_TUNEL, NodoX39.MENSAJE_NODO_NO_RESPONDE, NodoX39.MENSAJE_TUNEL_DESCONOCIDO,
        "el nodo no está disponible: ${NodoX39.MENSAJE_TUNEL}", "el nodo no está disponible: conectando", "el nodo no está disponible: es de otra red: génesis de signet",
        "no hay nodo configurado: elígelo en Ajustes → Nodo", "la billetera no está abierta",
        "falta el escaneo completo tras restaurar: configura el servidor y sincroniza antes de enviar", "fondos insuficientes para el envío y la comisión",
        "dirección inválida o de otra red", "ese servidor es de otra red: se esperaba bitcoin", "sin respuesta del servidor: red inalcanzable: sin conexión, o el túnel no está activo (Network is unreachable)",
        "sin respuesta del servidor: ese nodo va por detrás de lo ya visto: bloque 100 frente a 200", "sin respuesta del servidor: protocolo Electrum 1.2 no admitido (hace falta 1.4)",
        "hay un envío sin resolver; reintenta o cancela antes", "la revisión no coincide con lo que se iba a firmar: importe distinto",
        "la revisión caducó; prepara el envío otra vez", "la semilla no es la de esta billetera", "la billetera exige Android 6 o superior",
        "biblioteca nativa con redes no permitidas: x39wallet-redes=solo-prueba",
        "la clave de la billetera ha quedado invalidada; restaura desde la semilla", "no se pudo guardar la semilla cifrada", "no hay semilla guardada",
        "fichero de semilla ilegible", "IV de GCM inesperado", "ya abierta en este proceso", "sin CryptoObject", "marcador de redes desconocido: x",
        "el mnemónico ya se ha borrado", "la semilla ya se ha borrado", "mnemónico no codificable en UTF-8", "hace falta un ByteBuffer directo", "longitud de semilla no válida",
        "(sin huella: tcp://)", "el fichero es demasiado grande para ser un paquete (más de 16 KB)", "no se pudo leer el fichero", "no se pudo abrir el fichero", "paquete inválido",
        "el paquete trae un campo que parece una clave privada («clave_privada_tunel»): se rechaza entero",
        "el paquete trae un valor que parece una clave privada o una semilla (en /nombre): se rechaza entero",
        "falta el campo «red»", "el campo «nombre» debe ser texto", "la fecha «emitido» debe tener exactamente la forma 2026-01-31T12:00:00Z (UTC)",
        "la fecha «caduca» no es una fecha válida del calendario", "«electrum» debe ser [IPv6]:puerto", "«electrum»: «fd00::1:» no es una dirección IPv6 válida",
        "«tunel.endpoint» debe ser host:puerto", "«electrum»: una dirección IPv6 va entre corchetes: [IPv6]:puerto", "«electrum»: «999.1.1.1» no es una dirección IPv4 válida",
        "«electrum»: «nodo..local» no es un nombre de host válido", "«electrum»: el host contenga algo que parece una clave privada: se rechaza entero".replace("contenga", "contiene"),
        "«electrum»: el puerto debe estar entre 1 y 65535", "campo desconocido en «tunel»: «color»",
        "«tunel.clave_publica_nodo» debe ser una clave pública de 32 bytes en base64", "«tunel.ips_permitidas» debe ser una lista", "«tunel.ips_permitidas» está vacía",
        "«tunel.ips_permitidas» debe contener textos", "«tunel.ips_permitidas»: «10.0.0.0/33» no es una red válida (forma A.B.C.D/n o IPv6/n)",
        "«tunel.direccion_movil» no es una dirección válida (forma A.B.C.D/32)", "el paquete es demasiado grande (más de 16 KB)",
        "no es un paquete de emparejamiento (JSON inválido: clave duplicada: «nombre»)", "no es un paquete de emparejamiento (JSON inválido: no es un objeto)",
        "no es un paquete de emparejamiento (JSON inválido: texto sobrante)", "no es un paquete de emparejamiento (JSON inválido)",
        "campo desconocido: «color»", "falta la versión del paquete («v»)", "versión del paquete desconocida (2); esta billetera entiende la 1",
        "red desconocida: «dogecoin»", "el paquete es de signet y la billetera es de regtest", "«electrum» debe empezar por ssl:// o tcp://",
        "falta la huella SPKI del certificado (obligatoria con ssl://)", "la huella SPKI debe tener 64 caracteres hexadecimales",
        "con tcp:// no hay certificado: el paquete no debe traer huella", "«tunel» debe ser un objeto", "el nombre del nodo está vacío",
        "el nombre del nodo no puede tener saltos de línea ni caracteres de control", "el nombre del nodo es demasiado largo (máximo 64 caracteres)",
        "«caduca» debe ser posterior a «emitido»", "el paquete pretende valer más de 24 h: no se acepta",
        "el paquete está emitido más de 5 min en el futuro (2026-01-01 00:06 UTC; se toleran 5 min de desajuste de reloj): revisa el reloj del nodo o del móvil",
        "el paquete caducó el 2026-01-01 00:00 UTC: pide uno nuevo en el nodo",
        "no es un paquete de emparejamiento (JSON inválido: texto vacío)", "no es un paquete de emparejamiento (JSON inválido: el paquete debe ser un objeto JSON)",
        "no es un paquete de emparejamiento (JSON inválido: texto sobrante tras el JSON (posición 120))", "no es un paquete de emparejamiento (JSON inválido: fin inesperado del texto)",
        "no es un paquete de emparejamiento (JSON inválido: anidamiento excesivo)", "no es un paquete de emparejamiento (JSON inválido: carácter inesperado «'» en la posición 3)",
        "no es un paquete de emparejamiento (JSON inválido: se esperaba una clave entre comillas dobles en la posición 4)",
        "no es un paquete de emparejamiento (JSON inválido: se esperaba «:» en la posición 9)", "no es un paquete de emparejamiento (JSON inválido: se esperaba «,» o «}» en la posición 20)",
        "no es un paquete de emparejamiento (JSON inválido: se esperaba «,» o «]» en la posición 30)", "no es un paquete de emparejamiento (JSON inválido: cadena sin cerrar)",
        "no es un paquete de emparejamiento (JSON inválido: escape incompleto)", "no es un paquete de emparejamiento (JSON inválido: escape \\u incompleto)",
        "no es un paquete de emparejamiento (JSON inválido: escape \\u inválido)", "no es un paquete de emparejamiento (JSON inválido: escape inválido «\\x»)",
        "no es un paquete de emparejamiento (JSON inválido: carácter de control sin escapar dentro de una cadena)",
        "no es un paquete de emparejamiento (JSON inválido: literal inválido en la posición 5)", "no es un paquete de emparejamiento (JSON inválido: número inválido en la posición 7)",
        "no es un paquete de emparejamiento (JSON inválido: número inválido (fracción vacía))", "no es un paquete de emparejamiento (JSON inválido: número inválido (exponente vacío))",
    )

    /** Qué entradas de la tabla reconoce un mensaje (la primera que casa y, en los huecos anidados, las interiores). */
    private fun entradasUsadas(m: String, usadas: MutableSet<Int>) {
        val i = Textos.tabla.indexOfFirst { it.patron.matchEntire(m) != null }
        if (i < 0) return
        usadas += i
        val e = Textos.tabla[i]
        e.patron.matchEntire(m)!!.groupValues.drop(1).forEachIndexed { g, v -> if (g in e.anidados) entradasUsadas(v, usadas) }
    }

    @Test
    fun enCastellanoLaIdaYVueltaDevuelveElMensajeInternoExacto_yLaTablaEnteraTieneMuestra() {
        val es = resolver(claves("values"))
        val usadas = mutableSetOf<Int>()
        for (m in muestras) {
            assertEquals("ida y vuelta de «$m»", m, Textos.traducir(m, es))
            entradasUsadas(m, usadas)
        }
        val sinMuestra = Textos.tabla.indices.filter { it !in usadas }.map { nombrePorId[Textos.tabla[it].recurso] }
        assertEquals("entradas de la tabla sin muestra: $sinMuestra", emptyList<String?>(), sinMuestra)
        // Lo que no reconoce (un mensaje crudo del motor) vuelve tal cual.
        assertEquals("Network is unreachable (os error 101)", Textos.traducir("Network is unreachable (os error 101)", es))
        assertEquals("", Textos.traducir("", es))
    }

    @Test
    fun cadaEntradaDeLaTablaTieneRecursoEnLosCincoIdiomas_yLaTraduccionNoDejaHuecos() {
        val porIdioma = carpetas.associateWith { claves(it) }
        for (e in Textos.tabla) {
            val nombre = nombrePorId[e.recurso]
            assertTrue("recurso ${e.recurso} sin nombre", nombre != null)
            val grupos = Regex(e.patron.pattern).toPattern().matcher("").groupCount()
            for ((c, textos) in porIdioma) {
                val t = textos[nombre] ?: error("falta $nombre en $c")
                assertEquals("huecos de $nombre en $c frente a los grupos del patrón", (1..grupos).map { it.toString() }.toSet(), huecos(t))
            }
        }
        for (c in carpetas.drop(1)) {
            val r = resolver(porIdioma.getValue(c))
            for (m in muestras) {
                val t = Textos.traducir(m, r)
                assertTrue("en $c «$m» → «$t»", t.isNotBlank() && !t.contains("%1\$s") && !t.contains("%2\$s"))
                assertTrue("en $c «$m» no se tradujo", t != m || m.isEmpty())
            }
        }
        // Los tres textos del túnel y la frase de privacidad existen en los cinco idiomas con el mismo sentido: mismas claves, sin huecos.
        for (k in listOf("x39w_m_tunel_apagado", "x39w_m_nodo_no_responde", "x39w_m_tunel_desconocido", "x39w_frase_privacidad")) {
            for ((c, textos) in porIdioma) assertTrue("$k en $c", textos.getValue(k).isNotBlank() && huecos(textos.getValue(k)).isEmpty())
        }
    }
}
