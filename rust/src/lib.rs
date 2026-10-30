//! x39-wallet — billetera Bitcoin de autocustodia para x39.
//!
//! Deriva claves (BIP39 semilla, BIP84 segwit nativo `bc1…`/`tb1…`), genera direcciones
//! para RECIBIR, y construye y FIRMA transacciones para ENVIAR. Por debajo va BDK
//! (`bdk_wallet` + `bdk_electrum`): escaneo con gap limit, selección de monedas, cambio
//! por el descriptor interno y persistencia del estado en un fichero.
//!
//! La red se habla con un servidor Electrum que elige el usuario: su propio nodo o,
//! para pruebas en signet, uno público. La semilla y las claves privadas
//! nunca salen del aparato ni se escriben en el fichero de estado: la billetera guarda
//! solo los descriptores PÚBLICOS. La semilla se pide al crear/restaurar y al firmar,
//! y lo que se deriva de ella se borra de memoria al terminar (zeroize, lo que se puede:
//! ver `claves_privadas`).
//!
//! Esta es la parte que TIENE que ser correcta: derivación y firma. Se comprueba
//! con vectores BIP84 y verificando la firma del testigo.

use bdk_electrum::electrum_client::{self, ElectrumApi};
use bdk_electrum::BdkElectrumClient;
use bdk_wallet::chain::ChainPosition;
use bdk_wallet::descriptor::{ExtendedDescriptor, IntoWalletDescriptor};
use bdk_wallet::file_store::Store;
use bdk_wallet::miniscript::descriptor::{KeyMap, KeyMapWrapper};
use bdk_wallet::{ChangeSet, KeychainKind, PersistedWallet, SignOptions, Wallet};
use bitcoin::bip32::{DerivationPath, Xpriv};
use bitcoin::secp256k1::{Message, Secp256k1};
use bitcoin::sighash::{EcdsaSighashType, SighashCache};
use bitcoin::{
    absolute::LockTime, transaction::Version, Address, Amount, CompressedPublicKey, FeeRate,
    Network, OutPoint, Sequence, Transaction, TxIn, TxOut, Txid, Witness,
};
use std::path::Path;
use std::str::FromStr;
use std::sync::Arc;
use zeroize::Zeroizing;

pub const POLVO_SAT: u64 = 546;

/// Direcciones vacías seguidas que se miran antes de dar un escaneo por terminado.
pub const GAP_LIMIT: usize = 20;
const LOTE: usize = 10;
/// Cabecera del fichero de estado (cambiarla hace ilegibles los ficheros viejos).
const MAGIA: &[u8] = b"x39-wallet/bdk/v1";

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    Semilla,
    Derivacion,
    Direccion,
    Fondos,
    Utxo,
    Firma,
    /// El fichero de estado no se pudo leer/escribir, o es de otra billetera o red.
    Fichero(String),
    /// Fallo hablando con el servidor Electrum.
    Red(String),
    /// No se pudo construir el envío (importe bajo el polvo, comisión inválida…).
    Envio(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Semilla => f.write_str("semilla (mnemónico) inválida"),
            Error::Derivacion => f.write_str("fallo derivando claves"),
            Error::Direccion => f.write_str("dirección de destino inválida o de otra red"),
            Error::Fondos => f.write_str("fondos insuficientes para el envío y la comisión"),
            Error::Utxo => f.write_str("UTXO mal formado"),
            Error::Firma => f.write_str("fallo al firmar"),
            Error::Fichero(e) => write!(f, "estado de la billetera: {e}"),
            Error::Red(e) => write!(f, "servidor Electrum: {e}"),
            Error::Envio(e) => write!(f, "envío: {e}"),
        }
    }
}
impl std::error::Error for Error {}

/// Red en la que trabaja la billetera. Las pruebas van SIEMPRE en signet.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Red {
    Signet,
    Testnet,
    Mainnet,
}

