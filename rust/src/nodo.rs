//! Conexión con el nodo del usuario: TLS con huella fijada, comprobaciones en orden.
//!
//! Orden REAL de las comprobaciones en cada conexión y reconexión (`Conexion::abrir` y
//! `comprobar_nodo`):
//! 1. identidad del servidor (solo con `ssl://` y huella): el SPKI del certificado debe tener
//!    la huella esperada; se comprueba DENTRO del apretón de manos TLS, antes de que viaje
//!    ningún dato de aplicación. Sin huella y con `ssl://`, el certificado se valida contra
//!    las raíces públicas (CA, nombre y caducidad); CON huella no se valida nada más que el
//!    SPKI: ni CA, ni nombre, ni caducidad (la huella sustituye a la CA).
//! 2. red correcta (`genesis_hash` del servidor = génesis de la red pedida),
//! 3. `server.version`: protocolo Electrum 1.4 y latencia,
//! 4. altura no inferior a la última conocida.
//!
//! La huella viaja en TODAS las operaciones de red (escaneo, sincronización, difusión,
//! altura, comisión, comprobación): no hay ninguna que abra conexión sin ella.
//!
//! El cliente «normal» (tcp:// y ssl:// con raíces públicas) se crea con `retry(0)`: un
//! reintento interno del cliente Electrum reabriría el socket y repetiría el RPC SIN volver a
//! comprobar la red. Cualquier reintento lo hace la capa de arriba repitiendo TODA la
//! validación (apertura de `Conexion` nueva, génesis incluido).
//!
//! Política «ssl:// exige huella salvo el servidor público de signet»: la impone la capa
//! Kotlin (`ConfigNodo.invalida()`), no el motor; el motor acepta `ssl://` sin huella
//! validando contra las raíces públicas. Con `tcp://` no hay cifrado: se informa
//! (`cifrado = false`) y la capa de arriba avisa.

