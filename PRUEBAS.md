# PRUEBAS

Procedimientos ejecutados de verdad y sus salidas. Nada de lo que sigue está redactado a
mano salvo las cabeceras y las notas marcadas; las salidas se copian íntegras. Las únicas
sustituciones son la ruta del directorio personal por `~` y el nombre de usuario del sistema
(propietario de los ficheros en `ls -la`) por `usuario`.

Honestidad: se distingue lo **implementado**, lo **probado** (y contra qué red) y lo
**pendiente**. Las pruebas unitarias viven en `rust/src/tests.rs`; las de red se ejecutan
con `cargo test -- --ignored`.

## 1. Pruebas automáticas del motor (rust/)

| Orden | Qué cubre | Resultado (30/10/2026) |
|---|---|---|
| `cargo test` | 28 unitarias: derivación BIP84, firma, estado sin claves, PSBT en dos pasos con re-verificación, caducidad, red del servidor (genesis), difusión aceptada/incierta/«ya conocida», intento anotado y en disco antes de transmitir (comprobado por el simulado al recibir la transacción, y por corte entre transmitir y anotar), rechazo del servidor que nunca libera monedas (primer intento o reintento), reemplazo RBF, persistencia; con un servidor Electrum **simulado** en regtest | 28 pasadas, 0 fallos, 4 ignoradas |
| `cargo test --features mainnet` | lo mismo con mainnet permitida | 28 pasadas |
| `cargo test -- --ignored` | 4 contra un servidor **público de signet**: billetera nueva a cero, restaurar el vector BIP84 y encontrar direcciones, altura y comisión, rechazo del servidor si se pide otra red | 4 pasadas |
| `cargo clippy --all-targets` (con y sin `mainnet`) | | 0 avisos |

Regla de los rechazos (vigente desde el tercer commit): **ningún rechazo del servidor libera
monedas**, tampoco en el primer intento. Un error de protocolo que no sea «ya conocida» deja
la transacción *incierta* («rechazada: …; no se liberan monedas: reintenta o cancela por
reemplazo»). La única liberación automática es cancelar una transacción firmada con cero
intentos de difusión; *fallida* queda solo para un reemplazo cuya original se confirmó.
Limitación: leer un fichero de envíos de versión 1 o 2 no reinterpreta sus estados antiguos;
una *fallida* histórica por rechazo (solo laboratorio) se carga tal cual.

## 2. Ciclo completo contra un banco regtest local

Banco: Bitcoin Core **v30.2.0** y electrs **0.11.1** (compilado desde la etiqueta
v0.11.1), en la interfaz local de la misma máquina. Servidor Electrum del banco pasado
al binario con `X39_ELECTRUM=tcp://127.0.0.1:60401` (bucle local; no es ningún servidor
real). Binario: `x39-billetera-prueba` compilado en modo release desde `rust/`.

Secuencia pedida: crear → direccion → fondear dos veces (dos UTXO) → sincronizar →
enviar `--sin-difundir` → difundir → difundir otra vez la misma → minar 1 → sincronizar
(confirmada) → segundo envío y cancelación por reemplazo RBF → minar 1 → sincronizar
(original cancelada, reemplazo confirmado, saldo correcto) → borrar `estado.dat` y
reconstruir el estado desde la semilla (mismo saldo y movimientos).

Sobre el último paso, tal como fue: se retiró `estado.dat` y se **conservó**
`estado.dat.envios.json` (el registro local de envíos) en el mismo directorio; `restaurar`
recreó `estado.dat` desde la semilla y escaneó. Es una reconstrucción del estado de BDK
conservando el registro de envíos, no una restauración solo desde la semilla. La
restauración limpia (directorio nuevo con solo `red.txt` y `SEMILLA-DE-PRUEBA.txt`) está en
la sección 2.1.

Puerta de limpieza aplicada a este documento: cero IPs salvo direcciones de bucle local
(127.0.0.0/8, ::1, localhost). Las `127.0.0.1` que aparecen son el bucle local del banco.

Notas sobre lo que se ve en el registro (añadidas a mano):

- El banco estaba parado al empezar (su Bitcoin Core registró un apagado limpio minutos
  antes); se levantó con su propio `arriba.sh`.
- «Difundir otra vez la misma» con la transacción ya en estado *enviada* no habla con el
  servidor: el registro la da por enviada y devuelve su estado sin reintentar. Para
  ejercitar de verdad la respuesta «ya conocida» del servidor se añadieron la tercera y la
  cuarta transacción: firmadas sin difundir, difundidas por fuera con `bitcoin-cli`,
  minadas, y entonces difundidas desde la billetera.
- **Hallazgo 1 (tercera transacción).** Bitcoin Core 30 ya no responde «Transaction already
  in block chain» sino «Transaction outputs already in utxo set», y electrs 0.11 lo reenvía
  con código 2 (no -27). El motor de entonces lo clasificó como rechazo (*fallida*) y liberó
  las monedas; la sincronización siguiente la vio anclada y la pasó a «confirmada pese al
  rechazo», con el saldo correcto. Se añadió ese texto a la lista de respuestas «ya conocida»
  y una prueba unitaria con él; la cuarta transacción repite la secuencia con el binario
  corregido y queda *enviada — el servidor ya la conocía*. Con la regla vigente ese caso
  queda *enviada* (el texto «already in utxo set» ya se reconoce); incluso si ese texto no se
  reconociera, quedaría *incierta* sin liberar monedas.
- **Hallazgo 2 (reconstrucción del estado).** Tras `restaurar` (escaneo correcto), `direccion` devolvió el
  índice 0, una dirección ya usada: `sincronizar`/`escanear` solo persistían la cartera cuando
  había envíos sin resolver. Corregido (se persiste siempre), con prueba unitaria que falla
  con el código anterior y pasa con el nuevo; la reconstrucción se repitió y la dirección nueva
  fue la 2.
- El valor «comisión» que se muestra en los movimientos RECIBIDOS es la comisión que pagó
  quien envió (la calcula BDK sobre la transacción completa); no es un coste de esta
  billetera. Pendiente de mejorar la presentación.
- La orden `restaurar` del binario se añadió para poder ejecutar el último paso. Lee la
  semilla y la red del directorio, recrea `estado.dat` y escanea; si en el directorio hay un
  `estado.dat.envios.json`, lo carga.

### Registro íntegro