impl Red {
    pub fn network(self) -> Network {
        match self {
            Red::Signet => Network::Signet,
            Red::Testnet => Network::Testnet,
            Red::Mainnet => Network::Bitcoin,
        }
    }
}

impl FromStr for Red {
    type Err = Error;
    fn from_str(s: &str) -> Result<Self, Error> {
        match s {
            "signet" => Ok(Red::Signet),
            "testnet" => Ok(Red::Testnet),
            "mainnet" | "bitcoin" => Ok(Red::Mainnet),
            _ => Err(Error::Envio(format!("red desconocida: {s}"))),
        }
    }
}

/// Un fondo que la billetera puede gastar. `indice` es la posición de derivación
/// de la dirección que lo recibió (rama externa 0). `valor_sat` en satoshis.
pub struct Utxo {
    pub txid: String,
    pub vout: u32,
    pub valor_sat: u64,
    pub indice: u32,
}

fn coin(red: Network) -> u32 {
    if red == Network::Bitcoin {
        0
    } else {
        1
    }
}

fn raiz(mnemonic: &str, passphrase: &str, red: Network) -> Result<Xpriv, Error> {
    // `Mnemonic` se borra al soltarse (feature zeroize de bip39); la semilla de 64 bytes también.
    let mn = bip39::Mnemonic::parse(mnemonic).map_err(|_| Error::Semilla)?;
    let seed = Zeroizing::new(mn.to_seed(passphrase));
    Xpriv::new_master(red, &seed[..]).map_err(|_| Error::Derivacion)
}

fn ruta(red: Network, cambio: u32, i: u32) -> DerivationPath {
    DerivationPath::from_str(&format!("m/84'/{}'/0'/{}/{}", coin(red), cambio, i))
        .expect("ruta BIP84 válida")
}

fn clave_en<'a>(
    secp: &Secp256k1<bitcoin::secp256k1::All>,
    raiz: &Xpriv,
    red: Network,
    cambio: u32,
    i: u32,
) -> Result<(bitcoin::secp256k1::SecretKey, CompressedPublicKey), Error> {
    let x = raiz
        .derive_priv(secp, &ruta(red, cambio, i))
        .map_err(|_| Error::Derivacion)?;
    let sk = x.private_key;
    let cpk = CompressedPublicKey(sk.public_key(secp));
    Ok((sk, cpk))
}

/// Descriptores BIP84 (externo e interno) con sus claves privadas. Solo para firmar:
/// el `KeyMap` se suelta en cuanto se ha firmado. Los textos con la clave privada
/// extendida se borran; las copias internas de miniscript/secp256k1 no se pueden
/// borrar desde aquí (limitación conocida, ver notas de la billetera).
fn claves_privadas(
    mnemonic: &str,
    passphrase: &str,
    red: Network,
) -> Result<(ExtendedDescriptor, ExtendedDescriptor, KeyMap), Error> {
    let secp = Secp256k1::new();
    let raiz = raiz(mnemonic, passphrase, red)?;
    let c = coin(red);
    let ext = Zeroizing::new(format!("wpkh({raiz}/84'/{c}'/0'/0/*)"));
    let int = Zeroizing::new(format!("wpkh({raiz}/84'/{c}'/0'/1/*)"));
    let (de, mut ke) = ext
        .as_str()
        .into_wallet_descriptor(&secp, red.into())
        .map_err(|_| Error::Derivacion)?;
    let (di, ki) = int
        .as_str()
        .into_wallet_descriptor(&secp, red.into())
        .map_err(|_| Error::Derivacion)?;
    ke.extend(ki);
    Ok((de, di, ke))
}

/// Descriptores PÚBLICOS (con origen de la clave), los únicos que se guardan.
fn descriptores_publicos(
    mnemonic: &str,
    passphrase: &str,
    red: Network,
) -> Result<(ExtendedDescriptor, ExtendedDescriptor), Error> {
    let (de, di, _claves) = claves_privadas(mnemonic, passphrase, red)?;
    Ok((de, di))
}

