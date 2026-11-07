//! Conexión con el nodo del usuario: TLS con huella fijada, comprobaciones en orden.
//!
//! Orden REAL de las comprobaciones en CADA conexión y reconexión (`Conexion::abrir`; la
//! sonda `comprobar_nodo` no hace nada que no haga cualquier otra conexión):
//! 1. identidad del servidor (solo con `ssl://` y huella): el SPKI del certificado debe tener
//!    la huella esperada; se comprueba DENTRO del apretón de manos TLS, antes de que viaje
//!    ningún dato de aplicación. Sin huella y con `ssl://`, el certificado se valida contra
//!    las raíces públicas (CA, nombre y caducidad); CON huella no se valida nada más que el
//!    SPKI: ni CA, ni nombre, ni caducidad (la huella sustituye a la CA).
//! 2. `server.version`, PRIMER mensaje Electrum de la sesión, como exige la especificación
//!    (un servidor estricto corta cualquier RPC anterior): protocolo Electrum 1.4 (por
//!    componentes: «1», «4» y, si hay tercero, solo dígitos; «1.40» o «1.4basura» no valen) y
//!    latencia,
//! 3. red correcta (`genesis_hash` del servidor = génesis de la red pedida),
//! 4. altura no inferior a la última conocida (`altura_minima`; el que llama la pasa: la
//!    billetera, la suya; la sonda, la que le dan), y de ahí en adelante no inferior a la
//!    MÁXIMA que el propio servidor haya anunciado en esta sesión (marca atómica, ver
//!    `Vigilado`). bdk_electrum ignora en silencio un tip menor que el local, así que la
//!    compara el motor, aquí, antes de operar.
//!
//! Nada de esto se cachea entre conexiones: cada conexión se valida a sí misma, aunque cueste
//! un par de peticiones más. Una operación nunca viaja por una conexión que no haya pasado
//! las cuatro puertas.
//!
//! La huella viaja en TODAS las operaciones de red (escaneo, sincronización, difusión,
//! altura, comisión, comprobación): no hay ninguna que abra conexión sin ella.
//!
//! Transporte: el motor abre el flujo ÉL MISMO en los tres casos (`tcp://`; `ssl://` con
//! raíces públicas; `ssl://` con huella) y lo envuelve con `RawClient::from`, que no negocia
//! nada por su cuenta. No se usa `electrum_client::Client`: ese cliente negocia
//! `server.version` solo, proponiendo el rango 1.4–1.6, guarda la respuesta en un campo privado
//! sin lector y no la compara con nada; con él habría dos negociaciones por sesión (un servidor
//! estricto rechaza la segunda) y el servidor podría negociar 1.6 sin que el motor lo viera.
//! Aquí hay UNA negociación por sesión, la del motor, que propone «1.4» a secas y valida la
//! versión efectivamente negociada. Tampoco hay reintentos internos: un reintento reabriría el
//! socket y repetiría el RPC sin volver a pasar las puertas; cualquier reintento lo hace la
//! capa de arriba abriendo una `Conexion` nueva.
//!
//! Vigilancia durante BDK: `BdkElectrumClient` recibe el cliente envuelto en `Vigilado`, que
//! comprueba CADA respuesta de `blockchain.headers.subscribe` (también las que pide BDK dentro
//! de un escaneo o sincronización) contra `altura_minima` y corta en el acto. bdk_electrum
//! conserva en silencio el checkpoint local cuando el servidor anuncia un tip menor, así que
//! una altura inferior recibida a mitad de sesión solo se detecta aquí, antes de que el
//! resultado llegue a `envios.observar`, `apply_update` o cualquier persistencia.
//!
//! Política «ssl:// exige huella salvo el servidor público de signet»: la impone la capa
//! Kotlin (`ConfigNodo.invalida()`), no el motor; el motor acepta `ssl://` sin huella
//! validando contra las raíces públicas. Con `tcp://` no hay cifrado: se informa
//! (`cifrado = false`) y la capa de arriba avisa.
//!
//! Proxy SOCKS5 (Tor/Orbot): si quien llama da un proxy `host:puerto`, el transporte TCP se abre
//! hacia el proxy y se le pide CONNECT al destino con su nombre tal cual (ATYP 3), de modo que
//! quien resuelve el nombre es el proxy (Tor resuelve `.onion`). Un destino `.onion` SIN proxy
//! se rechaza antes de abrir ningún socket: el motor no resuelve `.onion` ni lo intenta.
//!
//! `huella_servidor` abre solo el apretón de manos TLS (ningún mensaje Electrum) y devuelve la
//! huella SPKI que presenta el servidor, para que la capa de arriba la muestre y, solo si el
//! usuario lo decide expresamente («certificado propio»), la fije.