```text
$ ./estado.sh
Error: timed out

$ ./bin/bitcoind --version
Bitcoin Core daemon version v30.2.0 bitcoind
Copyright (C) 2009-2026 The Bitcoin Core developers

Please contribute if you find Bitcoin Core useful. Visit
<https://bitcoincore.org/> for further information about the software.
The source code is available from <https://github.com/bitcoin/bitcoin>.

This is experimental software.
Distributed under the MIT software license, see the accompanying file COPYING
or <https://opensource.org/license/MIT>

$ ./bin/electrs --version
v0.11.1

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba crear ~/x39-wallet-prueba-regtest regtest
billetera de prueba creada en ~/x39-wallet-prueba-regtest (regtest)

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba direccion ~/x39-wallet-prueba-regtest
dirección nueva (índice 0): bcrt1qla7q5xmf3pr96lflk586q5a5pnh0aaa0zfatgu

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba direccion ~/x39-wallet-prueba-regtest
dirección nueva (índice 1): bcrt1qe9ju7wt2td5m5fhsuta90dgy7g77a7lnxagdsc

$ ./estado.sh
Error: timed out

$ ./fondear.sh bcrt1qla7q5xmf3pr96lflk586q5a5pnh0aaa0zfatgu 0.001
Error: error: timeout on transient error: Could not connect to the server 127.0.0.1:18443

Make sure the bitcoind server is running and that you are connecting to the correct RPC port.
Use "bitcoin-cli -help" for more info.

$ ./fondear.sh bcrt1qe9ju7wt2td5m5fhsuta90dgy7g77a7lnxagdsc 0.001
Error: error: timeout on transient error: Could not connect to the server 127.0.0.1:18443

Make sure the bitcoind server is running and that you are connecting to the correct RPC port.
Use "bitcoin-cli -help" for more info.

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba sincronizar ~/x39-wallet-prueba-regtest
servidor: tcp://127.0.0.1:60401 (regtest)
error: servidor Electrum: Made one or multiple attempts, all errored:
	- connection timed out


# (el banco estaba parado: Core registró un apagado limpio a las 19:42Z; se levanta con arriba.sh)
$ ./arriba.sh
Banco arrancado. RPC: 127.0.0.1:18443; Electrum: 127.0.0.1:60401; P2P: 127.0.0.1:18444

$ ./estado.sh
Altura Electrum: 102
Génesis: 0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206
Puerto Electrum: 127.0.0.1:60401
server.version: ["electrs/0.11.1", "1.4"]
server.features: {"genesis_hash": "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206", "hash_function": "sha256", "hosts": {"tcp_port": 60401}, "protocol_max": "1.4", "protocol_min": "1.4", "pruning": null, "server_version": "electrs/0.11.1"}

$ ./fondear.sh bcrt1qla7q5xmf3pr96lflk586q5a5pnh0aaa0zfatgu 0.001
Transacción: df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73
Bloques minados: 1
Altura: 103

$ ./fondear.sh bcrt1qe9ju7wt2td5m5fhsuta90dgy7g77a7lnxagdsc 0.001
Transacción: 0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835
Bloques minados: 1
Altura: 104

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba sincronizar ~/x39-wallet-prueba-regtest
servidor: tcp://127.0.0.1:60401 (regtest)
punta de la red: 104
altura sincronizada: 104
saldo: 200000 sat (confirmado 200000, pendiente 0, inmaduro 0)
índices revelados: externo Some(1), interno None
movimientos: 2
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103

--- destino externo (dirección nueva de la cartera 'minero' del banco):
$ ./bin/bitcoin-cli -datadir=~/x39-regtest/datos -regtest -rpcwallet=minero getnewaddress
bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba enviar ~/x39-wallet-prueba-regtest bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 150000 2 --sin-difundir
servidor: tcp://127.0.0.1:60401 (regtest)
REVISIÓN (pendiente acc8365df3d0d66d3cc0f01e09a3b4bb, caduca en 600 s):
  red:      regtest
  destino:  bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3
  importe:  150000 sat
  comisión: 417 sat (≈209 vB, 2.00 sat/vB; máximo aceptado 10000 sat)
  cambio:   bcrt1q3lk9h3fe3k6pyf4qr6046dnjzfc59hhtu9kw0t (49583 sat)
FIRMADA (re-verificada contra la revisión):
txid: 7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da
estado: firmada
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 150000 sat · comisión 417 sat (208 vB, 2.00 sat/vB) · cambio bcrt1q3lk9h3fe3k6pyf4qr6046dnjzfc59hhtu9kw0t (49583 sat)
  intentos de difusión 0 · reintentar sí · cancelar liberando sí · cancelar por reemplazo no
hex: 0200000000010273bf17226e382d7819299e97600f3c97acb277726a3b9e20d7790d55bdfe8adf0100000000fdffffff35d874fad471a7eb8fefd61368b997bcbecb4d683ae6dfd27bb20bb02f4108000100000000fdffffff02afc10000000000001600148fec5bc5398db41226a01e9f5d3672127142deebf049020000000000160014fd9cc7bf67485e695c74b247b4c2a48fa58a0b05024730440220539a6df07d5fd2eb4cd981cb541064aa7264745ef7ced2e48101925513cf572f022047b7135d0173835c5e506c83f9d8c63974b9eea5f34851583ca299f538681b04012102131b3b71565c720a952df578c4fce250f5ce763f1444fb3bc85fc276f5e79a6f02473044022010e7a21b8e60dd28bc92693fc423f5ef88096cd41959f756db10af3e0a97e6d4022024cf177189e3f6f25829aaa52e6fe34d070954d128b32951668d0028ea9f989d01210273170079bbb0c3124f4cc980bc10d21ca8cf45f47458a3af86f3805453b3a5fe68000000
no difundida: las monedas quedan reservadas. `difundir ~/x39-wallet-prueba-regtest 7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da` la envía (la misma), `cancelar` las libera.

TX1=7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da
$ ~/x39-wallet/rust/target/release/x39-billetera-prueba envios ~/x39-wallet-prueba-regtest
envíos firmados: 1
txid: 7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da
estado: firmada
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 150000 sat · comisión 417 sat (208 vB, 2.00 sat/vB) · cambio bcrt1q3lk9h3fe3k6pyf4qr6046dnjzfc59hhtu9kw0t (49583 sat)
  intentos de difusión 0 · reintentar sí · cancelar liberando sí · cancelar por reemplazo no

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba difundir ~/x39-wallet-prueba-regtest 7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da
servidor: tcp://127.0.0.1:60401 (regtest)
txid: 7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da
estado: enviada — aceptada por el servidor
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 150000 sat · comisión 417 sat (208 vB, 2.00 sat/vB) · cambio bcrt1q3lk9h3fe3k6pyf4qr6046dnjzfc59hhtu9kw0t (49583 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo sí

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba difundir ~/x39-wallet-prueba-regtest 7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da
servidor: tcp://127.0.0.1:60401 (regtest)
txid: 7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da
estado: enviada — aceptada por el servidor
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 150000 sat · comisión 417 sat (208 vB, 2.00 sat/vB) · cambio bcrt1q3lk9h3fe3k6pyf4qr6046dnjzfc59hhtu9kw0t (49583 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo sí

--- mempool del banco:
$ ./bin/bitcoin-cli -datadir=~/x39-regtest/datos -regtest getrawmempool
[
  "7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da"
]

$ ./minar.sh 1
Bloques minados: 1
Altura: 105

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba sincronizar ~/x39-wallet-prueba-regtest
servidor: tcp://127.0.0.1:60401 (regtest)
punta de la red: 105
altura sincronizada: 105
saldo: 49583 sat (confirmado 49583, pendiente 0, inmaduro 0)
índices revelados: externo Some(1), interno Some(0)
movimientos: 3
  7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da  +49583 -200000 comisión Some(417)  bloque 105
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba envios ~/x39-wallet-prueba-regtest
envíos firmados: 1
txid: 7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da
estado: confirmada — vista en un bloque
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 150000 sat · comisión 417 sat (208 vB, 2.00 sat/vB) · cambio bcrt1q3lk9h3fe3k6pyf4qr6046dnjzfc59hhtu9kw0t (49583 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no

--- segundo envío (se difunde) y cancelación por reemplazo a 3 sat/vB
$ ~/x39-wallet/rust/target/release/x39-billetera-prueba enviar ~/x39-wallet-prueba-regtest bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 20000 2
servidor: tcp://127.0.0.1:60401 (regtest)
REVISIÓN (pendiente 2f49f264d100692e472eb7afa23d1996, caduca en 600 s):
  red:      regtest
  destino:  bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3
  importe:  20000 sat
  comisión: 281 sat (≈141 vB, 1.99 sat/vB; máximo aceptado 10000 sat)
  cambio:   bcrt1q8syxhwv36zxer3ykxpjlq4sx7lrjkj0cvr2qp6 (29302 sat)
FIRMADA (re-verificada contra la revisión):
txid: 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa
estado: firmada
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 20000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1q8syxhwv36zxer3ykxpjlq4sx7lrjkj0cvr2qp6 (29302 sat)
  intentos de difusión 0 · reintentar sí · cancelar liberando sí · cancelar por reemplazo no
DIFUSIÓN:
txid: 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa
estado: enviada — aceptada por el servidor
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 20000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1q8syxhwv36zxer3ykxpjlq4sx7lrjkj0cvr2qp6 (29302 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo sí
altura sincronizada: 105
saldo: 29302 sat (confirmado 0, pendiente 29302, inmaduro 0)
índices revelados: externo Some(1), interno Some(1)
movimientos: 4
  275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa  +29302 -49583 comisión Some(281)  en mempool
  7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da  +49583 -200000 comisión Some(417)  bloque 105
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103

TX2=275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa
$ ./bin/bitcoin-cli -datadir=~/x39-regtest/datos -regtest getrawmempool
[
  "275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa"
]

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba cancelar ~/x39-wallet-prueba-regtest 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa 3
servidor: tcp://127.0.0.1:60401 (regtest)
ya se intentó difundir: se cancela con un REEMPLAZO hacia una dirección propia
REVISIÓN (pendiente bc5f48bb3c9f73d3b4a9a8ef9e500c0a, caduca en 600 s):
  red:      regtest
  destino:  bcrt1qknyzet8yhhfaw2y9hfy8atp9vnxztd66lupvn9
  importe:  49192 sat
  comisión: 391 sat (≈110 vB, 3.55 sat/vB; máximo aceptado 391 sat)
  cambio:   (sin cambio) (0 sat)
  REEMPLAZA A: 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa (gasta todas sus entradas; el destino es propio)
REEMPLAZO FIRMADO:
txid: 018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a
estado: firmada
  destino bcrt1qknyzet8yhhfaw2y9hfy8atp9vnxztd66lupvn9 · importe 49192 sat · comisión 391 sat (110 vB, 3.55 sat/vB) · cambio (sin cambio) (0 sat)
  REEMPLAZO (cancelación) de 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa
  intentos de difusión 0 · reintentar sí · cancelar liberando sí · cancelar por reemplazo no
DIFUSIÓN DEL REEMPLAZO:
txid: 018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a
estado: enviada — aceptada por el servidor
  destino bcrt1qknyzet8yhhfaw2y9hfy8atp9vnxztd66lupvn9 · importe 49192 sat · comisión 391 sat (110 vB, 3.55 sat/vB) · cambio (sin cambio) (0 sat)
  REEMPLAZO (cancelación) de 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no
altura sincronizada: 105
saldo: 29302 sat (confirmado 0, pendiente 29302, inmaduro 0)
índices revelados: externo Some(1), interno Some(2)
movimientos: 4
  275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa  +29302 -49583 comisión Some(281)  en mempool
  7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da  +49583 -200000 comisión Some(417)  bloque 105
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103
ENVÍO SIN RESOLVER: 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa (enviada) — `difundir` para reintentar o `cancelar`

$ ./bin/bitcoin-cli -datadir=~/x39-regtest/datos -regtest getrawmempool
[
  "018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a"
]

$ ./minar.sh 1
Bloques minados: 1
Altura: 106

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba sincronizar ~/x39-wallet-prueba-regtest
servidor: tcp://127.0.0.1:60401 (regtest)
punta de la red: 106
altura sincronizada: 106
saldo: 49192 sat (confirmado 49192, pendiente 0, inmaduro 0)
índices revelados: externo Some(1), interno Some(2)
movimientos: 4
  018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a  +49192 -49583 comisión Some(391)  bloque 106
  7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da  +49583 -200000 comisión Some(417)  bloque 105
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba envios ~/x39-wallet-prueba-regtest
envíos firmados: 3
txid: 7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da
estado: confirmada — vista en un bloque
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 150000 sat · comisión 417 sat (208 vB, 2.00 sat/vB) · cambio bcrt1q3lk9h3fe3k6pyf4qr6046dnjzfc59hhtu9kw0t (49583 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no
txid: 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa
estado: cancelada — reemplazada por 018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a, confirmada
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 20000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1q8syxhwv36zxer3ykxpjlq4sx7lrjkj0cvr2qp6 (29302 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no
txid: 018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a
estado: confirmada — vista en un bloque
  destino bcrt1qknyzet8yhhfaw2y9hfy8atp9vnxztd66lupvn9 · importe 49192 sat · comisión 391 sat (110 vB, 3.55 sat/vB) · cambio (sin cambio) (0 sat)
  REEMPLAZO (cancelación) de 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no

--- 'ya conocida' de verdad: tercera transacción firmada SIN difundir desde la billetera; una copia llega a la red por otro camino (bitcoin-cli) y se mina; después la billetera intenta difundirla
$ ~/x39-wallet/rust/target/release/x39-billetera-prueba enviar ~/x39-wallet-prueba-regtest bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 10000 2 --sin-difundir
servidor: tcp://127.0.0.1:60401 (regtest)
REVISIÓN (pendiente 93d8ce88ca445fe813f12ac746d62250, caduca en 600 s):
  red:      regtest
  destino:  bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3
  importe:  10000 sat
  comisión: 281 sat (≈141 vB, 1.99 sat/vB; máximo aceptado 10000 sat)
  cambio:   bcrt1qu9jl645eq3zhh4vemd2quzua3dyk4p33zenzaw (38911 sat)
FIRMADA (re-verificada contra la revisión):
txid: 86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb
estado: firmada
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 10000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1qu9jl645eq3zhh4vemd2quzua3dyk4p33zenzaw (38911 sat)
  intentos de difusión 0 · reintentar sí · cancelar liberando sí · cancelar por reemplazo no
hex: 020000000001012ad8cb85062611114e0a0d042f238a14b5d8f38296d90e5181340c0e307089010000000000fdffffff021027000000000000160014fd9cc7bf67485e695c74b247b4c2a48fa58a0b05ff97000000000000160014e165fd569904457bd599db540e0b9d8b496a863102483045022100ffdcee70682b5a0246e3b8fc000b0e5b66032aef01c899da960ad54fd1a87dcf02202014c0effc2868af2d9e6724bb5dfa6ae3915da65b29c920c68f3c723600da5601210325f5e39f795cb79b7f8a7deb5c30b3c7dbf9575a08ab9a3abcc70f9c0f581deb6a000000
no difundida: las monedas quedan reservadas. `difundir ~/x39-wallet-prueba-regtest 86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb` la envía (la misma), `cancelar` las libera.

TX3=86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb
$ ./bin/bitcoin-cli -datadir=~/x39-regtest/datos -regtest sendrawtransaction 020000000001012ad8cb85062611114e0a0d042f238a14b5d8f38296d90e5181340c0e307089010000000000fdffffff021027000000000000160014fd9cc7bf67485e695c74b247b4c2a48fa58a0b05ff97000000000000160014e165fd569904457bd599db540e0b9d8b496a863102483045022100ffdcee70682b5a0246e3b8fc000b0e5b66032aef01c899da960ad54fd1a87dcf02202014c0effc2868af2d9e6724bb5dfa6ae3915da65b29c920c68f3c723600da5601210325f5e39f795cb79b7f8a7deb5c30b3c7dbf9575a08ab9a3abcc70f9c0f581deb6a000000
86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb

$ ./minar.sh 1
Bloques minados: 1
Altura: 107

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba difundir ~/x39-wallet-prueba-regtest 86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb
servidor: tcp://127.0.0.1:60401 (regtest)
txid: 86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb
estado: fallida — Electrum server error: {"code":2,"message":"Transaction outputs already in utxo set"}
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 10000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1qu9jl645eq3zhh4vemd2quzua3dyk4p33zenzaw (38911 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba sincronizar ~/x39-wallet-prueba-regtest
servidor: tcp://127.0.0.1:60401 (regtest)
punta de la red: 107
altura sincronizada: 107
saldo: 38911 sat (confirmado 38911, pendiente 0, inmaduro 0)
índices revelados: externo Some(1), interno Some(3)
movimientos: 5
  86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb  +38911 -49192 comisión Some(281)  bloque 107
  018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a  +49192 -49583 comisión Some(391)  bloque 106
  7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da  +49583 -200000 comisión Some(417)  bloque 105
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba envios ~/x39-wallet-prueba-regtest
envíos firmados: 4
txid: 7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da
estado: confirmada — vista en un bloque
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 150000 sat · comisión 417 sat (208 vB, 2.00 sat/vB) · cambio bcrt1q3lk9h3fe3k6pyf4qr6046dnjzfc59hhtu9kw0t (49583 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no
txid: 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa
estado: cancelada — reemplazada por 018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a, confirmada
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 20000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1q8syxhwv36zxer3ykxpjlq4sx7lrjkj0cvr2qp6 (29302 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no
txid: 018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a
estado: confirmada — vista en un bloque
  destino bcrt1qknyzet8yhhfaw2y9hfy8atp9vnxztd66lupvn9 · importe 49192 sat · comisión 391 sat (110 vB, 3.55 sat/vB) · cambio (sin cambio) (0 sat)
  REEMPLAZO (cancelación) de 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no
txid: 86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb
estado: confirmada — vista en un bloque pese al rechazo
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 10000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1qu9jl645eq3zhh4vemd2quzua3dyk4p33zenzaw (38911 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no

--- (binario recompilado: 'already in utxo set' reconocido) cuarta transacción: misma secuencia que la tercera
$ ~/x39-wallet/rust/target/release/x39-billetera-prueba enviar ~/x39-wallet-prueba-regtest bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 5000 2 --sin-difundir
servidor: tcp://127.0.0.1:60401 (regtest)
REVISIÓN (pendiente eaa272ba1fb1f4fb8a6eeee45d0dc771, caduca en 600 s):
  red:      regtest
  destino:  bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3
  importe:  5000 sat
  comisión: 281 sat (≈141 vB, 1.99 sat/vB; máximo aceptado 10000 sat)
  cambio:   bcrt1qjhedrkf6xaafznpfdvrv44nlh2mmn5ggvghgy9 (33630 sat)
FIRMADA (re-verificada contra la revisión):
txid: b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c
estado: firmada
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 5000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1qjhedrkf6xaafznpfdvrv44nlh2mmn5ggvghgy9 (33630 sat)
  intentos de difusión 0 · reintentar sí · cancelar liberando sí · cancelar por reemplazo no
hex: 02000000000101fb160ed431c45567176d51560a18c57eafabaf21e1ea4113b30fff279988b6860100000000fdffffff025e8300000000000016001495f2d1d93a377a914c296b06cad67fbab7b9d1088813000000000000160014fd9cc7bf67485e695c74b247b4c2a48fa58a0b0502473044022029d62a39473c7f84c747bef246f213e1c8e0827f2dce632aeb62efb7d906c64e02205065142c66fbc48fdeec6fbd482f5cc2a1aa0a88c6f84fb9bd73b15009a5643801210368828fc665810600da18644a75ffb27033d6ecf1baec745946fc0af71500aea16b000000
no difundida: las monedas quedan reservadas. `difundir ~/x39-wallet-prueba-regtest b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c` la envía (la misma), `cancelar` las libera.

TX4=b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c
$ ./bin/bitcoin-cli -datadir=~/x39-regtest/datos -regtest sendrawtransaction 02000000000101fb160ed431c45567176d51560a18c57eafabaf21e1ea4113b30fff279988b6860100000000fdffffff025e8300000000000016001495f2d1d93a377a914c296b06cad67fbab7b9d1088813000000000000160014fd9cc7bf67485e695c74b247b4c2a48fa58a0b0502473044022029d62a39473c7f84c747bef246f213e1c8e0827f2dce632aeb62efb7d906c64e02205065142c66fbc48fdeec6fbd482f5cc2a1aa0a88c6f84fb9bd73b15009a5643801210368828fc665810600da18644a75ffb27033d6ecf1baec745946fc0af71500aea16b000000
b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c

$ ./minar.sh 1
Bloques minados: 1
Altura: 108

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba difundir ~/x39-wallet-prueba-regtest b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c
servidor: tcp://127.0.0.1:60401 (regtest)
txid: b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c
estado: enviada — el servidor ya la conocía: Electrum server error: {"code":2,"message":"Transaction outputs already in utxo set"}
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 5000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1qjhedrkf6xaafznpfdvrv44nlh2mmn5ggvghgy9 (33630 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo sí

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba sincronizar ~/x39-wallet-prueba-regtest
servidor: tcp://127.0.0.1:60401 (regtest)
punta de la red: 108
altura sincronizada: 108
saldo: 33630 sat (confirmado 33630, pendiente 0, inmaduro 0)
índices revelados: externo Some(1), interno Some(4)
movimientos: 6
  b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c  +33630 -38911 comisión Some(281)  bloque 108
  86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb  +38911 -49192 comisión Some(281)  bloque 107
  018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a  +49192 -49583 comisión Some(391)  bloque 106
  7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da  +49583 -200000 comisión Some(417)  bloque 105
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103

--- restauración desde la semilla: se retira estado.dat (copia en el área temporal de la sesión) y se recrea con 'restaurar'
$ ls -la ~/x39-wallet-prueba-regtest
total 36
drwx------   2 usuario  usuario   4096 Oct 30 20:53 .
drwxr-x--- 109 usuario  usuario  12288 Oct 30 20:43 ..
-rw-------   1 usuario  usuario    203 Oct 30 20:43 SEMILLA-DE-PRUEBA.txt
-rw-r--r--   1 usuario  usuario   3592 Oct 30 20:53 estado.dat
-rw-------   1 usuario  usuario   6748 Oct 30 20:53 estado.dat.envios.json
-rw-r--r--   1 usuario  usuario      8 Oct 30 20:43 red.txt

estado.dat retirado
$ ~/x39-wallet/rust/target/release/x39-billetera-prueba restaurar ~/x39-wallet-prueba-regtest
servidor: tcp://127.0.0.1:60401 (regtest)
estado recreado desde la semilla; escaneo completo…
altura sincronizada: 108
saldo: 33630 sat (confirmado 33630, pendiente 0, inmaduro 0)
índices revelados: externo Some(1), interno Some(4)
movimientos: 6
  b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c  +33630 -38911 comisión Some(281)  bloque 108
  86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb  +38911 -49192 comisión Some(281)  bloque 107
  018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a  +49192 -49583 comisión Some(391)  bloque 106
  7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da  +49583 -200000 comisión Some(417)  bloque 105
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba envios ~/x39-wallet-prueba-regtest
envíos firmados: 5
txid: 7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da
estado: confirmada — vista en un bloque
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 150000 sat · comisión 417 sat (208 vB, 2.00 sat/vB) · cambio bcrt1q3lk9h3fe3k6pyf4qr6046dnjzfc59hhtu9kw0t (49583 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no
txid: 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa
estado: cancelada — reemplazada por 018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a, confirmada
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 20000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1q8syxhwv36zxer3ykxpjlq4sx7lrjkj0cvr2qp6 (29302 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no
txid: 018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a
estado: confirmada — vista en un bloque
  destino bcrt1qknyzet8yhhfaw2y9hfy8atp9vnxztd66lupvn9 · importe 49192 sat · comisión 391 sat (110 vB, 3.55 sat/vB) · cambio (sin cambio) (0 sat)
  REEMPLAZO (cancelación) de 275645c0143a10e1d201353a301faa2b4b4e1f6a7c8d7d43139b93e1a9dc5faa
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no
txid: 86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb
estado: confirmada — vista en un bloque pese al rechazo
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 10000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1qu9jl645eq3zhh4vemd2quzua3dyk4p33zenzaw (38911 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no
txid: b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c
estado: confirmada — vista en un bloque
  destino bcrt1qlkwv00m8fp0xjhr5kfrmfs4y37jc5zc9ja0nt3 · importe 5000 sat · comisión 281 sat (141 vB, 1.99 sat/vB) · cambio bcrt1qjhedrkf6xaafznpfdvrv44nlh2mmn5ggvghgy9 (33630 sat)
  intentos de difusión 1 · reintentar no · cancelar liberando no · cancelar por reemplazo no

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba direccion ~/x39-wallet-prueba-regtest
dirección nueva (índice 0): bcrt1qla7q5xmf3pr96lflk586q5a5pnh0aaa0zfatgu

--- versión del fichero lateral:
  "version": 2,
--- FALLO DETECTADO: 'direccion' tras restaurar devolvió el índice 0 (dirección ya usada): sincronizar/escanear no persistían la cartera si no había envíos sin resolver. Corregido en el motor y binario recompilado; se repite la restauración.
estado.dat retirado de nuevo
$ ~/x39-wallet/rust/target/release/x39-billetera-prueba restaurar ~/x39-wallet-prueba-regtest
servidor: tcp://127.0.0.1:60401 (regtest)
estado recreado desde la semilla; escaneo completo…
altura sincronizada: 108
saldo: 33630 sat (confirmado 33630, pendiente 0, inmaduro 0)
índices revelados: externo Some(1), interno Some(4)
movimientos: 6
  b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c  +33630 -38911 comisión Some(281)  bloque 108
  86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb  +38911 -49192 comisión Some(281)  bloque 107
  018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a  +49192 -49583 comisión Some(391)  bloque 106
  7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da  +49583 -200000 comisión Some(417)  bloque 105
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba direccion ~/x39-wallet-prueba-regtest
dirección nueva (índice 2): bcrt1qmetshlk2cr3s24qjgxwhnwf5mtvhcedgz5ktzz

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba sincronizar ~/x39-wallet-prueba-regtest
servidor: tcp://127.0.0.1:60401 (regtest)
punta de la red: 108
altura sincronizada: 108
saldo: 33630 sat (confirmado 33630, pendiente 0, inmaduro 0)
índices revelados: externo Some(2), interno Some(4)
movimientos: 6
  b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c  +33630 -38911 comisión Some(281)  bloque 108
  86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb  +38911 -49192 comisión Some(281)  bloque 107
  018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a  +49192 -49583 comisión Some(391)  bloque 106
  7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da  +49583 -200000 comisión Some(417)  bloque 105
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103

$ ./estado.sh
Altura Electrum: 108
Génesis: 0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206
Puerto Electrum: 127.0.0.1:60401
server.version: ["electrs/0.11.1", "1.4"]
server.features: {"genesis_hash": "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206", "hash_function": "sha256", "hosts": {"tcp_port": 60401}, "protocol_max": "1.4", "protocol_min": "1.4", "pruning": null, "server_version": "electrs/0.11.1"}

```

