#!/usr/bin/env bash
# Pruebas de emparejar.sh: la huella del vector tiene que ser la misma que calcula el motor
# (rust/src/tests.rs, huella_spki_vector_fijo) y el paquete generado tiene que ser válido.
set -euo pipefail
AQUI="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
H="$AQUI/../emparejar.sh"
ESPERADA="$(cat "$AQUI/vector.huella")"
fallo() { echo "probar: $*" >&2; exit 1; }
ok() { echo "ok  $*"; }

# 1. Huella del vector: igual que la del motor.
h="$("$H" --cert "$AQUI/vector.pem" --solo-huella)"
[ "$h" = "$ESPERADA" ] || fallo "huella $h ≠ vector del motor $ESPERADA"
ok "huella SPKI del vector = $h (coincide con rust/src/tests.rs)"

# 2. Paquete completo con túnel: JSON válido con los campos de la versión 1 y la huella del vector.
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
"$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://nodo.ejemplo.local:50002 --nombre "nodo de prueba" \
     --tunel-endpoint nodo.ejemplo.local:51820 --tunel-clave-publica "$(printf 'A%.0s' $(seq 43))=" \
     --ip-permitida 127.0.0.1/32 --direccion-movil 127.0.0.2/32 --salida "$TMP/p.json" 2>/dev/null
python3 -I - "$TMP/p.json" "$ESPERADA" <<'PY'
import json, sys, re
p = json.load(open(sys.argv[1]))
assert set(p) == {"v", "red", "electrum", "huella_spki_sha256", "tunel", "nombre", "emitido", "caduca"}, set(p)
assert p["v"] == 1 and p["red"] == "signet" and p["electrum"] == "ssl://nodo.ejemplo.local:50002"
assert p["huella_spki_sha256"] == sys.argv[2]
assert set(p["tunel"]) == {"clave_publica_nodo", "endpoint", "ips_permitidas", "direccion_movil"}
assert p["tunel"]["ips_permitidas"] == ["127.0.0.1/32"]
f = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")
assert f.match(p["emitido"]) and f.match(p["caduca"])
from datetime import datetime
e = datetime.strptime(p["emitido"], "%Y-%m-%dT%H:%M:%SZ"); c = datetime.strptime(p["caduca"], "%Y-%m-%dT%H:%M:%SZ")
assert (c - e).total_seconds() == 24 * 3600, (c - e)
assert not re.search(r"(?i)priv|secret|xprv|tprv", json.dumps(p))
print("ok  paquete v1 válido, caduca a las 24 h, sin nada que parezca una clave privada")
PY
[ "$(stat -c %a "$TMP/p.json")" = "600" ] || fallo "el fichero del paquete debería ser solo del usuario (600)"
ok "fichero del paquete con permisos 600"

# 3. tcp:// sin huella; y negativas: más de 24 h, fichero con clave privada, clave de túnel mal formada, mainnet.
"$H" --red regtest --electrum tcp://127.0.0.1:60401 --nombre banco 2>/dev/null | python3 -I -c 'import json,sys; p=json.load(sys.stdin); assert "huella_spki_sha256" not in p; print("ok  tcp:// sin huella")'
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --horas 25 >/dev/null 2>&1 || fallo "debía negarse a 25 h"
ok "se niega a más de 24 h"
printf -- '-----BEGIN PRIVATE KEY-----\nMA==\n-----END PRIVATE KEY-----\n' > "$TMP/clave.pem"
! "$H" --cert "$TMP/clave.pem" --solo-huella >/dev/null 2>&1 || fallo "debía negarse ante una clave privada"
ok "se niega si el fichero trae una clave privada"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --tunel-endpoint n:51820 --tunel-clave-publica corta --ip-permitida 127.0.0.1/32 >/dev/null 2>&1 || fallo "debía negarse a una clave de túnel mal formada"
ok "se niega a una clave de túnel mal formada"
! "$H" --cert "$AQUI/vector.pem" --red mainnet --electrum ssl://n:1 >/dev/null 2>&1 || fallo "debía negarse a mainnet"
ok "se niega a mainnet"