use crate::{network_permitida, red_err, Error, Red, ESPERA_RED};
use bdk_electrum::electrum_client::raw_client::RawClient;
use bdk_electrum::electrum_client::{
    self, Batch, BroadcastPackageRes, ElectrumApi, EstimationMode, GetBalanceRes, GetHeadersRes,
    GetHistoryRes, GetMerkleRes, ListUnspentRes, MempoolInfoRes, Param, RawHeaderNotification,
    ScriptStatus, ServerFeaturesRes, TxidFromPosRes,
};
use bdk_electrum::BdkElectrumClient;
use bitcoin::blockdata::constants::genesis_block;
use bitcoin::hashes::{sha256, Hash};
use bitcoin::hex::{DisplayHex, FromHex};
use bitcoin::{BlockHash, Network, Script, Txid};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, Der, ServerName, TrustAnchor, UnixTime};
use rustls::{ClientConfig, ClientConnection, DigitallySignedStruct, RootCertStore, StreamOwned};
use std::borrow::Borrow;
use std::io::{Read, Write};
use std::net::{IpAddr, TcpStream};
use std::str::FromStr;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Lo que se sabe del nodo tras comprobarlo. Tipo plano para el FFI.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NodoInfo {
    pub servidor: String,
    pub version_servidor: String,
    pub protocolo: String,
    pub altura: u32,
    pub latencia_ms: u64,
    /// `false` con `tcp://`: sin cifrar.
    pub cifrado: bool,
    /// Huella SPKI (SHA-256, hex) del certificado presentado; vacía sin TLS o sin huella fijada.
    pub huella_vista: String,
    pub red: Red,
}

/// Destino de conexión analizado.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Destino {
    pub esquema: Esquema,
    pub host: String,
    pub puerto: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Esquema {
    Tcp,
    Ssl,
}

impl Destino {
    pub fn analizar(servidor: &str) -> Result<Destino, Error> {
        let (esquema, resto) = if let Some(r) = servidor.strip_prefix("ssl://") {
            (Esquema::Ssl, r)
        } else if let Some(r) = servidor.strip_prefix("tcp://") {
            (Esquema::Tcp, r)
        } else {
            return Err(Error::Red(
                "servidor sin esquema: usa ssl://host:puerto o tcp://host:puerto".into(),
            ));
        };
        let (host, puerto) = resto
            .rsplit_once(':')
            .ok_or_else(|| Error::Red("servidor sin puerto".into()))?;
        let puerto: u16 = puerto
            .parse()
            .map_err(|_| Error::Red("puerto inválido".into()))?;
        if host.is_empty() {
            return Err(Error::Red("servidor sin host".into()));
        }
        Ok(Destino {
            esquema,
            host: host.trim_matches(|c| c == '[' || c == ']').to_string(),
            puerto,
        })
    }
}

impl Destino {
    /// Servicio oculto de Tor: solo se alcanza mediante un proxy SOCKS5. El motor no resuelve
    /// `.onion` ni lo intenta: sin proxy se niega antes de abrir ningún socket.
    pub fn es_onion(&self) -> bool {
        self.host.to_ascii_lowercase().ends_with(".onion")
    }
}

/// Huella SPKI: SHA-256 del `SubjectPublicKeyInfo` (DER) del certificado de entidad final.
pub(crate) fn huella_spki(cert: &CertificateDer<'_>) -> Result<[u8; 32], rustls::Error> {
    let eec = webpki::EndEntityCert::try_from(cert)
        .map_err(|e| rustls::Error::General(format!("certificado ilegible: {e:?}")))?;
    let spki = eec.subject_public_key_info();
    Ok(sha256::Hash::hash(spki.as_ref()).to_byte_array())
}

fn analizar_huella(hex: &str) -> Result<[u8; 32], Error> {
    let limpio: String = hex.chars().filter(|c| c.is_ascii_hexdigit()).collect();
    <[u8; 32]>::from_hex(&limpio).map_err(|_| {
        Error::HuellaServidor("huella mal formada: hacen falta 64 caracteres hexadecimales".into())
    })
}

/// Verificador TLS que SOLO acepta el certificado cuyo SPKI tiene la huella esperada.
/// Guarda la huella vista para poder informarla. Con `esperada == None` solo OBSERVA: acepta el
/// certificado y anota su huella; se usa únicamente en [`huella_servidor`], que no habla Electrum.
#[derive(Debug)]
struct VerificadorHuella {
    esperada: Option<[u8; 32]>,
    vista: Mutex<Option<[u8; 32]>>,
    proveedor: Arc<rustls::crypto::CryptoProvider>,
}

impl ServerCertVerifier for VerificadorHuella {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let vista = huella_spki(end_entity)?;
        *self.vista.lock().unwrap_or_else(|p| p.into_inner()) = Some(vista);
        if let Some(esperada) = self.esperada {
            if vista != esperada {
                return Err(rustls::Error::General(
                    "huella del servidor distinta de la esperada".into(),
                ));
            }
        }
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.proveedor.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.proveedor.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.proveedor
            .signature_verification_algorithms
            .supported_schemes()
    }
}

type FlujoTls = StreamOwned<ClientConnection, TcpStream>;

/// Flujo de bytes con el servidor, abierto por el motor: TCP a secas o TLS (con raíces
/// públicas o con huella fijada; la diferencia está en cómo se abrió, no en el tipo).
pub(crate) enum Flujo {
    Plano(TcpStream),
    Tls(FlujoTls),
}

impl Read for Flujo {
    fn read(&mut self, b: &mut [u8]) -> std::io::Result<usize> {
        match self {
            Flujo::Plano(s) => s.read(b),
            Flujo::Tls(s) => s.read(b),
        }
    }
}

