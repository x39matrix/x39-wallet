# NodeBridge: definición, interfaz y contrato

Estado: **definición** (fase E). No hay código todavía; este documento fija qué se construye,
qué interfaz tendrá y qué contrato cumple. No contiene ningún dato de infraestructura real:
todos los nombres de host, puertos y direcciones de este documento son ejemplos genéricos.

## 1. Qué es

NodeBridge es la pieza que conecta la billetera (y cualquier otra app del usuario) con **el
nodo Bitcoin del propio usuario**: su servidor Electrum (electrs, Fulcrum, ElectrumX…) y, si
el nodo está en casa detrás de un router, un túnel WireGuard estándar hasta él.

- Librería Android (Kotlin) + cliente Electrum en Rust, reutilizando el crate `x39-wallet`
  (conexión, comprobación de red por `genesis_hash`, altura, comisión). La parte Rust se
  expone por el mismo UniFFI que la billetera; no hay un segundo mecanismo FFI.
- Funciona con **cualquier** nodo y **cualquier** túnel WireGuard. Cero dependencia de la
  infraestructura de X39 o de terceros: ni servidores por defecto, ni descubrimiento, ni
  relés. Lo único que se ofrece como ayuda es, SOLO en signet, un servidor público de pruebas
  explícitamente marcado como de terceros.
- La billetera la usa como su única vía de red: si NodeBridge dice que el nodo no es de la red
  esperada o no está sano, la billetera no sincroniza ni difunde.

## 2. Vocabulario

| Término | Significado |
|---|---|
| Nodo | Bitcoin Core (o equivalente) + servidor Electrum del usuario. |
| Punto de acceso Electrum | `esquema://host:puerto`, con `ssl://` (TLS) o `tcp://` (sin cifrar; solo dentro del túnel o la red local). |
| Túnel propio | Perfil WireGuard que NodeBridge compone con la clave privada que GENERA EL MÓVIL (nunca viaja) y los datos del nodo que llegan en el paquete de emparejamiento. |
| Túnel externo | Una VPN ya activa en el aparato (por ejemplo la de la app de comunicaciones) a través de la cual el nodo es alcanzable; NodeBridge no la toca. |
| Huella del servidor | SHA-256 del `SubjectPublicKeyInfo` (la CLAVE PÚBLICA, no el certificado entero) del certificado TLS del servidor Electrum. Con huella fijada no se valida CA, ni nombre, ni caducidad: la huella sustituye a la CA. Vector fijo de prueba en `rust/src/tests.rs`. |
| Emparejamiento | Acto por el que un móvil queda autorizado a hablar con un nodo: recibe su punto de acceso, su huella y, si procede, su perfil de túnel. |
| Revocación | Acto por el que el nodo deja de aceptar a ese móvil, y el móvil olvida al nodo. |

## 3. Interfaz (Kotlin)