# 4. Secretos DENTRO de los valores (igual que el móvil): semilla con texto delante, WIF en el nombre
#    y WIF dentro del host; nombre con salto de línea; puerto 0 y 70000; fichero de salida ya existente
#    con permisos abiertos → queda 0600; emitido y caduca exactamente 24 h aparte.
SEMILLA="abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
WIFX="5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --nombre "copia: $SEMILLA" >/dev/null 2>&1 || fallo "debía negarse a una semilla con texto delante"
ok "se niega a una semilla dentro del nombre"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --nombre "nodo $WIFX" >/dev/null 2>&1 || fallo "debía negarse a un WIF en el nombre"
ok "se niega a un WIF dentro del nombre"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum "ssl://$WIFX.ejemplo.local:50002" >/dev/null 2>&1 || fallo "debía negarse a un WIF en el host"
ok "se niega a un WIF dentro del host"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --nombre "$(printf 'uno\ndos')" >/dev/null 2>&1 || fallo "debía negarse a un nombre con salto de línea"
ok "se niega a un nombre con salto de línea"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:0 >/dev/null 2>&1 || fallo "debía negarse al puerto 0"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:70000 >/dev/null 2>&1 || fallo "debía negarse al puerto 70000"
ok "puertos fuera de 1..65535 rechazados"
: > "$TMP/viejo.json"; chmod 644 "$TMP/viejo.json"
"$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --salida "$TMP/viejo.json" 2>/dev/null
[ "$(stat -c %a "$TMP/viejo.json")" = "600" ] || fallo "al sobrescribir, el fichero debería quedar 0600"
ok "al sobrescribir un fichero existente queda 0600"
"$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 2>/dev/null | python3 -I -c '
import json, sys
from datetime import datetime
p = json.load(sys.stdin)
e = datetime.strptime(p["emitido"], "%Y-%m-%dT%H:%M:%SZ"); c = datetime.strptime(p["caduca"], "%Y-%m-%dT%H:%M:%SZ")
assert (c - e).total_seconds() == 86400, (c - e)
print("ok  emitido y caduca exactamente 24 h aparte (un solo instante)")'

# 5. Segunda vuelta: controles distintos de LF/CR/TAB; CIDR como en el móvil; 16 KB contando el salto final.
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --nombre "$(printf 'uno\x01dos')" >/dev/null 2>&1 || fallo "debía negarse a un carácter de control U+0001"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --nombre "$(printf 'uno\x7fdos')" >/dev/null 2>&1 || fallo "debía negarse a U+007F"
ok "se niega a cualquier carácter de control en el nombre"
T="--tunel-endpoint n:51820 --tunel-clave-publica $(printf 'A%.0s' $(seq 43))="
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 $T --ip-permitida 999.999.999.999/99 >/dev/null 2>&1 || fallo "debía negarse a 999.999.999.999/99"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 $T --ip-permitida ::::/64 >/dev/null 2>&1 || fallo "debía negarse a ::::/64"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 $T --ip-permitida 127.0.0.1/33 >/dev/null 2>&1 || fallo "debía negarse al prefijo 33"
"$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 $T --ip-permitida ::ffff:192.0.2.1/128 --ip-permitida fd00::/64 --ip-permitida 127.0.0.1/32 >/dev/null 2>&1 || fallo "debía aceptar ::ffff:192.0.2.1/128, fd00::/64 y 127.0.0.1/32"
ok "CIDR validado como en el móvil (999.999.999.999/99 y ::::/64 rechazados; IPv4 incrustada aceptada)"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 $T --ip-permitida fd00::1:/64 >/dev/null 2>&1 || fallo "debía negarse a fd00::1:/64"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 $T --ip-permitida 1:2:3:4:5:6:7:8:/64 >/dev/null 2>&1 || fallo "debía negarse a 1:2:3:4:5:6:7:8:/64"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 $T --ip-permitida :1::/64 >/dev/null 2>&1 || fallo "debía negarse a :1::/64"
"$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 $T --ip-permitida fd00::/64 --ip-permitida ::1/128 >/dev/null 2>&1 || fallo "debía aceptar fd00::/64 y ::1/128"
ok "IPv6 con «:» simple al final o al principio rechazada; «::» final o inicial aceptada"
SEM2="zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --nombre "copia:$SEM2" >/dev/null 2>&1 || fallo "debía negarse a «copia:» pegado a la semilla"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --nombre "$SEM2." >/dev/null 2>&1 || fallo "debía negarse a la semilla con punto"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --nombre "($SEM2)" >/dev/null 2>&1 || fallo "debía negarse a la semilla entre paréntesis"
ok "semilla pegada a puntuación rechazada también en el productor"
"$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --nombre "el nodo de mi familia para las pruebas locales en casa" >/dev/null 2>&1 || fallo "un nombre legítimo de once palabras debía aceptarse (la etiqueta «nombre» del JSON no cuenta)"
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 --nombre "el nodo de mi familia para las pruebas locales en casa ya" >/dev/null 2>&1 || fallo "doce palabras debían rechazarse"
ok "el filtro de palabras mira cada valor por separado: once palabras pasan, doce no"
# Un nombre de 64 caracteres y muchas IPs no llegan a 16 KB; forzamos el límite con IPs repetidas.
MUCHAS=(); for i in $(seq 1 1100); do MUCHAS+=(--ip-permitida 127.0.0.1/32); done
! "$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 $T "${MUCHAS[@]}" >/dev/null 2>&1 || fallo "debía negarse por tamaño (> 16 KB)"
POCAS=(); for i in $(seq 1 950); do POCAS+=(--ip-permitida 127.0.0.1/32); done
SAL="$("$H" --cert "$AQUI/vector.pem" --red signet --electrum ssl://n:1 $T "${POCAS[@]}" 2>/dev/null)" || fallo "con 950 IPs debía caber"
[ "$(printf '%s\n' "$SAL" | wc -c)" -le 16384 ] || fallo "lo emitido supera 16 KB contando el salto final"
ok "límite de 16 KB contado con el salto de línea final"
echo "todas las pruebas de emparejar.sh pasaron"
