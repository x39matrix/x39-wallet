//! x39-wallet — billetera Bitcoin de autocustodia para x39.
//!
//! Deriva claves (BIP39 semilla, BIP84 segwit nativo `bc1…`/`tb1…`/`bcrt1…`), genera
//! direcciones para RECIBIR, y construye, revisa y FIRMA transacciones para ENVIAR. Por
//! debajo va BDK (`bdk_wallet` + `bdk_electrum`): escaneo con gap limit, selección de
//! monedas, cambio por el descriptor interno y persistencia del estado en un fichero.
//!
//! La red se habla con un servidor Electrum que elige el usuario: su propio nodo o,
//! para pruebas en signet, uno público. Al conectar se comprueba que el servidor es de
//! la red esperada (su `genesis_hash`); si no, no se sincroniza nada.
//!
//! La semilla y las claves privadas nunca salen del aparato ni se escriben en el fichero
//! de estado: la billetera guarda solo los descriptores PÚBLICOS. El mnemónico entra
//! siempre como bytes (`&[u8]`), se envuelve en `Zeroizing` y lo que se deriva de él se
//! borra de memoria al terminar (lo que se puede: ver `claves_privadas`). La passphrase
//! BIP39 es fija, la cadena vacía ([`PASSPHRASE`]), en esta versión.
//!
//! Enviar va en dos pasos atados: [`Billetera::preparar_psbt`] construye SIN semilla un
//! PSBT y devuelve su revisión (destino, importe, comisión, cambio…); después
//! [`Billetera::firmar_y_extraer`] recupera ese pendiente por id, RE-VERIFICA en Rust que
//! lo que va a firmar es exactamente lo revisado y firma. Lo firmado se apunta como
//! gastado localmente ANTES de difundir: ver el módulo [`envios`] para el contrato de
//! difusión incierta. Cancelar algo que ya ha tocado un servidor no libera monedas: es un
//! REEMPLAZO RBF hacia una dirección propia ([`Billetera::preparar_cancelacion`]).
//!
//! Con la feature de Cargo `lab`, toda la API pública rechaza mainnet.
//!
//! Esta es la parte que TIENE que ser correcta: derivación, revisión y firma. Se comprueba
//! con vectores BIP84, verificando la firma del testigo y con un servidor simulado.

pub mod envios;
#[cfg(test)]
pub(crate) mod mock_electrum;

pub use envios::{EnvioInfo, EstadoEnvio};

use bdk_electrum::electrum_client::{self, ElectrumApi};
use bdk_electrum::BdkElectrumClient;
use bdk_wallet::chain::ChainPosition;
use bdk_wallet::descriptor::{ExtendedDescriptor, IntoWalletDescriptor};
use bdk_wallet::file_store::Store;
use bdk_wallet::miniscript::descriptor::{KeyMap, KeyMapWrapper};
use bdk_wallet::{ChangeSet, KeychainKind, PersistedWallet, SignOptions, Wallet};
use bitcoin::bip32::{DerivationPath, Xpriv};
use bitcoin::blockdata::constants::genesis_block;
use bitcoin::hashes::{sha256, Hash};
use bitcoin::hex::DisplayHex;
use bitcoin::psbt::Psbt;
use bitcoin::secp256k1::{Message, Secp256k1};
use bitcoin::sighash::{EcdsaSighashType, SighashCache};
use bitcoin::{
    absolute::LockTime, transaction::Version, Address, Amount, BlockHash, CompressedPublicKey,
    FeeRate, Network, OutPoint, Sequence, Transaction, TxIn, TxOut, Txid, Weight, Witness,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::path::Path;
use std::str::FromStr;
use std::sync::Arc;
use std::time::{Duration, Instant};
use zeroize::Zeroizing;

pub const POLVO_SAT: u64 = 546;

/// Passphrase BIP39: fija a la cadena vacía en esta versión.
pub const PASSPHRASE: &str = "";

/// Direcciones vacías seguidas que se miran antes de dar un escaneo por terminado.
pub const GAP_LIMIT: usize = 20;
const LOTE: usize = 10;
/// Cabecera del fichero de estado (cambiarla hace ilegibles los ficheros viejos).
const MAGIA: &[u8] = b"x39-wallet/bdk/v1";
/// Un PSBT pendiente deja de poder firmarse pasado este tiempo.
pub const CADUCIDAD_PSBT: Duration = Duration::from_secs(10 * 60);
/// Espera por defecto en las llamadas al servidor.
pub const ESPERA_RED: Duration = Duration::from_secs(30);

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
    /// Red no permitida en esta compilación (feature `lab`: mainnet prohibida).
    RedProhibida,
    /// El servidor Electrum es de otra red (su `genesis_hash` no es el esperado).
    RedServidor(String),
    /// La re-verificación previa a la firma no cuadra con lo revisado.
    Revision(String),
    /// No hay PSBT pendiente con ese id, o ha caducado.
    Pendiente(String),
    /// Hay un envío firmado sin resolver (firmado o de difusión incierta): no se
    /// construye otro hasta que se envíe, se confirme o se cancele.
    EnvioEnCurso(String),
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
            Error::RedProhibida => {
                f.write_str("red no permitida en esta compilación (mainnet prohibida)")
            }
            Error::RedServidor(e) => write!(f, "el servidor es de otra red: {e}"),
            Error::Revision(e) => write!(f, "revisión previa a la firma: {e}"),
            Error::Pendiente(e) => write!(f, "PSBT pendiente: {e}"),
            Error::EnvioEnCurso(t) => write!(
                f,
                "hay un envío sin resolver ({t}); reintentarlo o cancelarlo antes"
            ),
        }
    }
}
impl std::error::Error for Error {}

/// Red en la que trabaja la billetera. Las pruebas van SIEMPRE en signet o regtest.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Red {
    Signet,
    Testnet,
    Regtest,
    Mainnet,
}