```kotlin
// Configuración del nodo del usuario. Vacía por defecto: sin nodo no hay red.
data class ConfigNodo(
    val red: Red,                        // signet | regtest (mainnet solo con la feature `mainnet`)
    val electrum: String,                // "ssl://host:puerto" o "tcp://host:puerto"
    val huellaServidor: String?,         // SPKI SHA-256 en hex; obligatoria con ssl:// salvo signet público
    val tunel: ModoTunel,                // NINGUNO | EXTERNO | PROPIO (ver §6)
    val tunelPropio: TunelPropio?,       // solo con PROPIO: datos del nodo; la clave privada del móvil no está aquí
    val nombre: String,                  // etiqueta que elige el usuario ("mi nodo")
    val proxySocks5: String?,            // "IP:puerto" (Tor/Orbot, p. ej. 127.0.0.1:9050); obligatorio con .onion; solo IP literal, nunca un nombre
)

enum class ModoTunel { NINGUNO, EXTERNO, PROPIO, X39 } // X39: el túnel de la app, solo para el nodo X-39

// Lo que el nodo entrega para el túnel propio. Ninguna clave privada: la del móvil la genera
// el móvil y vive cifrada en su almacén; la del nodo no sale del nodo.
data class TunelPropio(
    val clavePublicaNodo: String,        // base64 WireGuard
    val endpointNodo: String,            // "host:puerto" UDP del nodo
    val ipsPermitidas: List<String>,     // AllowedIPs (p. ej. la red del túnel)
    val direccionMovil: String,          // dirección del móvil dentro del túnel
)

sealed class EstadoNodo {
    object SinConfigurar : EstadoNodo()
    object Conectando : EstadoNodo()
    data class Sano(val altura: UInt, val servidor: String, val latenciaMs: Long) : EstadoNodo()
    data class Caido(val desdeHaceS: Long, val ultimoError: String, val proximoIntentoEnS: Long) : EstadoNodo()
    data class RedIncorrecta(val esperada: Red, val genesisVisto: String) : EstadoNodo()
    data class HuellaIncorrecta(val esperada: String, val vista: String) : EstadoNodo()
}

interface NodeBridge {
    val estado: StateFlow<EstadoNodo>
    suspend fun configurar(config: ConfigNodo)          // guarda y reconecta
    suspend fun olvidar()                                 // borra configuración y túnel (revocación local)
    suspend fun comprobar(): EstadoNodo                   // conexión única, sin vigilancia
    suspend fun probar(config: ConfigNodo): EstadoNodo    // mismas reglas, sin guardar ni publicar
    suspend fun adoptarSiSano(config: ConfigNodo): EstadoNodo // emparejamiento: guarda y publica SOLO si Sano
    fun vigilar(activo: Boolean)                          // vigilancia con reconexión creciente; una señal de red (SenalRed) la despierta
    // La importación del paquete (QR o fichero) la hace Emparejamiento.analizar(texto, red) (E-2).
    fun clavePublicaDelMovil(): String                    // para mostrarla (QR) al nodo; la privada nunca sale (E-3)
    suspend fun conexionSana(): ConexionMotor?            // URL + huella de la MISMA generación, solo si Sano
}
```

Reglas de la interfaz:

- `configurar` no da el nodo por bueno hasta que `comprobar` devuelve `Sano`.
- Cada `configurar`/`olvidar` abre una **generación** de configuración; un resultado que llega
  de una generación anterior se descarta sin publicarse. `conexionSana` entrega URL y huella de
  la misma generación, y la billetera fija la huella en el motor y ejecuta la operación bajo un
  mutex, para que nada cambie la huella entre medias.
- Toda la red en `Dispatchers.IO`; el `StateFlow` se actualiza en cada intento.
- Reconexión con espera creciente: 2 s, 4 s, 8 s… hasta un máximo de 5 min, con un poco de
  azar para no sincronizarse con otros clientes; se reinicia a 2 s tras un éxito.
- Aviso de caída: el estado pasa a `Caido` tras el primer fallo; la app decide si notifica.
- La clave privada WireGuard del móvil nace en el móvil, se guarda cifrada con la misma clase
  de almacén que la semilla (clave propia del Keystore, sin autenticación por operación: el
  túnel debe poder levantarse solo), en `noBackupFilesDir`, y nunca se muestra, exporta ni
  viaja. Lo único que sale del móvil es su clave PÚBLICA.

## 4. Comprobaciones de red (en este orden, en cada conexión y reconexión)

1. **Red correcta**: `server.features.genesis_hash` debe ser el génesis de `config.red`. Si no,
   `RedIncorrecta` y no se hace nada más (ya implementado en el motor: `Error::RedServidor`).
2. **Identidad del servidor** (con `ssl://`): si hay huella fijada, la huella SPKI del
   certificado presentado debe ser `huellaServidor`; se comprueba dentro del apretón de manos
   TLS, en TODAS las operaciones (escaneo, sincronización, difusión, altura, comisión). Con
   huella no se valida CA, ni nombre, ni caducidad. Sin huella, el certificado se valida contra
   las raíces públicas (autoridad reconocida, nombre y caducidad), como cualquier cliente TLS;
   si no valida, se rechaza, salvo que el usuario active expresamente «certificado propio»: la
   app lee entonces la huella que presenta el servidor (`huella_servidor`, solo el apretón TLS,
   sin hablar Electrum), la muestra con aviso y la fija. El paquete de emparejamiento (§5) sigue
   exigiendo la huella con `ssl://`. `tcp://` se acepta solo con aviso visible («sin cifrar:
   solo por tu túnel o tu red local»); dentro del X39 Túnel no se avisa, porque el túnel cifra.
   Un host `.onion` exige `proxySocks5`: el motor pide CONNECT al proxy con el nombre tal cual y
   nunca resuelve `.onion`; sin proxy ninguna operación abre socket. El proxy se escribe como
   **IP literal** (`127.0.0.1:9050`, `[::1]:9050`), nunca como nombre: un nombre habría que
   resolverlo en el aparato antes de llegar al proxy (`ConfigNodo.proxyBienFormado`).
