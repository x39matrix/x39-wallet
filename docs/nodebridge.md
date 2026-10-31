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
| Huella del servidor | Hash SHA-256 de la clave pública (SPKI) del certificado TLS del servidor Electrum. |
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
)

enum class ModoTunel { NINGUNO, EXTERNO, PROPIO }

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
    fun vigilar(activo: Boolean)                          // vigilancia con reconexión creciente
    suspend fun importarEmparejamiento(texto: String): ConfigNodo  // QR o fichero; valida y devuelve
    fun clavePublicaDelMovil(): String                    // para mostrarla (QR) al nodo; la privada nunca sale
    fun urlParaElMotor(): String?                         // lo que la billetera pasa al crate
}
```

Reglas de la interfaz:

- `configurar` no da el nodo por bueno hasta que `comprobar` devuelve `Sano`.
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
2. **Identidad del servidor** (con `ssl://`): la huella SPKI del certificado presentado debe
   ser `huellaServidor`. Sin huella no se acepta `ssl://` salvo el servidor público de signet.
   `tcp://` se acepta solo con aviso visible («sin cifrar: solo por tu túnel o tu red local»).
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

Paquete de emparejamiento (texto JSON):

```json
{
  "v": 1,
  "red": "signet",
  "electrum": "ssl://nodo.ejemplo.local:50002",
  "huella_spki_sha256": "<64 hex>",
  "tunel": {
    "clave_publica_nodo": "<base64>",
    "endpoint": "nodo.ejemplo.local:51820",
    "ips_permitidas": ["10.0.0.0/24"],
    "direccion_movil": "10.0.0.2/32"
  },
  "nombre": "mi nodo",
  "emitido": "2026-10-31T00:00:00Z",
  "caduca": "2026-11-01T00:00:00Z"
}
```

(Las direcciones del ejemplo son privadas de documentación; ninguna es real.)

- El paquete **caduca** (por defecto 24 h): un QR viejo no empareja.
- El paquete **no contiene ninguna clave privada**; si contiene una (`PrivateKey`, un
  `[Interface]` completo o cualquier campo de clave privada), se **rechaza** sin importar nada.
- `tunel` es opcional: sin él, el modo es NINGUNO (nodo alcanzable directamente) o EXTERNO (la
  app ya tiene una VPN activa; ver §6).
- NodeBridge valida: versión, red permitida, esquema del punto de acceso, huella bien formada
  (64 hex), claves públicas en base64 de 32 bytes, endpoint `host:puerto`, IPs permitidas y
  dirección del móvil bien formadas, caducidad.
- Tras importar, `comprobar()` debe dar `Sano` antes de guardar nada como definitivo.

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
| (b) **Túnel externo** (`EXTERNO`) | No toca ninguna VPN. Comprueba que el nodo es alcanzable y sano A TRAVÉS del túnel que ya está activo (el de la app de comunicaciones u otro). Si ese túnel cae, el nodo deja de ser alcanzable y el estado lo refleja. |
| Ninguno (`NINGUNO`) | El nodo es alcanzable directamente (red local, nodo con TLS público…). |

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

## 9. Fuera de este documento

La herramienta del lado del nodo (añadir la clave pública de un móvil como peer, generar el
paquete de emparejamiento, revocar un móvil) queda fuera de este documento, pero dentro del
plan de seis meses como entregable propio. Antes de publicar, este documento tendrá su versión
en inglés junto a la española.
