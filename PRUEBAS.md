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
mainnet. Los **opcionales** no bloquean y son hoja de ruta. Estado a 31/10/2026, contrastado
con lo que registran las secciones anteriores y las pruebas del repo; «hecho» solo cuando hay
una prueba o un recorrido anotado que lo demuestre.

### 7.1 Bloqueantes

| # | Criterio | Qué demuestra | Cómo se demuestra | Estado hoy |
|---|---|---|---|---|
| B1 | **Contrato de difusión incierta de punta a punta en signet contra un nodo PROPIO** | Que una difusión nunca deja la billetera en un estado que pierda o libere monedas por error: `Difundiendo` se persiste **antes** de transmitir; un corte entre transmitir y anotar recupera como `Incierta`; ningún rechazo del servidor libera monedas (todo a `Incierta`); «already known» / «already in mempool» / «already in block chain» / «already in utxo set» / `txn-already` → `Enviada`; cancelar, **cuando ya hubo** un intento de difusión, = reemplazo RBF sobre las mismas entradas; una transacción solo firmada y nunca difundida se cancela descartándola, sin RBF (es la única liberación automática, §1) | Motor, `rust/src/tests.rs` contra el simulado: `regtest_corte_entre_transmitir_y_anotar_deja_incierta`, `regtest_difusion_incierta_nunca_reconstruye_y_reintenta_la_misma`, `regtest_ya_conocida_es_exito_y_no_libera`, `regtest_cancelar_tras_difundir_es_un_reemplazo_rbf`, `regtest_si_confirma_la_original_el_reemplazo_queda_fallido`, `regtest_cancelada_que_aparece_en_un_bloque_pasa_a_confirmada`, `regtest_firmada_sin_difundir_se_puede_cancelar` (§1); banco regtest real con electrs (§2: difusión, «ya conocida» difundiendo por fuera, RBF); en signet desde el aparato, §5.1. Falta el recorrido **punta a punta en signet contra un nodo propio**: envío, corte provocado durante la difusión, rechazo provocado, reintento de la misma, cancelación por RBF, todo anotado como §5.1 | **PENDIENTE** (parcial): hecho en simulado y en el banco regtest; en signet solo el camino feliz (§5.1) y solo contra el servidor público; no existe todavía un nodo propio en signet (el propio es mainnet, §5.2) |
| B2 | **Recuperación desde la semilla** | Que una instalación limpia con solo las 12 palabras recupera saldo e historial, y que el registro de envíos no miente: lo que el servidor muestra vuelve como movimientos; lo firmado o incierto anterior **no** se recupera desde la semilla (limitación documentada en el crate y en el README) y la app lo dice | Motor, §2.1: restauración limpia en regtest con el mismo saldo y los mismos movimientos, registro de envíos vacío; Android, §4: `RecuperacionYEscaneoInstrumentadoTest` (restaurar sin servidor deja escaneo pendiente, se niega a preparar envíos y se completa al sincronizar; recuperar la clave no toca estado ni envíos). Falta: **instalación limpia en el aparato** (desinstalar, instalar, restaurar con las 12 palabras en signet) con saldo e historial iguales a los de §5.1, y el texto que explica qué no vuelve | **PENDIENTE** (parcial): hecho en línea de órdenes (regtest) y en instrumentadas; no hecho en el aparato con instalación limpia |
| B3 | **Firma: autenticación por operación; la semilla no sale del búfer y se borra tras usarla** | Que cada firma exige al usuario (biometría fuerte o credencial) y que el mnemónico cruza a Rust como búfer directo que Kotlin pone a cero, nunca como `String` | Android, §4: `AlmacenSemillaInstrumentadoTest.laClaveExigeAutenticacionPorOperacion` (parámetros reales de la clave; sin autenticar no cifra); `MnemonicoTest` (JVM): borrado de la entrada en `deChars` y de la semilla nueva; `BilleterasInstrumentadoTest.nuevaSemilla_docePalabras_yCreaBilleteraConByteBufferDirecto`; prompt biométrico real en los recorridos de §5 y §5.1 (no se automatiza). Límite **documentado y no resuelto** (README, «Límites conocidos», apartado «Semilla nueva»): UniFFI deja en memoria nativa copias de la semilla que **no se ponen a cero** (el `Vec` entregado al serializador y el `RustBuffer` que Kotlin libera sin borrar) | **PENDIENTE (decisión)**. Hecho: autenticación por operación y borrado de los búferes controlados. **No cumplido**: «la semilla se borra tras usarla», porque quedan copias de la semilla sin borrar en memoria nativa; no se marca como borrado total conseguido. Decisión antes de mainnet, dos vías: (a) implementar el `Lower` a mano del README, y que cubra **también** el búfer final de UniFFI (si lo deja intacto, no basta); o (b) aceptarlo como **excepción expresa y registrada** en este documento, dicha como lo que es, copias de la semilla que no se borran, nunca como criterio cumplido. Recomendación anotada: corregirlo, por ser la semilla |
| B4 | **Puerta del nodo no evitable** (huella SPKI en el apretón TLS → génesis → protocolo 1.4 → altura que no baja; es el orden de `Conexion::abrir` y `comprobar_nodo` en `rust/src/nodo.rs`) | Que ninguna operación de red (escaneo, sincronización, difusión, altura, comisión) habla con un servidor de otra red, con otro certificado, que haya retrocedido o con otro protocolo; y que no hay camino que se la salte | Positivas: motor (`nodo_tls_con_huella_correcta_es_sano_y_la_billetera_sincroniza_por_el`, `nodo_la_huella_viaja_en_altura_y_comision`), §5 y §5.2 en el aparato. Negativas que **ya existen**: huella incorrecta (`nodo_tls_con_huella_incorrecta_se_rechaza_sin_tocar_nada`, `signet_publico_con_huella_equivocada_se_rechaza_tambien_en_comision`, `NodeBridgeInstrumentadoTest`), otra red (`nodo_corte_tras_validar_genesis_y_otra_red_al_volver`, la ignorada de signet, las de §1). Negativas que **faltan**: servidor que anuncia protocolo distinto de 1.4; servidor cuya altura retrocede respecto a la vista; y una batería ordenada, puerta por puerta y operación por operación, que afirme que el rechazo **conserva el estado íntegro y coherente**, distinguiendo dos casos: si la validación falla **antes** de transmitir, no se tocan ni los fondos ni el fichero de envíos (estado y registro idénticos byte a byte a los de antes); si la difusión **ya empezó**, el estado pasa a `Incierta` y las monedas siguen **reservadas**, nunca liberadas. Exigencia añadida: comprobar el nodo y después abrir **otra** conexión no demuestra nada; la batería debe demostrar que **cada** operación de red (escaneo, sincronización, difusión, altura, comisión) viaja por una conexión que pasó **todas** las puertas, no por una conexión aparte sin comprobar (hoy `Conexion::abrir` corre génesis y huella en cada conexión, pero altura y protocolo solo los corre `comprobar_nodo`; la batería tiene que dejar esto demostrado o corregido) | **PENDIENTE**: la batería negativa completa está por escribir (hoy cubre huella y red; no protocolo ni altura que baja, ni la conservación del estado caso por caso, ni la prueba de que cada operación viaja por una conexión comprobada). Las puertas existen y corren (§5.2 lo vio en real) |
| B5 | **Build reproducible verificado: dos compilaciones, mismo hash; alineación de 16 KB** | Que un tercero puede recompilar la biblioteca nativa y obtener el mismo binario que va en el APK, y que los segmentos LOAD están a 16 KB | `android/build-native.sh --verificar` (compilación limpia en directorio temporal y comparación con `build-native.lock`); la comprobación de LOAD la hace el script en cada compilación | **HECHO para la biblioteca VIGENTE** (solo-prueba, `abe7822e…213ed6`, 9.428.864 bytes): `--verificar` el 31/10/2026, compilación limpia con el mismo hash que el lock y que la `.so` empaquetada; NDK 30.0.16248370, rustc 1.98.1, cargo-ndk 4.1.2, uniffi 0.32.2, `--locked`, `remap-path-prefix`, `max-page-size=16384`; LOAD a `0x4000`; sin avisos; registro guardado fuera del repo. §4 lo había verificado antes para la biblioteca de la fase D (`6936ed89…`, 9.325.968 bytes). **PENDIENTE para el candidato mainnet**: misma receta con la feature `mainnet` (marcador `x39wallet-redes=mainnet`), que es otro binario y se verifica cuando se compile, y otra vez tras E-3 (`ring`, boringtun, smoltcp; `docs/e3-tunel-propio.md` §8). Por la regla de cierre, la evidencia que cuenta para mainnet es la del candidato |

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

## 3. Pendiente de probar

- Envío real en **signet** desde la app en el aparato: **hecho** (§5.1, 10.000 sat, bloque
  328731); la billetera de prueba de línea de órdenes `~/x39-wallet-prueba-signet` recibió
  esos 10.000 sat y ya no está a cero. Lo que sigue pendiente en red real lo lista §7: B1
  contra un nodo propio en signet, B2 con instalación limpia en el aparato, B4 batería
  negativa, B5 `--verificar` sobre el candidato mainnet y tras E-3 (la vigente, verificada el 31/10/2026), y la decisión de B3.
- Las pantallas Android en el aparato: recorridas en parte (§5, §5.1, §5.2, incluido NodeBridge
  por emparejamiento); el ciclo completo desde la app (fase G) sigue pendiente.