/// Mnemónico nuevo de 12 palabras (128 bits de entropía). La semilla del usuario.
pub fn nueva_semilla() -> Result<String, Error> {
    let mn = bip39::Mnemonic::generate(12).map_err(|_| Error::Semilla)?;
    Ok(mn.to_string())
}

/// Dirección para RECIBIR (segwit nativo), rama externa (0), índice `i`.
/// Sale del descriptor de BDK: el vector BIP84 de los tests comprueba que coincide.
pub fn direccion_recibir(
    mnemonic: &str,
    passphrase: &str,
    red: Network,
    i: u32,
) -> Result<String, Error> {
    let (ext, _int) = descriptores_publicos(mnemonic, passphrase, red)?;
    let d = ext.at_derivation_index(i).map_err(|_| Error::Derivacion)?;
    Ok(d.address(red).map_err(|_| Error::Derivacion)?.to_string())
}

/// Firma sin red de la versión anterior: construye y FIRMA un envío con los UTXO que
/// se le den. OJO: el cambio va siempre a la rama interna índice 0 (reutiliza dirección).
/// La billetera usa `Billetera::preparar_envio`, que no tiene ese problema; esta se
/// conserva porque su test verifica la firma BIP143 a mano.
pub fn firmar_envio(
    mnemonic: &str,
    passphrase: &str,
    red: Network,
    utxos: &[Utxo],
    destino: &str,
    cantidad_sat: u64,
    comision_sat: u64,
) -> Result<String, Error> {
    let secp = Secp256k1::new();
    let raiz = raiz(mnemonic, passphrase, red)?;

    let total: u64 = utxos.iter().map(|u| u.valor_sat).sum();
    if total < cantidad_sat + comision_sat {
        return Err(Error::Fondos);
    }
    let dest = Address::from_str(destino)
        .map_err(|_| Error::Direccion)?
        .require_network(red)
        .map_err(|_| Error::Direccion)?;

    // Entradas.
    let mut input = Vec::with_capacity(utxos.len());
    for u in utxos {
        let txid = Txid::from_str(&u.txid).map_err(|_| Error::Utxo)?;
        input.push(TxIn {
            previous_output: OutPoint { txid, vout: u.vout },
            script_sig: bitcoin::ScriptBuf::new(),
            sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
            witness: Witness::new(),
        });
    }

    // Salidas: destino + cambio (si supera el polvo).
    let mut output = vec![TxOut {
        value: Amount::from_sat(cantidad_sat),
        script_pubkey: dest.script_pubkey(),
    }];
    let cambio = total - cantidad_sat - comision_sat;
    if cambio > POLVO_SAT {
        let (_, ccpk) = clave_en(&secp, &raiz, red, 1, 0)?;
        output.push(TxOut {
            value: Amount::from_sat(cambio),
            script_pubkey: Address::p2wpkh(&ccpk, red).script_pubkey(),
        });
    }

    let mut tx = Transaction {
        version: Version::TWO,
        lock_time: LockTime::ZERO,
        input,
        output,
    };

    // Firmar cada entrada (P2WPKH, BIP143).
    let mut witnesses = Vec::with_capacity(utxos.len());
    {
        let mut cache = SighashCache::new(&tx);
        for (idx, u) in utxos.iter().enumerate() {
            let (sk, cpk) = clave_en(&secp, &raiz, red, 0, u.indice)?;
            let spk = Address::p2wpkh(&cpk, red).script_pubkey();
            let sighash = cache
                .p2wpkh_signature_hash(idx, &spk, Amount::from_sat(u.valor_sat), EcdsaSighashType::All)
                .map_err(|_| Error::Firma)?;
            let msg = Message::from(sighash);
            let sig = secp.sign_ecdsa(&msg, &sk);
            let esig = bitcoin::ecdsa::Signature {
                signature: sig,
                sighash_type: EcdsaSighashType::All,
            };
            let mut w = Witness::new();
            w.push(esig.to_vec());
            w.push(cpk.to_bytes());
            witnesses.push(w);
        }
    }
    for (i, w) in witnesses.into_iter().enumerate() {
        tx.input[i].witness = w;
    }

    Ok(bitcoin::consensus::encode::serialize_hex(&tx))
}

