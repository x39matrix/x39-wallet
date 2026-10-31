# E-3: túnel propio (`ModoTunel.PROPIO`), diseño

Estado: **diseño** (fase E-3). No hay código: este documento fija qué se construye, cómo y con
qué límites, para que el código que venga se pueda contrastar contra él. Todos los nombres de
host, puertos y direcciones son ejemplos genéricos; ninguno es real.

Alcance: solo el modo `PROPIO`. `NINGUNO` y `EXTERNO` no cambian de comportamiento; solo cambia
su texto de ayuda (§3.4). Todas las puertas de `docs/nodebridge.md` §4 (génesis → huella SPKI →
altura que no baja → protocolo 1.4 → latencia) siguen corriendo, ahora a través del túnel, y la
huella TLS se **conserva dentro del túnel** (defensa en profundidad: si el túnel se rompe o se
redirige, la identidad del servidor Electrum se sigue comprobando). `tcp://` dentro del túnel es
una opción futura documentada (§11), no de v1.

Lo que el documento verificó en las fuentes antes de afirmarlo está marcado con «(comprobado)»
y la ruta o la versión; lo que no se pudo comprobar está en §10 como pregunta abierta.

## 0. Resumen de decisiones

| Decisión | Resumen |
|---|---|
| Transporte | WireGuard en modo usuario (`boringtun::noise::Tunn`) + pila TCP/IP en modo usuario (`smoltcp`), patrón onetun. Sin `VpnService`, sin root, sin TUN. El único socket del sistema es el UDP hacia el endpoint. **Viabilidad de compilación confirmada** (§8.1): el prototipo compila y enlaza para Android con el NDK, con un suelo de +0,17 MiB con strip y la alineación de 16 KB intacta; el **funcionamiento** (el túnel, Electrum y TLS por él, varios sockets a la vez, la ausencia de salida directa) queda pendiente de la integración (§9). `ring` se mantiene en v1; lo único pendiente de la compilación es la reproducibilidad bit a bit. |
| Flujo Electrum | **En proceso**: un tipo `FlujoTunel: Read + Write` sobre el socket TCP de smoltcp, con rustls encima y el `VerificadorHuella` actual. Ningún puerto de escucha en `127.0.0.1`. |
| Clave del móvil | Par x25519 generado en el motor; la privada se guarda **envuelta** con una clave AES-GCM del Keystore (patrón `AlmacenSemilla`, sin autenticación por operación); se desenvuelve a memoria del motor solo con el túnel levantado y se borra al bajarlo. |
| Enrutado | Garantía **por construcción**: en `PROPIO` no hay camino de código que abra un TCP del sistema hacia el nodo; el host Electrum es una IP literal dentro de `ips_permitidas`; sin DNS interno. |
| Paquete | Extensión del objeto `tunel` del paquete v1 con `direccion_nodo` y `keepalive`, solo datos públicos. Sin PSK en v1. Regla nueva del escáner: una clave con forma WireGuard solo puede estar en `tunel.clave_publica_nodo`. |
| Autenticidad | El paquete **no se autentica solo**: primer uso sobre un canal que controla el operador, más **palabras de comprobación** calculadas (no transmitidas) que el operador imprime y el móvil muestra antes de Confirmar. Simétrico para la clave pública del móvil. |

## 1. Transporte

### 1.1 Pila

```
  app (Kotlin)          motor (Rust, misma biblioteca libx39wallet.so)
  ───────────           ───────────────────────────────────────────────────────────────
  NodeBridge ──FFI──▶  TunelPropio (objeto UniFFI, Arc)
                         │
                         ├─ hilo bomba ───────────────────────────────────────────────┐
                         │    UdpSocket (ÚNICO socket del sistema) ◀──▶ Tunn (boringtun) │
                         │    ◀──▶ Interface + Device virtual (smoltcp, medium IP)     │
                         │    ◀──▶ socket::tcp::Socket × N (smoltcp, uno por flujo)    │
                         └─────────────────────────────────────────────────────────────┘
                         │
                         ▼ FlujoTunel (Read + Write, en proceso, con plazo de lectura)
                         ▼ rustls StreamOwned<ClientConnection, FlujoTunel> + VerificadorHuella (SPKI)
                         ▼ RawClient<…> (electrum-client) ▶ BdkElectrumClient<…> (bdk_electrum)
                         ▼ Conexion::abrir → puertas de nodebridge.md §4, sin cambios
```

- **boringtun** (`noise::Tunn`): implementación en Rust del protocolo WireGuard sin interfaz de
  red. API comprobada en docs.rs para 0.7.1: `Tunn::new(static_private, peer_static_public,
  preshared_key: Option<[u8; 32]>, persistent_keepalive: Option<u16>, index, rate_limiter)`,
  `encapsulate(src, dst)`, `decapsulate(src_addr, datagram, dst)`, `update_timers(dst)`,
  `format_handshake_initiation(dst, force_resend)`, `set_static_private(...)`,
  `time_since_last_handshake()`. Feature `device` (la que trae TUN) **apagada**; `jni-bindings`
  apagada (el FFI es el UniFFI de la billetera, no un segundo mecanismo).
- **smoltcp**: pila TCP/IP en modo usuario, sin hilos propios, dirigida por `poll`. Features:
  `std`, `medium-ip` (sin Ethernet: lo que sale es un paquete IP, que es lo que WireGuard
  transporta), `proto-ipv4`, `socket-tcp`. Sin `socket-dns` (§3.1), sin `proto-ipv6` en v1 (§10).
- **Device virtual**: implementación del trait `smoltcp::phy::Device` sobre dos colas en
  memoria: lo que smoltcp transmite va a la cola de salida (→ `Tunn::encapsulate` → UDP); lo que
  llega del UDP pasa por `Tunn::decapsulate` y se deja en la cola de entrada (→ smoltcp
  `receive`). MTU 1420 (WireGuard sobre IPv4/UDP). Es el mismo patrón que `onetun` (que se usa
  como referencia de diseño, no como dependencia).
- **Hilo bomba**: un solo `std::thread` por túnel levantado. Bucle:
  1. `UdpSocket::recv` con plazo (`set_read_timeout`) = mín(`poll_delay` de smoltcp, tic de
     temporizadores de boringtun: 250 ms con tráfico, 1 s en reposo). Si el plazo calculado es
     **cero** (`poll_delay` = 0), **no** se llama a `set_read_timeout(0)` (en `std` eso es
     bloqueo indefinido o error): se itera sin bloquear (`recv` no bloqueante) y se recalcula el
     plazo tras el `poll`;
  2. cada datagrama recibido → `decapsulate`; si devuelve un paquete, se encola (o, si es de
     protocolo, se manda al UDP) y se **vuelve a llamar a `decapsulate` con datagrama vacío
     hasta `Done`** (vaciado completo de la cola interna, como exige boringtun);
  3. `Interface::poll`; vaciar la cola de salida → `encapsulate` → `UdpSocket::send`;
  4. `update_timers` en cada tic → lo que devuelva (keepalive, re-apretón) al UDP;
  5. mover bytes entre **cada** `socket::tcp::Socket` y los búferes de **su** `FlujoTunel`
     (§1.2) y despertar a quien espere (`Condvar`); todos los sockets se atienden en el mismo
     `poll`.
  El hilo muere al bajar el túnel; nunca sobrevive al objeto `TunelPropio`.

### 1.2 Flujo Electrum en proceso (sin puerto en 127.0.0.1)