3. **Altura razonable**: la punta del servidor no puede ir por detrás de la última altura vista
   por el móvil; si va por detrás, el nodo está resincronizando y el estado lo dice.
4. **Versión de protocolo**: `server.version` con protocolo 1.4; si el servidor no lo admite, error
   claro.
5. **Latencia** medida en el `server.version`, para mostrarla.

## 5. Contrato de emparejamiento y revocación

El emparejamiento se hace **en el nodo**, por su operador (el usuario mismo), y se transporta al
móvil por QR o fichero. NodeBridge no inventa nada: valida e importa.

### 5.1 Orden del emparejamiento: la clave privada nace en el móvil y no viaja nunca

1. El móvil genera su par de claves WireGuard y muestra su clave **pública** (QR o texto).
2. El operador la añade en el nodo como `[Peer]` de ese móvil y genera el paquete de
   emparejamiento, que solo lleva datos del nodo.
3. El móvil importa el paquete (QR o fichero), lo valida y comprueba el nodo; solo si da
   `Sano` lo guarda.

Paquete de emparejamiento (texto JSON, **versión 1**; lo genera `herramientas/nodo/emparejar.sh`):

```json
{
  "v": 1,
  "red": "signet",
  "electrum": "ssl://nodo.ejemplo.local:50002",
  "huella_spki_sha256": "<64 hex>",
  "tunel": {
    "clave_publica_nodo": "<base64 de 32 bytes>",
    "endpoint": "nodo.ejemplo.local:51820",
    "ips_permitidas": ["A.B.C.D/n"],
    "direccion_movil": "A.B.C.D/32"
  },
  "nombre": "mi nodo",
  "emitido": "2026-10-31T00:00:00Z",
  "caduca": "2026-11-01T00:00:00Z"
}
```

(Nombres y direcciones de ejemplo; ninguna es real.)

Reglas de validación (todas ANTES de tocar nada; `android/.../Emparejamiento.kt`):

| Campo | Regla |
|---|---|
| texto crudo | Pre-análisis **estricto** propio (`JsonEstricto.kt`) antes de `org.json`: exactamente un objeto JSON (RFC 8259) que consume TODO el texto (nada detrás), **sin claves duplicadas en ningún nivel** y sin las extensiones permisivas de `org.json` (comillas simples, claves sin comillas, comentarios). Necesario porque el `org.json` de Android sobrescribe las claves duplicadas en silencio y el de la JVM las rechaza: el veredicto no puede depender de la plataforma. Texto de 16 KB como máximo (la lectura del fichero se corta en 16 KB + 1). |
| (todo el árbol) | **Nada que parezca una clave privada**: un campo cuyo nombre contenga `priv`, `secret`, `secreto`, `semilla`, `seed`, `mnemo`, `xprv`/`tprv`/`vprv`, `wif`, `passphrase`, `contrase` o `interface`, o un valor que CONTENGA (con texto alrededor también) `PRIVATE KEY`, una clave extendida privada, un WIF o doce o más palabras alfabéticas seguidas (una palabra es una secuencia de letras; la puntuación separa y no esconde: «copia:zoo…», «…wrong.», «(zoo … wrong)» se rechazan; el conteo es un recorrido acotado sin regex) → se **rechaza entero**. Se aplica también al host del servidor y al endpoint del túnel. |
| WireGuard, con precisión | Una clave privada de WireGuard tiene la misma forma que una pública y **NO se puede detectar**; si llegara en `clave_publica_nodo` se guardaría como pública. E-2 no la usa como privada ni la transmite. La privada del móvil nacerá en el móvil en E-3 (pendiente de demostrar). |
| esquema | Cerrado: `v` debe ser `1` y solo se admiten los campos de arriba (también dentro de `tunel`); un campo desconocido rechaza. |
| `red` | `signet`, `regtest` o `testnet` (mainnet no está permitida en esta edición) y **la misma que la billetera**. |
| `electrum` | `ssl://host:puerto` o `tcp://host:puerto`; host = nombre de host, IPv4 literal o `[IPv6]`; puerto 1 a 65535. Las direcciones se validan como **literales**, nunca se resuelve un nombre desde el validador. |
| `huella_spki_sha256` | 64 hex (se normaliza a minúsculas). **Obligatoria con `ssl://`**; con `tcp://` no debe venir. |
| `tunel` (opcional) | `clave_publica_nodo` 32 bytes en base64 (44 caracteres acabados en `=`), `endpoint` `host:puerto`, `ips_permitidas` lista no vacía de redes `A.B.C.D/n` o IPv6 literal `/n` (una sola `::`, IPv4 incrustada solo al final: `::ffff:A.B.C.D` vale, `::::` no), `direccion_movil` opcional `A.B.C.D/32`. En E-2 se validan, se muestran y se guardan; el túnel propio (E-3) los usará. La configuración resultante lleva modo NINGUNO hasta entonces. |
| `nombre` | 1 a 64 caracteres, una sola línea, sin caracteres de control. |
| `emitido`, `caduca` | Exactamente `AAAA-MM-DDTHH:MM:SSZ` (UTC): el formato debe consumir toda la cadena (`…Z+09:00` se rechaza). `caduca` posterior a `emitido` y **a no más de 24 h**; `ahora < caduca` (estricto: `caduca == ahora` se rechaza); `emitido` puede estar como máximo 5 min en el futuro: **tolerancia explícita al desajuste de reloj** entre nodo y móvil, y así se dice al usuario. |