impl Write for Flujo {
    fn write(&mut self, b: &[u8]) -> std::io::Result<usize> {
        match self {
            Flujo::Plano(s) => s.write(b),
            Flujo::Tls(s) => s.write(b),
        }
    }
    fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Flujo::Plano(s) => s.flush(),
            Flujo::Tls(s) => s.flush(),
        }
    }
}

const METODO_ALTURA: &str = "blockchain.headers.subscribe";

/// Cliente Electrum vigilado: delega todo en el cliente interno y comprueba CADA altura que el
/// servidor anuncia (`blockchain.headers.subscribe`, la pida el motor o la pida BDK) contra la
/// MARCA de altura máxima vista: empieza en la altura mínima que da quien abre la conexión y
/// sube con cada altura anunciada. Una altura inferior a la marca (a lo que ya se había visto
/// antes de la sesión, o a lo que el propio servidor anunció en ella) corta la llamada en el
/// acto con el error de la puerta, así que BDK no llega a devolver un resultado que el motor
/// pudiera aplicar o persistir.
pub(crate) struct Vigilado<E> {
    interno: E,
    marca: AtomicU32,
}

impl<E> Vigilado<E> {
    pub(crate) fn nuevo(interno: E, altura_minima: u32) -> Vigilado<E> {
        Vigilado {
            interno,
            marca: AtomicU32::new(altura_minima),
        }
    }

    /// Comprobación y actualización de la marca en UN solo paso atómico: `fetch_max` sube la
    /// marca si procede y devuelve el valor previo; la altura recibida tiene que ser al menos
    /// ese valor previo. Nada de leer, comparar y escribir por separado.
    pub(crate) fn comprobar_altura(&self, altura: usize) -> Result<(), electrum_client::Error> {
        let h = u32::try_from(altura).map_err(|_| {
            electrum_client::Error::Message(format!("altura {altura} fuera de rango"))
        })?;
        let previa = self.marca.fetch_max(h, Ordering::Relaxed);
        if h < previa {
            return Err(electrum_client::Error::Message(format!(
                "el servidor va por detrás: altura {h}, ya se había visto {previa}"
            )));
        }
        Ok(())
    }

    fn comprobar_altura_json(&self, v: &serde_json::Value) -> Result<(), electrum_client::Error> {
        match v.get("height").and_then(|h| h.as_u64()) {
            // Sin pasar por `usize` (truncaría en 32 bits): `u32::try_from` directo.
            Some(h) => match u32::try_from(h) {
                Ok(h) => self.comprobar_altura(h as usize),
                Err(_) => Err(electrum_client::Error::Message(format!(
                    "altura {h} fuera de rango"
                ))),
            },
            None => Err(electrum_client::Error::Message(
                "headers.subscribe sin altura".into(),
            )),
        }
    }
}

impl<E: ElectrumApi> ElectrumApi for Vigilado<E> {
    fn raw_call(
        &self,
        method_name: &str,
        params: impl IntoIterator<Item = Param>,
    ) -> Result<serde_json::Value, electrum_client::Error> {
        let v = self.interno.raw_call(method_name, params)?;
        if method_name == METODO_ALTURA {
            self.comprobar_altura_json(&v)?;
        }
        Ok(v)
    }

    fn batch_call(&self, batch: &Batch) -> Result<Vec<serde_json::Value>, electrum_client::Error> {
        let respuestas = self.interno.batch_call(batch)?;
        for ((metodo, _), v) in batch.iter().zip(respuestas.iter()) {
            if metodo == METODO_ALTURA {
                self.comprobar_altura_json(v)?;
            }
        }
        Ok(respuestas)
    }

    fn block_headers_subscribe_raw(&self) -> Result<RawHeaderNotification, electrum_client::Error> {
        let n = self.interno.block_headers_subscribe_raw()?;
        self.comprobar_altura(n.height)?;
        Ok(n)
    }

    fn block_headers_pop_raw(
        &self,
    ) -> Result<Option<RawHeaderNotification>, electrum_client::Error> {
        let n = self.interno.block_headers_pop_raw()?;
        if let Some(n) = &n {
            self.comprobar_altura(n.height)?;
        }
        Ok(n)
    }

    fn block_header_raw(&self, height: usize) -> Result<Vec<u8>, electrum_client::Error> {
        self.interno.block_header_raw(height)
    }

    fn block_headers(
        &self,
        start_height: usize,
        count: usize,
    ) -> Result<GetHeadersRes, electrum_client::Error> {
        self.interno.block_headers(start_height, count)
    }

    fn estimate_fee(
        &self,
        number: usize,
        mode: Option<EstimationMode>,
    ) -> Result<f64, electrum_client::Error> {
        self.interno.estimate_fee(number, mode)
    }

    fn relay_fee(&self) -> Result<f64, electrum_client::Error> {
        self.interno.relay_fee()
    }

    fn script_subscribe(
        &self,
        script: &Script,
    ) -> Result<Option<ScriptStatus>, electrum_client::Error> {
        self.interno.script_subscribe(script)
    }