En Android el bucle local es compartido por todas las apps del aparato: un puerto de escucha en
`127.0.0.1` sería alcanzable por cualquier otra app. Por eso el cliente Electrum **no** se
conecta a un puerto local: recibe un objeto `FlujoTunel` que implementa `std::io::Read` y
`std::io::Write` y que por dentro habla con el `socket::tcp::Socket` de smoltcp a través del
hilo bomba (dos búferes, un `Mutex` y una `Condvar`).

Comprobado en las fuentes (registro de cargo, versiones del `Cargo.lock` del repo) que esto
encaja **sin bifurcar electrum-client**:

| Qué | Dónde | Lo que dice |
|---|---|---|
| `RawClient<S>` acepta cualquier flujo | `electrum-client-0.25.0/src/raw_client.rs:148` | `pub struct RawClient<S> where S: Read + Write`; `impl<S> From<S> for RawClient<S> where S: Read + Write` (l. 191): es exactamente la vía que ya usa `nodo.rs` (`RawClient::from(flujo)`). |
| `ElectrumApi` para cualquier `S` | `raw_client.rs:897` | `impl<T: Read + Write> ElectrumApi for RawClient<T>`. |
| Lo que exige `ClonableStream` | `electrum-client-0.25.0/src/stream.rs:8` | `ClonableStream<T: Read + Write>(Arc<Mutex<T>>)`: solo `Read + Write` sobre `T` (no sobre `&T`). |
| `BdkElectrumClient` | `bdk_electrum-0.24.0/src/bdk_electrum_client.rs:30` | `impl<E: ElectrumApi> BdkElectrumClient<E>`: genérico, sin referencia a `TcpStream`. |
| rustls encima | `rustls-0.23.45/src/stream.rs:164` | `StreamOwned<C: Sized, T: Read + Write + Sized>`: el `VerificadorHuella` y `conectar_pinado` (`rust/src/nodo.rs`) no dependen del transporte. |

Tres consecuencias que el adaptador tiene que resolver y que no son gratis:

- **Un túnel, varios sockets.** Cada `FlujoTunel` es un socket TCP **propio** de smoltcp sobre
  la misma `Interface` (un `SocketSet` con varios), con sus propios búferes. La vigilancia de
  NodeBridge (`comprobar_nodo`) y las operaciones de la billetera (escanear, sincronizar,
  difundir) no comparten socket ni búferes: una sincronización larga no bloquea la comprobación
  de salud, ni al revés. `Conexion::abrir` con `Transporte::Tunel` pide al túnel un socket
  nuevo (`TunelPropio::abrir_tcp(ip, puerto)`) por cada conexión, igual que hoy abre un
  `TcpStream` por operación.

- **Plazos.** `RawClient` no tiene plazos propios: depende de los del flujo (`set_read_timeout`
  solo existe en sus constructores con `TcpStream`), y su hilo lector mantiene el `Mutex` del
  flujo mientras lee (`raw_client.rs:820`, `recv`). `FlujoTunel` debe implementar **plazo de
  lectura y de escritura** por dentro (espera con `Condvar::wait_timeout` y error
  `io::ErrorKind::TimedOut`), con el mismo valor `ESPERA_RED` que hoy se pone en el `TcpStream`;
  de lo contrario un nodo mudo dejaría la lectura bloqueada para siempre.
- **Tipo del flujo en `Conexion`.** Hoy `FlujoTls = StreamOwned<ClientConnection, TcpStream>` y
  `Conexion::Pinada` lo guarda en concreto (`nodo.rs:178-186`). Pasa a
  `StreamOwned<ClientConnection, Box<dyn ReadWrite + Send>>` (con `trait ReadWrite: Read + Write`),
  de modo que `Pinada` sirve para el `TcpStream` directo y para `FlujoTunel` sin duplicar la
  variante; la macro `con_api!` no cambia.

### 1.3 Cambios en el motor y en el FFI

- Nuevo `enum Transporte { Directo, Tunel(Arc<TunelPropio>) }` en `nodo.rs`. `Conexion::abrir`
  recibe el transporte; con `Directo` todo sigue igual; con `Tunel` **solo** se admite `ssl://`
  con huella (el `(Esquema::Tcp, _)` y la rama «sin huella» devuelven error en v1) y el host del
  servidor debe ser una IP literal (§3.1).
- Nuevo objeto UniFFI `TunelPropio` (`#[derive(uniffi::Object)]`, `Send + Sync` por `Arc` y
  `Mutex`), con: constructor `levantar(config: ConfigTunel, clave_privada_movil: &[u8])`
  (32 bytes; se copian a un `Zeroizing<[u8; 32]>` y el búfer de UniFFI se pone a cero del lado
  Kotlin en un `finally`, igual que el mnemónico), `bajar()`, `estado() -> EstadoTunel`
  (apretón hecho o no, segundos desde el último, bytes, último error), `esta_levantado()`. Al
  `bajar()` (o al soltar el `Arc`): se para el hilo, se cierra el UDP, se descarta `Tunn` y se
  borra la privada (`zeroize`).
- Funciones libres `clave_publica_de(clave_privada: &[u8]) -> Vec<u8>` y
  `nueva_clave_tunel() -> Vec<u8>` (32 bytes de `StaticSecret::random_from_rng`; las dos copias
  que UniFFI deja al devolver un `Vec` son el mismo límite ya documentado para `nueva_semilla`
  en el README).
- `comprobar_nodo`, `altura_red`, `estimar_comision` y los métodos de red de `Billetera`
  (`escanear`, `sincronizar`, `difundir`) reciben `tunel: Option<Arc<TunelPropio>>`; `None`
  es `Directo`. En Kotlin, `ConexionMotor` lleva el `TunelPropio` cuando el modo es `PROPIO`, y
  `WalletRepository.conRed` lo pasa tal cual: la billetera no sabe de túneles, solo de transporte.
- `ConfigNodo.invalida()` deja de rechazar `PROPIO`; en su lugar exige `tunelPropio != null`,
  `direccion_movil` presente, `ssl://` con huella y host IP literal contenido en `ips_permitidas`.

### 1.4 Estimación: el adaptador es la mayor parte de E-3

| Parte | Contenido | Estimación (días de trabajo efectivo, a contrastar) |
|---|---|---|
| **Adaptador de transporte** | `Device` virtual, hilo bomba con temporizadores de boringtun y `poll` de smoltcp, `FlujoTunel` con plazos y cierre limpio, `Transporte` en `Conexion`, objeto UniFFI, pruebas de bucle local (§9.1) | **8 – 12** |
| Clave del móvil | generación, envoltura con Keystore, desenvoltura en memoria, olvidar/rotar | 2 – 3 |
| Paquete y escáner | campos nuevos, regla de forma WireGuard, `emparejar.sh --autorizar-movil`, pruebas | 2 – 3 |
| Palabras de comprobación | derivación en motor y en `emparejar.sh`, vector fijo compartido, vista previa | 1 – 2 |
| NodeBridge y pantallas | ciclo de vida, textos, estado del túnel, QR de la pública del móvil | 3 – 4 |
| Banco regtest con WireGuard, prueba de cero SYN, recorrido §5.3 | §9.2 – §9.4 | 3 – 4 |

El adaptador se construye y se prueba **primero y aislado** (contra el simulado
`mock_electrum.rs` por un túnel de bucle local), antes de tocar paquete o pantallas: si no
cierra, el resto no tiene sentido.

## 2. Clave del túnel del móvil