### 2.1 Restauración limpia solo desde la semilla

Directorio NUEVO con solo `red.txt` y `SEMILLA-DE-PRUEBA.txt` copiados de la billetera
anterior (sin `estado.dat` ni `estado.dat.envios.json`). `restaurar` recrea el estado y
escanea. Resultado: el mismo saldo (33630 sat) y los mismos 6 movimientos que la billetera
original; la siguiente dirección nueva es la 2, posterior a las dos usadas. El registro de
envíos queda vacío: una billetera restaurada solo desde la semilla no conoce los envíos
firmados o inciertos anteriores hasta que el servidor los muestre (limitación documentada
en la cabecera del crate).

```text
--- restauración LIMPIA: directorio nuevo con solo red.txt y SEMILLA-DE-PRUEBA.txt (sin estado.dat ni estado.dat.envios.json)
$ ls -la ~/x39-wallet-prueba-regtest-restaurada
total 24
drwx------   2 usuario  usuario   4096 Oct 30 21:09 .
drwxr-x--- 110 usuario  usuario  12288 Oct 30 21:09 ..
-rw-------   1 usuario  usuario    203 Oct 30 20:43 SEMILLA-DE-PRUEBA.txt
-rw-r--r--   1 usuario  usuario      8 Oct 30 20:43 red.txt

$ ./estado.sh
Altura Electrum: 108
Génesis: 0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206
Puerto Electrum: 127.0.0.1:60401
server.version: ["electrs/0.11.1", "1.4"]
server.features: {"genesis_hash": "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206", "hash_function": "sha256", "hosts": {"tcp_port": 60401}, "protocol_max": "1.4", "protocol_min": "1.4", "pruning": null, "server_version": "electrs/0.11.1"}

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba restaurar ~/x39-wallet-prueba-regtest-restaurada
servidor: tcp://127.0.0.1:60401 (regtest)
estado recreado desde la semilla; escaneo completo…
altura sincronizada: 108
saldo: 33630 sat (confirmado 33630, pendiente 0, inmaduro 0)
índices revelados: externo Some(1), interno Some(4)
movimientos: 6
  b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c  +33630 -38911 comisión Some(281)  bloque 108
  86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb  +38911 -49192 comisión Some(281)  bloque 107
  018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a  +49192 -49583 comisión Some(391)  bloque 106
  7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da  +49583 -200000 comisión Some(417)  bloque 105
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba envios ~/x39-wallet-prueba-regtest-restaurada
envíos firmados: 0

$ ~/x39-wallet/rust/target/release/x39-billetera-prueba direccion ~/x39-wallet-prueba-regtest-restaurada
dirección nueva (índice 2): bcrt1qmetshlk2cr3s24qjgxwhnwf5mtvhcedgz5ktzz

--- comparación con la billetera original:
$ ~/x39-wallet/rust/target/release/x39-billetera-prueba sincronizar ~/x39-wallet-prueba-regtest
servidor: tcp://127.0.0.1:60401 (regtest)
punta de la red: 108
altura sincronizada: 108
saldo: 33630 sat (confirmado 33630, pendiente 0, inmaduro 0)
índices revelados: externo Some(2), interno Some(4)
movimientos: 6
  b09e9b8f7f88fcea58c3ed789ee97951c97f65723bf7a4db3d158aa3acaed21c  +33630 -38911 comisión Some(281)  bloque 108
  86b6889927ff0fb31341eae121afabaf7ec5180a56516d176755c431d40e16fb  +38911 -49192 comisión Some(281)  bloque 107
  018970300e0c3481510ed99682f3d8b5148a232f040d0a4e1111260685cbd82a  +49192 -49583 comisión Some(391)  bloque 106
  7fd6da988cbb30db39306a4ec519bcfc61ef40387a88bb055a1a94bdfa6d78da  +49583 -200000 comisión Some(417)  bloque 105
  0008412fb00bb27bd2dfe63a684dcbbebc97b96813d6ef8feba771d4fa74d835  +100000 -0 comisión Some(1410)  bloque 104
  df8afebd550d79d7209e3b6a7277b2ac973c0f60979e2919782d386e2217bf73  +100000 -0 comisión Some(1410)  bloque 103

$ ls -la ~/x39-wallet-prueba-regtest-restaurada
total 28
drwx------   2 usuario  usuario   4096 Oct 30 21:09 .
drwxr-x--- 110 usuario  usuario  12288 Oct 30 21:09 ..
-rw-------   1 usuario  usuario    203 Oct 30 20:43 SEMILLA-DE-PRUEBA.txt
-rw-r--r--   1 usuario  usuario   2620 Oct 30 21:09 estado.dat
-rw-r--r--   1 usuario  usuario      8 Oct 30 20:43 red.txt

```

## 4. Módulo Android `:wallet` (fase D)

Compilación independiente en `android/` (AGP 8.7.2, Kotlin 1.9.25, Gradle 8.9, compileSdk 35,
minSdk 21; la billetera exige en tiempo de ejecución Android 6 / API 23 por el Keystore).

| Qué | Resultado (31/10/2026) |
|---|---|
| `android/build-native.sh --actualizar` y `--verificar` | `libx39wallet.so` 6936ed89b0d79a81fc75cb2c0253959b0501f9ff467233c01744703a61b24bdb, 9.325.968 bytes; misma suma en compilación limpia (REPRODUCIBLE) |
| `:wallet:lintDebug` | 0 errores, 0 avisos (1 informativo documentado en `lint.xml`) |
| `:wallet:testDebugUnitTest` (JVM) | `MnemonicoTest`: 6 pruebas, 0 fallos |
| `:wallet:connectedDebugAndroidTest` en el aparato | 10 pruebas, 0 fallos, 0 omitidas (abajo) |

Aparato: Redmi Note 15 5G (modelo 25098RA98G), Android 16 (API 36), arm64-v8a, páginas de
4 KB, con pantalla de bloqueo configurada, conectado por depuración inalámbrica desde WSL2.
La prueba de escaneo pendiente habló desde el aparato con el servidor público de signet y
encontró la historia del vector BIP84.

Salida de Gradle (íntegra, quitadas las tareas intermedias):

```text
> Task :wallet:connectedDebugAndroidTest
Starting 10 tests on 25098RA98G - 16
25098RA98G - 16 Tests 8/10 completed. (0 skipped) (0 failed)
Finished 10 tests on 25098RA98G - 16
BUILD SUCCESSFUL in 52s
53 actionable tasks: 2 executed, 51 up-to-date
```

Resultados por prueba (de los XML de `wallet/build/outputs/androidTest-results/connected/debug/`):

```text
x39.wallet.android.AlmacenSemillaInstrumentadoTest | tests 10 failures 0 errors 0 skipped 0
  - AlmacenSemillaInstrumentadoTest.ficheroCifradoFueraDeLasCopiasDeSeguridad ok 0.007s
  - AlmacenSemillaInstrumentadoTest.laClaveExigeAutenticacionPorOperacion ok 0.044s
  - BilleterasInstrumentadoTest.mainnetProhibidaPorElMotor ok 0.053s
  - BilleterasInstrumentadoTest.unaSolaBilleteraPorFichero_tambienDesdeAndroid ok 0.028s
  - BilleterasInstrumentadoTest.bibliotecaSoloRedesDePrueba ok 0.001s
  - BilleterasInstrumentadoTest.nuevaSemilla_docePalabras_yCreaBilleteraConByteBufferDirecto ok 0.029s
  - EnviarActivityInstrumentadoTest.firmarResultadoDifundir_sinExcepcion_yMuestraElEstado ok 1.921s
  - EnviarActivityInstrumentadoTest.siLaDifusionFalla_loDiceYNoRompe ok 1.597s
  - RecuperacionYEscaneoInstrumentadoTest.restaurarSinServidor_dejaEscaneoPendiente_ySincronizarLoCompleta ok 20.041s
  - RecuperacionYEscaneoInstrumentadoTest.recuperarLaClave_noTocaElEstadoNiElRegistroDeEnvios ok 0.078s
```

Lo que cubren: biblioteca nativa real con marcador de solo redes de prueba; semilla nueva de
12 palabras y creación con ByteBuffer directo; una billetera por fichero también desde
Android (índice 0 antes de cerrar, 1 al reabrir; el motor se niega aunque se salte el
registro Kotlin); mainnet prohibida; parámetros reales de la clave del Keystore
(autenticación por operación, 256 bits, GCM, tipo de autenticación biometría fuerte o
credencial del aparato) y que sin autenticar no cifra; fichero cifrado bajo
`noBackupFilesDir`; pantalla Enviar con repositorio falso (firmar → resultado → difundir sin
excepción, y difusión fallida avisada); recuperación de la clave sin tocar `estado.dat` ni
el registro de envíos (una firmada sigue firmada con 0 intentos); restauración sin servidor
que deja la marca de escaneo pendiente, se niega a preparar envíos, sobrevive a otra
instancia del repositorio y se completa al sincronizar con servidor.

No probado todavía: las pantallas una por una en el aparato (fase G), el prompt biométrico
real (no se automatiza) y el montaje en la app (fase F).

## 5. Primer recorrido en aparato real (signet, sin fondos propios → ingreso del grifo)

Hecho a mano por el autor en el aparato de pruebas, un Redmi Note 15 5G (modelo 25098RA98G, Android 16 / API 36,
arm64-v8a) el 31/10/2026, con el `.lab` debug del sabor `general` montado por `includeBuild`
(commit 88494463 de la app privada; módulo `:wallet` en 478e9ab). Estado leído desde el
ordenador solo por `logcat`/`dumpsys` del paquete `.lab` (la app lleva FLAG_SECURE: las capturas
salen negras).

| Paso | Resultado |
|---|---|
| Pestaña Billetera → Abrir billetera → Crear billetera nueva → 12 palabras copiadas a mano, comprobación de una palabra, autenticación del sistema | billetera creada (signet) |
| Ajustes → servidor público de signet → Probar conexión | «Conecta: signet, altura 328722» |
| Sincronizar | altura 328722, saldo 0 |
| Recibir | dirección índice 0: `tb1qu4fr0cl8scmahhlcdxmrnp25kz0jjtfdayervg` |
| Grifo de signet a esa dirección → Sincronizar | **6.451.533 sat pendientes**, altura 328723 |
| txid del ingreso (Historial) | `60fb73a98b6095dd91da3c34253b4cda8054eb5bc9ce81006540860ad4e12dad`, **confirmado en el bloque 328724**, 6.060.518 sat a la dirección índice 0 |

Nota: la cantidad vista como pendiente (6.451.533 sat) correspondía a una versión anterior de la
transacción del grifo, reemplazada (RBF) antes de confirmar; el explorador público solo muestra
la confirmada. La billetera mostró primero la versión que estaba en la mempool y, al confirmar
la sustituta, el importe confirmado: comportamiento correcto.

Mejoras de uso pedidas por el autor tras el recorrido y hechas en el commit siguiente del módulo: flecha de
volver en todas las pantallas; botón «Usar servidor público de signet (terceros)» en Ajustes.

### 5.1 Primer envío real desde el aparato (fase A cerrada en red real)

El 31/10/2026 a la 01:30, con el `.lab` actualizado (módulo `:wallet` en ddf730e), el autor envió
desde el aparato de pruebas **10.000 sat** a `tb1q5rwlvpkywkf7gxau6qv83kejrj930t3hhp0nkl` (dirección índice
0 de la billetera de pruebas de línea de órdenes del portátil, `~/x39-wallet-prueba-signet`):
revisión → autenticación del sistema → firma → difusión.

| Dato | Valor |
|---|---|
| txid | `a10728e0ee99b1ad64138e21df7f86e49937bd00f416a8dbdbaa0eba86af98ff` |
| importe | 10.000 sat |
| comisión | 281 sat (2 sat/vB) |
| cambio | 6.050.237 sat a una dirección interna propia |
| estado en el aparato | «enviada» en Historial; en la mempool a la 01:30 |
| confirmación | **bloque 328731** (vista desde el portátil a la 01:42, 5.ª consulta automática cada 3 min) |

Comprobación independiente desde el portátil, con el binario del motor contra el servidor
público de signet (salida íntegra):

```text
$ x39-billetera-prueba sincronizar ~/x39-wallet-prueba-signet
servidor: ssl://mempool.space:60602 (signet)
punta de la red: 328730
altura sincronizada: 328730
saldo: 10000 sat (confirmado 0, pendiente 10000, inmaduro 0)
índices revelados: externo Some(0), interno None
movimientos: 1
  a10728e0ee99b1ad64138e21df7f86e49937bd00f416a8dbdbaa0eba86af98ff  +10000 -0 comisión Some(281)  en mempool
```

Confirmación, vista desde el portátil (salida íntegra de la consulta automática):

```text
servidor: ssl://mempool.space:60602 (signet)
punta de la red: 328731
altura sincronizada: 328731
saldo: 10000 sat (confirmado 10000, pendiente 0, inmaduro 0)
índices revelados: externo Some(0), interno None
movimientos: 1
  a10728e0ee99b1ad64138e21df7f86e49937bd00f416a8dbdbaa0eba86af98ff  +10000 -0 comisión Some(281)  bloque 328731
```

Con esto la fase A (envío real en signet: construir → revisar → firmar → difundir → confirmar)
queda cerrada en red real y desde el móvil. Comprobado por el autor en el aparato el 31/10/2026:
tras **Sincronizar**, Historial muestra el envío como «confirmada» y el saldo en 6.050.237 sat.

### 5.2 Emparejar con mi nodo: recorrido manual en el aparato (fase E-2 en real)

Hecho a mano por el autor el 31/10/2026 entre las 6:02 y las 6:40 (horas locales del aparato,
CET; las fechas que llevan los paquetes van en UTC) en el mismo Redmi Note 15 5G, con el `.lab`
1.0.6 (vc43) recompilado con el módulo `:wallet` en 2d653cd (E-2) e instalado con `adb install -r`
sobre el anterior: billetera y Keystore conservados (saldo 6.050.237 sat, nodo público sano a
328760 al empezar). Los paquetes los generó `herramientas/nodo/emparejar.sh` en ficheros
temporales fuera del repo y se mostraron como QR en la pantalla del ordenador, uno cada vez. El
nodo propio (mainnet) no entró: un paquete suyo solo probaría el rechazo por génesis, y eso queda
para otro día. Capturas de pantalla del propio aparato para cada mensaje: las pantallas de saldo,
Ajustes y Emparejar no llevan FLAG_SECURE; las de semilla, firma, Historial y el visor de la
cámara sí (`Window.protegerContraCapturas()`, por diseño).

Acceso: pestaña Billetera → «Abrir billetera» → «Emparejar con mi nodo (QR o fichero)». La
actividad no es exportada: `am start` desde adb devuelve «Permission Denial … not exported», un
punto a favor (ninguna otra app puede abrirla con un paquete dentro).

