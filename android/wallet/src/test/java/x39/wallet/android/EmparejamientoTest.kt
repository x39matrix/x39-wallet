package x39.wallet.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import x39.wallet.Red

/** Pruebas JVM del analizador y validador del paquete de emparejamiento (versión 1). */
class EmparejamientoTest {

    private val ahora = 1_767_225_600L // 2026-01-01T00:00:00Z
    private val redActiva = Redes.porDefecto()
    private val nombreRedActiva = redActiva.name.lowercase()
    private val huella = "d69cfd0cee1cb723598e98e240ec62f14cc0ef834f47872dd6c29d4da6e230de"
    private val clavePublica = "A".repeat(43) + "="

    private fun paquete(
        v: String = "1",
        red: String = "\"$nombreRedActiva\"",
        electrum: String = "\"ssl://nodo.ejemplo.local:50002\"",
        huellaCampo: String? = "\"$huella\"",
        tunel: String? = null,
        nombre: String = "\"mi nodo\"",
        emitido: String = "\"2025-12-31T23:00:00Z\"",
        caduca: String = "\"2026-01-01T22:00:00Z\"",
        extra: String = "",
    ): String = buildString {
        append("{\"v\": $v, \"red\": $red, \"electrum\": $electrum")
        if (huellaCampo != null) append(", \"huella_spki_sha256\": $huellaCampo")
        if (tunel != null) append(", \"tunel\": $tunel")
        append(", \"nombre\": $nombre, \"emitido\": $emitido, \"caduca\": $caduca")
        append(extra)
        append("}")
    }

    private val tunelOk = """{"clave_publica_nodo": "$clavePublica", "endpoint": "nodo.ejemplo.local:51820", "ips_permitidas": ["127.0.0.1/32"], "direccion_movil": "127.0.0.2/32"}"""

    private fun rechazo(texto: String, red: Red = redActiva): String {
        try {
            Emparejamiento.analizar(texto, red, ahora)
        } catch (e: ErrorEmparejamiento) {
            return e.message!!
        }
        fail("debía rechazarse: $texto"); return ""
    }

    @Test
    fun validoSinTunel_yLaConfigNoTieneTunel() {
        val p = Emparejamiento.analizar(paquete(), redActiva, ahora)
        assertEquals(redActiva, p.red)
        assertEquals("ssl://nodo.ejemplo.local:50002", p.electrum)
        assertEquals(huella, p.huellaSpki)
        assertNull(p.tunel)
        assertEquals("mi nodo", p.nombre)
        assertEquals("d69cfd0c…a6e230de", p.huellaAbreviada())
        val c = p.aConfig()
        assertNull(c.invalida())
        assertEquals(ModoTunel.NINGUNO, c.tunel)
        assertEquals(huella, c.huellaServidor)
    }

    @Test
    fun validoConTunel_huellaEnMayusculasSeNormaliza() {
        val p = Emparejamiento.analizar(paquete(huellaCampo = "\"${huella.uppercase()}\"", tunel = tunelOk), redActiva, ahora)
        assertEquals(huella, p.huellaSpki)
        val t = p.tunel!!
        assertEquals(clavePublica, t.clavePublicaNodo)
        assertEquals("nodo.ejemplo.local:51820", t.endpoint)
        assertEquals(listOf("127.0.0.1/32"), t.ipsPermitidas)
        assertEquals("127.0.0.2/32", t.direccionMovil)
        // Aunque traiga túnel, la configuración resultante es NINGUNO (E-3 no implementado).
        assertEquals(ModoTunel.NINGUNO, p.aConfig().tunel)
    }

    @Test
    fun tcpSinHuellaValido_yConHuellaRechazado() {
        val p = Emparejamiento.analizar(paquete(electrum = "\"tcp://nodo.local:50001\"", huellaCampo = null), redActiva, ahora)
        assertEquals("", p.huellaSpki)
        assertNull(p.aConfig().huellaServidor)
        assertTrue(rechazo(paquete(electrum = "\"tcp://nodo.local:50001\"")).contains("no debe traer huella"))
    }

