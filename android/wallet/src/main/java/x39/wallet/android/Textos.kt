package x39.wallet.android

import android.content.Context
import x39.wallet.R

/**
 * Traducción, SOLO al pintar, de los mensajes internos de la lógica (orden 9a, I1).
 *
 * La lógica (`NodeBridge`, `WalletRepository`, `Emparejamiento`, `JsonEstricto`, `NodoX39`…) sigue
 * produciendo sus textos castellanos de siempre: son claves estables, las comprueban las pruebas JVM
 * y las lee el propietario en los registros. Esta tabla los reconoce (texto exacto o con huecos) y
 * devuelve el recurso traducido con los mismos datos; lo que no reconoce (un mensaje crudo del motor,
 * un detalle técnico) vuelve tal cual. Un mensaje compuesto («el nodo no está disponible: …») traduce
 * también su parte interior (`anidados`). `TextosTest` comprueba que cada entrada tiene recurso en los
 * cinco idiomas y que en castellano la ida y vuelta devuelve exactamente el mensaje original.
 */
object Textos {

    class Entrada(val patron: Regex, val recurso: Int, val anidados: Set<Int> = emptySet())

    private fun fijo(texto: String, recurso: Int) = Entrada(Regex(Regex.escape(texto)), recurso)

    /** `partes` alterna texto literal y hueco `(.*)`: `con(R.x, "el tuyo (", ")")` reconoce «el tuyo (host)». */
    private fun con(recurso: Int, vararg partes: String, anidados: Set<Int> = emptySet()) =
        Entrada(Regex(partes.joinToString("(.*)") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL), recurso, anidados)