// ===== Servidor Electrum ======================================================

fn conectar(servidor: &str) -> Result<BdkElectrumClient<electrum_client::Client>, Error> {
    let cfg = electrum_client::ConfigBuilder::new()
        .timeout(Some(std::time::Duration::from_secs(30)))
        .retry(2)
        .build();
    let c = electrum_client::Client::from_config(servidor, cfg).map_err(|e| Error::Red(e.to_string()))?;
    Ok(BdkElectrumClient::new(c))
}

/// Altura del último bloque que conoce el servidor.
pub fn altura_red(servidor: &str) -> Result<u32, Error> {
    let c = conectar(servidor)?;
    let h = c.inner.block_headers_subscribe().map_err(|e| Error::Red(e.to_string()))?;
    Ok(h.height as u32)
}

/// Comisión recomendada en sat/vB para confirmar en `bloques` bloques, según el nodo.
/// Si el nodo no tiene estimación (pasa en signet con poco tráfico) usa su comisión
/// mínima de retransmisión. Nunca menos de 1 sat/vB.
pub fn estimar_comision(servidor: &str, bloques: usize) -> Result<f64, Error> {
    let c = conectar(servidor)?;
    let btc_kvb = c.inner.estimate_fee(bloques, None).map_err(|e| Error::Red(e.to_string()))?;
    let btc_kvb = if btc_kvb > 0.0 {
        btc_kvb
    } else {
        c.inner.relay_fee().map_err(|e| Error::Red(e.to_string()))?
    };
    // BTC/kvB → sat/vB: ×1e8 sat/BTC ÷ 1000 vB/kvB.
    Ok((btc_kvb * 100_000.0).max(1.0))
}

// ===== Billetera con estado ===================================================

/// Saldo en satoshis.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Saldo {
    /// Confirmado y gastable.
    pub confirmado: u64,
    /// Sin confirmar (cambio propio y entradas de fuera).
    pub pendiente: u64,
    /// Coinbase sin madurar.
    pub inmaduro: u64,
    pub total: u64,
}

/// Un movimiento de la billetera.
#[derive(Debug, Clone)]
pub struct Movimiento {
    pub txid: String,
    pub recibido_sat: u64,
    pub enviado_sat: u64,
    pub comision_sat: Option<u64>,
    /// Altura del bloque que la confirma; `None` si está en la mempool.
    pub altura: Option<u32>,
}

/// Un envío firmado, listo para difundir.
#[derive(Debug, Clone)]
pub struct Envio {
    pub txid: String,
    pub hex: String,
    pub comision_sat: u64,
    pub vbytes: u64,
    pub sat_vb: f64,
    /// Dirección de cambio usada (vacía si no hubo cambio).
    pub cambio: String,
    tx: Arc<Transaction>,
}

pub struct Billetera {
    cartera: PersistedWallet<Store<ChangeSet>>,
    almacen: Store<ChangeSet>,
    red: Network,
}

impl Billetera {
    /// Crea una billetera NUEVA (o restaura una semilla) con su fichero de estado.
    /// Falla si el fichero ya existe. Tras restaurar, `escanear` para encontrar fondos.
    pub fn crear(mnemonic: &str, passphrase: &str, red: Red, fichero: &Path) -> Result<Self, Error> {
        if fichero.exists() {
            return Err(Error::Fichero("ya existe; usa abrir()".into()));
        }
        let net = red.network();
        let (ext, int) = descriptores_publicos(mnemonic, passphrase, net)?;
        let mut almacen = Store::<ChangeSet>::create(MAGIA, fichero).map_err(|e| Error::Fichero(e.to_string()))?;
        let cartera = Wallet::create(ext, int)
            .network(net)
            .create_wallet(&mut almacen)
            .map_err(|e| Error::Fichero(e.to_string()))?;
        Ok(Billetera { cartera, almacen, red: net })
    }