- El paquete **caduca** (24 h como máximo): un QR viejo no empareja.
- **Solo se guarda tras `Sano`**: la pantalla «Emparejar con mi nodo» muestra lo que trae el
  paquete (nombre, red, servidor, huella abreviada con opción de verla entera, caducidad, túnel si
  lo hay) y pide confirmación explícita; al confirmar se **vuelve a validar el paquete** (vigencia
  incluida: no vale la validación de cuando se abrió) y entonces `NodeBridge.adoptarSiSano(config)`
  comprueba el nodo con las mismas reglas de siempre y, únicamente si da `Sano`, persiste la
  configuración y la publica. Si no, se muestra el motivo y no queda rastro (ni configuración, ni
  huella, ni túnel). La adopción es **trabajo cancelable atado a la generación** y **indivisible**
  con `configurar`/`olvidar`: la comprobación de red corre fuera del cerrojo, pero comprobar la
  cancelación y la generación y guardar ocurren dentro del MISMO cerrojo que `configurar`/`olvidar`,
  así que un olvido intermedio no puede quedar pisado por un guardado tardío, y un «Descartar» con
  el `Sano` llegando después no guarda nada. El resultado del candidato se guarda aparte: no toca la
  altura vista ni el estado del nodo vigente hasta adoptarlo (descartar un candidato adelantado no
  deja al nodo vigente «caído» por altura). Configuración y metadatos del paquete (datos públicos
  del túnel, instante) se persisten en UNA escritura, o nada: `Ajustes.guardar` usa `commit()`
  síncrono y **lanza si devuelve `false`**, y entonces `adoptarSiSano` no publica `Sano`, no cambia
  la generación y la pantalla no anuncia la adopción. Como SharedPreferences cambia primero su
  memoria y luego el disco, `Ajustes.guardar` restaura en las preferencias la configuración anterior
  cuando el disco falla, y NodeBridge mantiene la configuración **activa en una variable propia**
  que solo cambia tras un guardado que ha vuelto sin lanzar: `comprobar()` y `conexionSana()` leen
  esa variable, nunca una relectura fresca de las preferencias.
- Importación por **fichero** (selector del sistema, 16 KB como máximo) o por **QR** con la cámara
  (CameraX + zxing; el permiso de cámara se pide al pulsar «Escanear QR» y sin él se puede
  importar el fichero). El escáner solo devuelve el texto; la validación es la misma en ambos casos.
- `olvidar()` borra también los datos del paquete (revocación local, §5.3).

### 5.2 Un móvil, una clave de túnel

Cada móvil emparejado tiene su propio par de claves WireGuard (un `[Peer]` propio en el nodo),
generado por el móvil. Nunca se comparte una clave entre dos móviles: así la revocación de uno
no toca a los demás.

### 5.3 Revocación

- **Desde el nodo** (la que vale): el operador elimina el `[Peer]` de ese móvil en WireGuard y,
  si el servidor Electrum tiene lista de clientes, su entrada. Desde ese momento el móvil no
  entra aunque conserve el perfil.
