package x39.wallet.android

import x39.wallet.Red

/** Modo del selector de nodo (Ajustes → Nodo): el nodo X-39 por el túnel, o el nodo del usuario. */
enum class ModoNodo { X39, PROPIO }

/**
 * El «Nodo X-39»: el servidor Electrum de X-39, alcanzable SOLO por el X39 Túnel de la app (la
 * VPN WireGuard de la propia app). Es el modo predeterminado: el usuario no escribe nada.
 *
 * La app no lleva ninguna otra dirección ni puerto de ese nodo (ni RPC, ni pool, ni SSH): solo
 * su punto de acceso Electrum, por red. Si el túnel no está activo, la billetera NO se conecta
 * por ninguna otra vía ([NodeBridgeImpl] lo exige en cada comprobación).
 */
object NodoX39 {
    /** Dirección del nodo DENTRO del túnel. */
    const val HOST = "10.39.0.3"
    /** Puerto Electrum (tcp dentro del túnel, que ya va cifrado) por red. */
    const val PUERTO_MAINNET = 50001
    const val PUERTO_SIGNET = 60601
    const val NOMBRE = "Nodo X-39"
    /** Proxy SOCKS5 habitual de Orbot, para «Mi nodo» con un servidor .onion. */
    const val PROXY_POR_DEFECTO = "127.0.0.1:9050"
    /**
     * Textos del modo X-39, uno por diagnóstico del túnel ([DiagnosticoTunel]). Desde la orden 7 el
     * diagnóstico se consulta ANTES de conectar: si dice con certeza que no hay túnel, no se abre
     * ningún socket y se muestra [MENSAJE_TUNEL] de inmediato; si no es concluyente, se intenta la
     * conexión y, si falla, se vuelve a diagnosticar para elegir el texto. Nunca el mismo texto
     * para dos casos.
     */
    /** No hay ninguna red VPN con ruta a 10.39.0.0/24. Corto y sin cola técnica: el detalle va al registro `x39.nodo`. */
    const val MENSAJE_TUNEL = "Túnel apagado. Activa el X39 Túnel; la billetera no se conecta por otra vía."
    /** Hay una red VPN con ruta al nodo, pero la conexión falló igual. */
    const val MENSAJE_NODO_NO_RESPONDE = "túnel activo pero el nodo X-39 no responde"
    /** No se pudo consultar las redes del aparato. */
    const val MENSAJE_TUNEL_DESCONOCIDO = "el nodo X-39 no responde y no se pudo saber si el túnel está activo"

    /**
     * Mensaje para la pantalla tras un fallo de conexión en modo X-39, según el diagnóstico: uno de
     * los tres textos fijos, y NADA más (orden 7, G6: ninguna excepción cruda en pantalla; el motivo
     * técnico va solo al registro `x39.nodo`, sin direcciones).
     */
    fun mensajeFallo(estado: EstadoTunel): String = when (estado) {
        EstadoTunel.SIN_TUNEL -> MENSAJE_TUNEL
        EstadoTunel.CON_RUTA -> MENSAJE_NODO_NO_RESPONDE
        EstadoTunel.DESCONOCIDO -> MENSAJE_TUNEL_DESCONOCIDO
    }

    /** La dirección del nodo dentro del túnel, como octetos (sin resolver ningún nombre). */
    val IP_NODO: ByteArray get() = byteArrayOf(10, 39, 0, 3)
    /** La red del túnel, 10.39.0.0/24, como octetos y longitud de prefijo. */
    val RED_TUNEL: ByteArray get() = byteArrayOf(10, 39, 0, 0)
    const val RED_TUNEL_BITS = 24

