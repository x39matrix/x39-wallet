#!/usr/bin/env bash
# Herramienta mínima del lado del NODO para el emparejamiento con la billetera (fase E-2).
#
# Dado el certificado del servidor Electrum (PEM), imprime su huella SPKI (SHA-256 del
# SubjectPublicKeyInfo en DER, en hex) y genera el paquete de emparejamiento JSON (versión 1)
# que el móvil importa por fichero o por QR. El paquete solo lleva datos PÚBLICOS: nunca una
# clave privada. El cálculo de la huella es exactamente el del motor (rust/src/nodo.rs); el
# vector fijo de pruebas/ tiene que coincidir siempre (pruebas/probar.sh).
#
# Uso:
#   emparejar.sh --cert servidor.pem --solo-huella
#   emparejar.sh --cert servidor.pem --red signet --electrum ssl://HOST:PUERTO --nombre "mi nodo" \
#                [--horas 24] [--tunel-endpoint HOST:PUERTO --tunel-clave-publica BASE64 \
#                 --ip-permitida A.B.C.D/n ... [--direccion-movil A.B.C.D/32]] [--salida paquete.json] [--qr]
#
#   --qr imprime además el QR en el terminal (hace falta `qrencode`; si no está, lo dice).
#   Con --electrum tcp:// no se escribe huella (no hay certificado que comprobar) y --cert sobra.
#
# Solo depende de bash, openssl y coreutils. Nada se envía a ningún sitio.
#
# Regla de los scripts de este repo: NUNCA «echo … | grep -q» bajo pipefail (grep -q cierra la
# tubería antes de que echo termine y el fallo de echo puede matar el script); grep sobre fichero
# o sobre una here-string (<<<"$VAR").
set -euo pipefail

fallo() { echo "emparejar: $*" >&2; exit 1; }

CERT=""; RED=""; ELECTRUM=""; NOMBRE="mi nodo"; HORAS=24; SALIDA=""; QR=0; SOLO_HUELLA=0
T_ENDPOINT=""; T_CLAVE=""; T_MOVIL=""; T_IPS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --cert) CERT="$2"; shift 2;;
        --red) RED="$2"; shift 2;;
        --electrum) ELECTRUM="$2"; shift 2;;
        --nombre) NOMBRE="$2"; shift 2;;
        --horas) HORAS="$2"; shift 2;;
        --salida) SALIDA="$2"; shift 2;;
        --tunel-endpoint) T_ENDPOINT="$2"; shift 2;;
        --tunel-clave-publica) T_CLAVE="$2"; shift 2;;
        --ip-permitida) T_IPS+=("$2"); shift 2;;
        --direccion-movil) T_MOVIL="$2"; shift 2;;
        --qr) QR=1; shift;;
        --solo-huella) SOLO_HUELLA=1; shift;;
        -h|--help) sed -n '2,20p' "$0"; exit 0;;
        *) fallo "argumento desconocido: $1 (ver --help)";;
    esac
done

command -v openssl >/dev/null || fallo "hace falta openssl"

# ---- huella SPKI: SHA-256 del SubjectPublicKeyInfo en DER (lo mismo que hace el motor) ----
huella_spki() {
    local cert="$1"
    [ -f "$cert" ] || fallo "no existe el certificado $cert"
    grep -q 'PRIVATE KEY' "$cert" && fallo "ese fichero contiene una CLAVE PRIVADA: pasa solo el certificado"
    openssl x509 -in "$cert" -pubkey -noout 2>/dev/null \
        | openssl pkey -pubin -outform DER 2>/dev/null \
        | openssl dgst -sha256 -r | cut -d' ' -f1
}

if [ "$SOLO_HUELLA" = 1 ]; then
    [ -n "$CERT" ] || fallo "--solo-huella necesita --cert"
    h="$(huella_spki "$CERT")"
    [ "${#h}" = 64 ] || fallo "no se pudo calcular la huella (¿es un certificado PEM?)"
    echo "$h"
    exit 0
fi