impl Red {
    pub fn network(self) -> Network {
        match self {
            Red::Signet => Network::Signet,
            Red::Testnet => Network::Testnet,
            Red::Regtest => Network::Regtest,
            Red::Mainnet => Network::Bitcoin,
        }
    }

    pub fn de_network(n: Network) -> Option<Red> {
        match n {
            Network::Signet => Some(Red::Signet),
            Network::Testnet => Some(Red::Testnet),
            Network::Regtest => Some(Red::Regtest),
            Network::Bitcoin => Some(Red::Mainnet),
            _ => None,
        }
    }

    pub fn nombre(self) -> &'static str {
        match self {
            Red::Signet => "signet",
            Red::Testnet => "testnet",
            Red::Regtest => "regtest",
            Red::Mainnet => "mainnet",
        }
    }

    /// Con la feature `lab`, mainnet está prohibida en toda la API pública.
    pub fn permitida(self) -> Result<Red, Error> {
        if cfg!(feature = "lab") && self == Red::Mainnet {
            return Err(Error::RedProhibida);
        }
        Ok(self)
    }
}

impl std::fmt::Display for Red {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.nombre())
    }
}

impl FromStr for Red {
    type Err = Error;
    fn from_str(s: &str) -> Result<Self, Error> {
        match s {
            "signet" => Ok(Red::Signet),
            "testnet" => Ok(Red::Testnet),
            "regtest" => Ok(Red::Regtest),
            "mainnet" | "bitcoin" => Red::Mainnet.permitida(),
            _ => Err(Error::Envio(format!("red desconocida: {s}"))),
        }
    }
}

