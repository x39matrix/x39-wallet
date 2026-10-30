# Módulo Android `:wallet`

Librería Android sobre el motor Rust (`libx39wallet.so`, arm64-v8a, bindings UniFFI).
La biblioteca nativa y `x39/wallet/x39wallet.kt` los genera `android/build-native.sh`; no
están en git (su suma está en `android/build-native.lock`).

## Contrato del mnemónico (igual que `rust/src/ffi.rs`)

- `Mnemonico`: `ByteBuffer` DIRECTO, `position() == 0`, `limit()` exacto; se entrega a Rust
  con `usar { }` y se pone a cero con `close()` (en un `finally` o `use { }`). Entrada como
  `CharArray` o `ByteArray`, que se borran. Nunca `String` ni `CharSequence`.
- `SemillaNueva`: la semilla que devuelve `nuevaSemilla()` (un `ByteArray`); se muestra una
  vez como `CharArray`, se cifra y se borra. Limitación documentada en `ffi.rs`: UniFFI deja
  dos copias nativas sin borrar al devolverla.

## Una sola `Billetera` por fichero

`Billeteras.crear/abrir` devuelven `BilleteraAbierta` (cerrar libera el objeto nativo). Una
segunda apertura del mismo fichero falla con «ya abierta en este proceso»; el motor lo exige
también por su cuenta. Protección por ruta canónica dentro del mismo proceso; en Android la
ruta es privada y fija.

## Semilla cifrada

`AlmacenSemilla`: AES-256-GCM con clave del Android Keystore (StrongBox si lo hay),
autenticación por operación (`BiometricPrompt.CryptoObject` vía `Autenticacion.pedir`),
invalidación al cambiar la biometría (`ClaveInvalidadaException` → restaurar desde la
semilla). Fichero en `noBackupFilesDir/x39-wallet/`. La app debe referenciar en su
`<application>` las reglas `@xml/x39_wallet_backup_rules` y
`@xml/x39_wallet_data_extraction_rules`, y llamar a `Window.protegerContraCapturas()` en las
pantallas con mnemónico o firma.

## Redes

`BuildConfig.REDES_PERMITIDAS` = `signet,regtest`; `Redes.exigir(red)` comprueba además que
la biblioteca nativa cargada lleva el marcador `x39wallet-redes=solo-prueba`.

## Pruebas

- JVM (`src/test`): contrato del `Mnemonico` y `SemillaNueva`; corren sin dispositivo.
- Instrumentadas (`src/androidTest`): biblioteca nativa real, una billetera por fichero,
  mainnet prohibida, parámetros de la clave del Keystore. Exigen un aparato arm64-v8a con
  pantalla de bloqueo.

## Ejecutar las pruebas instrumentadas en el aparato (depuración inalámbrica, sin USB)

Hace falta: el aparato y el ordenador en la misma red, «Depuración inalámbrica» activada en
el aparato (Ajustes → Opciones de desarrollador), y `adb` del SDK (`platform-tools`).

1. En el aparato: Opciones de desarrollador → Depuración inalámbrica → **Vincular dispositivo
   con código**. Aparecen una dirección `IP:puerto` de vinculación y un código de 6 cifras.
2. En WSL2, con el `adb` del SDK (una sola vez por aparato):

   ```bash
   ADB=~/Android/sdk/platform-tools/adb
   $ADB pair <IP:puerto de vinculación>        # pide el código de 6 cifras
   ```

3. En la pantalla de Depuración inalámbrica, el `IP:puerto` general (distinto del de
   vinculación). Conectar y comprobar:

   ```bash
   $ADB connect <IP:puerto>
   $ADB devices -l                              # debe aparecer el aparato como "device"
   ```

   Si WSL2 no llega a la red del aparato, en Windows ejecutar `adb pair` / `adb connect` con
   el mismo `adb` y luego en WSL2 `export ADB_SERVER_SOCKET=tcp:<IP de Windows>:5037`.

4. Compilar la biblioteca nativa y lanzar las pruebas desde `android/`:

   ```bash
   ./build-native.sh                            # exige la suma de build-native.lock
   ./gradlew :wallet:connectedDebugAndroidTest --console=plain
   ```

   Resultado: `BUILD SUCCESSFUL` y el informe en
   `wallet/build/reports/androidTests/connected/debug/index.html`; el detalle por prueba en
   `wallet/build/outputs/androidTest-results/connected/debug/`. Las pruebas del Keystore se
   omiten (`assume`) si el aparato no tiene pantalla de bloqueo configurada.

5. Pegar la salida del `gradlew` y el contenido de los XML de resultados en PRUEBAS.md.

Las pruebas no tocan datos de ninguna app instalada: usan el almacenamiento privado del
paquete de pruebas (`x39.wallet.test`) y un alias de clave propio que borran al terminar.
