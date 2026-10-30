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
| `cargo test` | 24 unitarias: derivación BIP84, firma, estado sin claves, PSBT en dos pasos con re-verificación, caducidad, red del servidor (genesis), difusión aceptada/rechazada/incierta/«ya conocida», reemplazo RBF, persistencia; con un servidor Electrum **simulado** en regtest | 24 pasadas, 0 fallos, 4 ignoradas |
| `cargo test --features mainnet` | lo mismo con mainnet permitida | 24 pasadas |
| `cargo test -- --ignored` | 4 contra un servidor **público de signet**: billetera nueva a cero, restaurar el vector BIP84 y encontrar direcciones, altura y comisión, rechazo del servidor si se pide otra red | 4 pasadas |
| `cargo clippy --all-targets` (con y sin `mainnet`) | | 0 avisos |

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
restauración limpia (directorio nuevo con solo `red.txt` y `SEMILLA-DE-PRUEBA.txt`) está
pendiente y se añadirá a este documento.

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
  con código 2 (no -27). El motor lo clasificó como rechazo (*fallida*) y liberó las monedas;
  la sincronización siguiente la vio anclada y la pasó a «confirmada pese al rechazo», con el
  saldo correcto. Se añadió ese texto a la lista de respuestas «ya conocida» y una prueba
  unitaria con él; la cuarta transacción repite la secuencia con el binario corregido y queda
  *enviada — el servidor ya la conocía*.
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

## 3. Pendiente de probar

- Restauración limpia solo desde la semilla (directorio nuevo sin `estado.dat` ni
  `estado.dat.envios.json`) contra el banco regtest.
- Envío real en **signet** con la billetera `~/x39-wallet-prueba-signet` (espera fondos del
  faucet): sincronizar, auto-envío a la dirección índice 1, confirmación.
- Todo lo de Android (UniFFI, módulo `:wallet`, ciclo desde la app) y NodeBridge.
