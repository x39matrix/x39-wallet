# Herramientas del lado del nodo

Scripts que corren **en el nodo del usuario** (o en el ordenador de su operador), no en el
móvil. Solo dependen de `bash`, `openssl` y coreutils; nada se envía a ningún sitio.

## `emparejar.sh`: huella SPKI y paquete de emparejamiento (fase E-2)

Dado el certificado PEM del servidor Electrum, imprime la huella SPKI que la billetera fija
(SHA-256 del `SubjectPublicKeyInfo` en DER, en hex) y genera el **paquete de emparejamiento**
JSON (versión 1) que el móvil importa por fichero o por QR. Formato y reglas: `docs/nodebridge.md`
§5.1.

```sh
# Solo la huella (para pegarla en Ajustes):
herramientas/nodo/emparejar.sh --cert servidor.pem --solo-huella

# Paquete completo, 24 h de vigencia, al fichero paquete.json (permisos 600) y QR en el terminal:
herramientas/nodo/emparejar.sh --cert servidor.pem --red signet \
    --electrum ssl://nodo.ejemplo.local:50002 --nombre "mi nodo" --salida paquete.json --qr

# Con los datos PÚBLICOS del túnel (los usará el túnel propio, E-3; en E-2 solo se guardan):
herramientas/nodo/emparejar.sh --cert servidor.pem --red signet \
    --electrum ssl://nodo.ejemplo.local:50002 --nombre "mi nodo" \
    --tunel-endpoint nodo.ejemplo.local:51820 --tunel-clave-publica 'CLAVE_PUBLICA_DEL_NODO_EN_BASE64=' \
    --ip-permitida A.B.C.D/32 --direccion-movil A.B.C.D/32
```

(Los nombres y direcciones son de ejemplo; sustitúyelos por los tuyos.)

Lo que garantiza la herramienta:

- La huella se calcula igual que en el motor (`rust/src/nodo.rs`): `openssl x509 -pubkey |
  openssl pkey -pubin -outform DER | sha256`. `pruebas/probar.sh` comprueba que el vector
  `pruebas/vector.pem` da exactamente la huella de `rust/src/tests.rs` (`huella_spki_vector_fijo`).
- No emite nada que parezca una clave privada: se niega si el fichero del certificado contiene
  una, aplica a cada valor el mismo filtro que el móvil (PEM privada, clave extendida privada,
  WIF y doce o más palabras seguidas, también con texto alrededor) y comprueba el paquete entero
  (y que no pase de 16 KB) antes de escribirlo. WireGuard, con precisión: una clave privada tiene
  la misma forma que una pública y **no se puede detectar**; la herramienta solo acepta la pública
  por argumento y nunca lee ficheros de WireGuard, pero si el operador le pasara la privada iría al
  paquete como «pública» y el móvil la guardaría como tal (E-2 no la usa como privada ni la
  transmite). `wg pubkey` deriva la pública sin exponer la privada.
- `emitido` y `caduca` salen de un mismo instante (nunca 24 h y 1 s). Vigencia de 1 a 24 h; el
  móvil no acepta más de 24 h.
- El fichero de salida queda con permisos 0600 también si ya existía.
- Regla de los scripts del repo: nunca `echo … | grep -q` bajo `pipefail`; `grep` sobre fichero
  o here-string (ver `android/wallet/LEEME.md`).
- `--qr` usa `qrencode` si está instalado; si no, se importa el fichero en el móvil.

Pruebas: `herramientas/nodo/pruebas/probar.sh`.

## Lo que esta carpeta NO hace (todavía)

No configura el servidor Electrum ni WireGuard en el nodo, ni genera certificados: eso lo hace el
operador con sus herramientas. La herramienta de gestión del nodo (altas y bajas de móviles
emparejados, rotación de paquetes) está en el plan a seis meses, fuera de este repositorio por
ahora.