| Prueba | Paquete | Resultado |
|---|---|---|
| 0 · QR «hola» (cámara real) | el texto `hola` | rechazo inmediato «PAQUETE RECHAZADO: no es un paquete de emparejamiento (JSON inválido: el paquete debe ser un objeto JSON)», sin vista previa, «No se ha guardado nada» |
| B · QR, nodo inexistente | `ssl://192.0.2.44:50002` (el propio móvil, puerto cerrado), huella del vector `pruebas/vector.pem` (`d69cfd0c…a6e230de`), nombre «nodo-inexistente-B» | vista previa completa (nombre, red, servidor, huella abreviada con «Ver la huella entera», emitido y caduca en UTC, «Túnel: no trae»). **Descartar** deja el nodo sin cambios. **Confirmar** → «No se ha guardado nada: servidor Electrum: no se pudo conectar con 192.0.2.44:50002: Connection refused (os error 111)», en naranja (paquete válido, nodo que no responde: distinto del rechazo en rojo de un paquete malo). El nodo activo quedó intacto, y su altura avanzó de 328760 a 328763 durante el recorrido: la vigilancia del nodo activo no se interrumpió por el candidato |
| C · QR, campo con pinta de clave privada | el mismo B más `"clave_privada": "<64 hex>"`, nombre «nodo-inexistente-C» | rechazo inmediato en rojo «PAQUETE RECHAZADO: el paquete trae un campo que parece una clave privada («clave_privada»): se rechaza entero», sin vista previa, sin botón de confirmar, sin tocar la red. `emparejar.sh` se negó a emitir ese paquete; hubo que montarlo a mano |
| D · QR, paquete REAL del servidor público | certificado vivo de `mempool.space:60602` (obtenido con `openssl s_client -showcerts`; emisor Sectigo, válido hasta el 06/04/2027), huella calculada por `emparejar.sh` = `c15ee689…f30dd4`, nombre «publico-prueba» | vista previa; **Confirmar** → SANO con la huella fijada (ya no por la excepción del servidor público); adoptado y guardado; aviso «Nodo emparejado y sano: publico-prueba»; la línea del nodo en el saldo pasa a «nodo sano · altura 328764 · 38 ms · huella comprobada». Ajustes muestra servidor y huella rellenos (el nombre no aparece en Ajustes: anotado en mejoras) y «Nodo SANO … · huella comprobada» |
| E · fichero, selector del sistema | `D-publico-prueba.json` y `B-nodo-inexistente.json` copiados con `adb push` a `/sdcard/Download/` | «Importar fichero» → Download → D adoptado de nuevo (6:34 y 6:37), mismo resultado que por QR; B rechazado con el mismo «Connection refused», nada guardado, nodo sin cambios |
| Persistencia | — | tras cerrar la app del todo y reabrirla (6:40): nodo sano, 35 ms, «huella comprobada» |

Notas:

- La huella de D dejará de valer cuando mempool.space renueve su certificado (como muy tarde el
  06/04/2027). «Usar servidor público de signet (terceros)» en Ajustes recupera la excepción sin
  huella.
- Los ficheros entrados por adb no salieron en «Recientes» del selector del sistema; se navegó a
  la carpeta Download.
- La adopción contra un nodo PROPIO en signet queda pendiente de tener uno (el propio es mainnet).
- La rama «permiso de cámara denegado» no se probó hoy: pendiente.

Mejoras menores anotadas tras el recorrido, sin tocar hoy: quitar «(os error 111)» del texto al
usuario; atajo a Emparejar desde los ajustes del nodo; mostrar el nombre del nodo emparejado en
Ajustes si no aparece; nota en el registro de ediciones de la app privada sobre el permiso de
cámara que el `.lab` hereda del módulo (diff aparte).

## 6. Emparejamiento con el nodo (fase E-2)

Tras cuatro vueltas de revisión cruzada (catorce, ocho, tres y dos correcciones, cada una con prueba de regresión).

| Qué | Resultado (31/10/2026) |
|---|---|
| `herramientas/nodo/pruebas/probar.sh` | 23 comprobaciones pasadas: la huella SPKI del vector `pruebas/vector.pem` es `d69cfd0c…a6e230de`, **la misma que calcula el motor** (`rust/src/tests.rs`, `huella_spki_vector_fijo`); paquete v1 válido con túnel, `emitido` y `caduca` exactamente 24 h aparte (un solo instante), fichero 600 también al sobrescribir uno existente, sin nada que parezca una clave privada; `tcp://` sin huella; se niega a más de 24 h, a un certificado con clave privada, a una clave de túnel mal formada, a mainnet, a una semilla o un WIF dentro del nombre, a un WIF dentro del host, a cualquier carácter de control en el nombre y a puertos fuera de 1..65535; CIDR validado como el móvil (`999.999.999.999/99` y `::::/64` rechazados, IPv4 incrustada aceptada); límite de 16 KB contado con el salto de línea final; IPv6 con «:» simple al final o al principio rechazada (`fd00::1:/64`, `1:2:3:4:5:6:7:8:/64`) y `fd00::/64` aceptada; semilla pegada a puntuación rechazada; el filtro de palabras mira cada valor por separado (un nombre legítimo de once palabras pasa) |
| `:wallet:testDebugUnitTest` (JVM) | `EmparejamientoTest` 17 (válido con y sin túnel, `tcp://` sin huella, caducado / `caduca == ahora` / emitido en el futuro / más de 24 h / fecha con cola «Z+09:00», fechas con anchuras exactas y calendario válido, otra red / red desconocida / mainnet, huella mal formada o ausente, diez variantes de clave privada o semilla, texto sobrante y claves duplicadas y JSON permisivo, secretos con texto alrededor y dentro del host, IPv6 literal, túnel mal formado, servidor / nombre con salto de línea / puertos / tamaño, robustez: 7.000 palabras no desbordan la pila; semilla pegada a «:», «.» o paréntesis rechazada; IPv6 con «:» simple al final rechazada); `NodeBridgeTest` 15 (nuevas: `adoptarSiSano` solo guarda si Sano; no guarda si se cancela o cambia la generación; un `olvidar` durante el guardado no queda pisado y config y metadatos van juntos; descartar un candidato lento adelantado no rompe el nodo vigente; `probar` no mueve la altura vista; si el almacén no persiste (`commit()` false) no se adopta ni se publica Sano; si el disco falla DESPUÉS de que el almacén toque su memoria, la configuración activa sigue siendo la anterior); `MnemonicoTest` 6. Total 39, 0 fallos |
| `:wallet:lintDebug` | 0 errores, 0 avisos (8 informativos `SetTextI18n`, documentados en `lint.xml`) |
| `:wallet:connectedDebugAndroidTest` en el aparato | 30 pruebas, 0 fallos, 0 omitidas (Redmi Note 15 5G, Android 16; salida abajo) |

Las pruebas adversarias del analizador corren **instrumentadas** (`EmparejamientoInstrumentadoTest`,
8 pruebas) porque el `org.json` de Android sobrescribe las claves duplicadas y acepta JSON
permisivo, mientras que el de la JVM rechaza las duplicadas: el verde de la JVM no vale para esto.
Cubren: paquete válido aceptado en Android; texto sobrante tras el JSON (incluido
« PRIVATE KEY=synthetic» detrás); `"tunel":{"private_key":…},"tunel":null` y duplicadas en un
nivel interior o con escape `\u`; claves sin comillas, comillas simples y comentarios; semilla y
WIF con texto alrededor y WIF dentro del host; fecha con cola, anchuras exactas y calendario,
`caduca == ahora`, emitido más de 5 min en el futuro; `[::::]` rechazado y `::ffff:A.B.C.D/128`
aceptado; 7.000 palabras y 16.000 caracteres sin desbordar nada; semilla pegada a «:», «.» o
paréntesis rechazada. Las carreras de NodeBridge corren también en el aparato con el almacén real
(`NodeBridgeCarrerasInstrumentadoTest`, 4): `olvidar` durante una adopción lenta no deja nada
guardado; descartar un candidato adelantado deja al nodo vigente sano a su altura y la adopción
guarda configuración y metadatos juntos; un almacén cuyo `commit()` devuelve `false` no adopta, no
publica Sano y no deja nada a medias; y con persistencia REAL (directorio de preferencias de solo
lectura), partiendo de A en disco y adoptando B, `commit()` falla después de tocar la memoria y
todo sigue en A: `comprobar()`, `conexionSana()`, la memoria restaurada de las preferencias y el
fichero releído en frío.

El flujo de pantalla (`EmparejarActivityInstrumentadoTest`, 5 pruebas, repositorio falso con el
«motor» del nodo sustituido): paquete válido → vista previa → nada guardado antes de confirmar →
confirmar → Sano → se guardan servidor, huella, nombre y datos públicos del túnel, NodeBridge
publica Sano y la pantalla se cierra; nodo caído → «No se ha guardado nada» con el motivo y sin
rastro; **Descartar durante un servidor lento → llega Sano → NO se guarda**; al confirmar se
revalida: un paquete caducado entre medias se rechaza sin guardar; paquete con un campo de clave
privada → «PAQUETE RECHAZADO», sin vista previa ni botón de confirmar. En total 30 instrumentadas
(13 anteriores, 5 de pantalla, 8 adversarias, 4 de carreras).

Resultados por prueba en el aparato (XML de `wallet/build/outputs/androidTest-results/connected/debug/`,
marca de tiempo 2026-10-31T04:20:28). Nota honesta: la primera pasada de esta vuelta dio 2 fallos en
`RecuperacionYEscaneoInstrumentadoTest`, porque esa prueba escribía el servidor directamente en
las preferencias y, con la configuración activa viviendo ya en NodeBridge, eso no configura nada;
se corrigió la prueba para configurar por `nodo.configurar(...)` (el código de la app nunca escribía
las preferencias a mano) y la segunda pasada fue limpia:

```text
x39.wallet.android | tests 30 failures 0 errors 0 skipped 0
  - AjustesActivityInstrumentadoTest.elBotonDelServidorPublicoRellenaElCampo_soloEnSignet ok 2.614s
  - AlmacenSemillaInstrumentadoTest.ficheroCifradoFueraDeLasCopiasDeSeguridad ok 0.003s
  - AlmacenSemillaInstrumentadoTest.laClaveExigeAutenticacionPorOperacion ok 0.08s
  - BilleterasInstrumentadoTest.mainnetProhibidaPorElMotor ok 0.003s
  - BilleterasInstrumentadoTest.unaSolaBilleteraPorFichero_tambienDesdeAndroid ok 0.028s
  - BilleterasInstrumentadoTest.bibliotecaSoloRedesDePrueba ok 0.001s
  - BilleterasInstrumentadoTest.nuevaSemilla_docePalabras_yCreaBilleteraConByteBufferDirecto ok 0.0s
  - EmparejamientoInstrumentadoTest.semillaPegadaAPuntuacion_seRechaza_yMilesDePalabrasNoDesbordan ok 0.085s
  - EmparejamientoInstrumentadoTest.jsonPermisivoDeAndroid_seRechaza ok 0.001s
  - EmparejamientoInstrumentadoTest.clavesDuplicadas_seRechazanAunqueAndroidLasSobrescribiria ok 0.002s
  - EmparejamientoInstrumentadoTest.fechasConAnchurasExactas_yRobustezAnteMilesDePalabras ok 0.055s
  - EmparejamientoInstrumentadoTest.textoSobranteTrasElJson_seRechaza ok 0.002s
  - EmparejamientoInstrumentadoTest.elPaqueteValidoSeAceptaEnAndroid ok 0.001s
  - EmparejamientoInstrumentadoTest.fechasEstrictas_eIpv6Literal ok 0.027s
  - EmparejamientoInstrumentadoTest.secretosDentroDeLosValores ok 0.002s
  - EmparejarActivityInstrumentadoTest.paqueteConClavePrivada_seRechazaEntero_sinBotonDeConfirmar ok 1.14s
  - EmparejarActivityInstrumentadoTest.nodoCaido_noGuardaNada_yDiceElMotivo ok 1.11s
  - EmparejarActivityInstrumentadoTest.descartarDuranteLaComprobacion_cancelaDeVerdad_yElSanoTardioNoSeGuarda ok 3.126s
  - EmparejarActivityInstrumentadoTest.paqueteValido_vistaPrevia_yConfirmarGuardaSoloTrasSano ok 1.912s
  - EmparejarActivityInstrumentadoTest.alConfirmarSeRevalida_unPaqueteCaducadoEntreMediasSeRechazaSinGuardar ok 1.138s
  - EnviarActivityInstrumentadoTest.firmarResultadoDifundir_sinExcepcion_yMuestraElEstado ok 1.515s
  - EnviarActivityInstrumentadoTest.laFlechaDeVolverCierraLaPantallaComoElBotonAtras ok 1.965s
  - EnviarActivityInstrumentadoTest.siLaDifusionFalla_loDiceYNoRompe ok 1.388s
  - NodeBridgeCarrerasInstrumentadoTest.olvidarDuranteUnaAdopcionLenta_noQuedaNadaGuardado ok 0.028s
  - NodeBridgeCarrerasInstrumentadoTest.siCommitDevuelveFalse_noSeAdoptaNiSePublicaSano_yNoQuedaNadaAMedias ok 0.028s
  - NodeBridgeCarrerasInstrumentadoTest.descartarCandidatoLento_elNodoVigenteSigueSanoASuAltura_yAdoptarGuardaConfigYMetadatosJuntos ok 0.027s
  - NodeBridgeCarrerasInstrumentadoTest.siCommitFallaDespuesDeTocarLaMemoria_todoSigueEnA_tambienEnFrio ok 0.028s
  - NodeBridgeInstrumentadoTest.servidorPublicoDeSignet_sano_yHuellaIncorrecta_rechazada ok 0.559s
  - RecuperacionYEscaneoInstrumentadoTest.restaurarSinServidor_dejaEscaneoPendiente_ySincronizarLoCompleta ok 18.593s
  - RecuperacionYEscaneoInstrumentadoTest.recuperarLaClave_noTocaElEstadoNiElRegistroDeEnvios ok 0.205s
```

La lectura real de un QR con la cámara del aparato y la importación de un fichero desde el
selector del sistema quedaron probadas a mano el 31/10/2026 (§5.2: tres lecturas de
paquete por QR (B dos veces, C y D) más un QR que no era paquete; tres importaciones por fichero
(D dos veces, B una); una adopción real con huella fijada que sobrevive al cierre de la app). Siguen sin
probar: la rama «permiso de cámara denegado», la adopción contra un nodo propio en signet y el
túnel propio, que no existe (E-3).

## 7. Criterios de salida a mainnet

Definición de «lista para dinero real». Cada criterio o pasa o no pasa; no hay «casi». Dos
grupos: los **bloqueantes** tocan fondos y tienen que estar los cinco **resueltos**: cada uno
en «HECHO» con su evidencia, o como **excepción** expresa, registrada en este documento y
aceptada por escrito por el usuario, que declare el riesgo residual. Una excepción **no** es un
criterio demostrado y se distingue de él; sin una de las dos para los cinco, no se va a
mainnet. Los **opcionales** no bloquean y son hoja de ruta. Estado a 01/11/2026, contrastado
con lo que registran las secciones anteriores y las pruebas del repo; «hecho» solo cuando hay
una prueba o un recorrido anotado que lo demuestre.

### 7.1 Bloqueantes