    @Test
    fun caducado_emitidoEnElFuturo_yVigenciaMayorDe24h() {
        assertTrue(rechazo(paquete(caduca = "\"2025-12-31T23:59:00Z\"")).contains("caducó"))
        // caduca == ahora también se rechaza (estricto); un segundo después de ahora sigue valiendo.
        assertTrue(rechazo(paquete(caduca = "\"2026-01-01T00:00:00Z\"")).contains("caducó"))
        Emparejamiento.analizar(paquete(caduca = "\"2026-01-01T00:00:01Z\""), redActiva, ahora)
        assertTrue(rechazo(paquete(emitido = "\"2026-01-01T00:06:00Z\"", caduca = "\"2026-01-01T12:00:00Z\"")).contains("futuro"))
        // Hasta 5 min de adelanto se tolera (relojes).
        Emparejamiento.analizar(paquete(emitido = "\"2026-01-01T00:04:00Z\"", caduca = "\"2026-01-01T12:00:00Z\""), redActiva, ahora)
        assertTrue(rechazo(paquete(emitido = "\"2025-12-31T23:00:00Z\"", caduca = "\"2026-01-01T23:00:01Z\"")).contains("más de 24 h"))
        assertTrue(rechazo(paquete(emitido = "\"2025-12-31T23:00:00Z\"", caduca = "\"2025-12-31T23:00:00Z\"")).contains("posterior"))
        assertTrue(rechazo(paquete(emitido = "\"31/12/2025\"")).contains("2026-01-31T12:00:00Z"))
        // La fecha debe consumir TODA la cadena: nada detrás de la Z.
        assertTrue(rechazo(paquete(emitido = "\"2025-12-31T23:00:00Z+09:00\"")).contains("exactamente la forma"))
        assertTrue(rechazo(paquete(caduca = "\"2026-01-01T22:00:00Z \"")).contains("exactamente la forma"))
        assertTrue(rechazo(paquete(caduca = "\"2026-01-01T22:00:00Zx\"")).contains("exactamente la forma"))
        // Anchuras y dígitos exactos, y calendario válido.
        assertTrue(rechazo(paquete(caduca = "\"2026-1-1T22:0:0Z\"")).contains("exactamente la forma"))
        assertTrue(rechazo(paquete(caduca = "\"2026-01-01t22:00:00Z\"")).contains("exactamente la forma"))
        assertTrue(rechazo(paquete(emitido = "\"2025-02-30T23:00:00Z\"", caduca = "\"2025-03-01T22:00:00Z\"")).contains("calendario"))
    }

    @Test
    fun robustez_milesDePalabrasNoDesbordanLaPila() {
        // Antes: regex con repetición ilimitada → StackOverflowError en la JVM con "a ".repeat(7000) (< 16 KB).
        val m = rechazo(paquete(nombre = "\"${"a ".repeat(7000)}\""))
        assertTrue(m, m.contains("clave privada o una semilla"))
        assertTrue(Emparejamiento.tieneRachaDePalabras("a ".repeat(7000)))
        assertTrue(!Emparejamiento.tieneRachaDePalabras("uno dos tres cuatro cinco seis siete ocho nueve diez once"))
        assertTrue(Emparejamiento.tieneRachaDePalabras("uno dos tres cuatro cinco seis siete ocho nueve diez once doce"))
        assertTrue(!Emparejamiento.tieneRachaDePalabras("uno dos tres cuatro cinco seis 7 ocho nueve diez once doce trece"))
        assertTrue(!Emparejamiento.tieneRachaDePalabras("uno dos tres cuatro cinco seis siete7 ocho nueve diez once doce trece"))
        assertTrue(!Emparejamiento.tieneRachaDePalabras("servidor-electrum-de-casa.nodo.ejemplo.local"))
    }

    @Test
    fun semillaPegadaAPuntuacion_tambienSeRechaza() {
        val zoo = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong" // vector público de BIP39
        // Los tres casos: «copia:» pegado sin espacio, punto detrás, entre paréntesis.
        for (n in listOf("copia:$zoo", "$zoo.", "($zoo)", "nota:$zoo;fin", "«$zoo»")) {
            val m = rechazo(paquete(nombre = "\"$n\""))
            assertTrue("$n → $m", m.contains("clave privada o una semilla"))
        }
        // Los de la vuelta anterior siguen rechazándose y los legítimos siguen pasando.
        assertTrue(rechazo(paquete(nombre = "\"copia: $zoo\"")).contains("semilla"))
        Emparejamiento.analizar(paquete(nombre = "\"nodo de casa, en el salón (2.º piso)\""), redActiva, ahora)
        assertTrue(!Emparejamiento.tieneRachaDePalabras("a.b.c.d.e.f.g.h.i.j.k"))
        assertTrue(Emparejamiento.tieneRachaDePalabras("a.b.c.d.e.f.g.h.i.j.k.l"))
        assertTrue(Emparejamiento.tieneRachaDePalabras("(" + "a ".repeat(7000) + ")"))
    }

