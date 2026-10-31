package x39.wallet.android

/**
 * Pre-análisis ESTRICTO del texto crudo de un paquete, antes de dárselo a `org.json`:
 * exactamente un valor JSON (RFC 8259) que consume TODO el texto, sin claves duplicadas en
 * ningún nivel y sin las extensiones permisivas de `org.json` (comillas simples, claves sin
 * comillas, comentarios). Hace falta porque el `org.json` de Android sobrescribe las claves
 * duplicadas en silencio y acepta texto permisivo, y el de la JVM se comporta distinto: el
 * veredicto no puede depender de la plataforma.
 */
internal object JsonEstricto {

    class Invalido(motivo: String) : Exception(motivo)

    private const val PROFUNDIDAD_MAX = 32

    fun comprobar(texto: String) {
        val p = Analizador(texto)
        p.espacios()
        if (p.pos >= texto.length) throw Invalido("texto vacío")
        if (texto[p.pos] != '{') throw Invalido("el paquete debe ser un objeto JSON")
        p.valor(0)
        p.espacios()
        if (p.pos != texto.length) throw Invalido("texto sobrante tras el JSON (posición ${p.pos})")
    }

    private class Analizador(val t: String) {
        var pos = 0

        fun espacios() { while (pos < t.length && (t[pos] == ' ' || t[pos] == '\t' || t[pos] == '\n' || t[pos] == '\r')) pos++ }

        private fun fin(): Nothing = throw Invalido("fin inesperado del texto")

        fun valor(prof: Int) {
            if (prof > PROFUNDIDAD_MAX) throw Invalido("anidamiento excesivo")
            if (pos >= t.length) fin()
            when (val c = t[pos]) {
                '{' -> objeto(prof)
                '[' -> lista(prof)
                '"' -> cadena()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> numero()
                else -> throw Invalido("carácter inesperado «$c» en la posición $pos")
            }
        }

        private fun objeto(prof: Int) {
            pos++
            val claves = HashSet<String>()
            espacios()
            if (pos < t.length && t[pos] == '}') { pos++; return }
            while (true) {
                espacios()
                if (pos >= t.length) fin()
                if (t[pos] != '"') throw Invalido("se esperaba una clave entre comillas dobles en la posición $pos")
                val k = cadena()
                if (!claves.add(k)) throw Invalido("clave duplicada: «$k»")
                espacios()
                if (pos >= t.length) fin()
                if (t[pos] != ':') throw Invalido("se esperaba «:» en la posición $pos")
                pos++
                espacios()
                valor(prof + 1)
                espacios()
                if (pos >= t.length) fin()
                when (t[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return }
                    else -> throw Invalido("se esperaba «,» o «}» en la posición $pos")
                }
            }
        }

        private fun lista(prof: Int) {
            pos++
            espacios()
            if (pos < t.length && t[pos] == ']') { pos++; return }
            while (true) {
                espacios()
                valor(prof + 1)
                espacios()
                if (pos >= t.length) fin()
                when (t[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return }
                    else -> throw Invalido("se esperaba «,» o «]» en la posición $pos")
                }
            }
        }

        /** Cadena entre comillas dobles; devuelve el valor ya sin escapes (para comparar claves como hace org.json). */
        private fun cadena(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= t.length) throw Invalido("cadena sin cerrar")
                val c = t[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= t.length) throw Invalido("escape incompleto")
                        when (val e = t[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > t.length) throw Invalido("escape \\u incompleto")
                                val h = t.substring(pos, pos + 4)
                                if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw Invalido("escape \\u inválido")
                                sb.append(h.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw Invalido("escape inválido «\\$e»")
                        }
                    }
                    c < ' ' -> throw Invalido("carácter de control sin escapar dentro de una cadena")
                    else -> sb.append(c)
                }
            }
        }

        private fun literal(l: String) {
            if (!t.startsWith(l, pos)) throw Invalido("literal inválido en la posición $pos")
            pos += l.length
        }

        private fun digitos(): Int { val ini = pos; while (pos < t.length && t[pos] in '0'..'9') pos++; return pos - ini }

        private fun numero() {
            if (t[pos] == '-') pos++
            if (pos >= t.length) fin()
            if (t[pos] == '0') pos++ else if (t[pos] in '1'..'9') digitos() else throw Invalido("número inválido en la posición $pos")
            if (pos < t.length && t[pos] == '.') { pos++; if (digitos() == 0) throw Invalido("número inválido (fracción vacía)") }
            if (pos < t.length && (t[pos] == 'e' || t[pos] == 'E')) {
                pos++
                if (pos < t.length && (t[pos] == '+' || t[pos] == '-')) pos++
                if (digitos() == 0) throw Invalido("número inválido (exponente vacío)")
            }
        }
    }
}