fn network_permitida(n: Network) -> Result<Network, Error> {
    match Red::de_network(n) {
        Some(r) => r.permitida().map(Red::network),
        None => Err(Error::Envio(format!("red no soportada: {n}"))),
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

fn ahora_s() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn raiz(mnemonico: &[u8], red: Network) -> Result<Xpriv, Error> {
    // El texto se mira sin copiarlo; `Mnemonic` se borra al soltarse (feature zeroize de
    // bip39); la semilla de 64 bytes también.
    let texto = std::str::from_utf8(mnemonico).map_err(|_| Error::Semilla)?;
    let mn = bip39::Mnemonic::parse(texto).map_err(|_| Error::Semilla)?;
    let seed = Zeroizing::new(mn.to_seed(PASSPHRASE));
    Xpriv::new_master(red, &seed[..]).map_err(|_| Error::Derivacion)
}

fn ruta(red: Network, cambio: u32, i: u32) -> DerivationPath {
    DerivationPath::from_str(&format!("m/84'/{}'/0'/{}/{}", coin(red), cambio, i))
        .expect("ruta BIP84 válida")
}

fn clave_en(
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
/// borrar desde aquí (limitación conocida, ver README).
fn claves_privadas(
    mnemonico: &[u8],
    red: Network,
) -> Result<(ExtendedDescriptor, ExtendedDescriptor, KeyMap), Error> {
    let secp = Secp256k1::new();
    let raiz = raiz(mnemonico, red)?;
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
    mnemonico: &[u8],
    red: Network,
) -> Result<(ExtendedDescriptor, ExtendedDescriptor), Error> {
    let (de, di, _claves) = claves_privadas(mnemonico, red)?;
    Ok((de, di))
}

/// Mnemónico nuevo de 12 palabras (128 bits de entropía), como bytes UTF-8 que se borran
/// al soltarse. La semilla del usuario. El binario de pruebas lo convierte a texto; el
/// FFI lo pasa tal cual.
pub fn nueva_semilla() -> Result<Zeroizing<Vec<u8>>, Error> {
    let mn = bip39::Mnemonic::generate(12).map_err(|_| Error::Semilla)?;
    let texto = Zeroizing::new(mn.to_string());
    Ok(Zeroizing::new(texto.as_bytes().to_vec()))
}

/// Dirección para RECIBIR (segwit nativo), rama externa (0), índice `i`.
/// Sale del descriptor de BDK: el vector BIP84 de los tests comprueba que coincide.
pub fn direccion_recibir(mnemonico: &[u8], red: Network, i: u32) -> Result<String, Error> {
    let red = network_permitida(red)?;
    let (ext, _int) = descriptores_publicos(mnemonico, red)?;
    let d = ext.at_derivation_index(i).map_err(|_| Error::Derivacion)?;
    Ok(d.address(red).map_err(|_| Error::Derivacion)?.to_string())
}

/// Firma sin red de la versión anterior: construye y FIRMA un envío con los UTXO que
/// se le den. OJO: el cambio va siempre a la rama interna índice 0 (reutiliza dirección),
/// por eso esta función NO se expone nunca por FFI. La billetera usa
/// `Billetera::preparar_psbt` + `firmar_y_extraer`; esta se conserva porque su test
/// verifica la firma BIP143 a mano.
pub fn firmar_envio(
    mnemonico: &[u8],
    red: Network,
    utxos: &[Utxo],
    destino: &str,
    cantidad_sat: u64,
    comision_sat: u64,
) -> Result<String, Error> {
    let red = network_permitida(red)?;
    let secp = Secp256k1::new();
    let raiz = raiz(mnemonico, red)?;

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
                .p2wpkh_signature_hash(
                    idx,
                    &spk,
                    Amount::from_sat(u.valor_sat),
                    EcdsaSighashType::All,
                )
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

fn red_err(e: electrum_client::Error) -> Error {
    Error::Red(e.to_string())
}

/// El servidor devolvió un error de protocolo (rechazo explícito), no un fallo de
/// transporte.
fn es_rechazo(e: &electrum_client::Error) -> bool {
    match e {
        electrum_client::Error::Protocol(_) => true,
        electrum_client::Error::AllAttemptsErrored(v) => v.iter().any(es_rechazo),
        _ => false,
    }
}

/// El "error" de protocolo dice en realidad que la transacción YA está en la red
/// (mempool o bloque): para nosotros es un éxito y las monedas siguen gastadas.
fn ya_conocida(e: &electrum_client::Error) -> bool {
    match e {
        electrum_client::Error::Protocol(v) => {
            if v.get("code").and_then(|c| c.as_i64()) == Some(-27) {
                return true;
            }
            let texto = v.to_string().to_lowercase();
            [
                "already known",
                "already in mempool",
                "already in block chain",
                "already in blockchain",
                "txn-already",
            ]
            .iter()
            .any(|m| texto.contains(m))
        }
        electrum_client::Error::AllAttemptsErrored(v) => v.iter().any(ya_conocida),
        _ => false,
    }
}

/// Comprueba que el servidor es de la red esperada: su `genesis_hash` debe ser el del
/// bloque génesis de esa red. Si no, [`Error::RedServidor`].
fn comprobar_genesis(c: &impl ElectrumApi, red: Network) -> Result<(), Error> {
    let f = c.server_features().map_err(red_err)?;
    let visto = BlockHash::from_str(&f.genesis_hash.to_lower_hex_string())
        .map_err(|_| Error::RedServidor("genesis_hash ilegible".into()))?;
    let esperado = genesis_block(red).block_hash();
    if visto != esperado {
        return Err(Error::RedServidor(format!(
            "se esperaba {red}, el servidor anuncia génesis {visto}"
        )));
    }
    Ok(())
}

/// Abre conexión y verifica la red del servidor. Cada operación conecta de nuevo, así
/// que la comprobación se repite también tras cualquier reconexión.
fn conectar(
    servidor: &str,
    red: Network,
    espera: Duration,
) -> Result<BdkElectrumClient<electrum_client::Client>, Error> {
    let red = network_permitida(red)?;
    let cfg = electrum_client::ConfigBuilder::new()
        .timeout(Some(espera))
        .retry(1)
        .build();
    let c = electrum_client::Client::from_config(servidor, cfg).map_err(red_err)?;
    comprobar_genesis(&c, red)?;
    Ok(BdkElectrumClient::new(c))
}

/// Altura del último bloque que conoce el servidor (de la red `red`, verificada).
pub fn altura_red(red: Red, servidor: &str) -> Result<u32, Error> {
    let c = conectar(servidor, red.permitida()?.network(), ESPERA_RED)?;
    let h = c.inner.block_headers_subscribe().map_err(red_err)?;
    Ok(h.height as u32)
}

/// Comisión recomendada en sat/vB para confirmar en `bloques` bloques, según el nodo.
/// Si el nodo no tiene estimación (pasa en signet con poco tráfico) usa su comisión
/// mínima de retransmisión. Nunca menos de 1 sat/vB.
pub fn estimar_comision(red: Red, servidor: &str, bloques: usize) -> Result<f64, Error> {
    let c = conectar(servidor, red.permitida()?.network(), ESPERA_RED)?;
    let btc_kvb = c.inner.estimate_fee(bloques, None).map_err(red_err)?;
    let btc_kvb = if btc_kvb > 0.0 {
        btc_kvb
    } else {
        c.inner.relay_fee().map_err(red_err)?
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

/// Un envío firmado por la vía antigua (`preparar_envio`), listo para difundir.
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

/// Lo que el usuario REVISA antes de firmar. Inmutable: lo que se firme tiene que ser
/// exactamente esto, y `firmar_y_extraer` lo re-verifica. El PSBT crudo se queda dentro.
#[derive(Debug, Clone, PartialEq)]
pub struct PsbtPendiente {
    /// Identificador aleatorio del pendiente; es lo único que cruza el FFI.
    pub id: String,
    /// SHA-256 del PSBT serializado.
    pub hash_psbt: String,
    pub red: Red,
    pub destino: String,
    pub importe_sat: u64,
    pub comision_sat: u64,
    /// Tope de comisión aceptado al preparar; la firma vuelve a comprobarlo.
    pub max_comision_sat: u64,
    /// Tamaño ESTIMADO con las firmas (el real sale al firmar).
    pub vbytes: u64,
    pub sat_vb: f64,
    /// Dirección de cambio (propia), vacía si no hay cambio.
    pub cambio: String,
    pub cambio_sat: u64,
    /// Si es una cancelación por reemplazo, el txid de la transacción a la que reemplaza
    /// (gasta todas sus entradas hacia `destino`, que es una dirección propia); si no, vacío.
    pub reemplaza_a: String,
    /// Segundos de validez desde que se preparó.
    pub caduca_en_s: u64,
}

struct Pendiente {
    info: PsbtPendiente,
    psbt: Psbt,
    creado: Instant,
}

pub struct Billetera {
    cartera: PersistedWallet<Store<ChangeSet>>,
    almacen: Store<ChangeSet>,
    red: Network,
    espera: Duration,
    pendiente: Option<Pendiente>,
    envios: envios::Registro,
}

impl Billetera {
    /// Crea una billetera NUEVA (o restaura una semilla) con su fichero de estado.
    /// Falla si el fichero ya existe. Tras restaurar, `escanear` para encontrar fondos.
    pub fn crear(mnemonico: &[u8], red: Red, fichero: &Path) -> Result<Self, Error> {
        let net = red.permitida()?.network();
        if fichero.exists() {
            return Err(Error::Fichero("ya existe; usa abrir()".into()));
        }
        let (ext, int) = descriptores_publicos(mnemonico, net)?;
        let mut almacen = Store::<ChangeSet>::create(MAGIA, fichero)
            .map_err(|e| Error::Fichero(e.to_string()))?;
        let cartera = Wallet::create(ext, int)
            .network(net)
            .create_wallet(&mut almacen)
            .map_err(|e| Error::Fichero(e.to_string()))?;
        let envios = envios::Registro::cargar(fichero)?;
        Ok(Billetera {
            cartera,
            almacen,
            red: net,
            espera: ESPERA_RED,
            pendiente: None,
            envios,
        })
    }

    /// Abre una billetera existente. No necesita la semilla: el fichero guarda los
    /// descriptores públicos. Comprueba que el fichero es de la red pedida y reanuda
    /// los envíos firmados sin resolver (sus monedas siguen gastadas localmente).
    pub fn abrir(fichero: &Path, red: Red) -> Result<Self, Error> {
        let net = red.permitida()?.network();
        let (mut almacen, _) =
            Store::<ChangeSet>::load(MAGIA, fichero).map_err(|e| Error::Fichero(e.to_string()))?;
        let cartera = Wallet::load()
            .check_network(net)
            .load_wallet(&mut almacen)
            .map_err(|e| Error::Fichero(e.to_string()))?
            .ok_or_else(|| Error::Fichero("fichero vacío".into()))?;
        let envios = envios::Registro::cargar(fichero)?;
        let mut b = Billetera {
            cartera,
            almacen,
            red: net,
            espera: ESPERA_RED,
            pendiente: None,
            envios,
        };
        b.mantener_gastadas()?;
        Ok(b)
    }

    /// Espera máxima por respuesta del servidor (por defecto [`ESPERA_RED`]).
    pub fn fijar_espera(&mut self, espera: Duration) {
        self.espera = espera;
    }

    fn guardar(&mut self) -> Result<(), Error> {
        self.cartera
            .persist(&mut self.almacen)
            .map_err(|e| Error::Fichero(e.to_string()))?;
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
        let c = conectar(servidor, self.red, self.espera)?;
        c.populate_tx_cache(self.cartera.tx_graph().full_txs().map(|t| t.tx));
        let pet = self.cartera.start_full_scan();
        let upd = c.full_scan(pet, GAP_LIMIT, LOTE, true).map_err(red_err)?;
        let ahora = ahora_s();
        let cambio = self.envios.observar(&upd.tx_update, ahora);
        self.cartera
            .apply_update(upd)
            .map_err(|e| Error::Red(e.to_string()))?;
        self.tras_actualizar(cambio)
    }

    /// Sincronización rápida de las direcciones ya reveladas. Comprueba también el
    /// estado de los envíos firmados (vistos, confirmados o desaparecidos).
    pub fn sincronizar(&mut self, servidor: &str) -> Result<(), Error> {
        let c = conectar(servidor, self.red, self.espera)?;
        c.populate_tx_cache(self.cartera.tx_graph().full_txs().map(|t| t.tx));
        let pet = self.cartera.start_sync_with_revealed_spks();
        let upd = c.sync(pet, LOTE, true).map_err(red_err)?;
        let ahora = ahora_s();
        let cambio = self.envios.observar(&upd.tx_update, ahora);
        self.cartera
            .apply_update(upd)
            .map_err(|e| Error::Red(e.to_string()))?;
        self.tras_actualizar(cambio)
    }

    fn tras_actualizar(&mut self, envios_cambiados: bool) -> Result<(), Error> {
        self.mantener_gastadas()?;
        if envios_cambiados {
            self.envios.guardar()?;
        }
        Ok(())
    }

    /// Las transacciones firmadas sin resolver siguen gastadas localmente aunque el
    /// servidor no las haya visto (BDK las desalojaría al sincronizar): se vuelven a
    /// aplicar como no confirmadas con un `seen_at` posterior a cualquier desalojo.
    fn mantener_gastadas(&mut self) -> Result<(), Error> {
        let pendientes = self.envios.no_resueltas();
        if pendientes.is_empty() {
            return Ok(());
        }
        let ahora = ahora_s();
        for (txid, tx) in pendientes {
            let desalojo = self.cartera.tx_graph().get_last_evicted(txid).unwrap_or(0);
            let visto = ahora
                .max(desalojo + 1)
                .max(self.envios.visto_en(&txid.to_string()));
            self.cartera.apply_unconfirmed_txs([(Arc::new(tx), visto)]);
            self.envios.anotar_visto(&txid.to_string(), visto);
        }
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
                    comision_sat: self
                        .cartera
                        .calculate_fee(&t.tx_node.tx)
                        .ok()
                        .map(|a| a.to_sat()),
                    altura,
                }
            })
            .collect();
        v.sort_by_key(|m| std::cmp::Reverse(m.altura.unwrap_or(u32::MAX)));
        v
    }

    fn destino_en_red(&self, destino: &str) -> Result<Address, Error> {
        Address::from_str(destino)
            .map_err(|_| Error::Direccion)?
            .require_network(self.red)
            .map_err(|_| Error::Direccion)
    }

    fn comprobar_importe_y_tasa(cantidad_sat: u64, sat_vb: f64) -> Result<FeeRate, Error> {
        if cantidad_sat <= POLVO_SAT {
            return Err(Error::Envio(format!(
                "importe por debajo del polvo ({POLVO_SAT} sat)"
            )));
        }
        if !(sat_vb.is_finite() && (1.0..=1_000.0).contains(&sat_vb)) {
            return Err(Error::Envio(
                "comisión fuera de rango (1–1000 sat/vB)".into(),
            ));
        }
        Ok(FeeRate::from_sat_per_kwu((sat_vb * 250.0).ceil() as u64))
    }

    fn construir_psbt(
        &mut self,
        dest: &Address,
        cantidad_sat: u64,
        tasa: FeeRate,
    ) -> Result<Psbt, Error> {
        let mut b = self.cartera.build_tx();
        b.add_recipient(dest.script_pubkey(), Amount::from_sat(cantidad_sat));
        b.fee_rate(tasa);
        b.finish().map_err(|e| match e {
            bdk_wallet::error::CreateTxError::CoinSelection(_) => Error::Fondos,
            e => Error::Envio(e.to_string()),
        })
    }

    /// Salidas de `tx`: exactamente la del destino (importe exacto) y, como mucho, una
    /// de cambio que sea nuestra. Devuelve (dirección de cambio, cambio en sat).
    fn salidas_declaradas(
        &self,
        tx: &Transaction,
        dest: &Address,
        importe_sat: u64,
    ) -> Result<(String, u64), Error> {
        let spk_dest = dest.script_pubkey();
        let pos = tx
            .output
            .iter()
            .position(|o| o.script_pubkey == spk_dest && o.value.to_sat() == importe_sat)
            .ok_or_else(|| {
                Error::Revision("falta la salida al destino con el importe exacto".into())
            })?;
        let otras: Vec<&TxOut> = tx
            .output
            .iter()
            .enumerate()
            .filter(|(i, _)| *i != pos)
            .map(|(_, o)| o)
            .collect();
        match otras.as_slice() {
            [] => Ok((String::new(), 0)),
            [c] => {
                if !self.cartera.is_mine(c.script_pubkey.clone()) {
                    return Err(Error::Revision("la salida de cambio no es nuestra".into()));
                }
                let a = Address::from_script(&c.script_pubkey, self.red)
                    .map_err(|_| Error::Revision("cambio sin dirección legible".into()))?;
                Ok((a.to_string(), c.value.to_sat()))
            }
            _ => Err(Error::Revision(format!(
                "{} salidas: solo se admiten destino y cambio",
                tx.output.len()
            ))),
        }
    }

    fn hash_psbt(psbt: &Psbt) -> String {
        sha256::Hash::hash(&psbt.serialize()).to_string()
    }

    /// Paso 1 de enviar: construye SIN semilla (descriptores públicos) un PSBT y devuelve
    /// lo que hay que revisar. Es inmutable y caduca ([`CADUCIDAD_PSBT`]). Solo hay un
    /// pendiente vivo por billetera: preparar otro invalida el anterior. BDK elige las
    /// monedas, pone el cambio en una dirección interna nueva y marca RBF. Si la comisión
    /// resultante supera `max_comision_sat`, no se prepara nada.
    pub fn preparar_psbt(
        &mut self,
        destino: &str,
        cantidad_sat: u64,
        sat_vb: f64,
        max_comision_sat: u64,
    ) -> Result<PsbtPendiente, Error> {
        self.pendiente = None; // crear otro invalida el anterior, incluso si esto falla
        if let Some(e) = self.envios.en_curso() {
            return Err(Error::EnvioEnCurso(e.txid.clone()));
        }
        let dest = self.destino_en_red(destino)?;
        let tasa = Self::comprobar_importe_y_tasa(cantidad_sat, sat_vb)?;
        let psbt = self.construir_psbt(&dest, cantidad_sat, tasa)?;
        self.guardar()?; // índice de cambio revelado
        let comision = psbt
            .fee()
            .map_err(|e| Error::Envio(e.to_string()))?
            .to_sat();
        if comision > max_comision_sat {
            return Err(Error::Envio(format!(
                "la comisión ({comision} sat) supera el máximo aceptado ({max_comision_sat} sat)"
            )));
        }
        let tx = &psbt.unsigned_tx;
        let (cambio, cambio_sat) =
            self.salidas_declaradas(tx, &dest, cantidad_sat)
                .map_err(|e| match e {
                    Error::Revision(m) => Error::Envio(m),
                    e => e,
                })?;
        let vbytes = self.vbytes_estimados(tx)?;
        let info = PsbtPendiente {
            id: Self::id_aleatorio(),
            hash_psbt: Self::hash_psbt(&psbt),
            red: Red::de_network(self.red).ok_or(Error::RedProhibida)?,
            destino: dest.to_string(),
            importe_sat: cantidad_sat,
            comision_sat: comision,
            max_comision_sat,
            vbytes,
            sat_vb: comision as f64 / vbytes as f64,
            cambio,
            cambio_sat,
            reemplaza_a: String::new(),
            caduca_en_s: CADUCIDAD_PSBT.as_secs(),
        };
        self.pendiente = Some(Pendiente {
            info: info.clone(),
            psbt,
            creado: Instant::now(),
        });
        Ok(info)
    }

    /// Cota superior del tamaño con las firmas: la tx sin firmar (que pesa como legacy, sin
    /// ningún testigo) + marcador y bandera segwit (2 wu) + por entrada el byte del número de
    /// elementos del testigo (1 wu) y el testigo máximo del descriptor (todas son P2WPKH
    /// nuestras). Las pruebas comprueban que el tamaño real nunca la supera.
    fn vbytes_estimados(&self, tx: &Transaction) -> Result<u64, Error> {
        let por_entrada = self
            .cartera
            .public_descriptor(KeychainKind::External)
            .max_weight_to_satisfy()
            .map_err(|_| Error::Envio("peso del descriptor".into()))?;
        let n = tx.input.len() as u64;
        let peso = tx.weight() + Weight::from_wu(2 + n) + por_entrada * n;
        Ok(peso.to_vbytes_ceil())
    }

    fn id_aleatorio() -> String {
        let mut id = [0u8; 16];
        bitcoin::secp256k1::rand::RngCore::fill_bytes(
            &mut bitcoin::secp256k1::rand::thread_rng(),
            &mut id,
        );
        id.to_lower_hex_string()
    }

    /// Cancelación por REEMPLAZO (RBF) de un envío que ya ha tocado un servidor: construye
    /// SIN semilla una transacción que gasta TODAS las entradas de la original y devuelve el
    /// total a una dirección interna nueva, con comisión absoluta al menos la original más
    /// 1 sat/vB por el tamaño del reemplazo, y no por debajo de `sat_vb`. Se revisa y firma
    /// como cualquier otro pendiente (`firmar_y_extraer`) y se difunde con `difundir`.
    /// Mientras ni la original ni el reemplazo se confirmen, las dos cuentan como en curso.
    pub fn preparar_cancelacion(
        &mut self,
        txid: &str,
        sat_vb: f64,
    ) -> Result<PsbtPendiente, Error> {
        self.pendiente = None;
        let orig = self
            .envios
            .get(txid)
            .cloned()
            .ok_or_else(|| Error::Envio(format!("envío desconocido: {txid}")))?;
        if !orig.puede_reemplazar {
            return Err(Error::Envio(format!(
                "no se puede reemplazar: está {}{}{}",
                orig.estado,
                if orig.intentos_difusion == 0 {
                    " y nunca se difundió (usa cancelar)"
                } else {
                    ""
                },
                if self.envios.reemplazo_vivo_de(txid).is_some() {
                    " y ya tiene un reemplazo en curso"
                } else {
                    ""
                },
            )));
        }
        if !(sat_vb.is_finite() && (1.0..=1_000.0).contains(&sat_vb)) {
            return Err(Error::Envio(
                "comisión fuera de rango (1–1000 sat/vB)".into(),
            ));
        }
        let t = Txid::from_str(txid).map_err(|_| Error::Envio("txid ilegible".into()))?;
        let tx_orig = self.envios.tx(txid)?;
        let comision_orig = self
            .cartera
            .calculate_fee(&tx_orig)
            .map_err(|e| Error::Envio(format!("comisión de la original: {e}")))?
            .to_sat();
        // Todo a una dirección interna nueva.
        let destino = self
            .cartera
            .reveal_next_address(KeychainKind::Internal)
            .address;
        let spk = destino.script_pubkey();
        let construir = |yo: &mut Self, comision: u64| -> Result<Psbt, Error> {
            let mut b = yo
                .cartera
                .build_fee_bump(t)
                .map_err(|e| Error::Envio(format!("reemplazo: {e}")))?;
            b.set_recipients(Vec::new());
            b.manually_selected_only();
            b.drain_to(spk.clone());
            b.fee_absolute(Amount::from_sat(comision));
            b.finish().map_err(|e| match e {
                bdk_wallet::error::CreateTxError::CoinSelection(_) => Error::Fondos,
                e => Error::Envio(format!("reemplazo: {e}")),
            })
        };
        // Primera pasada para conocer el tamaño; segunda con la comisión exigida.
        let borrador = construir(self, comision_orig + 1)?;
        let vbytes = self.vbytes_estimados(&borrador.unsigned_tx)?;
        let comision = (comision_orig + vbytes).max((sat_vb * vbytes as f64).ceil() as u64);
        let psbt = construir(self, comision)?;
        self.guardar()?;
        let tx = &psbt.unsigned_tx;
        let entradas: BTreeSet<OutPoint> = tx.input.iter().map(|i| i.previous_output).collect();
        let originales: BTreeSet<OutPoint> =
            tx_orig.input.iter().map(|i| i.previous_output).collect();
        if entradas != originales {
            return Err(Error::Envio(
                "el reemplazo no gasta exactamente las entradas de la original".into(),
            ));
        }
        let real = psbt
            .fee()
            .map_err(|e| Error::Envio(e.to_string()))?
            .to_sat();
        if real < comision_orig + vbytes {
            return Err(Error::Envio(format!(
                "comisión del reemplazo {real} sat, hacía falta al menos {}",
                comision_orig + vbytes
            )));
        }
        let importe = match tx.output.as_slice() {
            [o] if o.script_pubkey == spk => o.value.to_sat(),
            _ => {
                return Err(Error::Envio(format!(
                    "el reemplazo tiene {} salidas; debe tener solo la propia",
                    tx.output.len()
                )))
            }
        };
        let info = PsbtPendiente {
            id: Self::id_aleatorio(),
            hash_psbt: Self::hash_psbt(&psbt),
            red: Red::de_network(self.red).ok_or(Error::RedProhibida)?,
            destino: destino.to_string(),
            importe_sat: importe,
            comision_sat: real,
            max_comision_sat: real,
            vbytes,
            sat_vb: real as f64 / vbytes as f64,
            cambio: String::new(),
            cambio_sat: 0,
            reemplaza_a: txid.to_string(),
            caduca_en_s: CADUCIDAD_PSBT.as_secs(),
        };
        self.pendiente = Some(Pendiente {
            info: info.clone(),
            psbt,
            creado: Instant::now(),
        });
        Ok(info)
    }

    /// El PSBT pendiente, si lo hay y no ha caducado.
    pub fn psbt_pendiente(&self) -> Option<PsbtPendiente> {
        self.pendiente
            .as_ref()
            .filter(|p| p.creado.elapsed() <= CADUCIDAD_PSBT)
            .map(|p| p.info.clone())
    }

    /// Re-verificación ATADA A LA FIRMA: lo que se va a firmar es exactamente lo revisado.
    fn revisar(&self, p: &Pendiente) -> Result<(), Error> {
        let i = &p.info;
        if i.red.network() != self.red {
            return Err(Error::Revision(format!(
                "el pendiente es de {}, la billetera de {}",
                i.red, self.red
            )));
        }
        if Self::hash_psbt(&p.psbt) != i.hash_psbt {
            return Err(Error::Revision(
                "el PSBT ha cambiado desde la revisión".into(),
            ));
        }
        let comision = p
            .psbt
            .fee()
            .map_err(|e| Error::Revision(e.to_string()))?
            .to_sat();
        if comision != i.comision_sat {
            return Err(Error::Revision(format!(
                "comisión {comision} sat, revisada {} sat",
                i.comision_sat
            )));
        }
        if comision > i.max_comision_sat {
            return Err(Error::Revision(format!(
                "comisión {comision} sat supera el máximo {} sat",
                i.max_comision_sat
            )));
        }
        let dest = self
            .destino_en_red(&i.destino)
            .map_err(|_| Error::Revision("destino inválido".into()))?;
        let (cambio, cambio_sat) =
            self.salidas_declaradas(&p.psbt.unsigned_tx, &dest, i.importe_sat)?;
        if cambio != i.cambio || cambio_sat != i.cambio_sat {
            return Err(Error::Revision("el cambio no es el revisado".into()));
        }
        for (n, e) in p.psbt.inputs.iter().enumerate() {
            let propia = e
                .witness_utxo
                .as_ref()
                .map(|u| self.cartera.is_mine(u.script_pubkey.clone()))
                .unwrap_or(false);
            if !propia {
                return Err(Error::Revision(format!("la entrada {n} no es nuestra")));
            }
        }
        if !i.reemplaza_a.is_empty() {
            // Un reemplazo gasta exactamente las entradas de la original y paga a una
            // dirección propia: si no, no sería una cancelación.
            let orig = self
                .envios
                .tx(&i.reemplaza_a)
                .map_err(|_| Error::Revision("la original ya no está en el registro".into()))?;
            let a: BTreeSet<OutPoint> = p
                .psbt
                .unsigned_tx
                .input
                .iter()
                .map(|x| x.previous_output)
                .collect();
            let b: BTreeSet<OutPoint> = orig.input.iter().map(|x| x.previous_output).collect();
            if a != b {
                return Err(Error::Revision(
                    "el reemplazo no gasta las entradas de la original".into(),
                ));
            }
            if !self.cartera.is_mine(dest.script_pubkey()) {
                return Err(Error::Revision(
                    "el reemplazo no paga a una dirección propia".into(),
                ));
            }
            if self.envios.reemplazo_vivo_de(&i.reemplaza_a).is_some() {
                return Err(Error::Revision(
                    "la original ya tiene un reemplazo en curso".into(),
                ));
            }
        }
        Ok(())
    }

    /// Paso 2 de enviar: recupera el pendiente `id`, lo RE-VERIFICA, firma con el
    /// mnemónico (bytes; se borra lo derivado) y extrae la transacción. Antes de devolver
    /// nada, la aplica como no confirmada y la persiste: las monedas quedan gastadas
    /// localmente y es imposible reusarlas en otro pago. No difunde: eso es `difundir`.
    pub fn firmar_y_extraer(&mut self, id: &str, mnemonico: &[u8]) -> Result<EnvioInfo, Error> {
        let p = self
            .pendiente
            .take()
            .ok_or_else(|| Error::Pendiente("no hay ningún PSBT pendiente".into()))?;
        if p.info.id != id {
            return Err(Error::Pendiente("el id no es el del pendiente vivo".into()));
        }
        if p.creado.elapsed() > CADUCIDAD_PSBT {
            return Err(Error::Pendiente(
                "caducado: prepara el envío otra vez".into(),
            ));
        }
        self.revisar(&p)?;

        // Solo firma quien tenga la semilla de ESTA billetera.
        let (de, _di, claves) = claves_privadas(mnemonico, self.red)?;
        if de != *self.cartera.public_descriptor(KeychainKind::External) {
            return Err(Error::Semilla);
        }
        let mut psbt = p.psbt;
        let firmante = KeyMapWrapper::from(claves);
        psbt.sign(&firmante, self.cartera.secp_ctx())
            .map_err(|_| Error::Firma)?;
        drop(firmante);
        let ok = self
            .cartera
            .finalize_psbt(&mut psbt, SignOptions::default())
            .map_err(|_| Error::Firma)?;
        if !ok {
            return Err(Error::Firma);
        }
        let comision = psbt
            .fee()
            .map_err(|e| Error::Envio(e.to_string()))?
            .to_sat();
        let tx = psbt.extract_tx().map_err(|e| Error::Envio(e.to_string()))?;
        let vbytes = tx.vsize() as u64;
        let txid = tx.compute_txid();
        let ahora = ahora_s();
        let i = p.info;
        let info = EnvioInfo {
            id: i.id,
            txid: txid.to_string(),
            red: i.red,
            destino: i.destino,
            importe_sat: i.importe_sat,
            comision_sat: comision,
            vbytes,
            sat_vb: comision as f64 / vbytes as f64,
            cambio: i.cambio,
            cambio_sat: i.cambio_sat,
            reemplaza_a: i.reemplaza_a,
            estado: EstadoEnvio::Firmada,
            motivo: String::new(),
            intentos_difusion: 0,
            firmada_en: ahora,
            actualizada_en: ahora,
            puede_reintentar: true,
            puede_cancelar: true,
            puede_reemplazar: false,
        };
        // Primero el registro (con la tx completa), luego la billetera: si falla lo segundo,
        // `abrir` reanuda desde el registro.
        let info = self.envios.registrar(info, &tx, ahora)?;
        self.cartera.apply_unconfirmed_txs([(Arc::new(tx), ahora)]);
        self.guardar()?;
        Ok(info)
    }

    /// Difunde (o REINTENTA) la transacción firmada `txid`. Éxito, o "ya la conocía"
    /// (`already known`, `already in mempool`, `already in block chain`, código -27) →
    /// `Enviada`. Rechazo explícito del servidor → `Fallida` y monedas liberadas. Sin
    /// respuesta → `Incierta`: las monedas siguen gastadas y solo cabe reintentar esta
    /// misma transacción o cancelarla por reemplazo (`preparar_cancelacion`).
    pub fn difundir(&mut self, servidor: &str, txid: &str) -> Result<EnvioInfo, Error> {
        let actual = self
            .envios
            .get(txid)
            .cloned()
            .ok_or_else(|| Error::Envio(format!("envío desconocido: {txid}")))?;
        match actual.estado {
            EstadoEnvio::Enviada | EstadoEnvio::Confirmada => return Ok(actual),
            EstadoEnvio::Fallida | EstadoEnvio::Cancelada => {
                return Err(Error::Envio(format!(
                    "la transacción está {}; prepara un envío nuevo",
                    actual.estado
                )))
            }
            EstadoEnvio::Firmada | EstadoEnvio::Incierta => {}
        }
        // Si no se llega a conectar (o el servidor es de otra red) no se ha enviado nada:
        // el estado no cambia.
        let c = conectar(servidor, self.red, self.espera)?;
        let tx = self.envios.tx(txid)?;
        let ahora = ahora_s();
        match c.inner.transaction_broadcast(&tx) {
            Ok(_) => self.envios.marcar(
                txid,
                EstadoEnvio::Enviada,
                "aceptada por el servidor",
                true,
                ahora,
            ),
            Err(e) if ya_conocida(&e) => self.envios.marcar(
                txid,
                EstadoEnvio::Enviada,
                &format!("el servidor ya la conocía: {e}"),
                true,
                ahora,
            ),
            Err(e) if es_rechazo(&e) => {
                let info =
                    self.envios
                        .marcar(txid, EstadoEnvio::Fallida, &e.to_string(), true, ahora)?;
                self.liberar(txid)?;
                Ok(info)
            }
            Err(e) => self.envios.marcar(
                txid,
                EstadoEnvio::Incierta,
                &format!("sin respuesta: {e}"),
                true,
                ahora,
            ),
        }
    }

    /// Cancela liberando las monedas SOLO un envío `Firmada` que nunca se intentó difundir
    /// (ninguna copia ha salido del aparato). Si la transacción ha tocado un servidor no se
    /// sabe si está en la red: liberar sus monedas y gastarlas en otra cosa no crearía
    /// ningún conflicto (otras entradas) y se pagaría dos veces. Para eso está
    /// `preparar_cancelacion`: un reemplazo que gasta las MISMAS entradas, de modo que la
    /// red confirme como mucho una de las dos.
    pub fn cancelar(&mut self, txid: &str) -> Result<EnvioInfo, Error> {
        let actual = self
            .envios
            .get(txid)
            .cloned()
            .ok_or_else(|| Error::Envio(format!("envío desconocido: {txid}")))?;
        if !actual.puede_cancelar {
            return Err(Error::Envio(format!(
                "no se puede cancelar liberando monedas: está {} con {} intento(s) de difusión{}",
                actual.estado,
                actual.intentos_difusion,
                if actual.puede_reemplazar {
                    "; usa preparar_cancelacion (reemplazo)"
                } else {
                    ""
                }
            )));
        }
        let info = self.envios.marcar(
            txid,
            EstadoEnvio::Cancelada,
            "cancelada por el usuario",
            false,
            ahora_s(),
        )?;
        self.liberar(txid)?;
        Ok(info)
    }

    /// Marca la transacción como desalojada (después de su último `seen_at`): sus monedas
    /// vuelven a estar disponibles.
    fn liberar(&mut self, txid: &str) -> Result<(), Error> {
        let t = Txid::from_str(txid).map_err(|_| Error::Envio("txid ilegible".into()))?;
        let desalojo = ahora_s().max(self.envios.visto_en(txid) + 1);
        self.cartera.apply_evicted_txs([(t, desalojo)]);
        self.guardar()
    }

    pub fn estado_envio(&self, txid: &str) -> Option<EnvioInfo> {
        self.envios.get(txid).cloned()
    }

    pub fn envios(&self) -> Vec<EnvioInfo> {
        self.envios.todos()
    }

    /// El envío firmado sin resolver, si lo hay (bloquea `preparar_psbt`).
    pub fn envio_en_curso(&self) -> Option<EnvioInfo> {
        self.envios.en_curso().cloned()
    }

    /// Transacción firmada en hex. Para herramientas de línea de órdenes; no cruza el FFI.
    pub fn transaccion_hex(&self, txid: &str) -> Option<String> {
        self.envios.hex(txid)
    }

    /// Vía ANTIGUA en un solo paso: construye y FIRMA un envío. Se conserva por sus
    /// pruebas; la app usa `preparar_psbt` + `firmar_y_extraer`. No difunde ni apunta
    /// estados: eso es `difundir_envio`.
    pub fn preparar_envio(
        &mut self,
        mnemonico: &[u8],
        destino: &str,
        cantidad_sat: u64,
        sat_vb: f64,
    ) -> Result<Envio, Error> {
        let dest = self.destino_en_red(destino)?;
        let tasa = Self::comprobar_importe_y_tasa(cantidad_sat, sat_vb)?;

        // Solo firma quien tenga la semilla: si no es de esta billetera, se para aquí.
        let (de, _di, claves) = claves_privadas(mnemonico, self.red)?;
        if de != *self.cartera.public_descriptor(KeychainKind::External) {
            return Err(Error::Semilla);
        }

        let mut psbt = self.construir_psbt(&dest, cantidad_sat, tasa)?;
        let firmante = KeyMapWrapper::from(claves);
        psbt.sign(&firmante, self.cartera.secp_ctx())
            .map_err(|_| Error::Firma)?;
        drop(firmante);
        let ok = self
            .cartera
            .finalize_psbt(&mut psbt, SignOptions::default())
            .map_err(|_| Error::Firma)?;
        if !ok {
            return Err(Error::Firma);
        }
        let comision = psbt
            .fee()
            .map_err(|e| Error::Envio(e.to_string()))?
            .to_sat();
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

    /// Difunde un envío de la vía antigua y lo apunta como pendiente en la billetera.
    pub fn difundir_envio(&mut self, servidor: &str, envio: &Envio) -> Result<String, Error> {
        let c = conectar(servidor, self.red, self.espera)?;
        let txid = c.transaction_broadcast(&envio.tx).map_err(red_err)?;
        self.cartera
            .apply_unconfirmed_txs([(envio.tx.clone(), ahora_s())]);
        self.guardar()?;
        Ok(txid.to_string())
    }
}

#[cfg(test)]
mod tests;