use crate::{network_permitida, red_err, Error, Red, ESPERA_RED};
use bdk_electrum::electrum_client::raw_client::RawClient;
use bdk_electrum::electrum_client::{self, Client, ConfigBuilder, ElectrumApi};
use bdk_electrum::BdkElectrumClient;
use bitcoin::blockdata::constants::genesis_block;
use bitcoin::hashes::{sha256, Hash};
use bitcoin::hex::{DisplayHex, FromHex};
use bitcoin::{BlockHash, Network};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, ClientConnection, DigitallySignedStruct, StreamOwned};
use std::net::TcpStream;
use std::str::FromStr;
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
/// Guarda la huella vista para poder informarla.
#[derive(Debug)]
struct VerificadorHuella {
    esperada: [u8; 32],
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
        if vista != self.esperada {
            return Err(rustls::Error::General(
                "huella del servidor distinta de la esperada".into(),
            ));
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

/// Una conexión Electrum ya comprobada (red y, si procede, huella).
pub(crate) enum Conexion {
    /// `tcp://`, o `ssl://` validado contra las raíces públicas (electrum-client).
    Normal(BdkElectrumClient<Client>),
    /// `ssl://` con huella SPKI fijada (TLS propio).
    Pinada(BdkElectrumClient<RawClient<FlujoTls>>, [u8; 32]),
}

macro_rules! con_api {
    ($self:expr, |$c:ident| $e:expr) => {
        match $self {
            Conexion::Normal($c) => $e,
            Conexion::Pinada($c, _) => $e,
        }
    };
}

impl Conexion {
    /// Conecta y comprueba la red del servidor. `huella` solo tiene sentido con `ssl://`.
    pub fn abrir(
        servidor: &str,
        red: Network,
        huella: Option<&str>,
        espera: Duration,
    ) -> Result<Conexion, Error> {
        let red = network_permitida(red)?;
        let destino = Destino::analizar(servidor)?;
        let conexion = match (destino.esquema, huella) {
            (Esquema::Ssl, Some(h)) => {
                let esperada = analizar_huella(h)?;
                let cliente = conectar_pinado(&destino, esperada, espera)?;
                Conexion::Pinada(BdkElectrumClient::new(cliente), esperada)
            }
            (Esquema::Tcp, Some(_)) => {
                return Err(Error::HuellaServidor(
                    "la huella solo se comprueba con ssl://".into(),
                ))
            }
            _ => {
                // retry(0): sin reintentos internos que salten la comprobación de red.
                let cfg = ConfigBuilder::new().timeout(Some(espera)).retry(0).build();
                let c = Client::from_config(servidor, cfg).map_err(red_err)?;
                Conexion::Normal(BdkElectrumClient::new(c))
            }
        };
        conexion.comprobar_genesis(red)?;
        Ok(conexion)
    }

    fn comprobar_genesis(&self, red: Network) -> Result<(), Error> {
        let f = con_api!(self, |c| c.inner.server_features()).map_err(red_err)?;
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

    pub fn altura(&self) -> Result<u32, Error> {
        let h = con_api!(self, |c| c.inner.block_headers_subscribe()).map_err(red_err)?;
        Ok(h.height as u32)
    }

    pub fn comision_btc_kvb(&self, bloques: usize) -> Result<f64, Error> {
        let e = con_api!(self, |c| c.inner.estimate_fee(bloques, None)).map_err(red_err)?;
        if e > 0.0 {
            Ok(e)
        } else {
            con_api!(self, |c| c.inner.relay_fee()).map_err(red_err)
        }
    }

    pub fn difundir(
        &self,
        tx: &bitcoin::Transaction,
    ) -> Result<bitcoin::Txid, electrum_client::Error> {
        con_api!(self, |c| c.inner.transaction_broadcast(tx))
    }

    pub fn escaneo_completo(
        &self,
        txs: impl Iterator<Item = Arc<bitcoin::Transaction>>,
        peticion: impl Into<bdk_wallet::chain::spk_client::FullScanRequest<bdk_wallet::KeychainKind>>,
        gap: usize,
        lote: usize,
    ) -> Result<bdk_wallet::chain::spk_client::FullScanResponse<bdk_wallet::KeychainKind>, Error>
    {
        con_api!(self, |c| {
            c.populate_tx_cache(txs);
            c.full_scan(peticion, gap, lote, true).map_err(red_err)
        })
    }

    pub fn sincronizacion(
        &self,
        txs: impl Iterator<Item = Arc<bitcoin::Transaction>>,
        peticion: impl Into<bdk_wallet::chain::spk_client::SyncRequest<(bdk_wallet::KeychainKind, u32)>>,
        lote: usize,
    ) -> Result<bdk_wallet::chain::spk_client::SyncResponse, Error> {
        con_api!(self, |c| {
            c.populate_tx_cache(txs);
            c.sync(peticion, lote, true).map_err(red_err)
        })
    }

    /// `server.version`: (versión del servidor, protocolo) y latencia de la llamada.
    pub fn version(&self) -> Result<(String, String, u64), Error> {
        let t0 = Instant::now();
        let v = con_api!(self, |c| c.inner.raw_call(
            "server.version",
            vec![
                electrum_client::Param::String("x39-wallet".into()),
                electrum_client::Param::String("1.4".into())
            ]
        ))
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
        match self {
            Conexion::Normal(_) => None,
            Conexion::Pinada(_, h) => Some(*h),
        }
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

/// TLS propio con la huella fijada: el apretón de manos falla si el SPKI no es el esperado.
fn conectar_pinado(
    destino: &Destino,
    esperada: [u8; 32],
    espera: Duration,
) -> Result<RawClient<FlujoTls>, Error> {
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
    let direccion = format!("{}:{}", destino.host, destino.puerto);
    let tcp = {
        use std::net::ToSocketAddrs;
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
        flujo.ok_or_else(|| {
            Error::Red(format!(
                "no se pudo conectar con {direccion}: {}",
                ultimo.map(|e| e.to_string()).unwrap_or_default()
            ))
        })?
    };
    tcp.set_read_timeout(Some(espera)).map_err(red_io)?;
    tcp.set_write_timeout(Some(espera)).map_err(red_io)?;
    let mut flujo = StreamOwned::new(sesion, tcp);
    // Forzar el apretón de manos ahora, para que una huella distinta falle aquí.
    while flujo.conn.is_handshaking() {
        if let Err(e) = flujo.conn.complete_io(&mut flujo.sock) {
            let vista = verificador
                .vista
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .map(|v| v.to_lower_hex_string());
            return Err(match vista {
                Some(v) if v != esperada.to_lower_hex_string() => Error::HuellaServidor(format!(
                    "el servidor presenta la huella {v}, no la esperada"
                )),
                _ => Error::Red(format!("tls: {e}")),
            });
        }
    }
    let cliente: RawClient<FlujoTls> = RawClient::from(flujo);
    Ok(cliente)
}

#[cfg(test)]
pub(crate) fn instalar_proveedor_pruebas() {
    instalar_proveedor();
}

fn red_io(e: std::io::Error) -> Error {
    Error::Red(e.to_string())
}

/// Comprobación completa del nodo, en el orden de la cabecera. `altura_minima`: la última
/// altura que el móvil ya vio (0 si ninguna). No sincroniza nada.
pub fn comprobar_nodo(
    red: Red,
    servidor: &str,
    huella: Option<&str>,
    altura_minima: u32,
) -> Result<NodoInfo, Error> {
    let red = red.permitida()?;
    let destino = Destino::analizar(servidor)?;
    let c = Conexion::abrir(servidor, red.network(), huella, ESPERA_RED)?; // 1. huella/TLS; 2. génesis
    let (version_servidor, protocolo, latencia_ms) = c.version()?; // 3. protocolo y latencia
    if !protocolo.starts_with("1.4") {
        return Err(Error::Red(format!(
            "protocolo Electrum {protocolo} no admitido (hace falta 1.4)"
        )));
    }
    let altura = c.altura()?; // 4. altura
    if altura < altura_minima {
        return Err(Error::Red(format!(
            "el servidor va por detrás: altura {altura}, ya se había visto {altura_minima}"
        )));
    }
    Ok(NodoInfo {
        servidor: servidor.to_string(),
        version_servidor,
        protocolo,
        altura,
        latencia_ms,
        cifrado: destino.esquema == Esquema::Ssl,
        huella_vista: c
            .huella()
            .map(|h| h.to_lower_hex_string())
            .unwrap_or_default(),
        red,
    })
}
