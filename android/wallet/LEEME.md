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
cabecera ligada al cifrado (AAD), clave solo con el aparato desbloqueado. Con la credencial
del aparato admitida (API 30+, decisión del proyecto) un cambio de biometría NO invalida la
clave; la invalidación que se detecta (`ClaveInvalidadaException`, clave desaparecida) cubre
la pérdida o el reinicio del Keystore. Opción futura anotada: ajuste «solo biometría».
Recuperación tras invalidación: NO se borra nada; se piden las 12 palabras, el motor
comprueba que son de esta billetera (`coincideSemilla`, sin firmar) y solo entonces se crea
una clave nueva y se vuelve a cifrar; si no coinciden o se cancela, todo queda como estaba.
Fichero en `noBackupFilesDir/x39-wallet/`. La app debe referenciar en su
`<application>` las reglas `@xml/x39_wallet_backup_rules` y
`@xml/x39_wallet_data_extraction_rules`, y llamar a `Window.protegerContraCapturas()` en las
pantallas con mnemónico o firma.

## Redes

`BuildConfig.REDES_PERMITIDAS` = `signet,regtest`; `Redes.exigir(red)` comprueba además que
la biblioteca nativa cargada lleva el marcador `x39wallet-redes=solo-prueba`.

## Pruebas

- JVM (`src/test`): contrato del `Mnemonico` y `SemillaNueva`; corren sin dispositivo.
- Instrumentadas (`src/androidTest`), 10: biblioteca nativa real y marcador de redes, una
  billetera por fichero (índice 0 antes de cerrar, 1 al reabrir), mainnet prohibida,
  parámetros de la clave del Keystore y fichero fuera de copias, pantalla Enviar con un
  repositorio falso (firmar → resultado → difundir, y difusión fallida), recuperación de la
  clave sin tocar estado ni registro de envíos, y restauración sin servidor con escaneo
  pendiente completado al sincronizar (usa el servidor público de signet). Exigen un aparato
  arm64-v8a con pantalla de bloqueo y red.

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

## Repositorio y pantallas

- `WalletRepository` (una instancia por proceso): toda la red en `Dispatchers.IO`; un `Mutex`
  para que haya una operación de envío a la vez; `StateFlow<Vista>` con saldo, altura,
  movimientos y envíos; reanuda al abrir lo persistido (envíos firmados o inciertos). El
  mnemónico entra solo como `Mnemonico` y lo cierra quien lo abrió.
- `Ajustes`: servidor Electrum `host:puerto` (vacío por defecto; sin esquema → `ssl://`;
  aviso si `tcp://`) y red (solo cambia antes de crear la billetera).
- Pantallas (vistas programáticas, misma estética que la app): `BilleteraActivity` (saldo,
  estado, envío sin resolver con reintentar/cancelar), `CrearRestaurarActivity` (12 palabras
  una vez con `setText(char[])`, comprobación de una palabra, campo sin portapapeles,
  FLAG_SECURE), `RecibirActivity` (dirección nueva + QR), `EnviarActivity` (revisar →
  prompt → firmar → difundir, FLAG_SECURE), `HistorialActivity` (movimientos, envíos,
  reintento, cancelación y cancelación por reemplazo, FLAG_SECURE) y `AjustesActivity`.
- Escaneo completo pendiente: al restaurar se escribe la marca `estado.dat.escaneo-pendiente`
  (sobrevive al cierre del proceso); mientras exista, `sincronizar()` hace el escaneo
  completo, `prepararEnvio` se niega con mensaje claro y la pantalla principal lo avisa; se
  quita cuando el escaneo termina bien.
- Propiedad del mnemónico: quien lo crea lo cierra. `conSemilla` lo obtiene de forma no
  cancelable y lo cierra en `finally`; `guardarSemilla` toma la propiedad y lo cierra en todos
  los caminos (fallo al preparar, prompt cancelado, fallo al guardar, fin, cancelación);
  `CrearRestaurarActivity` pone a cero palabras, semilla y campo en `onStop` siempre.
- Pendiente: textos solo en español (la app tiene en, ar, ja y zh); se moverán a recursos al
  montar en la fase F.

## Requisito de Android

El módulo declara minSdk 21 (como la app), pero la semilla cifrada con autenticación por
operación exige las API del Keystore de Android 6 (API 23): en un aparato anterior la
billetera se niega a arrancar con un mensaje claro (`Redes.exigirAndroid()`).

## NodeBridge (fase E-1)

`NodeBridge.kt`: `ConfigNodo` (red, `ssl://`/`tcp://host:puerto`, huella SPKI obligatoria con
`ssl://` salvo el servidor público de signet, modo de túnel, nombre), `EstadoNodo`
(`SinConfigurar`, `Conectando`, `Sano`, `Caido`, `RedIncorrecta`, `HuellaIncorrecta`) y
`NodeBridgeImpl`: comprobaciones en orden por el motor (`comprobarNodo`: huella SPKI DENTRO
del apretón de manos TLS, génesis, protocolo 1.4 y latencia, altura no inferior a la última
vista), reconexión con espera creciente (2 s → 5 min, hasta un 20 % de azar; 60 s cuando está
sano) y modo de túnel EXTERNO (exige una VPN activa; detectarla no demuestra que el tráfico
hacia ese nodo vaya por ella, porque Android permite rutas por destino; nunca levanta un túnel).
La huella fija la CLAVE PÚBLICA (SPKI), no el certificado entero, y con huella no se valida CA,
ni nombre, ni caducidad. Toda operación de red de la billetera pasa por `conexionSana()`:
comprueba el nodo ahora y, solo si está sano, entrega URL y huella de la MISMA generación de
configuración; fijar la huella en el motor y usar la conexión van bajo un mutex, y la huella
viaja en todas las operaciones (también estimar comisión y altura). El túnel propio (E-3) está
definido en `docs/nodebridge.md` y no implementado.