- **Generación**: par x25519 en el motor (`x25519-dalek`, que boringtun ya arrastra), nunca en
  Kotlin. La privada son 32 bytes; la pública se deriva de ella cuando haga falta.
- **En reposo**: la privada se guarda **envuelta** con una clave AES-256-GCM del Keystore de
  Android, con el mismo patrón que `AlmacenSemilla.kt`: fichero en `noBackupFilesDir/x39-wallet/`
  (`tunel.enc`), cabecera (versión, longitud del IV) ligada por AAD, escritura por fichero
  temporal + `fsync`, StrongBox si lo hay y si no TEE. **Diferencias con la semilla**: otro
  alias de clave (`x39-tunel`), y **sin** `setUserAuthenticationRequired`: el túnel tiene que
  poder levantarse al sincronizar sin pedir biometría (justificación abajo).
- **En uso**: se desenvuelve a memoria del motor (`Zeroizing<[u8; 32]>`) **solo** al levantar el
  túnel; `Tunn` la copia a su propio `StaticSecret` (que `x25519-dalek` también borra al soltar).
  Al bajar el túnel se descarta todo. La privada cruza a Kotlin en claro solo el instante de
  pasarla al motor (búfer directo puesto a cero en `finally`, como el mnemónico) y nunca se
  muestra ni se exporta. Se minimizan las copias y se borran los búferes controlados;
  permanecen las limitaciones de UniFFI descritas en §1.3.
- **Honestidad sobre el límite**: el Keystore protege la clave **en reposo**, no **en uso**.
  Mientras el túnel está levantado la privada está en la memoria del proceso. Y como la clave del
  Keystore que la envuelve **no exige autenticación por operación**, un proceso comprometido
  puede descifrar `tunel.enc` **en cualquier momento**, con el túnel levantado o no: no tiene
  que esperar a nada. La exposición es, por tanto, «en cualquier momento con el proceso
  comprometido». Es un límite peor que el de la semilla (que sí exige autenticación por
  operación), y se acepta porque la clave del túnel no da acceso a los fondos (abajo). Las
  mitigaciones (borrado explícito, vida corta en memoria, copias minimizadas y búferes
  controlados borrados, con las limitaciones de UniFFI de §1.3) reducen la ventana en el uso,
  no en el reposo.
- **Por qué no exige biometría por sesión**: la clave del túnel da acceso **al nodo** como este
  móvil (lo que el servidor Electrum deja ver: direcciones, saldos, difundir transacciones
  firmadas por otro), **no a los fondos**: la clave del túnel y la semilla son independientes, y
  poseer solo la del túnel no permite derivar la semilla ni firmar; firmar sigue exigiendo la
  semilla y su autenticación por operación. (Un proceso comprometido por completo sí puede
  interceptar la semilla cuando se descifra para firmar, §6; eso no cambia con la clave del
  túnel ni lo arregla ninguna biometría de sesión.) Pedir biometría para cada sincronización
  en segundo plano haría inútil la vigilancia del nodo y empujaría al usuario a `NINGUNO`. Si
  en el futuro se quiere, es un ajuste, no un cambio de diseño.
- **Olvidar túnel** (`olvidar()` de NodeBridge, y una acción propia «regenerar clave del
  túnel»): baja el túnel, borra `tunel.enc`, genera un par nuevo y muestra la pública nueva como
  QR; el operador la autoriza en el nodo y retira la anterior (`nodebridge.md` §5.3, rotación).
  El móvil no puede revocar nada en el nodo: solo dejar de poder entrar.
- **Fuera de v1** (§11): delegar los DH estáticos del apretón de manos al Keystore (acuerdo de
  claves X25519 en el Keystore, `PURPOSE_AGREE_KEY`, de modo que la privada no salga nunca del
  hardware). Exigiría un boringtun modificado (el apretón de manos de Noise IKpsk2 calcula los
  DH estáticos dentro de `Tunn`) y Android 13 o superior; la disponibilidad real de X25519 en
  el Keystore de cada aparato hay que comprobarla (§10).

## 3. Garantía de enrutado

### 3.1 Por construcción

- En `PROPIO` **no existe ningún camino de código** que abra un `TcpStream` del sistema hacia el
  nodo: `Conexion::abrir` con `Transporte::Tunel` construye el flujo a partir del túnel y la rama
  `TcpStream::connect_timeout` de `conectar_pinado` no se ejecuta (se separa en dos funciones
  para que esto sea evidente en el código y en la revisión).
- La dirección del nodo dentro del túnel es **solo una IP literal** y debe estar contenida en
  `ips_permitidas` (comprobado en Kotlin al importar y en Rust al abrir). No hay DNS dentro del
  túnel (`socket-dns` apagado): un nombre en el campo `electrum` se rechaza en `PROPIO`.
- El **endpoint WireGuard** puede ser IP o nombre. Un nombre lo resuelve el **sistema**
  (`ToSocketAddrs` sobre el UDP), fuera del túnel: la consulta DNS revela ese nombre al resolutor
  de la red activa (operador, Wi-Fi, o la VPN del sistema si hay una) y a quien vea el tráfico
  DNS. El texto de ayuda lo dice; la recomendación es una IP o, si es un nombre, asumir que el
  nombre no es secreto. Lo que sí queda oculto es todo lo demás: el host Electrum, el puerto, el
  protocolo y el contenido.
- El **único socket del sistema** que abre el motor en `PROPIO` es el UDP hacia el endpoint. Sin
  `VpnService`, no se tocan las rutas del aparato ni las de otras apps. El UDP exterior sigue
  el enrutamiento de Android y puede atravesar la VPN del sistema si hay una activa (depende de
  sus rutas y de qué apps incluya); en ese caso el nodo ve la salida de esa VPN como origen del
  túnel.

### 3.2 Pruebas de aceptación (medibles)

Con el banco regtest de `PRUEBAS.md` §2 ampliado con un peer WireGuard en el host (§9.2) y
electrs escuchando **también** en la interfaz de la red local (para que «alcanzable en directo»
sea verdad):

| # | Situación | Esperado |
|---|---|---|
| R0 | **Control positivo**: modo `NINGUNO` contra la **misma tupla** que usa `PROPIO` (el host **efectivo** de `--electrum`, que es la IP interna del túnel, normalmente `direccion_nodo` o, si electrs está en otra máquina, otra IP de `ips_permitidas`, nunca la de la red local; el mismo puerto; `ssl://` y la misma huella), desde una posición de red donde eso sea alcanzable en directo (en el banco, exponiendo electrs en esa IP), con la misma captura y el mismo filtro | la captura **ve** ese SYN; si no lo ve, la captura está mal y ningún cero posterior vale. R0 fija además el **origen observado**: la IP de origen que aparece en la captura, que tras NAT o con una VPN del sistema puede no ser la que el móvil cree tener |
| R1 | Túnel caído (peer del host parado) y electrs alcanzable en directo desde el móvil | la billetera **NO** conecta: `Caido` con motivo del túnel; nada de datos |
| R2 | R1 tras cambiar Wi-Fi ↔ datos | igual que R1 |
| R3 | R1 tras modo avión activado y desactivado | igual que R1 |
| R4 | R1 tras matar y reabrir el proceso de la app | igual que R1 |
| R5 | Túnel levantado, `tcpdump` en el host sobre la interfaz de la red local durante escaneo, sincronización, difusión y vigilancia de 10 minutos | **cero** SYN desde la IP del móvil al puerto de electrs; todo el tráfico del móvil al host es UDP al puerto del endpoint |
| R6 | R5 con la vigilancia de NodeBridge activa durante una caída y vuelta del peer | la reconexión vuelve a pasar **todas** las puertas por el túnel; cero SYN en directo también durante la caída |

