package x39.wallet.android

import java.io.File
import x39.wallet.Billetera
import x39.wallet.Red

/**
 * Una sola [Billetera] abierta por fichero de estado en este proceso, también desde Android.
 * El motor lo exige por su cuenta (ruta canónica); aquí se evita llegar a ese error y se
 * libera la instancia nativa al cerrar. La protección es por ruta canónica dentro del mismo
 * proceso; no cubre otros procesos. En Android la ruta es privada y fija.
 */
object Billeteras {

    private val abiertas = HashMap<String, BilleteraAbierta>()

    /** Crea (o restaura) una billetera. El mnemónico cruza como ByteBuffer directo. */
    fun crear(mnemonico: Mnemonico, red: Red, fichero: File): BilleteraAbierta = synchronized(this) {
        val clave = clave(fichero)
        check(clave !in abiertas) { "ya abierta en este proceso" }
        val motor = mnemonico.usar { Billetera.crear(it, red, fichero.path) }
        BilleteraAbierta(clave, red, motor).also { abiertas[clave] = it }
    }

    /** Abre una billetera existente (sin semilla) y reanuda los envíos sin resolver. */
    fun abrir(fichero: File, red: Red): BilleteraAbierta = synchronized(this) {
        val clave = clave(fichero)
        check(clave !in abiertas) { "ya abierta en este proceso" }
        val motor = Billetera.abrir(fichero.path, red)
        BilleteraAbierta(clave, red, motor).also { abiertas[clave] = it }
    }

    /** La billetera abierta sobre ese fichero, si la hay. */
    fun abierta(fichero: File): BilleteraAbierta? = synchronized(this) { abiertas[clave(fichero)] }

    internal fun soltar(clave: String) {
        synchronized(this) { abiertas.remove(clave) }
    }

    /** Ruta canónica: resuelve enlaces simbólicos y `..`; si el fichero no existe, canoniza lo que exista. */
    private fun clave(fichero: File): String = fichero.canonicalPath
}

/** Billetera viva: envuelve el objeto nativo y lo libera al cerrar. */
class BilleteraAbierta internal constructor(
    private val clave: String,
    /** Red de la billetera: el suelo de altura solo vale para un nodo de la MISMA red. */
    val red: Red,
    val motor: Billetera,
) : AutoCloseable {

    @Volatile
    private var cerrada = false

    override fun close() {
        if (!cerrada) {
            cerrada = true
            Billeteras.soltar(clave)
            motor.destroy()
        }
    }
}