## Emparejamiento (fase E-2)

`Emparejamiento.kt` + `JsonEstricto.kt`: analizador y validador del paquete de emparejamiento
(JSON, versión 1; formato y reglas en `docs/nodebridge.md` §5.1). Todo se valida antes de tocar
nada: pre-análisis estricto del texto crudo (consumo completo, sin claves duplicadas, sin JSON
permisivo: el `org.json` de Android sobrescribe duplicadas y el de la JVM las rechaza, por eso
las pruebas adversarias corren INSTRUMENTADAS), nada que parezca una clave privada dentro de
nombres o valores (rechazo del paquete entero), esquema cerrado, red igual a la de la billetera,
servidor `ssl://`/`tcp://` con host literal (sin resolver nombres), huella SPKI de 64 hex
obligatoria con `ssl://`, túnel opcional bien formado, fechas UTC estrictas con vigencia de 24 h
como máximo y 5 min de tolerancia de reloj al futuro. WireGuard, con precisión: una clave privada
con forma de pública NO se puede detectar; si llegara en `clave_publica_nodo` se guardaría como
pública; E-2 no la usa como privada ni la transmite; la privada del móvil nacerá en el móvil en
E-3 (pendiente de demostrar).
`EmparejarActivity`: importa por fichero (selector del sistema, 16 KB) o por QR
(`EscanearQrActivity`, CameraX + zxing, permiso de cámara pedido al pulsar), muestra lo que trae
el paquete y, tras confirmación explícita, lo revalida (vigencia incluida) y llama a
`NodeBridge.adoptarSiSano(config)`: **solo si el nodo da Sano** se guarda la configuración (y los
datos públicos del túnel, en `Ajustes`, para E-3); si no, se muestra el motivo y no queda rastro.
«Descartar» cancela la comprobación en marcha: un Sano tardío no se guarda. `adoptarSiSano`
mira cancelación y generación y guarda DENTRO del mismo cerrojo (`Mutex`) que
`configurar`/`olvidar`, así que un olvido intermedio no puede quedar pisado; el resultado del
candidato no toca la altura vista del nodo vigente hasta adoptarlo; configuración y metadatos del
paquete van en una sola escritura (`Ajustes.guardar(c, paquete)`, `commit()` síncrono). La
lectura del fichero se corta en 16 KB + 1. El conteo de «doce o más palabras» es un recorrido
acotado, sin regex de repetición ilimitada (un nombre de 7.000 palabras desbordaba la pila), que
cuenta secuencias de letras y trata la puntuación como separador (una semilla pegada a «:», «.» o
paréntesis se rechaza igual). Si `commit()` devuelve `false`, `Ajustes.guardar` restaura la
configuración anterior en la memoria de las preferencias y lanza; la adopción no publica Sano ni
anuncia nada, y la configuración activa (variable propia de `NodeBridgeImpl`, la única que leen
`comprobar()` y `conexionSana()`) sigue siendo la anterior. Probado en el aparato con el directorio
de preferencias de solo lectura: todo sigue en A, también releyendo el fichero en frío. La configuración importada lleva modo de
túnel NINGUNO hasta que exista el túnel propio. La herramienta del lado del nodo está en
`herramientas/nodo/emparejar.sh`; su huella coincide con el vector del motor
(`herramientas/nodo/pruebas/probar.sh`).

Permiso nuevo del módulo: `CAMERA` (con `uses-feature` opcionales `camera.any`, `camera` y
`camera.autofocus`). Pruebas: `EmparejamientoTest` (JVM, analizador y validador),
`EmparejamientoInstrumentadoTest` (adversarias con el `org.json` de Android: texto sobrante,
claves duplicadas, JSON permisivo, secretos con texto alrededor, fechas con anchuras exactas,
IPv6, robustez ante miles de palabras), `adoptarSiSano` (cancelación, cambio de generación, olvido
durante el guardado, candidato lento descartado) y `probar` en `NodeBridgeTest` (JVM) y en
`NodeBridgeCarrerasInstrumentadoTest` (las dos carreras con el almacén real), y
`EmparejarActivityInstrumentadoTest` (flujo con repositorio falso: vista previa, guardado solo
tras Sano, nodo caído sin rastro, Descartar durante un servidor lento, revalidación al confirmar,
clave privada rechazada).

## Regla fija para todos los scripts del repo

Nunca `echo … | grep -q` bajo `set -o pipefail`: `grep -q` cierra la tubería en cuanto acierta y
el `echo` puede morir con SIGPIPE, con lo que la tubería falla y el script aborta o toma la rama
equivocada (ya pasó en `build-native.sh`). `grep` sobre fichero o sobre una here-string
(`grep -q patrón <<<"$VAR"`), o `[[ "$VAR" =~ patrón ]]`.