Filtro de referencia para R5/R6. `<host-electrs>` y `<puerto-electrs>` son los **efectivos** de
`--electrum` (el host es `direccion_nodo` o, con electrs en otra máquina, otra IP de
`ips_permitidas`: lo mismo que `PROPIO` marca en los dos casos de §4.5); `<origen-observado>` es
la IP de origen **vista en la captura de R0**, no la que el móvil cree tener (tras NAT o con una
VPN del sistema pueden diferir):
`tcp[tcpflags] & tcp-syn != 0 and dst host <host-electrs> and dst port <puerto-electrs> and src host <origen-observado>`.
Tras cada cambio de red (R2, R3) el origen observado puede cambiar: se repite R0 para fijarlo de
nuevo y se repite la captura con ese origen en `src host`; una captura con el origen anterior no
vale.

### 3.3 Qué NO garantiza

- Que el nodo sea quien dice: eso lo garantiza el apretón de manos WireGuard con la clave
  pública del paquete, y la huella SPKI dentro; y las dos dependen de que el paquete sea
  auténtico (§5).
- Nada sobre el tráfico de **otras** apps ni sobre el resto de la app de comunicaciones.
- Que el nodo no esté comprometido (§6).

### 3.4 Textos de ayuda (los tres modos)

- `NINGUNO`: «El nodo se alcanza directamente (tu red local o un nodo con TLS público). La app
  comprueba la red, la huella del certificado, la altura y el protocolo en cada conexión.»
- `EXTERNO` (reescrito): «Hay una VPN activa en el aparato y el nodo se alcanza a través de ella.
  La app **no puede comprobar** que el tráfico hacia este nodo vaya por esa VPN: Android permite
  rutas por destino y la app no ve las rutas. Si quieres forzarlo, actívalo tú en los ajustes de
  Android de esa VPN («bloquear conexiones sin VPN»). La app sí exige que la VPN esté activa y
  sigue comprobando la huella del certificado.»
- `PROPIO` (nuevo): «La app levanta su propio túnel WireGuard hasta tu nodo, dentro de la app y
  sin tocar las VPN del aparato. Por construcción, hacia este nodo **solo** sale tráfico por ese
  túnel: no existe otra vía. Dentro del túnel se sigue comprobando la huella del certificado.
  Lo que la red ve: paquetes UDP hacia la dirección y el puerto del túnel, y, si el endpoint es
  un nombre, la consulta DNS de ese nombre.»

## 4. Paquete y escáner

### 4.1 Extensión del objeto `tunel` (paquete v1)

Solo datos públicos. Campos actuales (E-2) y nuevos:

| Campo | Regla | Estado |
|---|---|---|
| `clave_publica_nodo` | 32 bytes en base64 (44 caracteres acabados en `=`) | E-2 |
| `endpoint` | `host:puerto` UDP del nodo (IP o nombre; §3.1) | E-2 |
| `ips_permitidas` | lista no vacía de redes (`A.B.C.D/n`; IPv6 se valida pero v1 no la usa, §10) | E-2 |
| `direccion_movil` | `A.B.C.D/32`; **obligatoria** en `PROPIO` (es la dirección de la interfaz smoltcp) | E-2, pasa a obligatoria |
| `direccion_nodo` | IP literal del nodo dentro del túnel (`A.B.C.D`); debe estar contenida en `ips_permitidas` | **nuevo** |
| `keepalive` | entero 0..65535 (segundos; 0 = sin keepalive); por defecto 25 si falta | **nuevo** |

Reglas añadidas al importar: con `tunel` presente y modo `PROPIO`, el host de `electrum` debe ser
una IP literal contenida en `ips_permitidas` (normalmente igual a `direccion_nodo`; puede ser
otra de la misma red si electrs vive en otra máquina detrás del nodo). `ips_permitidas` conviene
que sea lo mínimo (`direccion_nodo/32`): es lo único que la app enrutará por el túnel.

El esquema sigue **cerrado** y `v` sigue en 1: una compilación E-2 que reciba un paquete con
`direccion_nodo` lo rechaza con «campo desconocido en «tunel»» (falla cerrado, no a medias); como
el `.lab` no está publicado, no hace falta versión 2 (§10 deja la alternativa anotada).

### 4.2 El escáner de claves privadas y la forma de una clave WireGuard

Comprobado en `Emparejamiento.kt` (`buscarClavesPrivadas`, l. 131; `pareceClavePrivada`, l. 125):
el escáner de E-2 mira los **nombres** de campo (`priv|secret|secreto|semilla|seed|mnemo|xprv|
tprv|vprv|wif|passphrase|contrase|interface`) y, en los **valores**, PEM privada, clave
extendida privada, WIF y doce o más palabras seguidas. Una clave WireGuard (44 caracteres base64)
**no coincide con ninguna de esas formas**: el escáner actual no la marca, ni dentro ni fuera de
`tunel.clave_publica_nodo`. Así que la premisa «el escáner la marcaría» no se da; lo que sí se da
es la limitación ya documentada: una privada WireGuard tiene la misma forma que una pública y no
se puede distinguir.

Lo que E-3 define, en positivo, es la **excepción acotada a una ruta**: se añade una regla de
forma «clave WireGuard» que busca **dentro** del valor (`containsMatchIn` con
`(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/=])`, no `matches` sobre el valor entero),
para cazar una clave con prefijo, sufijo o puntuación alrededor, y que **rechaza** el paquete
entero si aparece en cualquier ruta que no sea exactamente `/tunel/clave_publica_nodo`
(incluidos `nombre`, `electrum`, `endpoint` y cualquier elemento de lista). Dentro de esa única
ruta el valor entero debe ser la clave (`BASE64_32`, como hoy) y se acepta como pública, con la
misma advertencia que hoy. Lección de E-2, anotada para no repetirla: la misma clase de fallo
(comprobar el valor entero con `matches` en vez de buscar dentro con `containsMatchIn`) se
corrigió en E-2 para las claves extendidas y los WIF. El filtro por nombre sigue
tumbando `psk`, `preshared`, `private`, `interface`… (se añaden `psk` y `preshared` a la lista),
de modo que ningún campo de clave compartida entra por mucho que su valor tenga forma de pública.

`emparejar.sh` aplica la misma regla antes de escribir (hoy ya solo acepta la pública por
argumento y nunca lee ficheros de WireGuard).

### 4.3 Sin PSK en v1

El paquete v1 no lleva clave precompartida y el motor no la usa (`preshared_key: None` en
`Tunn::new`). El protocolo WireGuard es conforme sin ella. v1 no utiliza PSK ni anuncia
resistencia poscuántica; una incorporación futura requerirá evaluación separada. Lo único que
queda fijado desde ya es que un secreto así nunca iría dentro del paquete ni del QR.

### 4.4 La clave pública del móvil (lo que el móvil muestra)

El móvil muestra su pública como QR y como texto para que el operador la autorice en el nodo.
Formato mínimo, JSON de una línea, solo datos públicos:

```json
{"v":1,"tipo":"clave_publica_movil","red":"signet","clave_publica_movil":"<base64 de 32 bytes>"}
```