| # | Criterio | Qué demuestra | Cómo se demuestra | Estado hoy |
|---|---|---|---|---|
| B1 | **Contrato de difusión incierta y reemplazo RBF** | Que una difusión nunca deja la billetera en un estado que pierda o libere monedas por error: `Difundiendo` se persiste **antes** de transmitir; un corte entre transmitir y anotar recupera como `Incierta`; ningún rechazo del servidor libera monedas (todo a `Incierta`); «already known» / «already in mempool» / «already in block chain» / «already in utxo set» / `txn-already` → `Enviada`; cancelar, **cuando ya hubo** un intento de difusión, = reemplazo RBF sobre las mismas entradas; una transacción solo firmada y nunca difundida se cancela descartándola, sin RBF (es la única liberación automática, §1) | Motor compilado con `--features mainnet` (`rust/src/tests.rs`, 19 pruebas `regtest_` en verde): `regtest_el_intento_esta_en_disco_antes_de_transmitir`, `regtest_corte_entre_transmitir_y_anotar_deja_incierta`, `regtest_rechazo_en_el_primer_intento_no_libera`, `regtest_rechazo_en_un_reintento_no_libera`, `regtest_rechazo_explicito_no_libera_las_monedas`, `regtest_difusion_incierta_nunca_reconstruye_y_reintenta_la_misma`, `regtest_ya_conocida_es_exito_y_no_libera`, `regtest_cancelar_tras_difundir_es_un_reemplazo_rbf`, `regtest_si_confirma_la_original_el_reemplazo_queda_fallido`, `regtest_cancelada_que_aparece_en_un_bloque_pasa_a_confirmada`, `regtest_firmada_sin_difundir_se_puede_cancelar` (§1); banco regtest real con Bitcoin Core + electrs 0.11.1 (§2: difusión, «ya conocida» difundiendo por fuera, RBF); Android `EnviarActivityInstrumentadoTest` (3 pruebas). Se descarta exigir un nodo propio en signet porque el nodo propio real opera en mainnet (§5.2) y las averías de difusión y RBF se ejercitan de forma determinista en regtest | **HECHO** (01/11/2026). Verificado sobre el código candidato con `cargo test --features mainnet -- regtest_` (19/19 en verde) y en el banco regtest real con electrs (§2). |
| B2 | **Recuperación desde la semilla** | Que una instalación limpia con solo las 12 palabras recupera saldo e historial, y que el registro de envíos no miente: lo que el servidor muestra vuelve como movimientos; lo firmado o incierto anterior **no** se recupera desde la semilla (limitación documentada en el crate y en el README) y la app lo dice | Motor, §2.1: restauración limpia en regtest con el mismo saldo y los mismos movimientos, registro de envíos vacío; Android, §4: `RecuperacionYEscaneoInstrumentadoTest` (restaurar sin servidor deja escaneo pendiente, se niega a preparar envíos y se completa al sincronizar; recuperar la clave no toca estado ni envíos). Falta: **instalación limpia en el aparato** (desinstalar, instalar, restaurar con las 12 palabras en signet) con saldo e historial iguales a los de §5.1, y el texto que explica qué no vuelve | **HECHO** (31/10/2026). Instalación limpia real en el Redmi Note 15 5G: respaldo previo, desinstalación del paquete (que se lleva la clave del Keystore, así que el `semilla.enc` respaldado queda inservible: la única vía de vuelta son las 12 palabras), instalación del `.lab` recién compilado y restauración solo con las 12 palabras. Resultado comprobado por el autor en el aparato: saldo idéntico al de §5.1 (6.050.237 sat confirmados, 0 pendientes), movimientos recuperados desde el servidor (ingreso del grifo y el envío de 10.000 sat del bloque 328731), lista de ENVÍOS FIRMADOS vacía con el texto que explica que lo firmado o incierto de otra instalación no se recupera desde la semilla, y escaneo pendiente hasta sincronizar. Las pantallas de Historial llevan `FLAG_SECURE` y no admiten captura; comprobado a ojo por el autor. Instrumentadas verdes en el aparato el mismo día (`RecuperacionYEscaneoInstrumentadoTest`, 2 pruebas). Hallazgo anotado: una instalación limpia pierde también el nodo emparejado (vive en `shared_prefs`); se recuperó reimportando el paquete por fichero, y esa adopción ya pasó por el suelo de altura real de la billetera (328835), no por 0. Los textos de `CrearRestaurarActivity.kt` e `HistorialActivity.kt` que explican qué no vuelve son parte de esta evidencia |
| B3 | **Firma: autenticación por operación; la semilla no sale del búfer y se borra tras usarla** | Que cada firma exige al usuario (biometría fuerte o credencial), que el mnemónico existente cruza a Rust como `ByteBuffer` directo puesto a cero en `finally`, y que `nueva_semilla` no serializa las palabras en un `Vec` temporal ni en un `RustBuffer` de UniFFI | `rust/src/ffi.rs`: `nueva_semilla` devuelve un objeto opaco `SemillaGenerada` cuyo almacenamiento vive en `Zeroizing<Vec<u8>>`; UniFFI solo cruza el identificador; Kotlin lee byte a byte con retornos escalares (`byte_en`) y ejecuta `borrar()` en un `finally` antes de cerrar el objeto, poniendo a cero el bloque nativo original e invalidando cualquier otro identificador (`pruebas_semilla_generada`, 2 pruebas en Rust con `--features mainnet`). Android: `AlmacenSemillaInstrumentadoTest.laClaveExigeAutenticacionPorOperacion`, `MnemonicoTest` (JVM) y `BilleterasInstrumentadoTest`. Límite residual honesto declarado en `README.md` y `ffi.rs`: cubre todos los bloques controlados por el motor y la capa FFI/Kotlin; no alcanza copias internas de la JVM, vistas de Android, registros de CPU ni `secp256k1`/`miniscript` | **HECHO** (01/11/2026). Eliminadas las copias intermedias de UniFFI en `nueva_semilla` mediante `SemillaGenerada` opaca + `borrar()` explícito; verificado en Rust (`pruebas_semilla_generada`, 2/2) y JVM (`./gradlew test -Px39.mainnet=true`, verde). |
| B4 | **Puerta del nodo no evitable** (huella SPKI en el apretón TLS → protocolo 1.4 por `server.version` como primer mensaje de la sesión → génesis → altura que no baja, ni respecto a lo visto ni respecto a lo que el propio servidor anunció en la sesión; es el orden de `Conexion::abrir` en `rust/src/nodo.rs`, el mismo para la sonda `comprobar_nodo` y para cada operación) | Que ninguna operación de red (escaneo, sincronización, difusión, altura, comisión) habla con un servidor de otra red, con otro certificado, que haya retrocedido o con otro protocolo; y que no hay camino que se la salte | Motor Rust, batería `puerta_*` en `rust/src/tests.rs` (47 pruebas, todas contra el simulado `mock_electrum.rs`, que numera las conexiones y apunta la secuencia de métodos de cada una): sonda, protocolo distinto de 1.4 (incluidos «1.40», «1.45», «1.4basura», «1.4.», «1.4.basura»; aceptados «1.4» y «1.4.2») y altura que retrocede respecto a la vista; conservación del estado en cada rechazo, puerta por puerta (huella, génesis, protocolo, altura), comparando tras CADA operación ficheros byte a byte, saldo, altura, movimientos, índices y envío: si la validación falla antes de transmitir nada cambia y el envío sigue Firmada con 0 intentos; si la difusión ya empezó (recepción acreditada por el contador del simulado, 1) un reintento rechazado deja Incierta, 1 intento, monedas reservadas y no suma recepción; operación por operación (escaneo, sincronización, difusión, altura, comisión) con maldad GLOBAL (la conexión de la operación rechaza) y con maldad POR CONEXIÓN (servidor bueno en la conexión siguiente y malo en la de después: la operación sale bien, viaja por UNA conexión que corrió `server.version`, `server.features` y `headers.subscribe` antes de operar, y ninguna conexión mala recibe nada; caza a un motor que compruebe por una conexión y opere por otra); altura inferior recibida DURANTE BDK (3→1 y 3→1→3) abortada antes de aplicar o persistir; marca máxima dentro de la sesión (100→500→200 y 0→500→200); `altura_red` devuelve la altura validada sin segunda consulta; `server.version` como primer mensaje y única negociación por conexión (simulado estricto que rechaza cualquier RPC anterior y un segundo `server.version`, y que negocia lo que propone el cliente), versión negociada no admitida aunque `server.features` anuncie 1.4; conexión caída que no se reabre sola y operación siguiente que vuelve a pasar las puertas; unidad del vigilante (`fetch_max`). Positivas de siempre (`nodo_tls_con_huella_correcta_es_sano_y_la_billetera_sincroniza_por_el`, `nodo_la_huella_viaja_en_altura_y_comision`) y las negativas previas de huella y red. Fuera del simulado: electrs 0.11.1 real del banco regtest (sincronización, altura y comisión por `tcp://`; electrs tolera un segundo `server.version` y responde 1.4 a un rango, por eso no bastaba) y las cinco ignoradas de signet contra el servidor público por TLS con raíces públicas. En el aparato: §5 y §5.2 (huella), `NodeBridgeInstrumentadoTest` | **VERIFICADO en el motor Rust** (31/10/2026): las cuatro puertas corren en CADA conexión (`Conexion::abrir`, la misma para la sonda y para cada operación), `server.version` es el primer mensaje de la sesión y la ÚNICA negociación (propone 1.4 a secas y valida la versión negociada, no `server.features`), marca atómica de altura máxima vista (rechaza una altura inferior a lo visto antes o a lo que el propio servidor anunció en la sesión, también la que recibe BDK a mitad de escaneo o sincronización, antes de aplicar o persistir nada), sin reconexión silenciosa (`RawClient` sin reintentos; una conexión caída no se reabre por dentro y la operación siguiente vuelve a pasar las puertas); batería negativa completa en `rust/src/tests.rs` (`puerta_*`, 47 pruebas) con conservación del estado en disco byte a byte y en memoria tras cada rechazo, maldad por conexión y servidor estricto en el simulado; comprobado contra electrs 0.11.1 real (banco regtest) y contra el servidor público de signet por TLS con raíces públicas. Integración Android HECHA (31/10/2026): alturaVista en estimarComision desde estado.dat; suelo de altura por red en NodeBridge, exigiendo el MAYOR entre el suelo persistido de la billetera y la marca máxima de la sesión, aplicado también en configurar, probar y adoptarSiSano (antes pasaban 0, daban por sano y adoptaban un nodo por debajo de la billetera); adoptarSiSano vuelve a comprobar el suelo tras la sonda lenta y antes de guardar, por si subió mientras tanto; configurar corre fuera del hilo de interfaz; bindings regenerados; 42 pruebas JVM verdes; prueba instrumentada EJECUTADA en el aparato el 31/10/2026 (Redmi 25098RA98G, Android 16, adb inalámbrico, 2026-10-31T17:00:01Z): NodeBridgeInstrumentadoTest.puertaDeAltura_conElMotorReal_formateaElRechazoYRespetaSueloYMarca contra la biblioteca nativa real (libx39wallet.so 9576c651…63e215) y servidor Electrum falso en 127.0.0.1 (suelo N+20 frente a servidor N; retroceso de N+50 a N+10), 1 caso, 0 fallos, 0,118 s, fijando la cadena exacta del motor y su traducción («ese nodo va por detrás de lo ya visto: bloque N frente a M»). Verificado sobre el código y binario candidato Mainnet (`libx39wallet.so` `581a852c2645520af6a10fda8c89919dfdb4ea1d19b2de7930de2028ca932764`, `cargo test --features mainnet -- puerta_` 47/47 en verde y suite JVM `-Px39.mainnet=true` con aislamiento en `PerfilBilleteraTest`). |
| B5 | **Build reproducible verificado: dos compilaciones, mismo hash; alineación de 16 KB** | Que un tercero puede recompilar la biblioteca nativa y obtener el mismo binario que va en el APK, y que los segmentos LOAD están a 16 KB | `android/build-native.sh --verificar` (compilación limpia en directorio temporal y comparación con `build-native.lock`); la comprobación de LOAD la hace el script en cada compilación | HECHO para la biblioteca VIGENTE (solo-prueba, 9576c651…63e215, 9.085.840 bytes): --verificar el 31/10/2026, compilación limpia en directorio temporal con el mismo hash que build-native.lock y que la .so empaquetada; tres segmentos LOAD a 0x4000; NDK 30.0.16248370, rustc 1.98.1, cargo-ndk 4.1.2, uniffi 0.32.2, --locked y remap-path-prefix; webpki-roots 0.25.4 entra como dependencia directa y la reproducibilidad se mantiene. La verificación anterior (abe7822e…, 9.428.864 bytes) queda como histórica. **HECHO para el candidato Mainnet** (01/11/2026, marcador `x39wallet-redes=mainnet`, `libx39wallet.so` `581a852c2645520af6a10fda8c89919dfdb4ea1d19b2de7930de2028ca932764`, 9.094.648 bytes): doble compilación limpia en directorio temporal idéntica a `android/build-native.lock` y a la `.so` empaquetada; tres segmentos `LOAD` a `0x4000` (16 KB); artefactos registrados en `REPRODUCIBILITY.md` y `SHA256SUMS.txt` (`wallet-release.aar` `c0bd8904…`, `x39-wallet-comunicaciones-mainnet-signed.apk` `a1e53685…`, `x39-wallet-general-mainnet-signed.apk` `8d219822…`). |

Regla de cierre: los cinco **resueltos** sobre el **binario candidato**: cada bloqueante en
«HECHO» con su evidencia anotada en este documento, o como **excepción** expresa, registrada
en este documento y aceptada por escrito por el usuario, que declare el riesgo residual. Una
excepción no es un criterio demostrado y se distingue de él; sin una de las dos para los
cinco, no se va a mainnet. El candidato es «mainnet habilitado» (compilado con la feature
`mainnet`; marcador `x39wallet-redes=mainnet` en la biblioteca), pero la red se elige en
**tiempo de ejecución** (`rust/src/lib.rs`: con la feature, `Red::permitida` admite signet,
regtest y mainnet), así que **ese mismo binario** se ejercita en signet y regtest sin dinero
real; signet y mainnet no son binarios distintos. La evidencia que cuenta: el binario candidato
(mainnet habilitado) probado en signet y regtest; las pruebas unitarias sobre el **mismo**
código y las **mismas** opciones de compilación (`cargo test --features mainnet`, como ya hace
§1); las integradas e instrumentadas cargando la biblioteca **exacta** del APK candidato; y
todo identificado por opciones de compilación y hashes (los del `build-native.lock` y del APK).
Un criterio verificado sobre otra biblioteca, otras opciones u otro commit no cuenta.

### 7.2 Opcionales (hoja de ruta, no bloquean mainnet)

| Qué | Estado hoy |
|---|---|
| Túnel propio (E-3) | **Diseño** confirmado en `docs/e3-tunel-propio.md`; viabilidad de compilación medida (§8.1 de ese documento); sin código |
| Las tres mejoras menores de §5.2 (quitar «(os error 111)» del texto al usuario; atajo a Emparejar desde los ajustes del nodo; nombre del nodo emparejado en Ajustes) | Anotadas, sin hacer |
| Rama «permiso de cámara denegado» | Sin probar (§5.2) |
| Multifirma | Sin diseño ni plan; fuera de esta versión |

### 7.3 Deuda de PRIORIDAD ALTA (03/11/2026): la billetera mainnet instalada va firmada con la clave de depuración

El `.lab` (`org.x39matrix.llamadas.lab`) que lleva la billetera mainnet en el Redmi, y los dos APK
de la entrega del selector de nodo (`cierre/apk/`), están firmados con el almacén de depuración de
Gradle/AGP de la máquina de desarrollo (`~/.android/debug.keystore`, certificado
`CN=Android Debug, O=Android, C=US`, SHA-256 `30922e27…8187`; detalle en
`client-sdk-android/entregas/selector-nodo-20261103/cierre/07-firma.txt` y `08-keystore.txt`).
Esa clave no es una identidad de X-39: la genera AGP con contraseña por defecto y cualquier copia
del fichero firma actualizaciones válidas del paquete. Vale para el laboratorio; no vale para una
billetera con fondos a largo plazo.

**Advertencia: NO existe cambio de firma sobre el paquete instalado.** Android exige que toda
actualización (`adb install -r`) vaya firmada con la misma clave que el paquete que ya está; un APK
con otra clave se rechaza (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), y la única forma de pasar a otra
clave es desinstalar e instalar de nuevo, lo que destruye los datos privados de la app
(noBackupFilesDir y las claves del Keystore de Android): la billetera del aparato desaparece y solo
vuelve desde la semilla. Por eso la migración es un plan de tres pasos, no un «re-firmado»:

1. **Semilla respaldada y PROBADA.** Las doce palabras en papel, fuera del móvil, leídas dos veces;
   y restauradas con éxito en otro aparato (el móvil secundario, edición signet basta para probar el
   formato BIP39/BIP84; la frase comprobada tiene que ser la del Redmi), borrando después esa
   restauración del secundario. Es el paso B-0.2 del guion
   `cierre/04-guion-movil.md`. Sin este PASA no se sigue.
2. **Instalación NUEVA con keystore propio.** Compilar el sabor con `signingConfig` de un almacén
   de X-39 (un keystore propio, distinto del de depuración y, si se quiere, distinto del de
   publicación de Comunicaciones), guardado fuera de la máquina de desarrollo. Como la clave
   cambia, esto es forzosamente desinstalar el `.lab` actual e instalar el nuevo (o instalarlo con
   OTRO `applicationId` y convivir hasta comprobar el paso 3: así el `.lab` viejo con sus fondos
   sigue intacto mientras se prueba). Antes de desinstalar nada: paso 1 en verde, saldo y
   direcciones anotados, y ningún envío en curso (`Firmada` o `Incierta`) en la billetera vieja.