    @Test
    fun ipv6ConDosPuntosSimplesAlFinal_seRechaza() {
        assertTrue(!Emparejamiento.ipv6Valida("fd00::1:"))
        assertTrue(!Emparejamiento.ipv6Valida("1:2:3:4:5:6:7:8:"))
        assertTrue(!Emparejamiento.ipv6Valida(":1::"))
        assertTrue(Emparejamiento.ipv6Valida("fd00::"))
        assertTrue(Emparejamiento.ipv6Valida("::1"))
        assertTrue(!Emparejamiento.cidrValido("fd00::1:/64"))
        assertTrue(Emparejamiento.cidrValido("fd00::/64"))
        // Un valor casi al tope de 16 KB sin blancos tampoco desborda nada.
        rechazo(paquete(nombre = "\"${"x".repeat(16_000)}\""))
    }

    @Test
    fun textoSobrante_yClavesDuplicadas_seRechazanAntesDeInterpretarNada() {
        // Texto detrás del JSON: se rechaza aunque el objeto en sí sea válido.
        assertTrue(rechazo(paquete() + " PRIVATE KEY=synthetic").contains("texto sobrante"))
        assertTrue(rechazo(paquete() + "{}").contains("texto sobrante"))
        assertTrue(rechazo(paquete() + "\n// comentario").contains("texto sobrante"))
        // Espacio alrededor sí se tolera.
        assertEquals("mi nodo", Emparejamiento.analizar("  " + paquete() + "\n", redActiva, ahora).nombre)
        // Clave duplicada en la raíz: la segunda «tunel» (null) taparía la primera en el org.json de Android.
        val dup = paquete(tunel = """{"private_key": "x"}""", extra = ", \"tunel\": null")
        assertTrue(rechazo(dup).contains("clave duplicada: «tunel»"))
        // Duplicada en un nivel interior, y con escape \u (misma clave una vez sin escapar).
        assertTrue(rechazo(paquete(tunel = """{"clave_publica_nodo": "$clavePublica", "endpoint": "n:1", "ips_permitidas": ["127.0.0.1/32"], "endpoint": "m:2"}""")).contains("clave duplicada: «endpoint»"))
        assertTrue(rechazo(paquete(extra = ", \"nom\\u0062re\": \"otro\"")).contains("clave duplicada: «nombre»"))
        // JSON permisivo que org.json aceptaría: claves sin comillas, comillas simples, comentarios, coma final.
        assertTrue(rechazo(paquete().replace("\"v\": 1", "v: 1")).contains("JSON inválido"))
        assertTrue(rechazo(paquete().replace("\"$nombreRedActiva\"", "'$nombreRedActiva'")).contains("JSON inválido"))
        assertTrue(rechazo(paquete().replace("\"v\": 1", "/* x */ \"v\": 1")).contains("JSON inválido"))
        assertTrue(rechazo(paquete().dropLast(1) + ",}").contains("JSON inválido"))
        assertTrue(rechazo(paquete().replace("\"v\": 1", "\"v\": 01")).contains("JSON inválido"))
    }

    @Test
    fun secretosDentroDeLosValores_conTextoAlrededor() {
        val semilla = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val wif = "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ"
        // Los tres casos: semilla con texto delante, WIF con texto delante, WIF dentro del host del servidor.
        assertTrue(rechazo(paquete(nombre = "\"copia: $semilla\"")).contains("clave privada"))
        assertTrue(rechazo(paquete(nombre = "\"nodo $wif\"")).contains("clave privada"))
        assertTrue(rechazo(paquete(electrum = "\"ssl://$wif.ejemplo.local:50002\"")).contains("clave privada"))
        // Y en el endpoint del túnel.
        assertTrue(rechazo(paquete(tunel = """{"clave_publica_nodo": "$clavePublica", "endpoint": "$wif.ejemplo.local:51820", "ips_permitidas": ["127.0.0.1/32"]}""")).contains("clave privada"))
        // Límites: un WIF «pegado» a más base58 no es un WIF; un host normal largo no dispara nada.
        Emparejamiento.analizar(paquete(nombre = "\"${wif}abc\""), redActiva, ahora)
        Emparejamiento.analizar(paquete(electrum = "\"ssl://servidor-electrum-de-casa.nodo.ejemplo.local:50002\""), redActiva, ahora)
    }