Junto al QR, el móvil muestra sus **palabras de comprobación** (§5.3), que no van en el QR. El
texto de la pantalla dice qué es y qué no es: «Esta clave identifica a este móvil en tu nodo. No
da acceso a tus fondos. Compara las palabras con las que imprime la herramienta del nodo.»

### 4.5 Paso nuevo de `herramientas/nodo/emparejar.sh`

- `--autorizar-movil '<clave_publica_movil>' --direccion-movil A.B.C.D/32 [--interfaz wg0]`:
  valida la forma (44 caracteres base64; se niega a leer ficheros), **imprime** el bloque
  `[Peer]` (`PublicKey`, `AllowedIPs = A.B.C.D/32`) y la orden `wg set <interfaz> peer <clave>
  allowed-ips A.B.C.D/32` para que el operador la aplique, y las palabras de comprobación del
  móvil (§5.3) para que las compare con la pantalla del móvil. Con `--aplicar` ejecuta `wg set`
  (requiere permisos en el nodo); por defecto no toca nada.
- **`AllowedIPs` en el nodo filtra el ORIGEN**, no el destino: dice qué IP de origen se acepta
  de este móvil (y por qué clave se enruta), no a qué puede llegar el móvil. Sin reglas de
  cortafuegos en el nodo, un móvil autorizado alcanza **cualquier destino y puerto** que el nodo
  enrute por su interfaz WireGuard. Por eso `--autorizar-movil` imprime además la regla de
  cortafuegos recomendada (en `nftables` y en `iptables`) para la IP y el puerto **efectivos**
  de `--electrum` (§4.1 admite electrs en otra máquina detrás del nodo), en dos casos: electrs
  en el propio nodo → regla en la cadena de **entrada** (permitir `direccion_movil` → host y
  puerto de `--electrum`); electrs en otra máquina → regla en la cadena de **reenvío** desde la
  interfaz WireGuard hacia ese host y puerto, con NAT de origen si la red de destino no enruta
  `direccion_movil`. En los dos casos, denegar el resto de lo que entre por la interfaz
  WireGuard desde ese móvil. **Imprimirla no la aplica**: la restricción no está en vigor hasta
  que el operador la aplique en el nodo, y la herramienta lo dice al imprimirla.
- Emisión del paquete extendido: las opciones actuales de túnel más `--tunel-direccion-nodo
  A.B.C.D` y `--tunel-keepalive N`; la herramienta exige `--direccion-movil` cuando hay túnel y
  comprueba que el host de `--electrum` es una IP contenida en alguna `--ip-permitida`. Imprime
  las palabras de comprobación del paquete (§5.2) debajo del QR.
- `pruebas/probar.sh` añade: vector fijo de palabras (§5.4), rechazo de una clave con forma
  WireGuard en `--nombre` o en el host, rechazo de `--electrum` con nombre cuando hay túnel.

## 5. Autenticidad

### 5.1 Modelo de confianza, dicho explícitamente

El paquete de emparejamiento **no se autentica solo**: no lleva firma y no hay autoridad que la
pudiera verificar. La confianza viene de que el paquete llega por un canal que controla el
operador (el QR en la pantalla del nodo o de su ordenador, el fichero copiado a mano) en el
**primer uso**, y de que lo que el paquete fija (clave pública del nodo, huella SPKI, endpoint)
decide todo lo demás. Quien pueda sustituir ese paquete en ese canal puede hacer que el móvil
hable con **su** nodo. Lo mismo, en sentido contrario, con la clave pública del móvil: quien la
sustituya antes de que el operador la autorice mete **su** móvil en el nodo.

Hoy (E-2) la comparación es implícita: la vista previa muestra la huella abreviada y el usuario
decide. E-3 la hace explícita y corta con palabras de comprobación. Esto **mejora E-2 tal cual**
y no cambia el esquema del paquete: la cadena se calcula a partir de lo que ya viaja, no se
transmite.

### 5.2 Palabras de comprobación del paquete

- Principio: **lo que se ve es lo que se verifica**. La entrada cubre **todos** los campos que
  la vista previa muestra y el usuario confirma, no solo los criptográficos: ningún campo
  mostrado puede cambiar sin que cambien las palabras.
- Entrada: serialización **canónica**, en este orden fijo y con un byte `0x00` entre campos
  (para que campos de longitud variable no se confundan): `"x39-nodebridge-comprobacion-v1" ‖
  v ‖ red ‖ electrum ‖ huella_spki_sha256 ‖ nombre ‖ emitido ‖ caduca ‖ tunel.clave_publica_nodo
  ‖ tunel.endpoint ‖ tunel.direccion_nodo ‖ tunel.ips_permitidas (sus elementos en el orden del
  paquete, unidos por `,`) ‖ tunel.direccion_movil ‖ tunel.keepalive`. Cada campo va como el
  **texto** que lleva el paquete, sin normalizar (si difiere en una letra, las palabras
  difieren); un campo ausente va vacío (la huella, con `tcp://`). Precedencia explícita para el
  túnel: **sin** objeto `tunel`, **todos** sus campos van vacíos, `keepalive` incluido; **con**
  objeto `tunel` pero sin `keepalive`, va `25`, que es lo que el móvil aplica. La serialización
  es **idéntica** en el motor y en `emparejar.sh`, y está fijada por el vector (§5.4).
- Resumen: SHA-256 de la entrada. De los primeros 66 bits salen **6 palabras** de 11 bits
  (índices de la lista BIP39 en inglés, que el motor ya trae con la crate `bip39`; la lista en
  español es una pregunta abierta, §10).
- Quién la calcula: el motor (`palabras_comprobacion(...)`, expuesta por FFI) y
  `emparejar.sh` (`openssl dgst -sha256` sobre la misma entrada y la misma lista, incluida en
  `herramientas/nodo/` como fichero de 2048 líneas). Un vector fijo compartido en
  `rust/src/tests.rs` y en `pruebas/probar.sh` garantiza que las dos implementaciones coinciden,
  como hoy con la huella del vector.
- Dónde se ve: `emparejar.sh` la imprime debajo del QR; la vista previa del móvil la muestra en
  grande encima de Confirmar, con el texto «Compara estas 6 palabras con las que imprimió tu
  nodo. Si no coinciden, descarta.» No sustituye a la huella entera (que sigue disponible):
  la acompaña.
- Por qué 6: 66 bits hacen que fabricar un paquete alternativo que dé las mismas palabras
  cueste del orden de 2^66 cálculos de SHA-256 (una segunda preimagen sobre las palabras); 4
  palabras (44 bits) estarían al alcance de un atacante con GPUs en horas. Seis palabras se
  comparan en unos segundos.

### 5.3 Palabras de comprobación de la clave del móvil (simétrico)

Entrada: `"x39-nodebridge-movil-v1" ‖ red ‖ 0x00 ‖ clave_publica_movil (32 bytes crudos)`; mismo
resumen, mismas 6 palabras. El móvil las muestra junto a su QR (§4.4); `emparejar.sh
--autorizar-movil` las imprime antes de proponer el `wg set`. El operador compara antes de
autorizar.

### 5.4 Vector fijo

Dos paquetes de ejemplo, uno **completo** (todos los campos, con túnel, incluidos
`direccion_nodo`, `direccion_movil`, `ips_permitidas` con dos elementos y `keepalive`) y otro
**sin túnel** (con los datos genéricos de `docs/nodebridge.md` §5.1 y el vector
`pruebas/vector.pem`), más una clave de móvil de ejemplo (32 bytes `0x01..0x20`), cada uno con
su serialización canónica **en bytes** (el vector sin túnel con sus seis campos de túnel vacíos,
`keepalive` incluido, byte a byte) y sus palabras esperadas, fijados en `rust/src/tests.rs` y
en `herramientas/nodo/pruebas/`. Si cambia la derivación, cambia la etiqueta (`-v2`) y el
vector; nunca en silencio.