3. **Restauración desde la semilla** en la instalación nueva: mismas doce palabras → mismo
   `xpub` BIP84 → mismas direcciones y mismo saldo tras sincronizar con el nodo X-39 (comprobar la
   primera dirección de recibir y el saldo frente a lo anotado). Solo entonces se desinstala el
   `.lab` viejo (si convivían) y se da la migración por hecha. Los envíos firmados o inciertos de la
   instalación vieja no vuelven con la semilla (§4, B2): por eso el paso 2 exige que no haya ninguno.

Hasta completar los tres pasos: copia del almacén de depuración fuera de la máquina (comandos en
`08-keystore.txt`), porque perderlo deja el `.lab` instalado sin posibilidad de actualización.

## 8. Selector de nodo: «Nodo X-39» o «Mi nodo» (03/11/2026)

Orden: que cada usuario elija con qué nodo sincroniza su billetera. Modo «Nodo X-39»
(predeterminado: el servidor Electrum de X-39 por el X39 Túnel, sin escribir nada; sin túnel no
se conecta por otra vía) y modo «Mi nodo» (`tcp://`, `ssl://` o `.onion` con proxy SOCKS5; un
servidor solo se adopta tras comprobar que responde y que su génesis es el de la red de la
billetera). Nada de fondos reales: todo contra el simulado, el banco regtest local o en JVM.
Lo que exige el aparato lo hace el propietario (P1, P5 y P8 en parte, P9).

### 8.1 Qué cambió

- Motor (`rust/src/nodo.rs`, `lib.rs`, `ffi.rs`): proxy SOCKS5 opcional en todas las conexiones
  (`Conexion::abrir(…, proxy)`, `comprobar_nodo`, `altura_red`, `estimar_comision`,
  `Billetera::fijar_proxy`); CONNECT con el nombre tal cual (ATYP 3; IP literal como IP), de modo
  que quien resuelve es el proxy; un destino `.onion` SIN proxy se rechaza antes de abrir ningún
  socket. `huella_servidor(servidor, proxy)`: solo el apretón TLS, sin hablar Electrum, devuelve
  la huella SPKI que presenta el servidor, para que la app la muestre y el usuario la fije si
  activa «certificado propio». Bindings regenerados; biblioteca nueva (§8.3).
- Android (`NodoX39.kt`, `NodeBridge.kt`, `Ajustes.kt`, `AjustesActivity.kt`,
  `WalletRepository.kt`, `BilleteraActivity.kt`): `ModoTunel.X39` (exige el X39 Túnel, que
  anuncia la app por `NodoX39.tunelActivo`), `ConfigNodo.proxySocks5`, modo persistido
  `modo_nodo` con migración (`NodoX39.resolverModo`), pantalla Ajustes → Nodo con los dos modos,
  «Probar conexión» (versión, red, altura, protocolo, latencia) y «Usar este nodo»
  (`adoptarSiSano`: si falla, nada cambia), casillas «certificado propio» y «proxy SOCKS5»,
  errores en lenguaje llano (`mensajeLlano`). Regla cambiada: `ssl://` sin huella ya no se
  rechaza en Kotlin (el motor valida contra las autoridades públicas); el paquete de
  emparejamiento sigue exigiendo huella con `ssl://`.
- App que incluye el módulo (`client-sdk-android`, sabor `general`): proveedor del túnel y fila
  «Nodo» en Seguridad con la frase fija, en cinco idiomas.

### 8.2 Pruebas automáticas del motor (`rust/`, `cargo test --features mainnet` y sin feature)

| Orden | Resultado (03/11/2026) |
|---|---|
| `cargo test --features mainnet` | **95 pasadas, 0 fallos, 5 ignoradas** (antes 88 + 2 de la semilla; nuevas: 5) |
| `cargo test` (sin mainnet) | 95 pasadas, 0 fallos, 5 ignoradas |
| `cargo clippy --all-targets` (con y sin `mainnet`) | 0 avisos nuevos; quedan 3 anteriores a este cambio (`large_enum_variant` en `Flujo`, y dos ayudantes de pruebas con 8 argumentos) |
| `cargo fmt --check` | limpio |

Pruebas nuevas (`rust/src/tests.rs`, con un **proxy SOCKS5 falso** local que apunta el destino
pedido y reenvía al simulado ignorando el nombre, como haría Tor con un `.onion`):

| Prueba | Qué demuestra |
|---|---|
| `nodo_onion_sin_proxy_se_rechaza_antes_de_abrir_socket` | `.onion` sin proxy → `Error::Red` con «proxy SOCKS5» en la sonda, en `altura_red`, en `huella_servidor` y en `Billetera::sincronizar`; inmediato (< 2 s) y el simulado no recibe ninguna conexión; un proxy en blanco cuenta como ninguno |
| `nodo_por_proxy_socks5_llega_al_servidor_y_el_nombre_lo_resuelve_el_proxy` | sonda, altura y comisión por el proxy; el proxy recibe el nombre `.onion` tal cual (ATYP 3) y una IP literal como IP (ATYP 1); la billetera con `fijar_proxy` sincroniza por el proxy (saldo y movimientos correctos) y TODAS sus conexiones van por él; el génesis sigue mandando a través del proxy |
| `nodo_por_proxy_socks5_con_tls_la_huella_sigue_mandando` | TLS con huella por el proxy: sano; huella equivocada: `HuellaServidor`; sin huella y autofirmado: rechazado; `huella_servidor` la observa por el proxy |
| `nodo_proxy_que_rechaza_falta_o_esta_mal_escrito_da_error_claro` | respuesta SOCKS5 0x04 → «servidor inalcanzable» nombrando el destino, y el simulado no recibe nada; nadie escucha en el proxy → error con la dirección del proxy; `127.0.0.1`, `127.0.0.1:abc`, `:9050` → «proxy SOCKS5 … host:puerto»; algo que acepta y cuelga → «sin respuesta al saludo» |
| `nodo_huella_servidor_observa_el_spki_sin_hablar_electrum` | la huella observada = la del simulado TLS; la conexión que vio el simulado no pidió NINGÚN método Electrum; con `tcp://` no hay certificado; observar no es validar (una huella fijada distinta sigue rechazándose) |

### 8.3 Banco regtest local real (Bitcoin Core 30.2 + electrs 0.11.1, `tcp://127.0.0.1:60401`)

`cargo test --features mainnet -- --ignored banco_regtest --nocapture` (banco levantado con
`arriba.sh`, altura 108, y parado después con `abajo.sh`):

```
electrs real: servidor «electrs/0.11.1», protocolo 1.4, altura 108, red regtest, 0 ms, cifrado false
pedido como signet: el servidor es de otra red: se esperaba signet, el servidor anuncia génesis 0f9188f1…66e2206
pedido como mainnet: el servidor es de otra red: se esperaba bitcoin, el servidor anuncia génesis 0f9188f1…66e2206
altura exigida +10: servidor Electrum: el servidor va por detrás: altura 108, ya se había visto 118
.onion sin proxy: servidor Electrum: un servidor .onion solo se alcanza por un proxy SOCKS5 (Orbot): activa el proxy en los ajustes del nodo
por proxy SOCKS5 127.0.0.1:38337: altura 108, servidor «electrs/0.11.1»
test tests::banco_regtest_selector_nodo_sonda_otra_red_y_proxy ... ok
```

Es la evidencia de **P3** (servidor de otra red → rechazado por génesis, sin tocar nada: la sonda
no escribe) y de **P7** (versión, altura y red en la sonda; errores en texto llano) contra un
electrs de verdad, y de que la misma sonda llega al electrs real a través de un proxy SOCKS5.

### 8.4 Biblioteca nativa (mainnet) y bindings

`X39_MAINNET=1 android/build-native.sh --actualizar`: `libx39wallet.so`
**`244eaa581adbaa822f724c44f5922c627fb96a1488ceca4eaf4580f26bff5a43`**, 9.119.776 bytes, tres
segmentos `LOAD` a `0x4000`; NDK 30.0.16248370, rustc 1.98.1, cargo-ndk 4.1.2, uniffi 0.32.2;
bindings con `comprobarNodo(…, proxySocks5)`, `alturaRed(…, proxySocks5)`,
`estimarComision(…, proxySocks5)`, `huellaServidor(servidor, proxySocks5)` y
`Billetera.fijarProxySocks5`. El binario certificado en §7 (`581a852c…`) queda sustituido.
**B5 repetido sobre esta biblioteca en el cierre (03/11/2026, §8.8): `X39_MAINNET=1
android/build-native.sh --verificar` da `244eaa58…` en compilación limpia en directorio temporal:
REPRODUCIBLE.**

### 8.5 Módulo Android: pruebas JVM y lint (`./gradlew :wallet:testDebugUnitTest -Px39.mainnet=true`)

| Clase | Pruebas | Fallos |
|---|---|---|
| EmparejamientoTest | 17 | 0 |
| MnemonicoTest | 9 | 0 |
| NodeBridgeTest | 23 (4 nuevas) | 0 |
| NodoX39Test (nueva) | 4 | 0 |
| PerfilBilleteraTest | 3 | 0 |
| **Total** | **56** | **0** |

Nuevas: `configuracionInvalida` ampliada (`ssl://` sin huella válida; host:puerto; `.onion` exige
proxy; proxy mal formado), `elNodoX39ExigeElTunelDeLaApp_yNoSeConectaPorOtraVia` (sin el X39
Túnel: `Caido` con el texto fijo y CERO llamadas al motor aunque haya otra VPN activa; con túnel:
sano; `probar` no guarda), `conexionSanaLlevaElProxyDeLaMismaConfiguracion`,
`olvidarDejaLoQueDigaElAlmacen`, `losErroresDeRedSalenEnLenguajeLlano_conservandoElOriginal`;
`NodoX39Test`: solo el puerto Electrum por red (nada para regtest ni testnet), modo
predeterminado X-39 en instalación limpia y migración de lo ya guardado, descripción para
Seguridad, frase fija. `:wallet:lintDebug`: **BUILD SUCCESSFUL** (había 2 errores `NewApi`
anteriores a este cambio en `TelemetriaPi5.urlPanel`, anotado entonces con `@RequiresApi(M)`;
ese fichero sale del módulo en el cierre, §8.8).

### 8.6 APK `general` (`.lab`) y P10

`X39_MAINNET=1 ./gradlew :sample-app:assembleGeneralDebug -Px39.mainnet=true` → BUILD
SUCCESSFUL; `:sample-app:assembleComunicacionesDebug` → BUILD SUCCESSFUL (la costura nueva
devuelve `null`). En el dex: `REDES_PERMITIDAS="mainnet"`, `MARCADOR_REDES_ESPERADO=
"x39wallet-redes=mainnet"`. **No instalado en ningún aparato** (lo instala el propietario).

P10 (`entregas/selector-nodo-20261103/05-p10-cadenas-apk.txt` en `client-sdk-android`): en
los DEX y `resources.arsc` no hay `8332`, `18443`, `stratum`, `rpcuser` ni `rpcpassword`; las
únicas direcciones del nodo son `tcp://10.39.0.3:50001` y `tcp://10.39.0.3:60601` (el Electrum
de X-39 por red). Los demás aciertos son de otras bibliotecas: «RPC» (29) es el RPC de LiveKit
(`RPC_REQUEST`, `RPC_ACK`…); «3333» son números (`333333`, curvas Bézier); las IP sueltas son
ejemplos de WebRTC/SIP y las del túnel de la propia app (`10.39.0.1/2`, `159.198.37.137:51820`).
Quedaba en el APK, por decisión del 02/11/2026 y fuera de esa orden, la telemetría de la
Pi 5 (`TelemetriaPi5.kt`: `:8000`, `:3000`, `192.0.2.46`, «CKPool», `/api/v1/pool/restart`;
`EdicionExtra.URL_PANEL_PI5`). **En el cierre (§8.8) sale del módulo y del sabor `general`**: P10
repetido sobre el APK recompilado en
`entregas/selector-nodo-20261103/cierre/01-p10-strings.txt`.

### 8.7 Pruebas de aceptación: estado

| # | Prueba | Estado |
|---|---|---|
| P1 | Instalación limpia → modo X-39 por defecto, sin dirección que escribir | Lógica probada en JVM (`resolverModo(null, "", red) = X39`); en el móvil (03/11/2026, APK `34499ed7…`): el modo X-39 arrancó pero la puerta del túnel daba «activa el X39 Túnel» con el túnel levantado → **corregido en §8.9**; recorrido pendiente con el APK nuevo |
| P2 | «Mi nodo» con `tcp://` de la misma red → sincroniza y el saldo coincide | Motor: sincronización con el simulado por proxy y en directo, saldo exacto (§8.2); cambio de servidor sin borrar nada (misma cadena, basta sincronizar); **en el móvil: pendiente** |
| P3 | Servidor de otra red → rechazado, estado intacto | Banco regtest real pedido como signet y mainnet (§8.3); `adoptarSiSano` no guarda si no da Sano (NodeBridgeTest) |
| P4 | `ssl://` válido OK; certificado no válido rechazado sin la casilla, aceptado con ella y con aviso | Motor: autofirmado sin huella rechazado, con huella observada y fijada aceptado (§8.2); la casilla y el aviso están en `AjustesActivity`; **recorrido en pantalla: pendiente** |
| P5 | `.onion` sin proxy rechazado con mensaje; con proxy conecta | Motor y Kotlin (sin proxy: ni un socket; con proxy SOCKS5 falso y con electrs real a través del proxy); **con Orbot en el móvil: pendiente (manual)** |
| P6 | Volver a «Nodo X-39» → sin escribir nada, vuelve a sincronizar | `configurar(NodoX39.config)` conserva «Mi nodo»; `olvidarDejaLoQueDigaElAlmacen`; **en el móvil: pendiente** |
| P7 | «Probar conexión» → versión, altura y red; errores llanos | §8.3 y `mensajeLlano` (JVM) |
| P8 | La dirección sobrevive al reinicio y no está en ningún log | Preferencias con `commit()` (como antes); el módulo solo escribe en logcat con la etiqueta `x39.nodo` (desde la orden 6) y siempre pasado por `sinDirecciones` o solo con el nombre de la clase de la excepción (orden 7, `BaseActivity.textoDeFallo`); `adb logcat -d \| grep 10.39.0.3` debe dar 0; **reinicio del móvil: pendiente** |
| P9 | Seguridad muestra el nodo y la frase fija | Código y cadenas en 5 idiomas; **a ojo en el móvil: pendiente** |
| P10 | Sin más direcciones ni puertos que el Electrum de X-39 | §8.6 y §8.8: cumplido; la telemetría de la Pi 5 ya no está en el módulo ni en el `.lab` (sabor `escritorio` de la app, que no se entrega) |

Las pruebas instrumentadas (`src/androidTest`) no se han ejecutado (exigen el aparato); se han
adaptado `FakeRepositorio` (nuevo método `huellaServidor`) y el `@Before` de
`EmparejarActivityInstrumentadoTest` (sin nodo ANTES de crear el repositorio, porque el modo
predeterminado arranca ya configurado). Pendiente de correr en el aparato por el propietario.

### 8.8 Cierre (03/11/2026): telemetría fuera, B5, auditoría

Entrega en `client-sdk-android/entregas/selector-nodo-20261103/cierre/` (01 a 05). Sin commit.

- **Telemetría de la Pi 5 fuera del módulo** (`TelemetriaPi5.kt` borrado; el panel y el botón
  «Minería y Nodo Pi 5» fuera de `BilleteraActivity`; `PI5_TOKEN` fuera de `build.gradle.kts`). El
  módulo `:wallet` ya no lleva ninguna dirección ni puerto de red salvo el Electrum (`NodoX39` y lo
  que escriba el usuario). En la app vive solo en un sabor `escritorio` que no se entrega.
- **B5 sobre `244eaa58…`**: `X39_MAINNET=1 android/build-native.sh --verificar` → misma suma en
  compilación limpia en directorio temporal (REPRODUCIBLE; `cierre/02-b5.txt`). Esta orden no
  cambia nada de `rust/src` que entre en la biblioteca (solo `tests.rs` y `mock_electrum.rs`,
  `#[cfg(test)]`); el candado se regenera al final solo por la lista de ficheros Kotlin.