    fn batch_script_subscribe<'s, I>(
        &self,
        scripts: I,
    ) -> Result<Vec<Option<ScriptStatus>>, electrum_client::Error>
    where
        I: IntoIterator + Clone,
        I::Item: Borrow<&'s Script>,
    {
        self.interno.batch_script_subscribe(scripts)
    }

    fn script_unsubscribe(&self, script: &Script) -> Result<bool, electrum_client::Error> {
        self.interno.script_unsubscribe(script)
    }

    fn script_pop(&self, script: &Script) -> Result<Option<ScriptStatus>, electrum_client::Error> {
        self.interno.script_pop(script)
    }

    fn script_get_balance(&self, script: &Script) -> Result<GetBalanceRes, electrum_client::Error> {
        self.interno.script_get_balance(script)
    }

    fn batch_script_get_balance<'s, I>(
        &self,
        scripts: I,
    ) -> Result<Vec<GetBalanceRes>, electrum_client::Error>
    where
        I: IntoIterator + Clone,
        I::Item: Borrow<&'s Script>,
    {
        self.interno.batch_script_get_balance(scripts)
    }

    fn script_get_history(
        &self,
        script: &Script,
    ) -> Result<Vec<GetHistoryRes>, electrum_client::Error> {
        self.interno.script_get_history(script)
    }

    fn batch_script_get_history<'s, I>(
        &self,
        scripts: I,
    ) -> Result<Vec<Vec<GetHistoryRes>>, electrum_client::Error>
    where
        I: IntoIterator + Clone,
        I::Item: Borrow<&'s Script>,
    {
        self.interno.batch_script_get_history(scripts)
    }

    fn script_list_unspent(
        &self,
        script: &Script,
    ) -> Result<Vec<ListUnspentRes>, electrum_client::Error> {
        self.interno.script_list_unspent(script)
    }

    fn batch_script_list_unspent<'s, I>(
        &self,
        scripts: I,
    ) -> Result<Vec<Vec<ListUnspentRes>>, electrum_client::Error>
    where
        I: IntoIterator + Clone,
        I::Item: Borrow<&'s Script>,
    {
        self.interno.batch_script_list_unspent(scripts)
    }

    fn transaction_get_raw(&self, txid: &Txid) -> Result<Vec<u8>, electrum_client::Error> {
        self.interno.transaction_get_raw(txid)
    }

    fn batch_transaction_get_raw<'t, I>(
        &self,
        txids: I,
    ) -> Result<Vec<Vec<u8>>, electrum_client::Error>
    where
        I: IntoIterator + Clone,
        I::Item: Borrow<&'t Txid>,
    {
        self.interno.batch_transaction_get_raw(txids)
    }

    fn batch_block_header_raw<I>(&self, heights: I) -> Result<Vec<Vec<u8>>, electrum_client::Error>
    where
        I: IntoIterator + Clone,
        I::Item: Borrow<u32>,
    {
        self.interno.batch_block_header_raw(heights)
    }

    fn batch_estimate_fee<I>(&self, numbers: I) -> Result<Vec<f64>, electrum_client::Error>
    where
        I: IntoIterator + Clone,
        I::Item: Borrow<usize>,
    {
        self.interno.batch_estimate_fee(numbers)
    }

    fn transaction_broadcast_raw(&self, raw_tx: &[u8]) -> Result<Txid, electrum_client::Error> {
        self.interno.transaction_broadcast_raw(raw_tx)
    }

    fn transaction_broadcast_package_raw<T: AsRef<[u8]>>(
        &self,
        raw_txs: &[T],
    ) -> Result<BroadcastPackageRes, electrum_client::Error> {
        self.interno.transaction_broadcast_package_raw(raw_txs)
    }

    fn transaction_get_merkle(
        &self,
        txid: &Txid,
        height: usize,
    ) -> Result<GetMerkleRes, electrum_client::Error> {
        self.interno.transaction_get_merkle(txid, height)
    }

    fn batch_transaction_get_merkle<I>(
        &self,
        txids_and_heights: I,
    ) -> Result<Vec<GetMerkleRes>, electrum_client::Error>
    where
        I: IntoIterator + Clone,
        I::Item: Borrow<(Txid, usize)>,
    {
        self.interno.batch_transaction_get_merkle(txids_and_heights)
    }

    fn txid_from_pos(&self, height: usize, tx_pos: usize) -> Result<Txid, electrum_client::Error> {
        self.interno.txid_from_pos(height, tx_pos)
    }

    fn txid_from_pos_with_merkle(
        &self,
        height: usize,
        tx_pos: usize,
    ) -> Result<TxidFromPosRes, electrum_client::Error> {
        self.interno.txid_from_pos_with_merkle(height, tx_pos)
    }

    fn server_features(&self) -> Result<ServerFeaturesRes, electrum_client::Error> {
        self.interno.server_features()
    }

    fn mempool_get_info(&self) -> Result<MempoolInfoRes, electrum_client::Error> {
        self.interno.mempool_get_info()
    }

    fn ping(&self) -> Result<(), electrum_client::Error> {
        self.interno.ping()
    }

    fn calls_made(&self) -> Result<usize, electrum_client::Error> {
        self.interno.calls_made()
    }
}