## 6. Modelo de amenazas

| Amenaza | `PROPIO` | Comentario |
|---|---|---|
| Escucha o manipulación en el camino (Wi-Fi ajena, operador, VPN de terceros) | **Protege** | WireGuard (autenticado por la clave del nodo) y, dentro, TLS con huella fijada. |
| Nodo suplantado (DNS, redirección, endpoint cambiado en la red) | **Protege** | El apretón de manos solo prospera con la privada del nodo que corresponde a la pública del paquete; además la huella SPKI dentro. |
| Otra app del aparato alcanza el nodo a través de la billetera | **Protege** | Flujo en proceso: no hay puerto local ni interfaz de VPN. |
| Paquete de emparejamiento sustituido en el primer uso | **No protege por sí solo** | Mitigación: palabras de comprobación (§5) y canal controlado por el operador. |
| Clave pública del móvil sustituida al autorizarla | **No protege por sí solo** | Mitigación: palabras de comprobación simétricas (§5.3). |
| Móvil comprometido (root, malware con acceso al proceso) | **No protege** | Un proceso comprometido puede descifrar la clave del túnel **en cualquier momento** (su clave de Keystore no exige autenticación por operación, §2) y, con el túnel levantado, leerla de memoria. Poseer **solo** la clave privada WireGuard no permite derivar la semilla ni firmar pagos: son claves independientes. Esa garantía no se extiende al control completo del proceso, que sí puede interceptar la semilla cuando se descifra en memoria para firmar. |
| Móvil autorizado que alcanza otros servicios del nodo o de su red | **No protege por sí solo** | `AllowedIPs` filtra el origen, no el destino (§4.5): sin cortafuegos en el nodo, el móvil llega a todo lo que el nodo enrute por la interfaz WireGuard. La regla que imprime `emparejar.sh` (§4.5) lo acota al host y puerto efectivos de electrs, pero solo cuando el operador la aplica: impresa no protege nada. |
| Nodo comprometido | **No protege** | El nodo ve lo que ve cualquier servidor Electrum del usuario: direcciones, saldos, transacciones. No ve la semilla ni puede firmar. |
| Metadatos del endpoint | **No oculta** | IP y puerto del túnel, tamaño y ritmo de los datagramas, y la consulta DNS si el endpoint es un nombre. Quien observe la red del móvil sabe que habla con ese endpoint, no qué. |
| Pérdida del móvil con el túnel configurado | Revocación desde el nodo | El operador retira el `[Peer]` (`nodebridge.md` §5.3); hasta entonces, ese móvil (o quien lo desbloquee) puede llegar al nodo. |
| Denegación: UDP bloqueado, NAT que expira | Se detecta, no se evita | Estado `Caido` con motivo; nunca se degrada a directo (§7). |

## 7. Ciclo de vida

- **Levantar**: NodeBridge, en modo `PROPIO`, levanta el túnel (idempotente) antes de cualquier
  `comprobar()`: al abrir la billetera (`onResume` → `vigilar(true)`), al sincronizar, al enviar
  (difundir), al probar o adoptar un candidato con túnel (el candidato usa un `TunelPropio`
  **aparte**, con la misma privada; se descarta si se descarta el candidato). Levantar =
  desenvolver la privada, crear `Tunn`, abrir el UDP, lanzar el hilo, enviar el apretón de
  manos y esperar al primer `time_since_last_handshake()` válido con plazo (5 s; WireGuard
  reintenta solo cada 5 s). Si no hay apretón: `Caido("túnel: el nodo no responde al apretón de
  manos WireGuard (¿está este móvil autorizado en el nodo? ¿endpoint y puerto correctos?)")`.
  WireGuard es **mudo** con un peer no autorizado, así que el móvil no puede distinguir «no
  autorizado» de «inalcanzable»: el texto lo dice en vez de adivinar.
- **Puertas**: tras el apretón, `comprobar_nodo` por el túnel, en el orden de siempre. Un túnel
  levantado con puertas fallidas es `Caido` (o `RedIncorrecta`/`HuellaIncorrecta`) exactamente
  como hoy; el túnel se mantiene levantado para el siguiente intento mientras haya vigilancia.
- **Keepalive**: el del paquete (25 s por defecto) solo mientras el túnel está levantado; sirve
  para mantener el NAT. En reposo no hay otro tráfico.
- **Bajar**: a los **N = 2 minutos** de pasar la app a segundo plano (`vigilar(false)` arranca
  el temporizador; `vigilar(true)` lo cancela), al `olvidar()`, al regenerar la clave, y al
  destruir el repositorio. Sin túnel no hay hilo ni socket: no se consume batería ni se mantiene
  el NAT. Esto implica que **no hay sincronización en segundo plano** en v1 (tampoco la hay hoy).
- **Reconexión**: ante cualquier fallo de red (plazo agotado, UDP con error, `Tunn::is_expired`,
  cambio de red notificado por `ConnectivityManager`) se **baja y se vuelve a levantar** el
  túnel y se vuelven a pasar **todas** las puertas. Esperas con la misma `esperaMs(intento)` de
  NodeBridge (creciente con azar). **Nunca** se degrada a directo: no hay rama de código para
  ello, y el estado dice por qué no hay conexión.
- **Doze y batería**: ningún servicio en primer plano, ningún `WorkManager`, ningún
  `AlarmManager`. El hilo bomba duerme en `recv` con plazo; con el túnel bajado no existe. El
  coste se mide en §9.4 y se anota en `PRUEBAS.md`.
- **Una VPN del sistema activa**: no impide `PROPIO` (no hay conflicto de `VpnService`); el UDP
  exterior sigue el enrutamiento de Android y puede atravesar la VPN del sistema. La tabla de
  `nodebridge.md` §6 («solo si NO hay otra VPN activa») queda **sustituida** por esta regla: el
  texto de ayuda lo explica, y la decisión queda anotada en §10 por si se prefiere mantener la
  restricción.

## 8. Coste de build

Dependencias nuevas del motor (versiones y licencias consultadas en crates.io el 31/10/2026;
se fijan en `Cargo.lock` al implementar):

| Crate | Versión | Licencia | Notas |
|---|---|---|---|
| `boringtun` | 0.7.1 | BSD-3-Clause | Dependencias normales: `ring ^0.17`, `x25519-dalek ^2`, `chacha20poly1305 ^0.10.0-pre`, `blake2`, `hmac`, `aead ^0.5.0-pre`, `parking_lot`, `nix ^0.31`, `ip_network`, `ip_network_table`, `tracing`, `portable-atomic`, `libc`, `base64`, `hex`, `untrusted`, `rand_core`. Features `device`, `jni-bindings`, `ffi-bindings` apagadas. |
| `smoltcp` | 0.14.0 | 0BSD | MSRV 1.91 (la toolchain del repo es 1.98.1). Dependencias: `heapless`, `managed`, `bitflags`, `byteorder`, `cfg-if`. Features `std`, `medium-ip`, `proto-ipv4`, `socket-tcp`; sin `log`/`defmt`. |
| `x25519-dalek` | la que fije boringtun (2.x) | BSD-3-Clause | Con feature `zeroize`. No se añade la 3.0.0 aparte para no duplicar la crate. |
| `onetun` | — | MIT | **No es dependencia**: patrón de referencia. |