- **Desde el móvil** (`olvidar()`): borra configuración, huella y perfil de túnel, y para la
  vigilancia. Es un acto local: no demuestra nada al nodo, por eso la revocación real es la del
  nodo.
- **Rotación**: el móvil genera un par nuevo y muestra la clave pública nueva; el nodo la
  añade como peer, emite un paquete nuevo y elimina el `[Peer]` viejo; el móvil importa el
  paquete (sustituye al anterior tras comprobarlo). NodeBridge mantiene UNA configuración por
  red.

### 5.4 Qué se muestra al usuario

Nombre del nodo, red, punto de acceso, huella (abreviada, con opción de verla entera), si hay
túnel o no, estado de salud con altura y latencia, última caída y próximo intento. Nunca la
clave privada del túnel.

## 6. Túnel: dos modos explícitos (Android solo permite una VPN activa)

| Modo | Qué hace NodeBridge |
|---|---|
| (a) **Túnel propio** (`PROPIO`) | Levanta su propio perfil WireGuard con la clave privada generada en el móvil y los datos del paquete. Solo si NO hay otra VPN activa en el aparato; si la hay, no lo levanta y lo dice (`Caido` con motivo «hay otra VPN activa»). |
| (b) **Túnel externo** (`EXTERNO`) | No toca ninguna VPN. EXIGE que haya una VPN activa en el aparato y comprueba que el nodo responde. Detectar una VPN activa NO demuestra que el tráfico hacia ESE nodo viaje por ella (Android permite rutas por destino): salvo que se verifique el encaminamiento, el texto al usuario no debe afirmarlo. Si la VPN cae, el estado pasa a caído. |
| Ninguno (`NINGUNO`) | El nodo es alcanzable directamente (red local, nodo con TLS público…). |
| **X39 Túnel** (`X39`) | Solo lo lleva la configuración del «Nodo X-39» (§10). ANTES de abrir ningún socket se consulta la detección REAL del túnel (`DetectorTunel` → `DiagnosticoTunel`: redes del sistema con transporte VPN cuyas rutas cubran `10.39.0.3`, por prefijo de bits —vale `10.39.0.0/24` o `0.0.0.0/0`—, o con dirección en `10.39.0.0/24`; nunca el estado en memoria del túnel de la app, que solo se anota en el detalle). `SIN_TUNEL` con certeza → «Túnel apagado. Activa el X39 Túnel; la billetera no se conecta por otra vía.» de inmediato, sin pasar por «conectando…», cero sockets, cero llamadas al motor. `CON_RUTA` o `DESCONOCIDO` → se conecta a `NodoX39.servidor(red)` y solo a esa dirección (nunca se bloquea por una duda: una puerta equivocada dejó la billetera sin nodo con el túnel activo, orden 6); si la conexión falla se vuelve a consultar y la pantalla muestra SOLO uno de los tres textos fijos («túnel apagado» / «túnel activo pero el nodo X-39 no responde» / «el nodo X-39 no responde y no se pudo saber si el túnel está activo»); el motivo técnico va al registro `x39.nodo`, sin direcciones. Ninguna excepción cruda llega a la pantalla; una `CancellationException` se relanza y nunca se enseña (orden 7, 04/11/2026). La vigilancia escucha además una señal de red (`SenalRed`; la real, un `NetworkCallback` de transporte VPN) que la despierta enseguida cuando aparece o desaparece la VPN, con rebote de 500 ms y reinicio de la espera creciente; la señal solo despierta, decide la puerta (orden 8). Los textos de los tres casos siguen siendo las constantes castellanas de `NodoX39` (claves estables); la pantalla los traduce al pintar con `Textos.kt` (orden 9a). |

La app elige el modo; NodeBridge nunca levanta un túnel si hay otro activo, y cuando no
puede, lo explica en el estado en vez de fallar en silencio. Orden de implementación: primero
(b), que no exige ningún componente nuevo; (a) después (ver `docs/` y el plan).

## 7. Qué NO hace

- No custodia nada ni ve la semilla: NodeBridge solo habla con el nodo; la billetera firma.
- No abre puertos ni expone el nodo: el túnel lo levanta el usuario en su router/nodo; aquí
  solo se usa el perfil que él genera.