    /// Abre una billetera existente. No necesita la semilla: el fichero guarda los
    /// descriptores públicos. Comprueba que el fichero es de la red pedida.
    pub fn abrir(fichero: &Path, red: Red) -> Result<Self, Error> {
        let net = red.network();
        let (mut almacen, _) = Store::<ChangeSet>::load(MAGIA, fichero).map_err(|e| Error::Fichero(e.to_string()))?;
        let cartera = Wallet::load()
            .check_network(net)
            .load_wallet(&mut almacen)
            .map_err(|e| Error::Fichero(e.to_string()))?
            .ok_or_else(|| Error::Fichero("fichero vacío".into()))?;
        Ok(Billetera { cartera, almacen, red: net })
    }

    fn guardar(&mut self) -> Result<(), Error> {
        self.cartera.persist(&mut self.almacen).map_err(|e| Error::Fichero(e.to_string()))?;
        Ok(())
    }

    pub fn red(&self) -> Network {
        self.red
    }

    /// Dirección de recibir NUEVA cada vez (siguiente índice externo). Devuelve (índice, dirección).
    pub fn nueva_direccion(&mut self) -> Result<(u32, String), Error> {
        let a = self.cartera.reveal_next_address(KeychainKind::External);
        self.guardar()?;
        Ok((a.index, a.address.to_string()))
    }

    /// Escaneo completo con gap limit: para una billetera restaurada o la primera vez.
    pub fn escanear(&mut self, servidor: &str) -> Result<(), Error> {
        let c = conectar(servidor)?;
        c.populate_tx_cache(self.cartera.tx_graph().full_txs().map(|t| t.tx));
        let pet = self.cartera.start_full_scan();
        let upd = c.full_scan(pet, GAP_LIMIT, LOTE, true).map_err(|e| Error::Red(e.to_string()))?;
        self.cartera.apply_update(upd).map_err(|e| Error::Red(e.to_string()))?;
        self.guardar()
    }

    /// Sincronización rápida de las direcciones ya reveladas.
    pub fn sincronizar(&mut self, servidor: &str) -> Result<(), Error> {
        let c = conectar(servidor)?;
        c.populate_tx_cache(self.cartera.tx_graph().full_txs().map(|t| t.tx));
        let pet = self.cartera.start_sync_with_revealed_spks();
        let upd = c.sync(pet, LOTE, true).map_err(|e| Error::Red(e.to_string()))?;
        self.cartera.apply_update(upd).map_err(|e| Error::Red(e.to_string()))?;
        self.guardar()
    }

    pub fn saldo(&self) -> Saldo {
        let b = self.cartera.balance();
        Saldo {
            confirmado: b.confirmed.to_sat(),
            pendiente: (b.trusted_pending + b.untrusted_pending).to_sat(),
            inmaduro: b.immature.to_sat(),
            total: b.total().to_sat(),
        }
    }

    /// Altura hasta la que la billetera está sincronizada.
    pub fn altura(&self) -> u32 {
        self.cartera.latest_checkpoint().height()
    }

    /// Último índice revelado de cada rama (externa, interna).
    pub fn indices(&self) -> (Option<u32>, Option<u32>) {
        (
            self.cartera.derivation_index(KeychainKind::External),
            self.cartera.derivation_index(KeychainKind::Internal),
        )
    }