Puntos que hay que medir y reverificar, no suponer:

- **`ring` junto a `aws-lc-rs`**: hoy rustls usa `aws-lc-rs` (`Cargo.toml` del motor;
  `ring` figura en `Cargo.lock` pero **hoy, sin boringtun**, no se compila para
  `aarch64-linux-android`, comprobado con `cargo tree -i ring --target aarch64-linux-android`;
  **al añadir boringtun sí se compila**, y §8.1 mide que lo que queda de él es mínimo).
  boringtun 0.7.1 arrastra `ring 0.17` como dependencia no opcional. **Medido (§8.1)**: `ring`
  **es una segunda implementación criptográfica en el binario**, mínima en el enlace: sobreviven
  tres puntos de entrada, `chacha20_poly1305_seal`, `chacha20_poly1305_open` y `CRYPTO_memcmp`,
  que arrastran sus núcleos internos (ChaCha20 y Poly1305 en C y ensamblador); boringtun le
  delega el cifrado del canal de datos y nada más. Decisión: `ring` **se mantiene en v1**. Los
  criterios para reconsiderarla son tres, y el tamaño no es uno de ellos: la **reproducibilidad**
  de la compilación, el **mantenimiento** de la dependencia (actualizaciones y avisos de
  seguridad de dos bibliotecas en vez de una) y el **alcance de auditoría** (dos implementaciones
  de primitivas que revisar). Las vías serían un parche a boringtun o pasar rustls al proveedor
  `ring` (`use-rustls-ring` de `bdk_electrum`).
- **Reproducibilidad**: `build-native.sh` ya fija NDK, cargo-ndk, toolchain y `--locked`, y
  tiene modo `--verificar` en directorio limpio. **No se ha comprobado** con las crates nuevas:
  se verifica sobre la integración real (dos compilaciones limpias, mismo hash); es el primero
  de los tres criterios de arriba (su C y ensamblador entran en la ecuación).
- **Alineación de 16 KB**: medida en §8.1, los tres segmentos LOAD siguen en `0x4000` con los
  objetos de `ring` enlazados; se vuelve a comprobar en cada compilación, como hoy.
- **Tamaño**: la medición del ancla (§8.1) da un **suelo de +0,17 MiB** con strip. La
  estimación previa de este documento (entre 1,5 y 3 MB) era para la integración completa, otro
  alcance; se confirma o se corrige al medirla.
- **Tiempo de compilación** en la máquina de desarrollo (ajustada de memoria): `ring` compila C y
  ensamblador con el clang del NDK; en la medición, el motor entero con boringtun y `-j1` tardó
  4 minutos desde cero. Se anota en `PRUEBAS.md` la primera vez que se compile la integración.

### 8.1 Medición de viabilidad (31/10/2026)

Hecha sobre una **copia** del motor fuera del repo, sin tocar el repo ni confirmar nada; es
una medición, no implementación. Copia de `rust/` con `boringtun 0.7.1` (`default-features =
false`) y `smoltcp 0.14.0` (`std`, `medium-ip`, `proto-ipv4`, `socket-tcp`), más una función
ancla exportada (`#[no_mangle] pub extern "C"`) que ejerce las dos pilas para que el enlazador
las conserve: `Tunn::new` sin PSK y con keepalive 25, `encapsulate`, `decapsulate` y
`update_timers`; y una `Interface` de smoltcp sobre un `Device` trivial (medio IP, MTU 1420),
un socket TCP añadido y conectado, y un `poll`. Compilación con el mismo NDK (30.0.16248370),
`cargo ndk --platform 21 -t arm64-v8a build --release`, los mismos `RUSTFLAGS` de
`build-native.sh` (remap y `max-page-size=16384`) y `llvm-strip --strip-all`.

| Dato | Resultado |
|---|---|
| Compila para `aarch64-linux-android` | sí; `ring` compila su C y ensamblador con el NDK **sin avisos** |
| `.so` actual del repo (con strip) | 9.428.864 bytes (8,99 MiB) |
| `.so` con boringtun + ring + smoltcp y el ancla (con strip) | 9.611.464 bytes (9,17 MiB) |
| Engorde | **+182.600 bytes = +0,17 MiB (+1,9 %)** |
| Segmentos LOAD | los tres siguen alineados a `0x4000` |
| Símbolos de `ring` que sobreviven al enlace | 3: `chacha20_poly1305_seal`, `chacha20_poly1305_open`, `CRYPTO_memcmp` |
| `aws-lc-rs` | sigue presente (TLS de rustls), sin cambios |

Advertencia honesta: el engorde medido es un **suelo**, lo que alcanza el ancla. El adaptador
real (§1) usará más código de smoltcp (retransmisión, ventanas, cierre) y de boringtun (apretón
de manos completo, temporizadores, rate limiter); cuánto más, se mide sobre la integración. Lo
que esta medición demuestra es que el prototipo **compila y enlaza**, con ese tamaño y esa
alineación; **no** demuestra que el túnel funcione, ni Electrum y TLS por él, ni varios sockets
a la vez, ni la ausencia de salida directa: eso es §9. Sin el ancla, el enlazador elimina las
dos crates enteras y la `.so` queda idéntica a la actual: una medición sin ancla no mide nada.

## 9. Plan de pruebas

### 9.1 Unitarias (motor, `cargo test`; módulo Android, JVM)

- **Túnel de bucle local en proceso**: dos `Tunn` (móvil y «nodo») unidos por un canal en
  memoria que hace de UDP, dos pilas smoltcp, y el `mock_electrum.rs` detrás: `comprobar_nodo`
  por el túnel pasa las cinco puertas; huella distinta → `HuellaIncorrecta` dentro del túnel;
  génesis de otra red → `RedIncorrecta`.
- **`FlujoTunel`**: plazo de lectura con nodo mudo (`TimedOut` en `ESPERA_RED`, sin bloqueo del
  `Mutex` del lector), cierre limpio a mitad de lectura, escritura con el túnel caído → error,
  no pánico.
- **Sin camino directo**: prueba que, con `Transporte::Tunel`, `Conexion::abrir` con host no
  literal o con `tcp://` devuelve error antes de abrir nada (y una prueba de compilación: la
  función que abre `TcpStream` no es accesible desde la rama del túnel).
- **Claves**: `nueva_clave_tunel` → `clave_publica_de` coincide con `wg pubkey` sobre el mismo
  secreto (vector fijo); zeroize al soltar.
- **Palabras de comprobación**: vectores fijos (§5.4) en Rust y en `probar.sh`.
- **Mutación de la serialización**: una prueba por cada campo de §5.2 (`v`, `red`, `electrum`,
  `huella_spki_sha256`, `nombre`, `emitido`, `caduca` y los seis de `tunel`): cambiar **solo**
  ese campo → las palabras cambian. Y los dos casos límite de la precedencia: «con `tunel` sin
  `keepalive`» da las mismas palabras que «con `keepalive: 25`», y «sin `tunel`» da otras.
- **Concurrencia**: vigilancia activa (`comprobar_nodo` cada pocos segundos) a la vez que un
  escaneo largo y una difusión, contra el simulado por el túnel de bucle local, afirmando que
  cada operación va por un socket smoltcp **distinto** (identificadores distintos en el
  `SocketSet`) y que ninguna bloquea a la otra: la comprobación de salud termina dentro de su
  plazo mientras el escaneo sigue, y al revés.