/// ¿Es «1.4» o una revisión suya («1.4.N»)? Por componentes, no por prefijo: dos o tres
/// componentes, el primero exactamente «1», el segundo exactamente «4», y el tercero, si lo
/// hay, no vacío y solo dígitos.
pub(crate) fn protocolo_admitido(protocolo: &str) -> bool {
    let partes: Vec<&str> = protocolo.split('.').collect();
    match partes.as_slice() {
        ["1", "4"] => true,
        ["1", "4", rev] => !rev.is_empty() && rev.bytes().all(|b| b.is_ascii_digit()),
        _ => false,
    }
}

/// Lo que una conexión comprobó de su servidor al abrirse (puertas 2 y 4).
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct Validacion {
    pub version_servidor: String,
    pub protocolo: String,
    pub latencia_ms: u64,
    pub altura: u32,
}

/// Una conexión Electrum que ha pasado las cuatro puertas (huella si procede, génesis,
/// protocolo y altura), vigilada durante toda su vida.
pub(crate) struct Conexion {
    cliente: BdkElectrumClient<Vigilado<RawClient<Flujo>>>,
    /// Huella SPKI fijada con la que se abrió (solo `ssl://` con huella).
    huella: Option<[u8; 32]>,
}

impl Conexion {
    /// Conecta y pasa las CUATRO puertas, en este orden: huella (con `ssl://` y huella),
    /// protocolo 1.4 (`server.version`, primer mensaje), génesis de `red` y altura no inferior
    /// a `altura_minima` (0 = nada visto todavía). `proxy`: proxy SOCKS5 `host:puerto` por el
    /// que abrir el transporte (obligatorio para un destino `.onion`); `None` = directo.
    pub fn abrir(
        servidor: &str,
        red: Network,
        huella: Option<&str>,
        espera: Duration,
        altura_minima: u32,
        proxy: Option<&str>,
    ) -> Result<Conexion, Error> {
        Self::abrir_validada(servidor, red, huella, espera, altura_minima, proxy).map(|(c, _)| c)
    }

    /// Como [`Conexion::abrir`], devolviendo además lo que la conexión comprobó.
    pub(crate) fn abrir_validada(
        servidor: &str,
        red: Network,
        huella: Option<&str>,
        espera: Duration,
        altura_minima: u32,
        proxy: Option<&str>,
    ) -> Result<(Conexion, Validacion), Error> {
        let red = network_permitida(red)?;
        let destino = Destino::analizar(servidor)?;
        // 1. transporte e identidad (la huella se comprueba dentro del apretón TLS).
        let (flujo, huella_fijada) = match (destino.esquema, huella) {
            (Esquema::Ssl, Some(h)) => {
                let esperada = analizar_huella(h)?;
                (
                    Flujo::Tls(conectar_pinado(&destino, proxy, esperada, espera)?),
                    Some(esperada),
                )
            }
            (Esquema::Tcp, Some(_)) => {
                return Err(Error::HuellaServidor(
                    "la huella solo se comprueba con ssl://".into(),
                ))
            }
            (Esquema::Ssl, None) => (
                Flujo::Tls(conectar_tls_publico(&destino, proxy, espera)?),
                None,
            ),
            (Esquema::Tcp, None) => (
                Flujo::Plano(conectar_transporte(&destino, proxy, espera)?),
                None,
            ),
        };
        // `RawClient::from` no negocia nada: la única negociación es la del motor, abajo.
        let conexion = Conexion {
            cliente: BdkElectrumClient::new(Vigilado::nuevo(RawClient::from(flujo), altura_minima)),
            huella: huella_fijada,
        };
        // 2. `server.version`, PRIMER mensaje Electrum de la sesión.
        let (version_servidor, protocolo, latencia_ms) = conexion.negociar_version()?;
        if !protocolo_admitido(&protocolo) {
            return Err(Error::Red(format!(
                "protocolo Electrum {protocolo} no admitido (hace falta 1.4)"
            )));
        }
        conexion.comprobar_genesis(red)?; // 3. red correcta
                                          // 4. altura que no baja: la comprueba el vigilante en esta misma respuesta (y en todas
                                          // las siguientes de la sesión, contra la marca máxima); aquí solo se recoge el valor.
        let altura = conexion.altura()?;
        Ok((
            conexion,
            Validacion {
                version_servidor,
                protocolo,
                latencia_ms,
                altura,
            },
        ))
    }