    /**
     * ¿El prefijo `prefijo/bits` cubre la dirección `ip`? Comparación de bits (equivalente a
     * `IpPrefix.contains` / `RouteInfo.matches` de Android, pero en Kotlin puro y probada en JVM;
     * nunca por texto). Familias distintas (IPv4 frente a IPv6) no se cubren: `::/0` no cubre una
     * IPv4. `0.0.0.0/0` cubre todo IPv4; `10.39.0.0/24` cubre `10.39.0.3`; `192.168.1.0/24` no.
     */
    fun prefijoCubre(prefijo: ByteArray, bits: Int, ip: ByteArray): Boolean {
        if (prefijo.size != ip.size || bits < 0 || bits > prefijo.size * 8) return false
        var restantes = bits
        for (i in prefijo.indices) {
            if (restantes <= 0) return true
            val mascara = if (restantes >= 8) 0xFF else (0xFF shl (8 - restantes)) and 0xFF
            if ((prefijo[i].toInt() and mascara) != (ip[i].toInt() and mascara)) return false
            restantes -= 8
        }
        return true
    }

    /** ¿Una ruta con destino `destino/bits` lleva al nodo X-39? (10.39.0.0/24 sí; 0.0.0.0/0 sí; 192.168.1.0/24 no.) */
    fun rutaCubreNodo(destino: ByteArray, bits: Int): Boolean = prefijoCubre(destino, bits, IP_NODO)

    /** ¿Una dirección de interfaz está dentro de la red del túnel (10.39.0.0/24)? */
    fun direccionEnRedTunel(ip: ByteArray): Boolean = prefijoCubre(RED_TUNEL, RED_TUNEL_BITS, ip)
    /** Frase fija de la pantalla Seguridad y de Ajustes → Nodo. */
    const val FRASE_PRIVACIDAD = "El servidor Electrum que uses ve las direcciones de tu billetera. Con tu propio nodo no las ve nadie más."

    fun servidor(red: Red): String? = when (red) {
        Red.MAINNET -> "tcp://$HOST:$PUERTO_MAINNET"
        Red.SIGNET -> "tcp://$HOST:$PUERTO_SIGNET"
        else -> null
    }

    /** ¿Hay nodo X-39 para esta red? (No lo hay para regtest ni testnet.) */
    fun disponible(red: Red): Boolean = servidor(red) != null

    /** Configuración de NodeBridge del nodo X-39 para `red`, o `null` si no hay nodo X-39 en esa red. */
    fun config(red: Red): ConfigNodo? = servidor(red)?.let { ConfigNodo(red, it, null, ModoTunel.X39, NOMBRE) }

    /**
     * ¿Dice la app que SU túnel está arriba? Lo pone la app que incluye el módulo. Desde el cierre
     * (orden 6) es solo una PISTA que va al detalle del registro: NUNCA decide si se intenta la
     * conexión ni el mensaje (eso lo hace la detección real de redes VPN). El estado en memoria
     * del túnel de la app puede ir por detrás de la interfaz real (re-alta con la señalización,
     * proceso reiniciado con la VPN del sistema aún levantada), y por eso no sirve de puerta.
     */
    @Volatile var tunelActivo: (() -> Boolean)? = null

    /**
     * Modo que corresponde: el guardado si lo hay; si no (instalación nueva, o anterior a este
     * ajuste), X-39 cuando no había servidor propio o cuando el servidor guardado ERA el de X-39, y
     * «Mi nodo» en otro caso. Sin nodo X-39 para la red, siempre «Mi nodo». Función pura, probada en JVM.
     */
    fun resolverModo(guardado: String?, servidorPropio: String, red: Red): ModoNodo {
        if (!disponible(red)) return ModoNodo.PROPIO
        guardado?.let { g -> ModoNodo.values().firstOrNull { it.name == g } }?.let { return it }
        val s = servidorPropio.trim()
        val url = if (s.isEmpty() || "://" in s) s else "ssl://$s"
        return if (url.isEmpty() || url == servidor(red)) ModoNodo.X39 else ModoNodo.PROPIO
    }
}

/** Texto corto del nodo en uso, para Seguridad y la pantalla principal. */
fun ConfigNodo?.descripcionNodo(): String = when {
    this == null -> "sin configurar"
    tunel == ModoTunel.X39 -> "X-39 por túnel"
    else -> "el tuyo (${host()})"
}