- **Esquema y escáner (Kotlin)**: `direccion_nodo`/`keepalive` válidos e inválidos;
  `direccion_movil` obligatoria en `PROPIO`; host no contenido en `ips_permitidas`; clave con
  forma WireGuard en `nombre`, en `electrum`, en `endpoint`, dentro de una lista, y **con texto
  alrededor** («clave: <44 base64>.») → rechazo entero; en `tunel.clave_publica_nodo` →
  aceptada; campos `psk`/`preshared` → rechazo por nombre. Instrumentadas en el aparato las
  que dependan de `org.json` (como en E-2).
- **Envoltura de la clave (instrumentada)**: guardar/leer `tunel.enc`, cabecera manipulada no
  descifra, StrongBox o TEE anotado, fichero fuera de las copias de seguridad.

### 9.2 Integración en el banco regtest con un peer WireGuard

- Host: el banco de `PRUEBAS.md` §2 (Bitcoin Core + electrs) más una interfaz WireGuard en el
  host (el núcleo de la máquina de desarrollo tiene `CONFIG_WIREGUARD=m` y `wg`/`wg-quick`
  instalados; crear la interfaz exige permisos). electrs escucha en el host **efectivo** de
  `--electrum` (la IP interna del túnel: `direccion_nodo` o, con electrs en otra máquina, otra
  IP de `ips_permitidas`); para R0 y R1 esa **misma** IP se hace alcanzable en directo desde el
  móvil de pruebas fuera del túnel (alias de dirección en la interfaz de la red local del banco o
  ruta estática), de modo que la tupla sea idéntica. `tcpdump` hay que instalarlo.
- Móvil: `.lab` con un paquete generado por `emparejar.sh` con túnel; clave del móvil autorizada
  con `--autorizar-movil`.
- Secuencia: emparejar (vista previa con palabras, comparar, confirmar, adopción tras Sano por
  el túnel) → escanear → sincronizar → enviar y difundir por el túnel → minar → confirmar;
  después R1–R6 de §3.2, con el registro de `tcpdump` guardado íntegro en `PRUEBAS.md` (con la
  puerta de limpieza de IPs de siempre: solo bucle local o direcciones del túnel de ejemplo).

### 9.3 La prueba de cero SYN

Es la prueba que convierte «por construcción» en «medido»: primero el control positivo R0 (modo
`NINGUNO` contra la misma tupla que `PROPIO`: el host y el puerto efectivos de `--electrum`,
`ssl://` y la misma huella, alcanzable en directo; la captura **ve** ese SYN y fija el origen
observado; si no lo ve, la captura no vale), después R5 y R6 con el filtro de §3.2, 10 minutos
con vigilancia, y el recuento de paquetes del filtro **igual a cero**, mientras el recuento de
UDP al puerto del endpoint es mayor que cero; tras cada cambio de red, R0 repetido para fijar el
origen observado nuevo. Se repite tras cada cambio en el adaptador.

### 9.4 Recorrido manual §5.3 (`PRUEBAS.md`)

Como §5.2: generar la clave del móvil y mostrar el QR y las palabras; autorizar en el nodo
comparando palabras; importar el paquete con túnel por QR y por fichero, comparar palabras,
confirmar; saldo con «túnel propio · apretón hace N s · huella comprobada»; cambio Wi-Fi ↔
datos, modo avión, cierre de la app a los 2 minutos (túnel bajado: cero tráfico) y reapertura;
olvidar túnel y volver a emparejar con clave nueva; medida de batería en una hora con la app en
primer plano y vigilancia activa.

## 10. Preguntas abiertas (sin respuesta inventada)

1. **Pila criptográfica**: medido (§8.1): `ring` compila para Android sin avisos, boringtun usa
   tres puntos de entrada suyos y el conjunto boringtun + ring + smoltcp engorda la `.so` al
   menos +0,17 MiB; `ring` se mantiene en v1 como segunda implementación criptográfica, mínima
   en el enlace. Se reconsidera por tres criterios (§8): la **reproducibilidad bit a bit**
   (`--verificar`, pendiente, sobre la integración real), el mantenimiento de la dependencia y
   el alcance de auditoría.
2. **Lista de palabras**: BIP39 en inglés (ya en el binario) o en español (feature `spanish`
   de la crate `bip39`, lista adicional en `herramientas/nodo/`). El usuario compara palabras,
   no las escribe: la legibilidad manda; la decisión es del autor.
3. **Seis palabras**: ¿bastan 66 bits para el modelo de amenaza del primer uso, o se prefieren
   8 (88 bits) a costa de comodidad?
4. **X25519 en el Keystore**: `PURPOSE_AGREE_KEY` existe en la API desde Android 12 (comprobado
   en `android.jar` 35), pero la constante de algoritmo `XDH` no aparece en `KeyProperties` de
   esa API; hay que comprobar en el aparato de pruebas (Android 16) si `KeyPairGenerator` con
   `"XDH"` y `AndroidKeyStore` funciona, antes de contar con la opción de §11.
5. **IPv6**: `ips_permitidas` ya valida IPv6, pero v1 compila smoltcp solo con `proto-ipv4`.
   ¿Se exige IPv4 dentro del túnel en v1 (y se rechaza IPv6 al importar en `PROPIO`) o se
   compila `proto-ipv6` desde el principio?
6. **Versión del paquete**: mantener `v: 1` con campos nuevos (las compilaciones E-2 rechazan
   el paquete extendido, que es fallar cerrado) o pasar a `v: 2`.
7. **VPN del sistema activa**: §7 propone que no impida `PROPIO` (sustituye la regla de
   `nodebridge.md` §6). Si se prefiere mantener «no levantar con otra VPN activa», es un
   `if` y un texto, pero hay que decidirlo antes de escribir las pruebas.
8. **N minutos en segundo plano**: 2 es una propuesta; medir batería y NAT antes de fijarlo.
9. **MTU**: 1420 fija en v1. ¿Hace falta en el paquete para redes con sobrecarga extra?
10. **Candidato con túnel durante la adopción**: el candidato usa la misma privada del móvil que
    el túnel vigente (un móvil, una clave); con dos túneles levantados a la vez hacia nodos
    distintos no hay conflicto, pero hacia el **mismo** nodo con el mismo par puede haber dos
    sesiones WireGuard simultáneas desde la misma clave. Verificar que el nodo las admite, o
    bajar el vigente mientras se prueba el candidato (y decir que se bajó).
11. **electrs alcanzable en directo** para R0–R6: el banco actual escucha solo en bucle local
    (`PRUEBAS.md` §2); hace falta que escuche en el host efectivo de `--electrum` y que esa IP
    sea alcanzable en directo desde el móvil de pruebas fuera del túnel (alias en la interfaz de
    la red local o ruta estática), solo para esas pruebas, y documentarlo.

## 11. Fuera de v1 (documentado, no comprometido)

- `tcp://` dentro del túnel: ahorraría el TLS interno, pero quitaría la segunda comprobación de
  identidad. Si se ofrece, será con la misma advertencia que hoy («sin cifrar: solo por tu
  túnel») y nunca por defecto.
- Delegar los DH estáticos al Keystore (§2): boringtun modificado, Android 13 o superior, y la
  pregunta 4 resuelta.
- PSK por canal aparte (§4.3).
- Sincronización en segundo plano con túnel: exigiría servicio en primer plano o trabajo
  diferido y una política de batería; fuera de E-3.
- Versión en inglés de este documento antes de publicar, junto a la española.