- **Auditoría** (`cierre/03-auditoria.md`): (1) sin resolución DNS local con proxy: el nombre del
  destino va al proxy tal cual (ATYP 3) y el único `to_socket_addrs` del motor es el del propio
  proxy; por eso Kotlin exige ahora el proxy como **IP literal** (`ConfigNodo.proxyBienFormado`,
  `esIpv4Literal`, `esIpv6Literal`; un nombre habría que resolverlo en el aparato). (2)
  `adoptarSiSano` no escribe nada antes de `Sano`; un servidor que negocia `server.version` pero
  falla en `server.features` se rechaza sin tocar disco ni memoria. (3) Aviso de «certificado
  propio» reescrito en lenguaje llano (`AjustesActivity.AVISO_CERTIFICADO_PROPIO`): confía SOLO en
  esa huella y en ninguna autoridad; sin la casilla, en las autoridades públicas reconocidas (las
  raíces de Mozilla que lleva el motor, no el almacén del sistema: anotado como discrepancia con
  la orden, sin corregir a ciegas).
- Pruebas nuevas: Rust `nodo_por_proxy_el_nombre_de_mi_nodo_no_se_resuelve_en_este_proceso` y
  `puerta_genesis_servidor_que_negocia_pero_falla_en_features_se_rechaza_sin_tocar_nada`
  (simulado con `fijar_features_falla`); JVM `configuracionInvalida` ampliada (proxy solo IP) y
  `adoptarSiSano_servidorQueRespondeVersionPeroFallaEnFeatures_noEscribeNada` (cuenta los intentos
  de escritura del almacén). Suites: `cargo test` con y sin `mainnet` 97 pasadas, 0 fallos, 6
  ignoradas; clippy sin avisos nuevos; fmt limpio; JVM 57, 0 fallos; lint en verde
  (`cierre/05-pruebas.txt`).
- Guion manual para el propietario (P1, P2, P4, P5, P6, P8, P9; primero móvil secundario en
  signet, después el principal en mainnet): `cierre/04-guion-movil.md`, con los dos APK y sus
  sumas en `cierre/apk/`. Nada instalado.
- **Deuda del motor (anotada 03/11/2026, orden 3).** Un proxy SOCKS5 escrito como NOMBRE
  (`localhost:9050`, `orbot.local:9050`) debe rechazarse TAMBIÉN en Rust, en `analizar_proxy`
  (`rust/src/nodo.rs`, hoy acepta cualquier `host:puerto` y `conectar_tcp` lo resolvería con
  `to_socket_addrs`, es decir, una consulta DNS desde el aparato antes de que nada pase por el
  proxy). Hoy lo impide solo la capa Kotlin: `ConfigNodo.proxyBienFormado` / `esIpv4Literal` /
  `esIpv6Literal` (`NodeBridge.kt`), aplicada en `invalida()` antes de llamar al motor, cubierta
  por la prueba JVM `NodeBridgeTest.configuracionInvalida` (bloque «El proxy es una IP literal,
  nunca un nombre»: `localhost:9050`, `orbot.local:9050`, `proxy.ejemplo.onion:9050`,
  `[localhost]:9050`… rechazados; `10.0.0.1:9050`, `[fe80::1]:9050`, `[::ffff:127.0.0.1]:9050`…
  aceptados). Queda para la **próxima biblioteca** (cambia la `.so`, así que exige repetir B5 y
  regenerar bindings y candado): `analizar_proxy` debe exigir `IpAddr` literal con un error claro
  («el proxy SOCKS5 debe ser IP:puerto, no un nombre») y una prueba Rust que lo demuestre sin abrir
  socket. No se ha hecho en el cierre para no tocar la biblioteca certificada `244eaa58…`.

### 8.9 Orden 6 (03/11/2026): la puerta del modo X-39 no detectaba el túnel en el Redmi

Evidencia del propietario: APK `34499ed7…` instalado sobre el `.lab`, túnel WireGuard
levantado (x39tunel, 10.39.0.2/32, apretón reciente), y la app decía «nodo caído: activa el X39
Túnel» con reintento cada 8 s; `tcpdump -ni wg0 host 10.39.0.2` en el VPS no veía ni un paquete.

**Causa** (`NodeBridge.kt`, `comprobarUnaVez`, orden 1): `if (c.tunel == X39 && !hayTunelX39())
return Caido(MENSAJE_TUNEL)` ANTES de llamar al motor, y `hayTunelX39` era `NodoX39.tunelActivo`,
que la app pone como `{ TunelX39.arriba() }` = `fase == PROTEGIDO || PROTEGIDO_SIN_PSK`: el estado
EN MEMORIA del objeto del túnel, no la interfaz. Esa fase es falsa con el túnel levantado en al
menos tres casos: (a) `ESPERANDO_ALTA`/`ESPERANDO_PSK`/`LEVANTANDO` cada vez que se reabre el socket
de señalización (el propio `TunelX39.kt` lo documenta: «la fase pasa por ESPERANDO_ALTA cada vez
que se reabre el socket de señalización»), que con HyperOS cortando sockets en segundo plano pasa
en cada vuelta al primer plano; (b) `FALLO` tras un `Tunnel.State.DOWN` transitorio; (c) proceso
reiniciado con la VPN del sistema aún arriba: `asegurar()` solo pone PROTEGIDO si
`GoBackend.getState(tunel) == UP`, y un `GoBackend` nuevo no conoce el túnel que levantó el proceso
anterior. El repliegue `hayVpnActiva` (solo `activeNetwork` con `TRANSPORT_VPN`) tampoco servía con
rutas parciales (`10.39.0.0/24`): la red por defecto sigue siendo el wifi. Como la puerta cortaba
antes del socket, el VPS no veía nada. La versión del 31/10 sincronizaba porque su configuración
era `ModoTunel.NINGUNO` (sin puerta).

**Arreglo**: la puerta no decide. En modo X-39 se intenta SIEMPRE la conexión a
`NodoX39.servidor(red)` (y a ninguna otra dirección: `c.electrum` es la de `NodoX39.config`); si
falla, `diagnosticoTunel()` (`WalletRepository.diagnosticoTunel`: recorre `allNetworks`, cuenta las
de `TRANSPORT_VPN` y las que tienen ruta (`routes[].destination.contains(10.39.0.3)`) o dirección en
`10.39.0.0/24`; sin DNS ni sockets; la pista de la app solo va al detalle) elige el mensaje:
`EstadoTunel.SIN_TUNEL` → `NodoX39.MENSAJE_TUNEL` («Túnel apagado. Activa el X39 Túnel; la billetera
no se conecta por otra vía.», corto y sin cola técnica: el motivo solo va al registro; G5),
`CON_RUTA` → `MENSAJE_NODO_NO_RESPONDE` («túnel activo pero el nodo X-39 no responde: motivo»),
`DESCONOCIDO` → tercer texto. Nunca el mismo texto para dos casos. Registro en logcat, etiqueta
`x39.nodo`: resultado del diagnóstico y motivo del fallo, pasados por `sinDirecciones` (sin URL, host,
proxy, IP ni `.onion`; el nodo X-39 tampoco se escribe). Un diagnóstico que lance no tumba la
comprobación. `RedServidor`/`HuellaServidor` siguen siendo rechazos del nodo, no del túnel.

Pruebas JVM nuevas: `elNodoX39SiempreIntentaLaConexion_yElMensajeLoEligeElDiagnosticoDespues` (las
tres ramas del mensaje, distintas entre sí; la sonda se hace siempre y solo a la dirección X-39;
con nodo sano no se diagnostica; otra red → `RedIncorrecta`; diagnóstico que lanza → DESCONOCIDO;
el registro lleva diagnóstico y motivo sin direcciones) y `elRegistroNoLlevaDireccionesDelNodoDelUsuario`
(host, puerto, proxy, IPv4, `[IPv6]`, `.onion` y esquema fuera del registro en «Mi nodo»).
`NodoX39Test` comprueba los tres textos. Suite JVM 58/58; Rust sin cambios (97/97). Lint en verde.
Residuo asumido por la orden: con el túnel apagado el intento sale por la red normal hacia la IP
privada `10.39.0.3` (fracasa; si la LAN usara `10.39.0.0/24`, el SYN llegaría a esa máquina: las
puertas del motor —génesis, protocolo, altura— se aplican igual). Entrega:
`client-sdk-android/entregas/selector-nodo-20261103/cierre/09-puerta-tunel.md`.

### 8.10 Orden 7 (04/11/2026): sin túnel no se abre socket; ninguna excepción cruda en pantalla

Evidencia del propietario (APK `624694a7…` en el Redmi): con el túnel apagado, en modo «Nodo
X-39» la app seguía en «conectando…» hasta el timeout (abría el socket igualmente) y apareció el
toast «Job was cancelled». G5 (texto corto) ya estaba hecho; aquí G1, G2, G3, G4 y G6.

**G1 — puerta con la detección real, antes de conectar.** `NodeBridge.kt`: `puertaTunel(c)` se
consulta ANTES de llamar al motor en modo X-39, tanto en `comprobar()` (antes de publicar
`Conectando`: con el túnel apagado la pantalla no pasa por «conectando…») como en
`comprobarUnaVez` (`probar`, `adoptarSiSano`). Usa SOLO `DetectorTunel` (la implementación real es
`WalletRepository.diagnosticoTunel`: `ConnectivityManager.allNetworks`, redes con
`TRANSPORT_VPN` cuyas rutas cubran 10.39.0.3 o con dirección en 10.39.0.0/24), nunca
`NodoX39.tunelActivo` / `TunelX39.arriba()` (solo va al detalle del registro). Tres caminos:
`SIN_TUNEL` con certeza → `Caido(NodoX39.MENSAJE_TUNEL)` de inmediato, cero sockets, cero llamadas
al motor; `CON_RUTA` → se conecta a `NodoX39.servidor(red)` y solo ahí; `DESCONOCIDO` (sin
`ConnectivityManager`, excepción, detector que lanza o sin detector inyectado) → se conecta como
hoy: nunca se bloquea por una duda. En «Mi nodo» no hay puerta. Comprobación de ruta por bits
(`NodoX39.prefijoCubre` / `rutaCubreNodo`, equivalente a `IpPrefix.contains`/`RouteInfo.matches`,
nunca por texto): 10.39.0.0/24 cubre, 0.0.0.0/0 cubre, 192.168.1.0/24 no; `::/0` no cubre una
IPv4. `WalletRepository` ya no usa `InetAddress`.

**G2 — atar el socket a la red del túnel: DEUDA, no hecho.** El socket lo abre el motor Rust
(`rust/src/nodo.rs`, `conectar_tcp`: `TcpStream::connect_timeout` sobre la dirección resuelta);
`comprobar_nodo` (`ffi.rs`) recibe red, URL, huella, altura mínima y proxy, y ni él ni `Billetera`
aceptan un socket, un descriptor ni una fábrica de sockets. Desde Kotlin solo hay dos vías:
`Network.bindSocket(...)`, que exige el `Socket`/`FileDescriptor` que el motor no expone, o
`ConnectivityManager.bindProcessToNetwork`, prohibida por la orden (ata TODO el proceso, llamadas y
vídeo incluidos, y si la VPN cae deja la app muerta). Hacerlo exige cambiar el motor (aceptar un
`fd` ya conectado o un callback de conexión por UniFFI) y recompilar la biblioteca certificada
`244eaa58…`, cosa que la orden prohíbe. Riesgo residual que queda cubierto por G1: sin VPN con
ruta no se abre socket; con VPN con ruta, el sistema enruta 10.39.0.3 por ella (ruta
10.39.0.0/24 o por defecto). Pendiente para la próxima biblioteca.

**G3 — inyección por interfaz y pruebas JVM.** `fun interface DetectorTunel` y `fun interface
ConexionNodo` (parámetros `diagnosticoTunel` y `comprobador` de `NodeBridgeImpl`; la firma pública
del motor, `comprobarNodo`, no cambia; los dobles de las pruebas cuentan intentos y anotan el
destino sin abrir red). `NodeBridgeTest`: `g3a_sinVpn_ceroIntentosDeConexion_textoInmediato_sinConectando`
(0 intentos en configurar/comprobar/conexionSana/probar/adoptar, ningún `Conectando` observado en
el `StateFlow`, texto exacto, registro sin direcciones, «Mi nodo» sin puerta),
`g3b_vpnConRuta_unSoloIntento_soloAlNodoX39` (exactamente 1 intento y solo a
`tcp://10.39.0.3:50001`), `g3c_deteccionNoConcluyente_unIntento_nuncaSeBloqueaPorUnaDuda`
(1 intento; detector que lanza y sin detector: también se intenta; los tres textos distintos);
`NodoX39Test.laRutaCubreElNodo_porPrefijo_noPorTexto` (G3 d). Sustituye a la prueba de la orden
6 `elNodoX39SiempreIntentaLaConexion…` (su regla cambia por orden).

**G6 — ninguna excepción cruda en pantalla.** `NodeBridge.comprobarUnaVez`: `CancellationException`
se relanza antes del `catch (Exception)` (también en `diagnosticar()`); en modo X-39 cualquier otra
excepción da SOLO uno de los tres textos fijos (`NodoX39.mensajeFallo(estado)`, ya sin `motivo`;
antes CON_RUTA y DESCONOCIDO llevaban «: motivo» con el texto del motor); el motivo pasa por
`mensajeLlano` + `sinDirecciones` solo al registro `x39.nodo`. El rechazo por altura («ese nodo va
por detrás de lo ya visto…») es texto de la app y se conserva en los dos modos (el servidor
respondió: no es fallo del túnel). `BaseActivity.lanzar`: `CancellationException` se relanza
(adiós «Job was cancelled»); sin `alFallar`, en pantalla solo va el texto de un `ErrorBilletera`
(ya traducido) y cualquier otra excepción se muestra como «No se pudo completar la operación.
Inténtalo otra vez.» con solo su clase en el registro. Pruebas:
`g6_unaCancelacionEnMitadDeLaComprobacion_noProduceTextoDeError` (cancelación simulada y real:
se relanza, el estado no recibe `Caido`, el registro no contiene «cancel») y
`g6_unaExcepcionGenericaProduceElTextoFijo_nuncaSuMensaje` (cinco clases de excepción × dos
diagnósticos → texto fijo exacto, sin mensaje ni IP; con túnel apagado 0 intentos; altura
conservada). En «Mi nodo» no cambia nada: `mensajeLlano` (explicación fija + original entre
paréntesis, decisión de la orden 1) sigue igual, porque es el servidor del propio usuario en su
propia pantalla; anotado en la entrega por si el propietario quiere también texto fijo ahí.
Quedan fuera de esta orden, con `alFallar` propio, los avisos de `EmparejarActivity`
(`e.message ?: "paquete inválido"`: textos del propio módulo), `CrearRestaurarActivity`,
`EscanearQrActivity` (cámara) y la preparación del Keystore en `BaseActivity`.

**G4 — suites, APK, P10.** Resultados en la entrega
`client-sdk-android/entregas/selector-nodo-20261103/cierre/10-sin-socket-sin-tunel.md`:

| Qué | Antes (04/11/2026, 14:00) | Después (04/11/2026, 14:08–14:16) |
|---|---|---|
| `cargo test --features mainnet` / `cargo test` | 97 pasadas, 0 fallos, 6 ignoradas (cada una) | 97 pasadas, 0 fallos, 6 ignoradas (cada una); `rust/` y el candado idénticos byte a byte al diff de la entrega |
| `:wallet:testDebugUnitTest -Px39.mainnet=true` (con `--rerun`) | 58 pruebas, 0 fallos | **63 pruebas, 0 fallos** (EmparejamientoTest 17, MnemonicoTest 9, NodeBridgeTest 29, NodoX39Test 5, PerfilBilleteraTest 3) |
| `:sample-app:testGeneralDebugUnitTest -Px39.mainnet=true` (app, con `--rerun`) | 8 pruebas, 0 fallos | 8 pruebas, 0 fallos |
| `:wallet:lintDebug -Px39.mainnet=true` / `:wallet:compileDebugAndroidTestKotlin` | — | BUILD SUCCESSFUL los dos (las instrumentadas solo se compilan; no hay aparato) |
| `X39_MAINNET=1 :sample-app:assembleGeneralDebug -Px39.mainnet=true` | APK anterior `624694a7…` | APK **`eadc5173db92bf44bbdf1b5ce60dc0e48207852b7c370767b650a192d2e417bd`** (31.234.525 B), `.so` `244eaa58…`, cert `30922e27…8187` (`CN=Android Debug`); **no instalado** |
| P10 (`strings`, mismo guion) | IP 55, `:3000` 1, `:8000` 0, `8332` 0, `3333` 54, `ckpool` 3, `stratum` 0, `rpc` 299 | idéntico, `diff` vacío frente al APK anterior; `PI5_TOKEN`/`192.0.2.46`/`:8000`/`8332`: 0 en todo el APK |