    fn comprobar_genesis(&self, red: Network) -> Result<(), Error> {
        let f = self.cliente.inner.server_features().map_err(red_err)?;
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

    /// Altura que anuncia el servidor, ya comprobada por el vigilante contra la mínima.
    pub fn altura(&self) -> Result<u32, Error> {
        let h = self
            .cliente
            .inner
            .block_headers_subscribe()
            .map_err(red_err)?;
        Ok(h.height as u32)
    }

    pub fn comision_btc_kvb(&self, bloques: usize) -> Result<f64, Error> {
        let e = self
            .cliente
            .inner
            .estimate_fee(bloques, None)
            .map_err(red_err)?;
        if e > 0.0 {
            Ok(e)
        } else {
            self.cliente.inner.relay_fee().map_err(red_err)
        }
    }

    pub fn difundir(
        &self,
        tx: &bitcoin::Transaction,
    ) -> Result<bitcoin::Txid, electrum_client::Error> {
        self.cliente.inner.transaction_broadcast(tx)
    }

    pub fn escaneo_completo(
        &self,
        txs: impl Iterator<Item = Arc<bitcoin::Transaction>>,
        peticion: impl Into<bdk_wallet::chain::spk_client::FullScanRequest<bdk_wallet::KeychainKind>>,
        gap: usize,
        lote: usize,
    ) -> Result<bdk_wallet::chain::spk_client::FullScanResponse<bdk_wallet::KeychainKind>, Error>
    {
        self.cliente.populate_tx_cache(txs);
        self.cliente
            .full_scan(peticion, gap, lote, true)
            .map_err(red_err)
    }

    pub fn sincronizacion(
        &self,
        txs: impl Iterator<Item = Arc<bitcoin::Transaction>>,
        peticion: impl Into<bdk_wallet::chain::spk_client::SyncRequest<(bdk_wallet::KeychainKind, u32)>>,
        lote: usize,
    ) -> Result<bdk_wallet::chain::spk_client::SyncResponse, Error> {
        self.cliente.populate_tx_cache(txs);
        self.cliente.sync(peticion, lote, true).map_err(red_err)
    }

    /// La ÚNICA negociación `server.version` de la sesión: propone «1.4» a secas (no un rango)
    /// y devuelve (versión del servidor, protocolo negociado, latencia de la llamada). Lo
    /// llama `abrir_validada` una vez; un segundo `server.version` sería rechazado por un
    /// servidor estricto.
    fn negociar_version(&self) -> Result<(String, String, u64), Error> {
        let t0 = Instant::now();
        let v = self
            .cliente
            .inner
            .raw_call(
                "server.version",
                vec![
                    Param::String("x39-wallet".into()),
                    Param::String("1.4".into()),
                ],
            )
            .map_err(red_err)?;
        let ms = t0.elapsed().as_millis() as u64;
        let par = v
            .as_array()
            .ok_or_else(|| Error::Red("server.version: respuesta inesperada".into()))?;
        let srv = par
            .first()
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .to_string();
        let proto = par
            .get(1)
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .to_string();
        Ok((srv, proto, ms))
    }

    pub fn huella(&self) -> Option<[u8; 32]> {
        self.huella
    }
}

fn instalar_proveedor() -> Arc<rustls::crypto::CryptoProvider> {
    if rustls::crypto::CryptoProvider::get_default().is_none() {
        let _ = rustls::crypto::CryptoProvider::install_default(
            rustls::crypto::aws_lc_rs::default_provider(),
        );
    }
    rustls::crypto::CryptoProvider::get_default()
        .cloned()
        .unwrap_or_else(|| Arc::new(rustls::crypto::aws_lc_rs::default_provider()))
}

/// `host:puerto` del proxy SOCKS5 (p. ej. Orbot en `127.0.0.1:9050`).
fn analizar_proxy(p: &str) -> Result<(String, u16), Error> {
    let (host, puerto) = p
        .trim()
        .rsplit_once(':')
        .ok_or_else(|| Error::Red("proxy SOCKS5 sin puerto: usa host:puerto".into()))?;
    let puerto: u16 = puerto
        .parse()
        .map_err(|_| Error::Red("puerto del proxy SOCKS5 inválido".into()))?;
    let host = host.trim_matches(|c| c == '[' || c == ']');
    if host.is_empty() {
        return Err(Error::Red("proxy SOCKS5 sin host".into()));
    }
    Ok((host.to_string(), puerto))
}

/// Transporte TCP hasta el destino: directo, o a través del proxy SOCKS5 si se da uno. Un
/// destino `.onion` sin proxy se rechaza AQUÍ, antes de abrir ningún socket ni resolver nada.
fn conectar_transporte(
    destino: &Destino,
    proxy: Option<&str>,
    espera: Duration,
) -> Result<TcpStream, Error> {
    let proxy = proxy.map(str::trim).filter(|p| !p.is_empty());
    match proxy {
        None if destino.es_onion() => Err(Error::Red(
            "un servidor .onion solo se alcanza por un proxy SOCKS5 (Orbot): activa el proxy en los ajustes del nodo"
                .into(),
        )),
        None => conectar_tcp(&destino.host, destino.puerto, espera),
        Some(p) => {
            let (ph, pp) = analizar_proxy(p)?;
            let mut s = conectar_tcp(&ph, pp, espera)
                .map_err(|e| Error::Red(format!("proxy SOCKS5 {ph}:{pp}: {e}")))?;
            socks5_conectar(&mut s, &destino.host, destino.puerto)?;
            Ok(s)
        }
    }
}

/// CONNECT de SOCKS5 (RFC 1928) sin autenticación. El nombre del destino viaja tal cual
/// (ATYP 3) salvo que sea una IP literal: quien resuelve es el proxy, nunca este proceso.
fn socks5_conectar(s: &mut TcpStream, host: &str, puerto: u16) -> Result<(), Error> {
    fn err(m: impl std::fmt::Display) -> Error {
        Error::Red(format!("proxy SOCKS5: {m}"))
    }
    s.write_all(&[0x05, 0x01, 0x00]).map_err(err)?;
    let mut saludo = [0u8; 2];
    s.read_exact(&mut saludo)
        .map_err(|e| err(format!("sin respuesta al saludo: {e}")))?;
    if saludo != [0x05, 0x00] {
        return Err(err("el proxy no admite conexión sin autenticación"));
    }
    let mut peticion = vec![0x05, 0x01, 0x00];
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => {
            peticion.push(0x01);
            peticion.extend_from_slice(&ip.octets());
        }
        Ok(IpAddr::V6(ip)) => {
            peticion.push(0x04);
            peticion.extend_from_slice(&ip.octets());
        }
        Err(_) => {
            if host.is_empty() || host.len() > 255 {
                return Err(err("nombre de servidor vacío o demasiado largo"));
            }
            peticion.push(0x03);
            peticion.push(host.len() as u8);
            peticion.extend_from_slice(host.as_bytes());
        }
    }
    peticion.extend_from_slice(&puerto.to_be_bytes());
    s.write_all(&peticion).map_err(err)?;
    let mut cab = [0u8; 4];
    s.read_exact(&mut cab)
        .map_err(|e| err(format!("sin respuesta a la conexión: {e}")))?;
    if cab[0] != 0x05 {
        return Err(err("la respuesta no es SOCKS5"));
    }
    if cab[1] != 0x00 {
        let motivo = match cab[1] {
            0x01 => "fallo general del proxy",
            0x02 => "conexión no permitida por el proxy",
            0x03 => "red inalcanzable",
            0x04 => "servidor inalcanzable (¿la dirección es correcta y está en línea?)",
            0x05 => "conexión rechazada por el servidor",
            0x06 => "tiempo agotado",
            0x07 => "orden no admitida por el proxy",
            0x08 => "tipo de dirección no admitido por el proxy",
            _ => "error desconocido",
        };
        return Err(err(format!(
            "no pudo conectar con {host}:{puerto}: {motivo}"
        )));
    }
    // Dirección ligada en la respuesta: se consume y se descarta.
    let resto = match cab[3] {
        0x01 => 4 + 2,
        0x04 => 16 + 2,
        0x03 => {
            let mut l = [0u8; 1];
            s.read_exact(&mut l).map_err(err)?;
            l[0] as usize + 2
        }
        _ => return Err(err("tipo de dirección desconocido en la respuesta")),
    };
    let mut ligada = vec![0u8; resto];
    s.read_exact(&mut ligada).map_err(err)?;
    Ok(())
}