    val tabla: List<Entrada> = listOf(
        // ConfigNodo.invalida()
        fijo("sin servidor Electrum", R.string.x39w_m_sin_servidor),
        fijo("el servidor debe empezar por ssl:// o tcp://", R.string.x39w_m_esquema),
        fijo("el servidor debe ser host:puerto (puerto entre 1 y 65535)", R.string.x39w_m_host_puerto),
        fijo("la huella solo se comprueba con ssl://", R.string.x39w_m_huella_solo_ssl),
        fijo("un servidor .onion solo se alcanza por un proxy SOCKS5 (Orbot): activa el proxy", R.string.x39w_m_onion_proxy),
        fijo("el proxy SOCKS5 debe ser IP:puerto (por ejemplo 127.0.0.1:9050), no un nombre: un nombre habría que resolverlo en este aparato", R.string.x39w_m_proxy_ip),
        fijo("el túnel propio todavía no está disponible", R.string.x39w_m_tunel_propio_no),
        // EstadoNodo.describir() y descripcionNodo()
        fijo("sin configurar", R.string.x39w_m_sin_configurar),
        fijo("conectando", R.string.x39w_m_conectando),
        fijo("sano", R.string.x39w_m_sano),
        con(R.string.x39w_m_otra_red, "es de otra red: ", ""),
        con(R.string.x39w_m_huella_no_esperada, "la huella del certificado no es la esperada: ", ""),
        fijo("X-39 por túnel", R.string.x39w_m_x39_por_tunel),
        con(R.string.x39w_m_el_tuyo, "el tuyo (", ")"),
        // mensajeLlano: explicación fija + original entre paréntesis
        con(R.string.x39w_m_ll_rechazada, "nadie responde en esa dirección y puerto (", ")"),
        con(R.string.x39w_m_ll_tiempo, "sin respuesta a tiempo: comprueba la dirección, el túnel o el proxy (", ")"),
        con(R.string.x39w_m_ll_nombre, "no se encuentra ese nombre de servidor (", ")"),
        con(R.string.x39w_m_ll_inalcanzable, "red inalcanzable: sin conexión, o el túnel no está activo (", ")"),
        con(R.string.x39w_m_ll_corto, "el servidor cortó la conexión: ¿seguro que ese puerto habla Electrum? (", ")"),
        con(R.string.x39w_m_ll_certificado, "el certificado del servidor no lo reconoce ninguna autoridad: si es tu propio certificado, activa «certificado propio» (", ")"),
        con(R.string.x39w_m_ll_tls, "fallo del cifrado TLS: ¿seguro que ese puerto habla ssl://? (", ")"),
        // altura y otros de NodeBridge
        con(R.string.x39w_m_altura, "ese nodo va por detrás de lo ya visto: bloque ", " frente a ", ""),
        fijo("túnel externo no activo: se exige una VPN activa en el aparato", R.string.x39w_m_tunel_externo),
        con(R.string.x39w_m_no_guardar_config, "no se pudo guardar la configuración del nodo: ", "", anidados = setOf(0)),
        fijo("la configuración del nodo cambió durante la comprobación: no se guarda", R.string.x39w_m_config_cambio),
        fijo("red no permitida en esta edición", R.string.x39w_m_red_no_permitida),
        con(R.string.x39w_m_red_no_permitida_x, "red no permitida en esta edición: ", ""),
        fijo("no se pudo guardar la configuración del nodo en las preferencias (el disco no aceptó la escritura; se conserva la anterior)", R.string.x39w_m_prefs_no_guardado),
        // NodoX39: los tres textos del túnel
        fijo(NodoX39.MENSAJE_TUNEL, R.string.x39w_m_tunel_apagado),
        fijo(NodoX39.MENSAJE_NODO_NO_RESPONDE, R.string.x39w_m_nodo_no_responde),
        fijo(NodoX39.MENSAJE_TUNEL_DESCONOCIDO, R.string.x39w_m_tunel_desconocido),
        // WalletRepository.mensaje y conRed
        con(R.string.x39w_m_nodo_no_disponible, "el nodo no está disponible: ", "", anidados = setOf(0)),
        fijo("no hay nodo configurado: elígelo en Ajustes → Nodo", R.string.x39w_m_sin_nodo_configurado),
        fijo("la billetera no está abierta", R.string.x39w_m_billetera_no_abierta),
        fijo("falta el escaneo completo tras restaurar: configura el servidor y sincroniza antes de enviar", R.string.x39w_m_falta_escaneo),
        fijo("fondos insuficientes para el envío y la comisión", R.string.x39w_m_fondos),
        fijo("dirección inválida o de otra red", R.string.x39w_m_direccion_invalida),
        con(R.string.x39w_m_servidor_otra_red, "ese servidor es de otra red: ", ""),
        con(R.string.x39w_m_sin_respuesta, "sin respuesta del servidor: ", "", anidados = setOf(0)),
        fijo("hay un envío sin resolver; reintenta o cancela antes", R.string.x39w_m_envio_sin_resolver),
        con(R.string.x39w_m_revision_no_coincide, "la revisión no coincide con lo que se iba a firmar: ", ""),
        fijo("la revisión caducó; prepara el envío otra vez", R.string.x39w_m_revision_caduco),
        fijo("la semilla no es la de esta billetera", R.string.x39w_m_semilla_otra),
        fijo("la billetera exige Android 6 o superior", R.string.x39w_m_android6),
        con(R.string.x39w_m_biblioteca_redes, "biblioteca nativa con redes no permitidas: ", ""),
        // AlmacenSemilla, Mnemonico, Billeteras, Autenticacion, PerfilBilletera
        fijo("la clave de la billetera ha quedado invalidada; restaura desde la semilla", R.string.x39w_m_clave_invalidada),
        fijo("no se pudo guardar la semilla cifrada", R.string.x39w_m_semilla_no_guardada),
        fijo("no hay semilla guardada", R.string.x39w_m_sin_semilla),
        fijo("fichero de semilla ilegible", R.string.x39w_m_semilla_ilegible),
        fijo("IV de GCM inesperado", R.string.x39w_m_iv),
        fijo("ya abierta en este proceso", R.string.x39w_m_ya_abierta),
        fijo("sin CryptoObject", R.string.x39w_m_sin_cryptoobject),
        con(R.string.x39w_m_marcador, "marcador de redes desconocido: ", ""),
        fijo("el mnemónico ya se ha borrado", R.string.x39w_m_mn_borrado),
        fijo("la semilla ya se ha borrado", R.string.x39w_m_semilla_borrada),
        fijo("mnemónico no codificable en UTF-8", R.string.x39w_m_mn_utf8),
        fijo("hace falta un ByteBuffer directo", R.string.x39w_m_bytebuffer),
        fijo("longitud de semilla no válida", R.string.x39w_m_longitud_semilla),
        fijo("(sin huella: tcp://)", R.string.x39w_m_sin_huella_tcp),
        // EmparejarActivity
        con(R.string.x39w_m_fichero_grande, "el fichero es demasiado grande para ser un paquete (más de ", " KB)"),
        fijo("no se pudo leer el fichero", R.string.x39w_m_no_leer_fichero),
        fijo("no se pudo abrir el fichero", R.string.x39w_m_no_abrir_fichero),
        fijo("paquete inválido", R.string.x39w_m_paquete_invalido),
        // Emparejamiento.analizar
        con(R.string.x39w_m_e_campo_privado, "el paquete trae un campo que parece una clave privada («", "»): se rechaza entero"),
        con(R.string.x39w_m_e_valor_privado, "el paquete trae un valor que parece una clave privada o una semilla (en ", "): se rechaza entero"),
        con(R.string.x39w_m_e_falta_campo, "falta el campo «", "»"),
        con(R.string.x39w_m_e_campo_texto, "el campo «", "» debe ser texto"),
        con(R.string.x39w_m_e_fecha_forma, "la fecha «", "» debe tener exactamente la forma 2026-01-31T12:00:00Z (UTC)"),
        con(R.string.x39w_m_e_fecha_calendario, "la fecha «", "» no es una fecha válida del calendario"),
        con(R.string.x39w_m_e_ipv6_puerto, "«", "» debe ser [IPv6]:puerto"),
        con(R.string.x39w_m_e_ipv6_invalida, "«", "»: «", "» no es una dirección IPv6 válida"),
        con(R.string.x39w_m_e_host_puerto, "«", "» debe ser host:puerto"),
        con(R.string.x39w_m_e_ipv6_corchetes, "«", "»: una dirección IPv6 va entre corchetes: [IPv6]:puerto"),
        con(R.string.x39w_m_e_ipv4_invalida, "«", "»: «", "» no es una dirección IPv4 válida"),
        con(R.string.x39w_m_e_host_invalido, "«", "»: «", "» no es un nombre de host válido"),
        con(R.string.x39w_m_e_host_privado, "«", "»: el host contiene algo que parece una clave privada: se rechaza entero"),
        con(R.string.x39w_m_e_puerto_rango, "«", "»: el puerto debe estar entre 1 y 65535"),
        con(R.string.x39w_m_e_tunel_campo_desconocido, "campo desconocido en «tunel»: «", "»"),
        fijo("«tunel.clave_publica_nodo» debe ser una clave pública de 32 bytes en base64", R.string.x39w_m_e_tunel_clave),
        fijo("«tunel.ips_permitidas» debe ser una lista", R.string.x39w_m_e_tunel_ips_lista),
        fijo("«tunel.ips_permitidas» está vacía", R.string.x39w_m_e_tunel_ips_vacia),
        fijo("«tunel.ips_permitidas» debe contener textos", R.string.x39w_m_e_tunel_ips_textos),
        con(R.string.x39w_m_e_tunel_ips_red, "«tunel.ips_permitidas»: «", "» no es una red válida (forma A.B.C.D/n o IPv6/n)"),
        fijo("«tunel.direccion_movil» no es una dirección válida (forma A.B.C.D/32)", R.string.x39w_m_e_tunel_movil),
        con(R.string.x39w_m_e_grande, "el paquete es demasiado grande (más de ", " KB)"),
        con(R.string.x39w_m_e_json_invalido_x, "no es un paquete de emparejamiento (JSON inválido: ", ")", anidados = setOf(0)),
        fijo("no es un paquete de emparejamiento (JSON inválido)", R.string.x39w_m_e_json_invalido),
        fijo("no es un objeto", R.string.x39w_m_j_no_objeto),
        fijo("texto sobrante", R.string.x39w_m_j_sobrante),
        con(R.string.x39w_m_e_campo_desconocido, "campo desconocido: «", "»"),
        fijo("falta la versión del paquete («v»)", R.string.x39w_m_e_falta_version),
        con(R.string.x39w_m_e_version, "versión del paquete desconocida (", "); esta billetera entiende la ", ""),
        con(R.string.x39w_m_e_red_desconocida, "red desconocida: «", "»"),
        con(R.string.x39w_m_e_red_distinta, "el paquete es de ", " y la billetera es de ", ""),
        fijo("«electrum» debe empezar por ssl:// o tcp://", R.string.x39w_m_e_electrum_esquema),
        fijo("falta la huella SPKI del certificado (obligatoria con ssl://)", R.string.x39w_m_e_falta_huella),
        fijo("la huella SPKI debe tener 64 caracteres hexadecimales", R.string.x39w_m_e_huella_64),
        fijo("con tcp:// no hay certificado: el paquete no debe traer huella", R.string.x39w_m_e_tcp_sin_huella),
        fijo("«tunel» debe ser un objeto", R.string.x39w_m_e_tunel_objeto),
        fijo("el nombre del nodo está vacío", R.string.x39w_m_e_nombre_vacio),
        fijo("el nombre del nodo no puede tener saltos de línea ni caracteres de control", R.string.x39w_m_e_nombre_control),
        con(R.string.x39w_m_e_nombre_largo, "el nombre del nodo es demasiado largo (máximo ", " caracteres)"),
        fijo("«caduca» debe ser posterior a «emitido»", R.string.x39w_m_e_caduca_posterior),
        fijo("el paquete pretende valer más de 24 h: no se acepta", R.string.x39w_m_e_24h),
        con(R.string.x39w_m_e_futuro, "el paquete está emitido más de 5 min en el futuro (", "; se toleran 5 min de desajuste de reloj): revisa el reloj del nodo o del móvil"),
        con(R.string.x39w_m_e_caducado, "el paquete caducó el ", ": pide uno nuevo en el nodo"),
        // JsonEstricto (dentro de «JSON inválido: …»)
        fijo("texto vacío", R.string.x39w_m_j_vacio),
        fijo("el paquete debe ser un objeto JSON", R.string.x39w_m_j_objeto_json),
        con(R.string.x39w_m_j_sobrante_pos, "texto sobrante tras el JSON (posición ", ")"),
        fijo("fin inesperado del texto", R.string.x39w_m_j_fin),
        fijo("anidamiento excesivo", R.string.x39w_m_j_anidamiento),
        con(R.string.x39w_m_j_caracter, "carácter inesperado «", "» en la posición ", ""),
        con(R.string.x39w_m_j_clave_comillas, "se esperaba una clave entre comillas dobles en la posición ", ""),
        con(R.string.x39w_m_j_clave_duplicada, "clave duplicada: «", "»"),
        con(R.string.x39w_m_j_dos_puntos, "se esperaba «:» en la posición ", ""),
        con(R.string.x39w_m_j_coma_llave, "se esperaba «,» o «}» en la posición ", ""),
        con(R.string.x39w_m_j_coma_corchete, "se esperaba «,» o «]» en la posición ", ""),
        fijo("cadena sin cerrar", R.string.x39w_m_j_cadena),
        fijo("escape incompleto", R.string.x39w_m_j_escape_incompleto),
        fijo("escape \\u incompleto", R.string.x39w_m_j_escape_u_incompleto),
        fijo("escape \\u inválido", R.string.x39w_m_j_escape_u_invalido),
        con(R.string.x39w_m_j_escape_invalido, "escape inválido «", "»"),
        fijo("carácter de control sin escapar dentro de una cadena", R.string.x39w_m_j_control),
        con(R.string.x39w_m_j_literal, "literal inválido en la posición ", ""),
        con(R.string.x39w_m_j_numero, "número inválido en la posición ", ""),
        fijo("número inválido (fracción vacía)", R.string.x39w_m_j_fraccion),
        fijo("número inválido (exponente vacío)", R.string.x39w_m_j_exponente),
    )

    /**
     * Traduce `interno` con `obtener(recurso, argumentos)`; si ningún patrón lo reconoce, lo devuelve
     * tal cual. Pura: la pantalla pasa `getString`; las pruebas JVM, los `strings.xml` leídos del disco.
     */
    fun traducir(interno: String, obtener: (Int, List<String>) -> String): String {
        for (e in tabla) {
            val m = e.patron.matchEntire(interno) ?: continue
            val args = m.groupValues.drop(1).mapIndexed { i, g -> if (i in e.anidados) traducir(g, obtener) else g }
            return obtener(e.recurso, args)
        }
        return interno
    }
}

/** Texto de pantalla de un mensaje interno (o `null`), en el idioma de la app. */
fun Context.textoPantalla(interno: String?): String =
    Textos.traducir(interno ?: "") { id, args -> getString(id, *args.toTypedArray()) }