# ---- validación de lo que va al paquete (lo mismo que exigirá el móvil) -------------------
case "$RED" in signet|regtest|testnet) ;; "") fallo "falta --red (signet, regtest o testnet)";; mainnet) fallo "mainnet no está permitida en esta edición";; *) fallo "red desconocida: $RED";; esac
[ -n "$ELECTRUM" ] || fallo "falta --electrum ssl://HOST:PUERTO (o tcp://)"
case "$ELECTRUM" in ssl://*|tcp://*) ;; *) fallo "--electrum debe empezar por ssl:// o tcp://";; esac
host_puerto() {
    [[ "$1" =~ ^(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?):([0-9]{1,5})$ ]] || fallo "$2 debe ser HOST:PUERTO"
    local puerto="${BASH_REMATCH[3]}"
    [ "$puerto" -ge 1 ] && [ "$puerto" -le 65535 ] || fallo "$2: el puerto debe estar entre 1 y 65535"
}
# Lo mismo que rechaza el móvil DENTRO de un valor: PEM privada, clave extendida privada, WIF,
# doce o más palabras seguidas (semilla). Se aplica a cada valor que va al paquete.
B58='[1-9A-HJ-NP-Za-km-z]'
# Claves con forma reconocible (PEM, extendida privada, WIF): vale sobre un valor o sobre el paquete entero.
sin_claves() {
    local v="$1" campo="$2"
    grep -qiE 'PRIVATE[ _-]?KEY' <<<"$v" && fallo "$campo contiene algo que parece una clave privada; no se emite"
    grep -qE "(^|[^1-9A-HJ-NP-Za-km-z])[xtyzuvYZUV]prv${B58}{100,120}([^1-9A-HJ-NP-Za-km-z]|$)" <<<"$v" && fallo "$campo contiene una clave extendida privada; no se emite"
    grep -qE "(^|[^1-9A-HJ-NP-Za-km-z])[5KLc9]${B58}{50,51}([^1-9A-HJ-NP-Za-km-z]|$)" <<<"$v" && fallo "$campo contiene algo con forma de WIF; no se emite"
    return 0
}
# Todo lo anterior MÁS doce o más palabras alfabéticas seguidas (semilla; la puntuación separa, no
# esconde). Solo sobre cada VALOR por separado: sobre el JSON entero sumaría las etiquetas
# («nombre») al valor y rechazaría un nombre legítimo de once palabras.
sin_secretos() {
    local v="$1" campo="$2"
    sin_claves "$v" "$campo"
    grep -qiE '(^|[^[:alnum:]])[[:alpha:]]+([^[:alnum:]]+[[:alpha:]]+){11,}' <<<"$v" && fallo "$campo tiene doce o más palabras seguidas: parece una semilla; no se emite"
    return 0
}
host_puerto "${ELECTRUM#*://}" "--electrum"
[[ "$HORAS" =~ ^[0-9]+$ ]] && [ "$HORAS" -ge 1 ] && [ "$HORAS" -le 24 ] || fallo "--horas debe estar entre 1 y 24 (el móvil no acepta más de 24 h)"
[ "${#NOMBRE}" -le 64 ] && [ -n "$NOMBRE" ] || fallo "--nombre: entre 1 y 64 caracteres"
case "$NOMBRE" in *'"'*|*'\'*) fallo "--nombre no puede llevar comillas ni barras invertidas";; esac
# Ningún carácter de control (U+0000–U+001F y U+007F) en nada que vaya al paquete.
sin_controles() { [[ "$1" =~ [[:cntrl:]] ]] && fallo "$2 no puede llevar caracteres de control (saltos de línea, tabuladores…)"; return 0; }
sin_controles "$NOMBRE" "--nombre"; sin_controles "$ELECTRUM" "--electrum"; sin_controles "$RED" "--red"
sin_secretos "$NOMBRE" "--nombre"
sin_secretos "$ELECTRUM" "--electrum"