    pub fn movimientos(&self) -> Vec<Movimiento> {
        let mut v: Vec<Movimiento> = self
            .cartera
            .transactions()
            .map(|t| {
                let (env, rec) = self.cartera.sent_and_received(&t.tx_node.tx);
                let altura = match t.chain_position {
                    ChainPosition::Confirmed { anchor, .. } => Some(anchor.block_id.height),
                    ChainPosition::Unconfirmed { .. } => None,
                };
                Movimiento {
                    txid: t.tx_node.txid.to_string(),
                    recibido_sat: rec.to_sat(),
                    enviado_sat: env.to_sat(),
                    comision_sat: self.cartera.calculate_fee(&t.tx_node.tx).ok().map(|a| a.to_sat()),
                    altura,
                }
            })
            .collect();
        v.sort_by_key(|m| std::cmp::Reverse(m.altura.unwrap_or(u32::MAX)));
        v
    }

    /// Construye y FIRMA un envío. BDK elige las monedas, pone el cambio en una
    /// dirección interna nueva y marca RBF. La comisión se fija en sat/vB sobre el
    /// tamaño real. No difunde: eso es `difundir`.
    pub fn preparar_envio(
        &mut self,
        mnemonic: &str,
        passphrase: &str,
        destino: &str,
        cantidad_sat: u64,
        sat_vb: f64,
    ) -> Result<Envio, Error> {
        let dest = Address::from_str(destino)
            .map_err(|_| Error::Direccion)?
            .require_network(self.red)
            .map_err(|_| Error::Direccion)?;
        if cantidad_sat <= POLVO_SAT {
            return Err(Error::Envio(format!("importe por debajo del polvo ({POLVO_SAT} sat)")));
        }
        if !(sat_vb.is_finite() && sat_vb >= 1.0 && sat_vb <= 1_000.0) {
            return Err(Error::Envio("comisión fuera de rango (1–1000 sat/vB)".into()));
        }
        let tasa = FeeRate::from_sat_per_kwu((sat_vb * 250.0).ceil() as u64);

        // Solo firma quien tenga la semilla: si no es de esta billetera, se para aquí.
        let (de, _di, claves) = claves_privadas(mnemonic, passphrase, self.red)?;
        if de != *self.cartera.public_descriptor(KeychainKind::External) {
            return Err(Error::Semilla);
        }

        let mut psbt = {
            let mut b = self.cartera.build_tx();
            b.add_recipient(dest.script_pubkey(), Amount::from_sat(cantidad_sat));
            b.fee_rate(tasa);
            b.finish().map_err(|e| match e {
                bdk_wallet::error::CreateTxError::CoinSelection(_) => Error::Fondos,
                e => Error::Envio(e.to_string()),
            })?
        };
        let firmante = KeyMapWrapper::from(claves);
        psbt.sign(&firmante, self.cartera.secp_ctx()).map_err(|_| Error::Firma)?;
        drop(firmante);
        let ok = self
            .cartera
            .finalize_psbt(&mut psbt, SignOptions::default())
            .map_err(|_| Error::Firma)?;
        if !ok {
            return Err(Error::Firma);
        }
        let comision = psbt.fee().map_err(|e| Error::Envio(e.to_string()))?.to_sat();
        let tx = psbt.extract_tx().map_err(|e| Error::Envio(e.to_string()))?;
        let cambio = tx
            .output
            .iter()
            .find(|o| self.cartera.is_mine(o.script_pubkey.clone()))
            .and_then(|o| Address::from_script(&o.script_pubkey, self.red).ok())
            .map(|a| a.to_string())
            .unwrap_or_default();
        self.guardar()?; // índice de cambio revelado
        let vbytes = tx.vsize() as u64;
        Ok(Envio {
            txid: tx.compute_txid().to_string(),
            hex: bitcoin::consensus::encode::serialize_hex(&tx),
            comision_sat: comision,
            vbytes,
            sat_vb: comision as f64 / vbytes as f64,
            cambio,
            tx: Arc::new(tx),
        })
    }