- No descubre nodos, no tiene lista de servidores, no usa relés ni servicios de X39.
- No elige la red ni el servidor por el usuario; con la configuración vacía no hay red.
- No envía telemetría ni registros a nadie.
- No toca el túnel de la app de comunicaciones ni su señalización: es un túnel propio y
  separado (Android permite una VPN activa por perfil: si el usuario necesita las dos a la vez,
  la app lo tiene que explicar, no resolver a escondidas).
- No gestiona el nodo: ni instala, ni actualiza, ni configura electrs o Bitcoin Core.

## 8. Pruebas previstas (fase E, con el simulado y con servidores reales)

| Prueba | Cómo |
|---|---|
| Conexión al servidor público de signet | real; `Sano` con altura > 300 000 |
| Rechazo de red incorrecta | el simulado anuncia génesis de signet con `red = regtest` → `RedIncorrecta`, sin sincronizar |
| Rechazo de huella incorrecta | servidor TLS de prueba con otro certificado → `HuellaIncorrecta` |
| Reconexión tras corte simulado | el simulado deja de responder y vuelve: `Caido` con esperas 2, 4, 8 s y `Sano` al volver |
| Importación de paquete | válido, caducado, red no permitida, túnel mal formado, **con clave privada → rechazado** |
| `tcp://` con aviso | se acepta con el estado marcado como sin cifrar |
| Túnel externo | nodo alcanzable solo a través de la VPN activa: `Sano`; VPN caída: `Caido` |
| Túnel propio con otra VPN activa | no se levanta y el estado lo dice |

## 10. Selector de nodo (Ajustes → Nodo)

Cada usuario elige con qué nodo sincroniza SU billetera:

| Modo | Qué es | Cómo se guarda |
|---|---|---|
| **Nodo X-39** (predeterminado) | El servidor Electrum de X-39, por red (`NodoX39.servidor(red)`: `tcp://` + puerto Electrum, DENTRO del túnel; mainnet y signet, no regtest), con `ModoTunel.X39`. Sin nada que escribir. Antes de conectar, la detección real del túnel: sin túnel (certeza) no se abre socket y se muestra de inmediato «Túnel apagado. Activa el X39 Túnel; la billetera no se conecta por otra vía.»; con ruta o con duda se conecta solo a esa dirección y, si falla, un texto fijo («túnel activo pero el nodo X-39 no responde» o el de «no se pudo saber si el túnel está activo»), sin motivo técnico en pantalla. Nunca se conecta por otra vía. La app no lleva ninguna otra dirección ni puerto del nodo (ni RPC, ni pool, ni SSH). | `configurar` (se guarda aunque el túnel esté apagado; se reintenta cuando vuelva). «Mi nodo» y su emparejamiento se conservan. |
| **Mi nodo** | El servidor Electrum del usuario (electrs, Fulcrum… sobre su nodo): `tcp://host:puerto`, `ssl://host:puerto` o `.onion` (con proxy SOCKS5). Lo que tenga encima el nodo no es asunto de la app: solo habla Electrum. | «Probar conexión» = `probar` (versión del servidor, red, altura, protocolo, latencia; sin guardar). «Usar este nodo» = `adoptarSiSano`: solo se guarda si da `Sano` (génesis de la red de la billetera incluido); si no, no se toca nada. También por emparejamiento (§5). |

- Sin `modo_nodo` guardado (instalación nueva o anterior a este ajuste): X-39 si no había
  servidor propio o si el guardado era el de X-39; «Mi nodo» en otro caso (`NodoX39.resolverModo`).
- Cambiar de servidor no borra nada: la cadena es la misma y basta sincronizar; con un escaneo
  completo pendiente, se hace al sincronizar y se dice.
- La dirección de «Mi nodo» sobrevive al reinicio y a volver al nodo X-39, y no pasa por ningún
  registro (`Log`).
- Seguridad (en la app): «Nodo: X-39 por túnel» o «Nodo: el tuyo (host)» y la frase fija: «El
  servidor Electrum que uses ve las direcciones de tu billetera. Con tu propio nodo no las ve
  nadie más.»
- `olvidar()` deja «Mi nodo» vacío (sin nodo) hasta que el usuario elija.

## 9. Fuera de este documento

La herramienta del lado del nodo (añadir la clave pública de un móvil como peer, generar el
paquete de emparejamiento, revocar un móvil) queda fuera de este documento, pero dentro del
plan de seis meses como entregable propio. Antes de publicar, este documento tendrá su versión
en inglés junto a la española.