HUELLA=""
case "$ELECTRUM" in
    ssl://*) [ -n "$CERT" ] || fallo "con ssl:// hace falta --cert (el certificado del servidor Electrum)"
             HUELLA="$(huella_spki "$CERT")"; [ "${#HUELLA}" = 64 ] || fallo "no se pudo calcular la huella (¿es un certificado PEM?)";;
    tcp://*) [ -z "$CERT" ] || echo "aviso: con tcp:// no hay certificado que comprobar; --cert se ignora" >&2;;
esac

TUNEL=""
if [ -n "$T_ENDPOINT" ] || [ -n "$T_CLAVE" ] || [ "${#T_IPS[@]}" -gt 0 ] || [ -n "$T_MOVIL" ]; then
    [ -n "$T_ENDPOINT" ] && [ -n "$T_CLAVE" ] && [ "${#T_IPS[@]}" -gt 0 ] || fallo "el túnel necesita --tunel-endpoint, --tunel-clave-publica y al menos una --ip-permitida"
    host_puerto "$T_ENDPOINT" "--tunel-endpoint"
    [[ "$T_CLAVE" =~ ^[A-Za-z0-9+/]{43}=$ ]] || fallo "--tunel-clave-publica debe ser una clave de 32 bytes en base64 (44 caracteres acabados en =)"
    # Una clave privada de WireGuard tiene la misma forma que una pública y NO se puede detectar:
    # esta herramienta solo acepta la pública por argumento y nunca lee ficheros de WireGuard; si
    # aun así se le pasara una privada, iría al paquete como «pública» y el móvil la guardaría como
    # tal (E-2 no la usa como privada ni la transmite). La responsabilidad de pasar la pública es
    # del operador: `wg pubkey` la deriva sin exponer la privada.
    sin_secretos "$T_ENDPOINT" "--tunel-endpoint"
    sin_controles "$T_ENDPOINT" "--tunel-endpoint"; sin_controles "$T_CLAVE" "--tunel-clave-publica"
    # CIDR validado igual que en el móvil: IPv4 con octetos 0..255 y prefijo 0..32; IPv6 literal con
    # una sola «::», grupos de 1 a 4 hex, 8 grupos (o menos con «::»), IPv4 incrustada solo al final,
    # prefijo 0..128. Nunca se resuelve un nombre.
    ipv4_ok() {
        [[ "$1" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
        local o; for o in "${BASH_REMATCH[@]:1:4}"; do [ "$o" -le 255 ] || return 1; done
    }
    ipv6_ok() {
        local s="$1" izq der n=0 g gs
        [[ "$s" == *:::* ]] && return 1
        [[ "$s" =~ ^[0-9A-Fa-f:.]+$ ]] || return 1
        # Sobre la cadena CRUDA, antes de que read -a se coma un grupo vacío final: un «:» simple al
        # principio o al final no es válido («fd00::1:», «1:2:3:4:5:6:7:8:», «:1::»); «::» sí.
        { [[ "$s" == *: ]] && [[ "$s" != *:: ]]; } && return 1
        { [[ "$s" == :* ]] && [[ "$s" != ::* ]]; } && return 1
        grupos() { # imprime el número de grupos de "$1" o falla
            local x="$1" cnt=0 i=0 arr
            [ -z "$x" ] && { echo 0; return 0; }
            IFS=':' read -r -a arr <<<"$x"
            for ((i = 0; i < ${#arr[@]}; i++)); do
                g="${arr[$i]}"; [ -n "$g" ] || return 1
                if [[ "$g" == *.* ]]; then [ "$i" -eq $((${#arr[@]} - 1)) ] && ipv4_ok "$g" || return 1; cnt=$((cnt + 2))
                else [[ "$g" =~ ^[0-9A-Fa-f]{1,4}$ ]] || return 1; cnt=$((cnt + 1)); fi
            done
            echo "$cnt"
        }
        if [[ "$s" == *::* ]]; then
            izq="${s%%::*}"; der="${s#*::}"
            [[ "$der" == *::* ]] && return 1
            [[ "$izq" == *.* ]] && return 1
            local ni nd; ni="$(grupos "$izq")" || return 1; nd="$(grupos "$der")" || return 1
            [ $((ni + nd)) -le 7 ]
        else
            n="$(grupos "$s")" || return 1; [ "$n" -eq 8 ]
        fi
    }
    cidr() {
        local dir="${1%/*}" pref="${1##*/}"
        [[ "$1" == */* ]] && [[ "$pref" =~ ^[0-9]{1,3}$ ]] || fallo "$2: «$1» no es una red válida (A.B.C.D/n o IPv6/n)"
        if [[ "$dir" == *:* ]]; then ipv6_ok "$dir" && [ "$pref" -le 128 ] || fallo "$2: «$1» no es una red IPv6 válida"
        else ipv4_ok "$dir" && [ "$pref" -le 32 ] || fallo "$2: «$1» no es una red IPv4 válida"; fi
    }
    IPS_JSON=""
    for ip in "${T_IPS[@]}"; do sin_controles "$ip" "--ip-permitida"; cidr "$ip" "--ip-permitida"; IPS_JSON="${IPS_JSON:+$IPS_JSON, }\"$ip\""; done
    MOVIL_JSON=""
    if [ -n "$T_MOVIL" ]; then cidr "$T_MOVIL" "--direccion-movil"; MOVIL_JSON=",
    \"direccion_movil\": \"$T_MOVIL\""; fi
    TUNEL=",
  \"tunel\": {
    \"clave_publica_nodo\": \"$T_CLAVE\",
    \"endpoint\": \"$T_ENDPOINT\",
    \"ips_permitidas\": [$IPS_JSON]$MOVIL_JSON
  }"
fi

# emitido y caduca salen de UN mismo instante: dos lecturas del reloj podrían dar 24 h + 1 s y el
# móvil rechazaría un paquete legítimo.
AHORA="$(date -u +%s)"
fecha_utc() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ; }
EMITIDO="$(fecha_utc "$AHORA")"
CADUCA="$(fecha_utc "$((AHORA + HORAS * 3600))")"

HUELLA_JSON=""
[ -n "$HUELLA" ] && HUELLA_JSON=",
  \"huella_spki_sha256\": \"$HUELLA\""

PAQUETE="{
  \"v\": 1,
  \"red\": \"$RED\",
  \"electrum\": \"$ELECTRUM\"$HUELLA_JSON$TUNEL,
  \"nombre\": \"$NOMBRE\",
  \"emitido\": \"$EMITIDO\",
  \"caduca\": \"$CADUCA\"
}"

# Última barrera sobre el paquete entero: nada que parezca una clave privada sale de aquí, y no
# más de 16 KB (el móvil no lee más).
grep -qiE 'private|priv_|secret|xprv|tprv|vprv' <<<"$PAQUETE" && fallo "el paquete contiene algo que parece una clave privada; no se emite"
sin_claves "$PAQUETE" "el paquete"
[ "$(printf '%s\n' "$PAQUETE" | wc -c)" -le 16384 ] || fallo "el paquete supera los 16 KB (contado con el salto de línea final que se escribe); el móvil no lo leería"

[ -n "$HUELLA" ] && echo "huella SPKI (SHA-256): $HUELLA" >&2
echo "válido hasta: $CADUCA (${HORAS} h)" >&2
if [ -n "$SALIDA" ]; then
    # Solo del usuario, también si el fichero ya existía (la umask no cambia permisos previos).
    umask 077
    : > "$SALIDA"
    chmod 0600 "$SALIDA"
    printf '%s\n' "$PAQUETE" > "$SALIDA"
    echo "paquete escrito en $SALIDA" >&2
else
    printf '%s\n' "$PAQUETE"
fi
if [ "$QR" = 1 ]; then
    if command -v qrencode >/dev/null; then
        printf '%s' "$PAQUETE" | qrencode -t ANSIUTF8 -l M
    else
        echo "aviso: falta qrencode; instálalo para ver el QR, o importa el fichero en el móvil" >&2
    fi
fi