    /// Difunde un envío firmado y lo apunta como pendiente en la billetera.
    pub fn difundir(&mut self, servidor: &str, envio: &Envio) -> Result<String, Error> {
        let c = conectar(servidor)?;
        let txid = c.transaction_broadcast(&envio.tx).map_err(|e| Error::Red(e.to_string()))?;
        let ahora = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        self.cartera.apply_unconfirmed_txs([(envio.tx.clone(), ahora)]);
        self.guardar()?;
        Ok(txid.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MN: &str = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    const SIGNET: &str = "ssl://mempool.space:60602";

    #[test]
    fn bip84_direccion_vector() {
        // Vector oficial de BIP84 (ahora sale del descriptor de BDK).
        let a = direccion_recibir(MN, "", Network::Bitcoin, 0).unwrap();
        assert_eq!(a, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu");
        let a1 = direccion_recibir(MN, "", Network::Bitcoin, 1).unwrap();
        assert_eq!(a1, "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g");
    }

    #[test]
    fn bip84_bdk_igual_que_a_mano() {
        // El descriptor de BDK y la derivación a mano dan lo mismo, externa e interna, en signet.
        let secp = Secp256k1::new();
        let red = Network::Signet;
        let r = raiz(MN, "", red).unwrap();
        let (ext, int) = descriptores_publicos(MN, "", red).unwrap();
        for i in 0..5 {
            for (rama, d) in [(0, &ext), (1, &int)] {
                let (_, cpk) = clave_en(&secp, &r, red, rama, i).unwrap();
                let a_mano = Address::p2wpkh(&cpk, red).to_string();
                let bdk = d.at_derivation_index(i).unwrap().address(red).unwrap().to_string();
                assert_eq!(a_mano, bdk, "rama {rama} índice {i}");
            }
        }
    }

    #[test]
    fn nueva_semilla_12_palabras() {
        let s = nueva_semilla().unwrap();
        assert_eq!(s.split_whitespace().count(), 12);
        // y deriva una dirección válida
        assert!(direccion_recibir(&s, "", Network::Testnet, 0).unwrap().starts_with("tb1"));
    }

    #[test]
    fn firma_envio_valida() {
        // UTXO ficticio de 100_000 sat pagado a nuestra dirección índice 0 (testnet).
        let red = Network::Testnet;
        let utxos = vec![Utxo {
            txid: "0000000000000000000000000000000000000000000000000000000000000001".into(),
            vout: 0,
            valor_sat: 100_000,
            indice: 0,
        }];
        let destino = direccion_recibir(MN, "", red, 5).unwrap(); // a otra de las nuestras, da igual
        let hex = firmar_envio(MN, "", red, &utxos, &destino, 60_000, 1_000).unwrap();

        // Reconstruir la tx y VERIFICAR la firma del testigo de la entrada 0.
        let bytes = hex::decode(&hex).unwrap();
        let tx: Transaction = bitcoin::consensus::encode::deserialize(&bytes).unwrap();
        assert_eq!(tx.input.len(), 1);
        assert_eq!(tx.output.len(), 2, "destino + cambio");

        let secp = Secp256k1::new();
        let raiz = raiz(MN, "", red).unwrap();
        let (_, cpk) = clave_en(&secp, &raiz, red, 0, 0).unwrap();
        let spk = Address::p2wpkh(&cpk, red).script_pubkey();
        let mut cache = SighashCache::new(&tx);
        let sighash = cache
            .p2wpkh_signature_hash(0, &spk, Amount::from_sat(100_000), EcdsaSighashType::All)
            .unwrap();
        let w = &tx.input[0].witness;
        let sig_bytes = w.nth(0).unwrap();
        let esig = bitcoin::ecdsa::Signature::from_slice(sig_bytes).unwrap();
        let msg = Message::from(sighash);
        assert!(
            secp.verify_ecdsa(&msg, &esig.signature, &cpk.0).is_ok(),
            "la firma del testigo debe ser válida"
        );
    }

    #[test]
    fn estado_sin_claves_privadas_y_direcciones_nuevas() {
        let dir = tempfile::tempdir().unwrap();
        let f = dir.path().join("billetera.dat");
        let (d0, d1);
        {
            let mut b = Billetera::crear(MN, "", Red::Signet, &f).unwrap();
            d0 = b.nueva_direccion().unwrap();
            d1 = b.nueva_direccion().unwrap();
            assert_ne!(d0.1, d1.1, "cada petición da una dirección nueva");
            assert_eq!((d0.0, d1.0), (0, 1));
            assert!(d0.1.starts_with("tb1q"));
        }
        // El fichero no lleva claves privadas extendidas.
        let bytes = std::fs::read(&f).unwrap();
        let txt = String::from_utf8_lossy(&bytes);
        assert!(!txt.contains("tprv") && !txt.contains("xprv"), "el estado no debe llevar claves privadas");
        assert!(txt.contains("tpub"), "sí lleva los descriptores públicos");
        // Al reabrir (sin semilla) sigue por donde iba.
        let mut b = Billetera::abrir(&f, Red::Signet).unwrap();
        assert_eq!(b.nueva_direccion().unwrap().0, 2);
        // Y no se abre como si fuera de otra red.
        drop(b);
        assert!(Billetera::abrir(&f, Red::Mainnet).is_err());
        // Ni se crea encima de una existente.
        assert!(Billetera::crear(MN, "", Red::Signet, &f).is_err());
    }

    #[test]
    fn firmar_exige_la_semilla_de_esta_billetera() {
        let dir = tempfile::tempdir().unwrap();
        let f = dir.path().join("b.dat");
        let mut b = Billetera::crear(MN, "", Red::Signet, &f).unwrap();
        let otra = nueva_semilla().unwrap();
        let dest = direccion_recibir(&otra, "", Network::Signet, 0).unwrap();
        assert_eq!(b.preparar_envio(&otra, "", &dest, 10_000, 1.0).unwrap_err(), Error::Semilla);
        // Con la buena, sin fondos: Fondos.
        assert_eq!(b.preparar_envio(MN, "", &dest, 10_000, 1.0).unwrap_err(), Error::Fondos);
        // Dirección de mainnet en una billetera de signet: rechazada.
        assert_eq!(
            b.preparar_envio(MN, "", "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", 10_000, 1.0).unwrap_err(),
            Error::Direccion
        );
    }

    // ----- Contra un servidor Electrum de signet (necesitan red: `cargo test -- --ignored`) -----

    #[test]
    #[ignore]
    fn signet_billetera_nueva_saldo_cero() {
        let dir = tempfile::tempdir().unwrap();
        let s = nueva_semilla().unwrap(); // semilla de PRUEBA, generada ahora y tirada al acabar
        let mut b = Billetera::crear(&s, "", Red::Signet, &dir.path().join("b.dat")).unwrap();
        b.escanear(SIGNET).unwrap();
        assert_eq!(b.saldo().total, 0);
        assert!(b.movimientos().is_empty());
        assert!(b.altura() > 300_000, "la billetera conoce la punta de signet");
    }

    #[test]
    #[ignore]
    fn signet_restaurar_encuentra_direcciones() {
        // El vector BIP84 es público y tiene historia en signet (rama m/84'/1'/0').
        let dir = tempfile::tempdir().unwrap();
        let mut b = Billetera::crear(MN, "", Red::Signet, &dir.path().join("b.dat")).unwrap();
        assert_eq!(b.indices(), (None, None));
        b.escanear(SIGNET).unwrap();
        let (ext, _int) = b.indices();
        assert!(ext.is_some(), "el escaneo con gap limit encuentra direcciones usadas");
        assert!(!b.movimientos().is_empty());
    }

    #[test]
    #[ignore]
    fn signet_altura_y_comision() {
        let h = altura_red(SIGNET).unwrap();
        assert!(h > 300_000, "altura de signet: {h}");
        let c = estimar_comision(SIGNET, 2).unwrap();
        assert!(c >= 1.0 && c < 1_000.0, "comisión: {c} sat/vB");
    }
}