    @Test
    fun ipv6LiteralesSinResolverNombres() {
        assertTrue(Emparejamiento.ipv6Valida("::ffff:192.0.2.1")) // IPv4 incrustada (192.0.2.0/24: rango reservado para documentación)
        assertTrue(Emparejamiento.ipv6Valida("fd00::1"))
        assertTrue(Emparejamiento.ipv6Valida("1:2:3:4:5:6:7:8"))
        assertTrue(Emparejamiento.ipv6Valida("::"))
        assertTrue(!Emparejamiento.ipv6Valida("::::"))
        assertTrue(!Emparejamiento.ipv6Valida("1::2::3"))
        assertTrue(!Emparejamiento.ipv6Valida("1:2:3:4:5:6:7:8:9"))
        assertTrue(!Emparejamiento.ipv6Valida("12345::1"))
        assertTrue(!Emparejamiento.ipv6Valida("fe80::1%eth0"))
        assertTrue(!Emparejamiento.ipv6Valida("::ffff:192.0.2.300"))
        assertTrue(!Emparejamiento.ipv6Valida("nodo.ejemplo.local"))
        // En host:puerto con corchetes y en redes permitidas.
        Emparejamiento.analizar(paquete(electrum = "\"ssl://[fd00::1]:50002\""), redActiva, ahora)
        assertTrue(rechazo(paquete(electrum = "\"ssl://[::::]:50002\"")).contains("no es una dirección IPv6"))
        assertTrue(rechazo(paquete(electrum = "\"ssl://fd00::1:50002\"")).contains("entre corchetes"))
        assertTrue(rechazo(paquete(electrum = "\"ssl://300.1.1.1:50002\"")).contains("no es una dirección IPv4"))
        Emparejamiento.analizar(paquete(tunel = """{"clave_publica_nodo": "$clavePublica", "endpoint": "[fd00::2]:51820", "ips_permitidas": ["::ffff:192.0.2.1/128", "fd00::/64"]}"""), redActiva, ahora)
        assertTrue(rechazo(paquete(tunel = """{"clave_publica_nodo": "$clavePublica", "endpoint": "n:1", "ips_permitidas": ["::::/64"]}""")).contains("no es una red válida"))
    }

    @Test
    fun otraRed_redDesconocida_yMainnet() {
        if (Redes.permitida(Red.MAINNET)) {
            assertTrue(rechazo(paquete(red = "\"signet\"")).contains("no permitida"))
        } else {
            assertTrue(rechazo(paquete(), Red.REGTEST).contains("el paquete es de signet y la billetera es de regtest"))
            assertTrue(rechazo(paquete(red = "\"mainnet\"")).contains("no permitida"))
        }
        assertTrue(rechazo(paquete(red = "\"liquid\"")).contains("red desconocida"))
    }

    @Test
    fun huellaMalFormada_oAusenteConSsl() {
        assertTrue(rechazo(paquete(huellaCampo = "\"${huella.dropLast(1)}\"")).contains("64 caracteres"))
        assertTrue(rechazo(paquete(huellaCampo = "\"${"g".repeat(64)}\"")).contains("64 caracteres"))
        assertTrue(rechazo(paquete(huellaCampo = null)).contains("falta la huella"))
        assertTrue(rechazo(paquete(huellaCampo = "123")).contains("debe ser texto"))
    }

    @Test
    fun cualquierClavePrivadaRechazaElPaqueteEntero() {
        val casos = listOf(
            paquete(extra = ", \"clave_privada\": \"$clavePublica\"") to "campo",
            paquete(extra = ", \"PrivateKey\": \"x\"") to "campo",
            paquete(tunel = """{"clave_publica_nodo": "$clavePublica", "endpoint": "n:1", "ips_permitidas": ["127.0.0.1/32"], "secreto": "x"}""") to "campo",
            paquete(nombre = "\"-----BEGIN PRIVATE KEY-----\"") to "valor",
            paquete(nombre = "\"[Interface] PrivateKey = abc\"") to "valor",
            paquete(nombre = "\"xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi\"") to "valor",
            paquete(nombre = "\"tprv8ZgxMBicQKsPd7Uf69XL1XwhmjHopUGep8GuEiJDZmbQz6o58LninorQAfcKZWARbtRtfnLcJ5MQ2AtHcQJCCRUcMRvmDUjyEdNiNoWwDJZ\"") to "valor",
            paquete(nombre = "\"abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about\"") to "valor",
            paquete(nombre = "\"5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ\"") to "valor",
            paquete(extra = ", \"tunel_interface\": {\"x\": 1}") to "campo",
        )
        for ((texto, tipo) in casos) {
            val m = rechazo(texto)
            assertTrue("$tipo → $m", m.contains("clave privada") && m.contains("se rechaza entero") && m.contains(if (tipo == "campo") "campo" else "valor"))
        }
        // Un nombre normal con pocas palabras no dispara el detector de semillas.
        Emparejamiento.analizar(paquete(nombre = "\"nodo de casa en el salón\""), redActiva, ahora)
    }