Solo signet NO se ha recompilado (orden: solo mainnet); `cierre/apk/x39-llamadas-lab-signet-debug.apk`
sigue siendo el `327dbe66…` de la orden 6 + G5, SIN G1/G6.

### 8.11 Orden 8 (04/11/2026): un solo aviso, reacción inmediata al túnel, remates de pantalla

Evidencia del propietario (Redmi, mainnet, APK `eadc5173…`, 04/11): la puerta de la orden 7
funciona (túnel apagado → texto fijo inmediato y 0 sockets; túnel activo → nodo sano; reinicio →
configuración conservada y registro sin direcciones; registros `cierre/11`, `12` y `13b`). Tres
cosas de pantalla, ninguna de seguridad, vistas en esa prueba:

**H1 — un solo aviso por suceso.** Con el túnel apagado el mismo texto salía tres veces: línea de
estado del nodo («⚠ nodo caído: Túnel apagado…»), línea de último error («⚠ el nodo no está
disponible: Túnel apagado…») y un toast. Ahora `WalletRepository.conRed` lanza
`NodoNoDisponible(estadoNodo)` (subclase de `ErrorBilletera`, con el texto que ya muestra la
línea del nodo), `sincronizar` fija `Vista.ultimoError = errorParaLaVista(e, nodo.estado.value)`
(función pura: `null` si `loMuestraElNodo(e, estado)`, es decir, si el fallo es «nodo no
disponible» y su motivo es exactamente `estado.describir()`; cualquier otro fallo, su texto una
vez), y en `BilleteraActivity` los botones «Sincronizar» y «Reintentar la misma transacción» pasan
por `lanzarSinRepetirElNodo`, que no hace toast en ese mismo caso. La regla no depende del texto
del túnel: vale para cualquier estado del nodo que la línea ya enseñe (caído, otra red, huella,
conectando). Las demás pantallas (Enviar, Historial, Recibir) no tienen línea de estado del nodo y
siguen avisando una vez con el toast. Pruebas JVM nuevas (`WalletRepositoryTest`):
`h1_siElNodoYaMuestraElMotivo_laSincronizacionNoLoRepite` (nodo en `Caido(texto fijo)` por el
detector «sin túnel», `conexionSana()` nulo y 0 intentos, la excepción tal como la construye
`conRed` → `ultimoError` nulo; también con «reintento en N s» y con los otros estados) y
`h1_unFalloDeOtroTipo_seMuestraUnaVez` (motor, fondos, red con el nodo sano, y un «nodo no
disponible» cuyo motivo ya no es el de la línea → se muestra una vez). `sincronizar` entero no se
prueba en JVM (exige `Context` y billetera en disco): se prueba la decisión que él llama, con la
misma excepción.

**H2 — reacción inmediata al túnel.** `fun interface SenalRed { fun escuchar(alCambiar: () ->
Unit): () -> Unit }` (`NodeBridge.kt`), parámetro `senalRed` de `NodeBridgeImpl` (por defecto, sin
señal) y `reboteMs` (500 ms, `REBOTE_MS`). La real es `WalletRepository.senalRedVpn(ctx)`: un
`ConnectivityManager.NetworkCallback` registrado con `NetworkRequest` de `TRANSPORT_VPN` (y sin
`NET_CAPABILITY_NOT_VPN`, que la petición lleva por defecto), `onAvailable` /
`onLinkPropertiesChanged` / `onLost` → `alCambiar()`; la función devuelta hace
`unregisterNetworkCallback`. En `vigilar(true)` se registra y en `vigilar(false)` se retira (sin
fugas); si `escuchar` lanza (permiso, demasiados callbacks) se anota en `x39.nodo` y la vigilancia
sigue como antes con la espera creciente. La señal solo `trySend` a un canal conflado
(`despertador`); el bucle de la vigilancia espera «lo primero que llegue» entre la espera inyectada
y la señal (`esperarODespertar`, `select`), tras la señal espera el rebote y vacía el canal (una
ráfaga = una comprobación), pone `intento = 0` y comprueba por `comprobar()` → la puerta y
`diagnosticoTunel` deciden como siempre (sin túnel: 0 sockets). Decisión añadida: si el nodo está
sano, el modo es X-39 y el diagnóstico sigue `CON_RUTA`, la señal no comprueba nada (registro
«cambio de red sin efecto»): es el `onAvailable` inicial que el sistema emite al registrar el
callback con la VPN ya levantada, y sin esta regla cada `onResume` abriría un segundo socket medio
segundo después del primero. Pruebas JVM (`NodeBridgeTest`, dobles `SenalSimulada` y
`EsperaControlada`: la espera larga no termina sola, solo la señal la interrumpe):
`h2_unaSenalDeRedDespiertaLaVigilancia_pasaPorLaPuerta_ySeRetiraAlDesactivar` (a, b, c: con el
túnel apagado y la vigilancia en espera ≈2 s, la señal comprueba enseguida y la espera vuelve a
≈2 s; sigue sin túnel → 0 sockets; con ruta → 1 intento y solo a 10.39.0.3:50001, sano, espera
60 s; se apaga → texto fijo sin socket; `vigilar(false)` retira el callback y una señal tardía no
hace nada; al reactivar se vuelve a registrar), `h2_variasSenalesSeguidas_unaSolaComprobacionPorRebote`
(d: 5 señales dentro del rebote → 1 comprobación; otra ráfaga → otra) y
`h2_senalConNodoSanoYTunelConRuta_noAbreOtroSocket_ySinSenalLaVigilanciaSigue` (sin efecto con
sano + CON_RUTA; con SIN_TUNEL sí, sin socket; `escuchar` que lanza `SecurityException` → la
vigilancia arranca y espera 2, 4 s como siempre).

**H3 — Ajustes → Nodo.** `AjustesActivity.mostrar(m)`: si el modo mostrado no es el del nodo en
uso, la etiqueta ya no dice «Nodo: sin configurar» en naranja: dice, en gris, «Sin probar todavía.
En uso: X-39 por túnel.» (o «En uso: el tuyo (host).» en el caso simétrico, el mismo `when`);
«sin configurar» queda solo cuando de verdad no hay nodo (`config == null`). El nodo en uso no
cambia; la billetera lo sigue leyendo igual.

**G4.** Suites, APK y P10 en `cierre/14-orden8.md` de la entrega:

| Qué | Antes (04/11/2026, 15:49) | Después (15:53–16:05) |
|---|---|---|
| `cargo test --features mainnet` / `cargo test` | 97 pasadas, 0 fallos, 6 ignoradas (cada una) | no se repite: `rust/` y `android/build-native.lock` no se tocan (diff idéntico, fichero a fichero, al `x39-wallet.diff` de la orden 7) |
| `:wallet:testDebugUnitTest -Px39.mainnet=true --rerun` | 63 pruebas, 0 fallos | **68 pruebas, 0 fallos** (EmparejamientoTest 17, MnemonicoTest 9, NodeBridgeTest 32, NodoX39Test 5, PerfilBilleteraTest 3, WalletRepositoryTest 2) |
| `:sample-app:testGeneralDebugUnitTest -Px39.mainnet=true --rerun` | 8 pruebas, 0 fallos | 8 pruebas, 0 fallos |
| `:wallet:lintDebug` / `:wallet:compileDebugAndroidTestKotlin` | — | BUILD SUCCESSFUL los dos, 0 avisos |
| `X39_MAINNET=1 :sample-app:assembleGeneralDebug -Px39.mainnet=true` | APK anterior `eadc5173…` | APK **`2c9377e4a65d1a8c6182f6add5bcd9b3c4883e34ca32f8592a81c046a76076a3`** (31.234.525 B), `.so` `244eaa58…`, cert `30922e27…8187`; **no instalado** |
| P10 (`cierre/14-p10.sh`, guion reconstruido y guardado esta vez) | — | totales y referencias idénticos a los de la orden 7 (IP 55, `:3000` 1, `:8000` 0, `8332` 0, `3333` 54, `ckpool` 3, `stratum` 0, `rpc` 299); solo `tcp://10.39.0.3:50001` y `:60601`; `PI5_TOKEN`/`192.0.2.46`/`:8000`/`8332`: 0 |

Signet NO se recompila (solo mainnet); el APK de signet sigue siendo el `327dbe66…` de la orden 6 + G5.

Pendiente en el móvil (propietario): con el túnel apagado, el texto UNA sola vez y ningún toast
al pulsar «Sincronizar»; al encender el túnel, «nodo sano» en pocos segundos sin tocar nada; al
apagarlo con la app abierta, el aviso en pocos segundos; en Ajustes → Nodo con «Mi nodo» marcado,
«Sin probar todavía. En uso: X-39 por túnel.» en gris.

### 8.12 Orden 9a (04/11/2026): cinco idiomas, camino de release, instrumentadas

Entrega completa en `client-sdk-android/entregas/selector-nodo-20261103/cierre/18-orden9a.md` (inventario de
textos en `18-inventario-textos.txt`, P10 en `18-p10-strings-orden9a-*.txt`, instrumentadas en
`17-instrumentadas-20261104/`).

**I1 — cinco idiomas.** Inventario previo: 417 literales en `android/wallet/src/main/java/x39/wallet/android`
(209 de pantalla, 136 mensajes internos que llegan a pantalla, 19 de registro, 53 que no son texto). Todos los
de pantalla pasan a `res/values/strings.xml` (castellano, base) + `values-en`, `values-zh-rCN`, `values-ja`,
`values-ar`: 344 claves idénticas por carpeta, prefijo `x39w_`. Los mensajes de la lógica (los tres del túnel,
el de altura, los del estado del nodo, los de `invalida()`, `mensajeLlano`, `WalletRepository.mensaje`,
`Emparejamiento.analizar`, `JsonEstricto`, `AlmacenSemilla`…) NO cambian: siguen en castellano como claves
estables (las comprueban estas suites y los lee el propietario en `x39.nodo`); `Textos.kt` (tabla de 129
patrones → recurso `x39w_m_*`, con anidamiento) los traduce SOLO al pintar (`Context.textoPantalla`). El
módulo sigue el idioma del selector de la app sin acoplarse: la app usa `AppCompatDelegate.setApplicationLocales`
(de proceso) y las nueve pantallas del módulo son `AppCompatActivity`. Árabe: `supportsRtl` de la app, flecha
de volver invertida, márgenes y paddings relativos. Ninguna prueba JVM existente cambia (ninguna comprobaba
un texto que ahora sea recurso). Pruebas nuevas, `TextosTest` (3): paridad de claves y huecos en las cinco
carpetas (y = `R.string`); ida y vuelta EXACTA en castellano de 140 muestras que cubren las 129 entradas; cada
entrada con recurso en los cinco idiomas, sin huecos sin rellenar. Lint 0 avisos. Comprobado por el
propietario en el Redmi en los cinco idiomas: PASA.

**I2 — release del sabor con billetera.** `~/x39_mensajeria/scripts/build_llamadas_lab_release.sh` (hermano de
`build_llamadas_release.sh`): `assembleGeneralRelease` con `X39_MAINNET=1 -Px39.mainnet=true`, firma con
`~/x39-release.jks` alias `x39` (contraseña por `read -s`, comprobada con `keytool`, nunca en ficheros ni en la
línea de comandos), comprobaciones del de `comunicaciones` + billetera en el manifiesto + `.so` `244eaa58…`
dentro del APK + dex mainnet + P10 (`cierre/14-p10.sh`); 20 permisos (18 + `USE_BIOMETRIC` + `USE_FINGERPRINT`);
destino `cierre/apk/x39-llamadas-lab-mainnet-release.apk`; sin OTS, sin publicar, sin instalar (el Redmi lleva la
clave de depuración). Validado sin firmar (`X39_LAB_SIN_FIRMAR=1`): `8ce74581…`, 23.094.250 B, 12 ✓. Firmado por el
propietario (17:42, sin ✗): `cierre/apk/x39-llamadas-lab-mainnet-release.apk`
**`fc9abe0187ab74110728f84fdc29cc54455ac4715337fe0e153d47ec3e7dd5eb`** (23.186.328 B), certificado
`72f0745f993605b1b3cd30966162b6d98d84fcacbd23e2e07e24c3d15f7d8935` (`CN=x39`), versionCode 43 / 1.0.6, P10
limpio (las 3 «192.168.» son los vectores SIP de JAIN-SIP, las mismas que en el de depuración). NO instalado.

**I3 — instrumentadas en el Redmi.** Receta en la entrega (WSL «mirrored» → el `adb` de Linux usa el servidor
de Windows en `127.0.0.1:5037`; `ANDROID_SERIAL` del Redmi; `:wallet:connectedDebugAndroidTest`). Tres pasadas:
1.ª 17 pasan / 16 fallan / 1 omitida (13 pruebas fuerzan signet y la edición es mainnet; 2 de fechas con la
misma causa; 1 con el directorio fijo del perfil de pruebas); 2.ª (13 `assumeTrue(Redes.permitida(Red.SIGNET))`)
17 / 3 / 14; 3.ª (2 `assumeTrue` más y `PerfilBilletera.actual.directorio` en `recuperarLaClave` y su
`limpiar()`) 17 / 1 / 16 (`recuperarLaClave` borraba `ALIAS_POR_DEFECTO`, alias del perfil de pruebas, y en
mainnet la semilla vive bajo `PerfilBilletera.MAINNET.aliasSemilla`); 4.ª (`PerfilBilletera.actual.aliasSemilla`,
con el visto bueno del propietario y tras confirmar que el paquete de pruebas `x39.wallet.test` tiene UID y
Keystore propios, distintos de `org.x39matrix.llamadas.lab` uid 10537) **18 pasan, 0 fallan, 16 omitidas**,
BUILD SUCCESSFUL. Solo código de pruebas; en la edición de prueba siguen corriendo enteras. Las
instrumentadas instalan `x39.wallet.test`, nunca `.lab`.

| Qué | Antes (04/11/2026, 16:38) | Después |
|---|---|---|
| `cargo test --features mainnet` / `cargo test` | 97 pasadas, 0 fallos, 6 ignoradas | no se repite: `rust/` y el candado sin cambios (diff idéntico fichero a fichero) |
| `:wallet:testDebugUnitTest -Px39.mainnet=true` (`--rerun`) | 68 pruebas, 0 fallos | **71 pruebas, 0 fallos** (+ `TextosTest` 3) |
| `:sample-app:testGeneralDebugUnitTest -Px39.mainnet=true` (app) | 8, 0 fallos | 8, 0 fallos |
| `:wallet:lintDebug` / `compileDebugAndroidTestKotlin` | — | BUILD SUCCESSFUL, 0 avisos / BUILD SUCCESSFUL |
| `X39_MAINNET=1 :sample-app:assembleGeneralDebug -Px39.mainnet=true` | APK anterior `2c9377e4…` | APK **`822968658740e80f05de3fa001165b172368334eb23ea3af27af2201250d8af5`** (31.234.525 B), `.so` `244eaa58…`, cert `30922e27…8187`; instalado y recorrido por el propietario |
| P10 | IP 55, resto como la orden 7 | IP **68**: las 13 nuevas son `127.0.0.1:9050` (proxy de Orbot) en los textos de los cinco idiomas de `resources.arsc`, que antes estaban en el dex; `:3000`/`8332`/`3333`/`rpc` idénticos; solo `tcp://10.39.0.3:50001` y `:60601`; `PI5_TOKEN`/`192.0.2.46`/`8332`: 0 |

## 3. Pendiente de probar

- Envío real en **signet** desde la app en el aparato: **hecho** (§5.1, 10.000 sat, bloque
  328731); la billetera de prueba de línea de órdenes `~/x39-wallet-prueba-signet` recibió
  esos 10.000 sat y ya no está a cero. Los cinco criterios bloqueantes de §7.1 (B1, B2, B3, B4 y B5) quedan cerrados sobre la edición Mainnet (`libx39wallet.so` `581a852c…`, APKs `a1e53685…` y `8d219822…`).
- Las pantallas Android en el aparato: recorridas en parte (§5, §5.1, §5.2, incluido NodeBridge
  por emparejamiento); el ciclo completo desde la app (fase G) sigue pendiente.
