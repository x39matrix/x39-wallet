# x39-wallet

> **Qué es.** La pieza de dinero del ecosistema [X39 Evidencia](https://github.com/x39matrix/x39-android): una
> billetera Bitcoin de autocustodia que aplica el mismo principio que Evidencia, comprobar sin confiar, al dinero.
> Va dentro de la app de llamadas y mensajes [X39 Enlace](https://github.com/x39matrix/x39-enlace) y está pensada
> para periodistas, activistas y ciudadanos que necesitan recibir y mover bitcoin cuando no hay otro medio de pago,
> desde el mismo teléfono con el que sellan sus pruebas y sin depender de un intermediario. Sincroniza contra el
> nodo que el usuario elija (el de X39 por el túnel de la app, o el suyo propio, también por Tor).
>
> **What it is.** The money piece of the [X39 Evidencia](https://github.com/x39matrix/x39-android) ecosystem: a
> non-custodial Bitcoin wallet that applies Evidencia's principle, verify without trusting, to money. It ships inside
> the calls-and-messaging app [X39 Enlace](https://github.com/x39matrix/x39-enlace) and is meant for journalists,
> activists and citizens who need to receive and move bitcoin when no other payment rail is available, from the same
> phone they seal their evidence with and without an intermediary. It syncs against the node the user chooses (X39's,
> through the app's tunnel, or their own, also over Tor). An English summary is at the end of this file.

Billetera Bitcoin de autocustodia en dos partes: un motor en Rust (`rust/`) y, sobre él,
un módulo Android (`android/wallet`) que se integra en la app de llamadas x39 ([x39-enlace](https://github.com/x39matrix/x39-enlace)). La capa que
valida y vigila el nodo (`NodeBridge`) vive dentro del módulo Android; su formato de
emparejamiento está en `docs/nodebridge.md`. Por defecto la API rechaza mainnet y solo una
compilación con la feature `mainnet` la permite; la biblioteca publicada se compila con ella.

Lo que está decidido y probado, con sus comandos y salidas, se recoge en `PRUEBAS.md`; la
compilación reproducible, en `REPRODUCIBILITY.md`; cómo salen las direcciones y qué cruza
hacia el nodo, en `docs/arbol-derivacion.html`.

## Qué hace el motor

- Semilla BIP39 (12 palabras, passphrase fija a vacía en esta versión) y direcciones BIP84.
- Estado persistido con BDK (solo descriptores públicos; la semilla no se escribe nunca).
- Servidor Electrum elegido por el usuario, con comprobación del `genesis_hash` en cada
  conexión: un servidor de otra red se rechaza.
- Envío en dos pasos: preparar (sin semilla) → revisar → firmar (re-verificando lo revisado)
  → difundir. Lo firmado queda gastado localmente antes de difundir; ningún rechazo del
  servidor libera monedas; cancelar algo que ya tocó un servidor es un reemplazo RBF a una
  dirección propia.

## Límites conocidos

- **Billetera caliente**: la semilla se maneja en el aparato.
- **Borrado de memoria**: lo que el motor controla (semilla de 64 bytes, descriptores con
  clave privada, mnemónico analizado) va en `Zeroizing`. Las copias internas de miniscript y
  secp256k1 no se pueden borrar desde el motor. En Android no se puede garantizar el borrado
  de las copias internas de la máquina virtual ni de las vistas; el mnemónico cruza
  a Rust como `ByteBuffer` directo que Kotlin pone a cero en un `finally`, nunca como
  `String`.
- **Semilla nueva**: `nueva_semilla` devuelve un objeto opaco cuyo bloque original está
  en `Zeroizing`. UniFFI transmite un identificador, no las palabras serializadas. Kotlin
  recoge los bytes con retornos escalares y, en un `finally`, pone a cero el bloque nativo
  con `borrar()` y cierra el objeto; incluso si la lectura se interrumpe. No existe un
  `RustBuffer` con la semilla ni un `Vec` temporal entregado al serializador. Cerrar el
  último identificador también activa `Zeroizing`. `SemillaNueva` borra después el
  `ByteArray` Kotlin al cerrar; ante un error durante la recogida lo borra antes de propagar
  el error. Esto cubre los bloques controlados, no registros del procesador, copias de la
  máquina virtual, vistas ni otras bibliotecas.
- **Clave del Keystore**: la semilla se cifra con una clave que exige autenticación por
  operación (biometría fuerte o credencial del aparato en Android 11+). Con la credencial del
  aparato admitida, un cambio de biometría no invalida la clave; la invalidación que se
  detecta cubre la pérdida o el reinicio del almacén de claves, y se recupera con las 12
  palabras sin borrar el estado de la billetera. Opción futura: ajuste «solo biometría».
- **Selector de nodo** (Ajustes → Nodo): «Nodo X-39» (predeterminado: el servidor Electrum de
  X-39, solo por el túnel de la app, sin escribir nada; sin túnel activo no se conecta por otra
  vía) o «Mi nodo» (el servidor Electrum del usuario por `tcp://`, `ssl://` o `.onion` con proxy
  SOCKS5). Un servidor solo se adopta tras comprobar que responde y que su `genesis_hash` es el de
  la red de la billetera; si no, no se toca nada. Cambiar de servidor no borra nada: la cadena es
  la misma y basta sincronizar. La app solo habla Electrum: no lleva ninguna otra dirección ni
  puerto del nodo X-39.
- **Nodo propio con huella**: la conexión TLS al nodo del usuario fija la CLAVE PÚBLICA de su
  certificado (huella SPKI, SHA-256 del `SubjectPublicKeyInfo`), no el certificado entero; con
  huella no se valida CA, ni nombre, ni caducidad. La huella viaja en todas las operaciones de
  red. Sin huella, `ssl://` se valida contra las raíces públicas (certificado de una autoridad
  reconocida, como cualquier cliente TLS). La huella se fija solo si el usuario activa
  expresamente «certificado propio»: entonces el motor la lee del servidor (`huella_servidor`,
  solo el apretón TLS, sin hablar Electrum), la app la muestra con aviso y la fija.
- **Tor**: un servidor `.onion` exige un proxy SOCKS5 (Orbot, `127.0.0.1:9050` por defecto,
  configurable, siempre como dirección IP y no como nombre); el motor pide CONNECT al proxy con el
  nombre tal cual y nunca resuelve `.onion` por su cuenta; con proxy, este proceso no resuelve
  ningún nombre (ni el del servidor ni el del proxy); sin proxy, ninguna operación abre socket.
- **Emparejamiento con el nodo propio**: el nodo genera un paquete JSON (versión 1) con sus datos
  PÚBLICOS (red, servidor, huella SPKI, nombre, caducidad de 24 h y, opcionalmente, los datos
  públicos del túnel); el móvil lo importa por fichero o QR, lo valida entero (JSON estricto sin
  claves duplicadas; cualquier cosa que parezca una clave privada lo rechaza), lo muestra y, tras
  confirmación, lo revalida y lo guarda **solo si el nodo responde sano**. Una clave privada de
  WireGuard tiene la misma forma que una pública y no se puede detectar: E-2 no la usa como
  privada ni la transmite. Herramienta del nodo en `herramientas/nodo/`; formato en
  `docs/nodebridge.md` §5.1.
- **Una sola instancia por fichero**: el motor rechaza abrir dos veces el mismo fichero de
  estado en un proceso; el lado Android debe mantener una sola `Billetera` por fichero. La
  protección es por ruta canónica dentro del mismo proceso; no cubre otros procesos. En
  Android la ruta es privada y fija.
- Un reemplazo RBF no puede reemplazarse a su vez todavía. Una billetera restaurada solo
  desde la semilla no conoce los envíos firmados o inciertos anteriores hasta que el
  servidor los muestre: restaurar exige escanear antes de nada.

## Compilación reproducible de la biblioteca Android

`android/build-native.sh` compila `libx39wallet.so` (solo arm64-v8a) con versiones fijadas
(NDK 30.0.16248370, Rust 1.98.1, cargo-ndk 4.1.2, uniffi 0.32.2, API 21), genera los
bindings Kotlin desde esa misma biblioteca y comprueba arquitectura, alineación de 16 KB,
ausencia de rutas de la máquina y el marcador de redes permitidas. `--verificar` recompila
en un directorio limpio y exige la misma suma que `android/build-native.lock`.

## Dónde está en producción y cómo comprobarlo

La biblioteca que sale de este repositorio viaja dentro del APK publicado de la app de llamadas, y cada eslabón
se puede comprobar sin pedir permiso a nadie:

| Eslabón | Dato |
|---|---|
| Fuente | este repositorio, etiqueta `v1.1.0-mainnet`; `android/build-native.lock` fija la suma que `build-native.sh --verificar` debe reproducir |
| Biblioteca nativa (`libx39wallet.so`, SHA-256) | `244eaa581adbaa822f724c44f5922c627fb96a1488ceca4eaf4580f26bff5a43` |
| APK que la contiene | `org.x39matrix.llamadas` 1.0.8 (46), SHA-256 `34f382f7a5b0d0645281502afd6e9c30af74cf72e84943bb409bc74369ba6225`, firmado con el certificado `8d46f850509ebfd4778156195c14bb69719dd09f799a4fb661b940e7e2b22fbe` (CN=x39matrix) |
| Dónde se descarga | repositorio F-Droid propio `https://mining.x39matrix.org/app/fdroid/repo` (huella `8d46f850…`), ficha con hashes y pruebas |
| Prueba de existencia | OpenTimestamps, primer bloque 975174 (09/11/2026), `.ots` junto al APK; sello de tiempo cualificado (RFC 3161, ANF AC Qualified TSA, eIDAS) del 10/11/2026, serie `0xC04082135132D07DCC478923CCBEC7`; ambos verificados contra nuestro propio nodo Bitcoin Core |

```bash
unzip -p org.x39matrix.llamadas_46.apk lib/arm64-v8a/libx39wallet.so | sha256sum   # 244eaa58…
apksigner verify --print-certs org.x39matrix.llamadas_46.apk                      # CN=x39matrix, 8d46f850…
ots verify org.x39matrix.llamadas_46.apk.ots                                        # con un nodo propio
```

Lo que esto prueba: que la biblioteca del APK es la que sale de este código y que el APK existía en esa fecha.
Lo que no prueba: que el software sea seguro. La auditoría independiente está pendiente.

## Licencia

Software libre distribuido bajo doble licencia `MIT OR Apache-2.0` (véase `LICENSE`).

---

## English summary

**x39-wallet** is a non-custodial Bitcoin wallet in two parts: a Rust engine (`rust/`, BDK underneath) and an
Android module (`android/wallet`) that ships inside the X39 calls-and-messaging app
([x39-enlace](https://github.com/x39matrix/x39-enlace)). The node-pairing layer (`NodeBridge`) lives in the Android
module; its format is documented in `docs/nodebridge.md`. Mainnet is refused by default and only a build with the
`mainnet` feature enables it; the published library is built with it.

What the engine does: BIP39 seed (12 words) and BIP84 addresses; BDK state with public descriptors only (the seed is
never written); user-chosen Electrum server with `genesis_hash` check on every connection; two-step sending
(prepare without the seed → review → sign re-verifying what was reviewed → broadcast), with RBF replacement to an own
address as the only way to cancel. Node selector: X39's Electrum server through the app's tunnel, or the user's own
node over `tcp://`, `ssl://` (with optional SPKI pinning) or `.onion` through a SOCKS5 proxy; pairing with the user's
node through a signed public JSON package (file or QR) that is adopted only if the node answers healthy.

Known limits, stated plainly: it is a hot wallet (the seed is handled on the device); memory zeroing covers what the
engine controls, not the copies made by miniscript, secp256k1 or the Android runtime; an RBF replacement cannot yet be
replaced again; a wallet restored from the seed alone must scan before anything else. No independent audit yet.

Where it runs and how to check it: the library `libx39wallet.so` (SHA-256 `244eaa58…5a43`) ships inside the published
APK `org.x39matrix.llamadas` 1.0.8 (46), SHA-256 `34f382f7…6225`, signed with certificate `8d46f850…2fbe`, distributed
from our own F-Droid repository (`https://mining.x39matrix.org/app/fdroid/repo`), anchored in Bitcoin (first block
975174) and time-stamped by a qualified EU trust service, all verified against our own Bitcoin Core node. Tests and
their outputs are in `PRUEBAS.md`; the reproducible build in `REPRODUCIBILITY.md`; how addresses are derived and
what crosses to the node in `docs/arbol-derivacion.html`.

License: `MIT OR Apache-2.0` (see `LICENSE`). Contact: security@x39matrix.org.