    @Test
    fun versionDesconocida_noJson_yCampoDesconocido() {
        assertTrue(rechazo(paquete(v = "2")).contains("versión del paquete desconocida (2)"))
        assertTrue(rechazo(paquete(v = "\"1\"")).contains("falta la versión"))
        assertTrue(rechazo("hola").contains("JSON inválido"))
        assertTrue(rechazo("").contains("JSON inválido"))
        assertTrue(rechazo(paquete(extra = ", \"color\": \"rojo\"")).contains("campo desconocido: «color»"))
        assertTrue(rechazo(paquete(tunel = """{"clave_publica_nodo": "$clavePublica", "endpoint": "n:1", "ips_permitidas": ["127.0.0.1/32"], "mtu": 1420}""")).contains("campo desconocido en «tunel»"))
    }

    @Test
    fun tunelMalFormado() {
        fun t(clave: String = "\"$clavePublica\"", endpoint: String = "\"nodo.ejemplo.local:51820\"", ips: String = "[\"127.0.0.1/32\"]", movil: String? = null) =
            """{"clave_publica_nodo": $clave, "endpoint": $endpoint, "ips_permitidas": $ips${if (movil != null) ", \"direccion_movil\": $movil" else ""}}"""
        assertTrue(rechazo(paquete(tunel = t(clave = "\"${"A".repeat(43)}\""))).contains("32 bytes en base64"))
        assertTrue(rechazo(paquete(tunel = t(clave = "\"${"A".repeat(44)}\""))).contains("32 bytes en base64"))
        assertTrue(rechazo(paquete(tunel = t(endpoint = "\"nodo.ejemplo.local\""))).contains("host:puerto"))
        assertTrue(rechazo(paquete(tunel = t(endpoint = "\"nodo.ejemplo.local:70000\""))).contains("entre 1 y 65535"))
        assertTrue(rechazo(paquete(tunel = t(ips = "[]"))).contains("vacía"))
        assertTrue(rechazo(paquete(tunel = t(ips = "[\"127.0.0.1\"]"))).contains("no es una red válida"))
        assertTrue(rechazo(paquete(tunel = t(ips = "[\"300.0.0.1/32\"]"))).contains("no es una red válida"))
        assertTrue(rechazo(paquete(tunel = t(movil = "\"casa\""))).contains("direccion_movil"))
        assertTrue(rechazo(paquete(tunel = "\"si\"")).contains("debe ser un objeto"))
        // IPv6 permitida con prefijo válido.
        Emparejamiento.analizar(paquete(tunel = t(ips = "[\"fd00::/64\"]")), redActiva, ahora)
    }

    @Test
    fun servidorMalFormado_nombre_yTamano() {
        assertTrue(rechazo(paquete(electrum = "\"nodo.ejemplo.local:50002\"")).contains("ssl:// o tcp://"))
        assertTrue(rechazo(paquete(electrum = "\"ssl://nodo.ejemplo.local\"")).contains("host:puerto"))
        assertTrue(rechazo(paquete(nombre = "\"\"")).contains("vacío"))
        assertTrue(rechazo(paquete(nombre = "\"uno\\ndos\"")).contains("saltos de línea"))
        assertTrue(rechazo(paquete(electrum = "\"ssl://nodo.ejemplo.local:0\"")).contains("entre 1 y 65535"))
        assertTrue(rechazo(paquete(electrum = "\"ssl://nodo.ejemplo.local:65536\"")).contains("entre 1 y 65535"))
        assertTrue(rechazo(paquete(nombre = "\"${"n".repeat(65)}\"")).contains("demasiado largo"))
        assertTrue(rechazo(paquete(extra = ", \"nombre2\": \"${"x".repeat(17_000)}\"")).contains("demasiado grande"))
    }

    @Test
    fun formatearFechas() {
        assertEquals("2026-01-01 00:00 UTC", Emparejamiento.formatearUtc(ahora))
    }
}
