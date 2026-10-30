# x39-wallet

Billetera Bitcoin de autocustodia en dos partes: un motor en Rust (`rust/`) y, sobre él,
un módulo Android (`android/`, en construcción) y una librería de conexión al nodo propio
(`nodebridge/`, pendiente). Pensada para redes de prueba mientras madura: por defecto la
API rechaza mainnet y solo una compilación con la feature `mainnet` la permite.

Este README se completará antes de publicar (tabla de madurez, compilación reproducible paso
a paso, límites conocidos). Lo que ya está decidido y probado se recoge en `PRUEBAS.md`.

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
  de los búferes intermedios de UniFFI ni de las vistas; se minimiza así: el mnemónico cruza
  a Rust como `ByteBuffer` directo que Kotlin pone a cero en un `finally`, nunca como
  `String`.
- **Semilla nueva**: al devolver `nueva_semilla`, UniFFI deja dos copias en memoria nativa
  que no se ponen a cero (el `Vec` que se entrega al serializador y el `RustBuffer` que
  Kotlin libera sin borrar); la copia `Zeroizing` del motor sí se borra; Kotlin pone a cero
  su `ByteArray` en cuanto lo ha mostrado y cifrado. Trabajo futuro: implementar `Lower` a
  mano para un tipo `Semilla` que escriba directamente en el búfer de salida (dejaría una
  sola copia, la de UniFFI).
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

## Licencia

Pendiente de decisión.

## NOTA SOBRE USO DE IA

[la redacta Sr. X]