/// TCP con tiempo límite de conexión, lectura y escritura.
fn conectar_tcp(host: &str, puerto: u16, espera: Duration) -> Result<TcpStream, Error> {
    use std::net::ToSocketAddrs;
    let direccion = format!("{host}:{puerto}");
    let mut ultimo = None;
    let mut flujo = None;
    for addr in direccion
        .to_socket_addrs()
        .map_err(|e| Error::Red(format!("resolviendo {direccion}: {e}")))?
    {
        match TcpStream::connect_timeout(&addr, espera) {
            Ok(s) => {
                flujo = Some(s);
                break;
            }
            Err(e) => ultimo = Some(e),
        }
    }
    let tcp = flujo.ok_or_else(|| {
        Error::Red(format!(
            "no se pudo conectar con {direccion}: {}",
            ultimo.map(|e| e.to_string()).unwrap_or_default()
        ))
    })?;
    tcp.set_read_timeout(Some(espera)).map_err(red_io)?;
    tcp.set_write_timeout(Some(espera)).map_err(red_io)?;
    Ok(tcp)
}

/// Raíces públicas (Mozilla, vía `webpki-roots`, la misma versión que trae electrum-client).
fn raices_publicas() -> RootCertStore {
    webpki_roots::TLS_SERVER_ROOTS
        .iter()
        .map(|t| TrustAnchor {
            subject: Der::from_slice(t.subject),
            subject_public_key_info: Der::from_slice(t.spki),
            name_constraints: t.name_constraints.map(Der::from_slice),
        })
        .collect()
}

/// `ssl://` SIN huella: certificado validado contra las raíces públicas (CA, nombre y
/// caducidad), como cualquier cliente TLS (servidor público, o nodo propio con certificado de
/// una CA reconocida).
fn conectar_tls_publico(
    destino: &Destino,
    proxy: Option<&str>,
    espera: Duration,
) -> Result<FlujoTls, Error> {
    let proveedor = instalar_proveedor();
    let config = ClientConfig::builder_with_provider(proveedor)
        .with_safe_default_protocol_versions()
        .map_err(|e| Error::Red(format!("tls: {e}")))?
        .with_root_certificates(raices_publicas())
        .with_no_client_auth();
    let nombre = ServerName::try_from(destino.host.clone())
        .map_err(|_| Error::Red(format!("nombre de servidor inválido: {}", destino.host)))?;
    let sesion = ClientConnection::new(Arc::new(config), nombre)
        .map_err(|e| Error::Red(format!("tls: {e}")))?;
    let mut flujo = StreamOwned::new(sesion, conectar_transporte(destino, proxy, espera)?);
    // Forzar el apretón de manos ahora: un certificado no reconocido falla aquí, antes de
    // que viaje ningún dato de aplicación.
    while flujo.conn.is_handshaking() {
        flujo
            .conn
            .complete_io(&mut flujo.sock)
            .map_err(|e| Error::Red(format!("tls: {e}")))?;
    }
    Ok(flujo)
}

