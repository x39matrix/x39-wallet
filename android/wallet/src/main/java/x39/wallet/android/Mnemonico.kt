package x39.wallet.android

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import x39.wallet.SemillaGenerada

/**
 * Mnemónico BIP39 en memoria NATIVA, listo para cruzar a Rust sin copias.
 *
 * CONTRATO (el mismo que en `rust/src/ffi.rs`):
 *  - El mnemónico vive en un `ByteBuffer` DIRECTO con `position() == 0` y `limit()` igual a
 *    la longitud exacta en UTF-8. El conversor de UniFFI (`FfiConverterByRefBytes`) exige
 *    `isDirect` y pasa a Rust el puntero y `remaining()`; no suma `position` al puntero.
 *  - Se entrega a la llamada FFI con [usar]; Rust lo recibe prestado solo durante la llamada.
 *  - Se pone a cero con [close] (úsalo en un `finally` o con `use { }`).
 *  - Nunca `String` ni `CharSequence`: la entrada es un `CharArray` (que se borra) o un
 *    `ByteArray` (que se borra).
 *
 * Límite conocido: el borrado de las vistas de Android y de cualquier copia que haga la
 * máquina virtual no se puede garantizar desde aquí; esta clase garantiza que NUESTRAS
 * copias (este búfer y las entradas que recibe) se ponen a cero.
 *
 * PARA LAS PANTALLAS (fase F): las palabras se muestran con
 * `TextView.setText(char[], inicio, longitud)`, nunca convertidas a `String`; el `CharArray`
 * se pone a cero al salir de la pantalla; y la pantalla lleva FLAG_SECURE
 * (`Window.protegerContraCapturas()`). Al teclear o pegar, los caracteres se leen del
 * `Editable` con `getChars` a un `CharArray` que se pasa a [deChars] (que lo borra).
 */
class Mnemonico private constructor(private val buf: ByteBuffer) : AutoCloseable {

    @Volatile
    private var cerrado = false

    /** Bytes UTF-8 del mnemónico. */
    val longitud: Int get() = buf.limit()

    /** Entrega el búfer a una llamada FFI: directo, `position() == 0`, `limit()` exacto. */
    fun <T> usar(f: (ByteBuffer) -> T): T {
        check(!cerrado) { "el mnemónico ya se ha borrado" }
        buf.position(0)
        return try {
            f(buf)
        } finally {
            buf.position(0)
        }
    }

    /** Pone a cero el búfer. Idempotente. */
    override fun close() {
        if (!cerrado) {
            cerrado = true
            borrar(buf)
        }
    }

    companion object {
        /**
         * Desde los caracteres tecleados o pegados. Codifica UTF-8 directamente en memoria
         * nativa y borra `chars` (salvo que se pida lo contrario).
         */
        fun deChars(chars: CharArray, borrarEntrada: Boolean = true): Mnemonico {
            val enc = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            // Cota superior de UTF-8 para cualquier secuencia de `char`: 3 bytes por char.
            val buf = ByteBuffer.allocateDirect(chars.size * 3)
            val resultado = enc.encode(CharBuffer.wrap(chars), buf, true)
            if (resultado.isError || enc.flush(buf).isError) {
                borrar(buf)
                if (borrarEntrada) chars.fill('\u0000')
                throw IllegalArgumentException("mnemónico no codificable en UTF-8")
            }
            buf.flip() // limit = bytes escritos, position = 0
            if (borrarEntrada) chars.fill('\u0000')
            return Mnemonico(buf)
        }

        /** Desde bytes UTF-8 (p. ej. la semilla nueva que devuelve Rust). Borra `bytes`. */
        fun deBytes(bytes: ByteArray, borrarEntrada: Boolean = true): Mnemonico {
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.flip()
            if (borrarEntrada) bytes.fill(0)
            return Mnemonico(buf)
        }

        /**
         * Envuelve un búfer DIRECTO ya relleno (la salida del descifrado): se toma como
         * contenido lo que hay entre 0 y `limit()`.
         */
        internal fun deBufferDirecto(buf: ByteBuffer): Mnemonico {
            require(buf.isDirect) { "hace falta un ByteBuffer directo" }
            buf.position(0)
            return Mnemonico(buf)
        }

        internal fun borrar(b: ByteBuffer) {
            b.clear()
            while (b.hasRemaining()) b.put(0.toByte())
            b.clear()
        }
    }
}

/**
 * Semilla recién generada por el motor. Su objeto nativo se consume y borra antes de
 * devolver esta instancia; los bytes escalares no pasan por el serializador de UniFFI.
 * La copia Kotlin se muestra una vez, se cifra y se borra al cerrar esta instancia.
 *
 * PARA LAS PANTALLAS (fase F): [palabras] se pinta con `TextView.setText(char[], 0, n)`,
 * nunca como `String`; el `CharArray` se pone a cero al salir de la pantalla (y la pantalla
 * lleva FLAG_SECURE). Tras cifrar con [AlmacenSemilla], `close()`.
 */
class SemillaNueva(private val bytes: ByteArray) : AutoCloseable {

    /** Recoge la salida nativa y la borra incluso si falla la lectura de algún byte. */
    constructor(nativa: SemillaGenerada) : this(extraerYBorrar(nativa))

    companion object {
        internal fun extraerYBorrar(nativa: SemillaGenerada, destino: ((Int) -> ByteArray)? = null): ByteArray {
            var salida: ByteArray? = null
            try {
                val n = nativa.longitud().toInt()
                require(n in 1..256) { "longitud de semilla no válida" }
                val buf = destino?.invoke(n) ?: ByteArray(n)
                salida = buf
                for (i in buf.indices) buf[i] = nativa.byteEn(i.toUInt()).toByte()
                return buf
            } catch (e: Throwable) {
                salida?.fill(0)
                throw e
            } finally {
                try {
                    nativa.borrar()
                } catch (e: Throwable) {
                    salida?.fill(0)
                    throw e
                } finally {
                    try {
                        nativa.close()
                    } catch (e: Throwable) {
                        salida?.fill(0)
                        throw e
                    }
                }
            }
        }
    }

    @Volatile
    private var cerrada = false

    /** Las palabras para mostrarlas, como `CharArray` (nunca `String`). Borra el resultado quien lo pida. */
    fun palabras(): CharArray {
        check(!cerrada) { "la semilla ya se ha borrado" }
        val dec = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val salida = CharBuffer.allocate(bytes.size)
        dec.decode(ByteBuffer.wrap(bytes), salida, true)
        dec.flush(salida)
        salida.flip()
        val chars = CharArray(salida.remaining())
        salida.get(chars)
        // Borrar el búfer intermedio de caracteres.
        salida.clear()
        while (salida.hasRemaining()) salida.put('\u0000')
        return chars
    }

    /** Copia a memoria nativa directa para cifrar o crear la billetera. No borra esta instancia. */
    fun comoMnemonico(): Mnemonico {
        check(!cerrada) { "la semilla ya se ha borrado" }
        return Mnemonico.deBytes(bytes, borrarEntrada = false)
    }

    override fun close() {
        if (!cerrada) {
            cerrada = true
            bytes.fill(0)
        }
    }
}