/// TLS propio con la huella fijada: el apretón de manos falla si el SPKI no es el esperado.
fn conectar_pinado(
    destino: &Destino,
    proxy: Option<&str>,
    esperada: [u8; 32],
    espera: Duration,
) -> Result<FlujoTls, Error> {
    conectar_observando(destino, proxy, Some(esperada), espera).map(|(f, _)| f)
}

/// Apretón de manos TLS con el verificador propio: con `esperada` fija la huella (falla si el
/// SPKI no es el esperado); sin ella solo observa. Devuelve también el verificador, que guarda
/// la huella vista.
fn conectar_observando(
    destino: &Destino,
    proxy: Option<&str>,
    esperada: Option<[u8; 32]>,
    espera: Duration,
) -> Result<(FlujoTls, Arc<VerificadorHuella>), Error> {
    let proveedor = instalar_proveedor();
    let verificador = Arc::new(VerificadorHuella {
        esperada,
        vista: Mutex::new(None),
        proveedor: proveedor.clone(),
    });
    let config = ClientConfig::builder_with_provider(proveedor)
        .with_safe_default_protocol_versions()
        .map_err(|e| Error::Red(format!("tls: {e}")))?
        .dangerous()
        .with_custom_certificate_verifier(verificador.clone())
        .with_no_client_auth();
    let nombre = ServerName::try_from(destino.host.clone())
        .map_err(|_| Error::Red(format!("nombre de servidor inválido: {}", destino.host)))?;
    let sesion = ClientConnection::new(Arc::new(config), nombre)
        .map_err(|e| Error::Red(format!("tls: {e}")))?;
    let mut flujo = StreamOwned::new(sesion, conectar_transporte(destino, proxy, espera)?);
    // Forzar el apretón de manos ahora, para que una huella distinta falle aquí.
    while flujo.conn.is_handshaking() {
        if let Err(e) = flujo.conn.complete_io(&mut flujo.sock) {
            let vista = verificador
                .vista
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .map(|v| v.to_lower_hex_string());
            return Err(match (vista, esperada) {
                (Some(v), Some(esp)) if v != esp.to_lower_hex_string() => Error::HuellaServidor(
                    format!("el servidor presenta la huella {v}, no la esperada"),
                ),
                _ => Error::Red(format!("tls: {e}")),
            });
        }
    }
    Ok((flujo, verificador))
}

/// Huella SPKI (SHA-256, hex en minúsculas) del certificado que presenta un servidor `ssl://`.
/// Abre SOLO el apretón de manos TLS, sin validar el certificado (ni CA ni huella) y sin enviar
/// ningún mensaje Electrum; cierra y devuelve la huella. Es para MOSTRARLA al usuario antes de
/// que decida fijarla («certificado propio»): nada de lo que devuelve se acepta como válido por
/// sí mismo. `proxy`: como en [`Conexion::abrir`].
pub fn huella_servidor(servidor: &str, proxy: Option<&str>) -> Result<String, Error> {
    let destino = Destino::analizar(servidor)?;
    if destino.esquema != Esquema::Ssl {
        return Err(Error::Red(
            "la huella del certificado solo existe con ssl://".into(),
        ));
    }
    let (flujo, verificador) = conectar_observando(&destino, proxy, None, crate::ESPERA_RED)?;
    drop(flujo);
    let vista = *verificador.vista.lock().unwrap_or_else(|p| p.into_inner());
    vista
        .map(|v| v.to_lower_hex_string())
        .ok_or_else(|| Error::Red("el servidor no presentó certificado".into()))
}

#[cfg(test)]
pub(crate) fn instalar_proveedor_pruebas() {
    instalar_proveedor();
}

fn red_io(e: std::io::Error) -> Error {
    Error::Red(e.to_string())
}

/// Sonda del nodo: abre una conexión como cualquier otra operación (las cuatro puertas, en el
/// orden de la cabecera) e informa de lo que vio. `altura_minima`: la última altura que el
/// móvil ya vio (0 si ninguna). `proxy`: proxy SOCKS5, como en [`Conexion::abrir`]. No
/// sincroniza nada.
pub fn comprobar_nodo(
    red: Red,
    servidor: &str,
    huella: Option<&str>,
    altura_minima: u32,
    proxy: Option<&str>,
) -> Result<NodoInfo, Error> {
    let red = red.permitida()?;
    let destino = Destino::analizar(servidor)?;
    let (c, v) = Conexion::abrir_validada(
        servidor,
        red.network(),
        huella,
        ESPERA_RED,
        altura_minima,
        proxy,
    )?;
    Ok(NodoInfo {
        servidor: servidor.to_string(),
        version_servidor: v.version_servidor,
        protocolo: v.protocolo,
        altura: v.altura,
        latencia_ms: v.latencia_ms,
        cifrado: destino.esquema == Esquema::Ssl,
        huella_vista: c
            .huella()
            .map(|h| h.to_lower_hex_string())
            .unwrap_or_default(),
        red,
    })
}
