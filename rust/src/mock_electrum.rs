//! Servidor Electrum SIMULADO para las pruebas (solo `cfg(test)`).
//!
//! Habla el protocolo Electrum (JSON-RPC por líneas) sobre TCP en la interfaz local,
//! con una cadena falsa en memoria (génesis real de la red elegida + bloques inventados,
//! sin prueba de trabajo). Permite simular, al difundir: aceptación, rechazo explícito
//! y silencio (sin respuesta, para provocar el estado de difusión incierta).

use bitcoin::block::{Header, Version as VersionBloque};
use bitcoin::blockdata::constants::genesis_block;
use bitcoin::hashes::{sha256, Hash};
use bitcoin::hex::DisplayHex;
use bitcoin::{consensus, Network, ScriptBuf, Transaction, TxMerkleNode, Txid};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

#[derive(Clone, Debug)]
pub enum Difusion {
    Aceptar,
    /// Error de protocolo con ese código y mensaje.
    Rechazar(i64, String),
    /// No responder (la conexión se queda muda ese tiempo).
    Colgar(Duration),
    /// Al recibir la transacción, leer ese fichero y guardar una copia de su contenido en
    /// `capturas` (para comprobar qué había en disco ANTES de responder); después seguir
    /// con el modo indicado.
    LeerAlRecibir(std::path::PathBuf, Box<Difusion>),
}

/// Comportamiento «malo» que el simulado aplica SOLO a algunas conexiones (por su
/// identificador), para distinguir «la operación validó su propia conexión» de «se comprobó
/// una conexión y se operó por otra».
#[derive(Clone, Debug)]
pub enum Maldad {
    /// Anuncia este protocolo en `server.version` y `server.features`.
    Protocolo(String),
    /// Presenta la cadena truncada a esta altura (como un servidor que ha retrocedido).
    AlturaTope(u32),
}

pub struct Estado {
    pub cabeceras: Vec<Header>,
    pub txs: HashMap<Txid, Transaction>,
    /// scripthash Electrum (hex) → [(txid, altura)]; altura 0 = mempool.
    pub historia: HashMap<String, Vec<(Txid, i64)>>,
    pub difusion: Difusion,
    pub difundidas: Vec<Txid>,
    /// Protocolo Electrum que anuncia el servidor (`server.version` y `server.features`).
    /// Por defecto 1.4; las pruebas de la puerta del nodo lo cambian para simular un
    /// servidor que habla otro protocolo.
    pub protocolo: String,
    /// Copias de ficheros leídas con `Difusion::LeerAlRecibir`.
    pub capturas: Vec<String>,
    /// Conexiones abiertas (para cortarlas al parar).
    pub abiertas: Vec<TcpStream>,
    /// Veces que se RECIBIÓ `blockchain.transaction.broadcast`, se acepte, se rechace o se
    /// cuelgue después: acredita que la transacción llegó al servidor.
    pub recibidas: u64,
    /// Identificador de la próxima conexión que se acepte (empiezan en 1, en orden).
    pub siguiente_conexion: u64,
    /// Por conexión aceptada: identificador y secuencia de métodos pedidos, en orden.
    pub conexiones: Vec<(u64, Vec<String>)>,
    /// Maldad aplicada solo a las conexiones con identificador ≥ el primero del par.
    pub maldad_desde: Option<(u64, Maldad)>,
    /// Altura que anuncia `blockchain.headers.subscribe` según el ORDEN de la petición dentro
    /// de cada conexión: la n-ésima petición de una conexión recibe el n-ésimo valor (el
    /// último se repite). Simula un servidor que retrocede entre dos respuestas de la misma
    /// sesión (3→1, 3→1→3).
    pub alturas_por_respuesta: Option<Vec<u32>>,
    /// Servidor estricto: un segundo `server.version` en la misma conexión es un error.
    pub estricto: bool,
    /// Si está, `server.version` responde esta versión negociada, aunque `server.features`
    /// siga anunciando `protocolo` como mínimo y máximo.
    pub protocolo_negociado: Option<String>,
    /// Negociar como un servidor real: responder la versión que propone el cliente (si propone
    /// un rango, la más alta). Descubre a un cliente que proponga 1.4–1.6 en vez de 1.4.
    pub negociar_segun_cliente: bool,
    /// Servidor que exige que `server.version` sea el PRIMER mensaje de la sesión (como manda
    /// la especificación): cualquier otro RPC anterior a la negociación es un error.
    pub negociacion_primero: bool,
}

pub struct Simulado {
    /// `tcp://<local>:<puerto>` (o `ssl://localhost:<puerto>` en modo TLS), para pasar como servidor.
    pub url: String,
    pub estado: Arc<Mutex<Estado>>,
    parar: Arc<AtomicBool>,
    puerto: u16,
    /// Certificado DER del servidor en modo TLS.
    pub cert_der: Option<Vec<u8>>,
}

pub fn scripthash(spk: &ScriptBuf) -> String {
    let mut h = sha256::Hash::hash(spk.as_bytes()).to_byte_array();
    h.reverse();
    h.to_lower_hex_string()
}

impl Simulado {
    fn estado_inicial(red: Network) -> Arc<Mutex<Estado>> {
        Arc::new(Mutex::new(Estado {
            cabeceras: vec![genesis_block(red).header],
            txs: HashMap::new(),
            historia: HashMap::new(),
            difusion: Difusion::Aceptar,
            difundidas: Vec::new(),
            protocolo: "1.4".to_string(),
            capturas: Vec::new(),
            abiertas: Vec::new(),
            recibidas: 0,
            siguiente_conexion: 1,
            conexiones: Vec::new(),
            maldad_desde: None,
            alturas_por_respuesta: None,
            estricto: false,
            protocolo_negociado: None,
            negociar_segun_cliente: false,
            negociacion_primero: false,
        }))
    }

    /// Servidor sin cifrar (`tcp://`) en un puerto libre.
    pub fn arrancar(red: Network) -> Simulado {
        Self::arrancar_en(0, red)
    }

    /// Servidor sin cifrar en un puerto concreto (0 = libre): permite «volver» en la misma
    /// dirección, incluso con otra red (para probar que se vuelve a comprobar el génesis).
    pub fn arrancar_en(puerto_pedido: u16, red: Network) -> Simulado {
        let escucha =
            TcpListener::bind((Ipv4Addr::LOCALHOST, puerto_pedido)).expect("puerto local");
        let puerto = escucha.local_addr().unwrap().port();
        let estado = Self::estado_inicial(red);
        let parar = Arc::new(AtomicBool::new(false));
        let (e2, p2) = (estado.clone(), parar.clone());
        std::thread::spawn(move || {
            for s in escucha.incoming().flatten() {
                if p2.load(Ordering::SeqCst) {
                    break;
                }
                if let Ok(copia) = s.try_clone() {
                    e2.lock().unwrap().abiertas.push(copia);
                }
                let id = nueva_conexion(&e2);
                let e = e2.clone();
                std::thread::spawn(move || atender(s, id, e));
            }
        });
        Simulado {
            url: format!("tcp://{}:{puerto}", Ipv4Addr::LOCALHOST),
            estado,
            parar,
            puerto,
            cert_der: None,
        }
    }

    /// Servidor TLS (`ssl://localhost:puerto`) con un certificado autofirmado efímero
    /// generado con el `openssl` de la máquina (nombre `localhost`).
    pub fn arrancar_tls(red: Network) -> Simulado {
        Self::arrancar_tls_en(0, red)
    }

    /// Servidor TLS en un puerto concreto (0 = libre). Cada arranque genera un certificado
    /// NUEVO: «volver» en la misma dirección con otro certificado simula un servidor
    /// reinstalado o suplantado, cuya huella SPKI ya no es la fijada.
    pub fn arrancar_tls_en(puerto_pedido: u16, red: Network) -> Simulado {
        use rustls::pki_types::pem::PemObject;
        use rustls::pki_types::{CertificateDer, PrivateKeyDer};
        let dir = tempfile::tempdir().expect("dir temporal");
        let (clave, cert) = (dir.path().join("clave.pem"), dir.path().join("cert.pem"));
        let salida = std::process::Command::new("openssl")
            .args([
                "req",
                "-x509",
                "-newkey",
                "ec",
                "-pkeyopt",
                "ec_paramgen_curve:prime256v1",
                "-nodes",
                "-days",
                "2",
            ])
            .args([
                "-subj",
                "/CN=localhost",
                "-addext",
                "subjectAltName=DNS:localhost",
            ])
            .arg("-keyout")
            .arg(&clave)
            .arg("-out")
            .arg(&cert)
            .output()
            .expect("openssl disponible");
        assert!(
            salida.status.success(),
            "openssl: {}",
            String::from_utf8_lossy(&salida.stderr)
        );
        let cert_der = CertificateDer::from_pem_file(&cert).expect("cert pem");
        let clave_der = PrivateKeyDer::from_pem_file(&clave).expect("clave pem");
        crate::nodo::instalar_proveedor_pruebas();
        let config = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert_der.clone()], clave_der)
            .expect("config tls");
        let config = Arc::new(config);
        let escucha =
            TcpListener::bind((Ipv4Addr::LOCALHOST, puerto_pedido)).expect("puerto local");
        let puerto = escucha.local_addr().unwrap().port();
        let estado = Self::estado_inicial(red);
        let parar = Arc::new(AtomicBool::new(false));
        let (e2, p2) = (estado.clone(), parar.clone());
        std::thread::spawn(move || {
            for s in escucha.incoming().flatten() {
                if p2.load(Ordering::SeqCst) {
                    break;
                }
                let id = nueva_conexion(&e2);
                let e = e2.clone();
                let cfg = config.clone();
                std::thread::spawn(move || {
                    let Ok(conn) = rustls::ServerConnection::new(cfg) else {
                        return;
                    };
                    let flujo = rustls::StreamOwned::new(conn, s);
                    atender(flujo, id, e);
                });
            }
        });
        Simulado {
            url: format!("ssl://localhost:{puerto}"),
            estado,
            parar,
            puerto,
            cert_der: Some(cert_der.to_vec()),
        }
    }

    /// Huella SPKI (SHA-256, hex) del certificado del servidor TLS.
    pub fn huella(&self) -> String {
        use bitcoin::hex::DisplayHex;
        let der = rustls::pki_types::CertificateDer::from(self.cert_der.clone().expect("modo TLS"));
        crate::nodo::huella_spki(&der)
            .expect("spki")
            .to_lower_hex_string()
    }

    /// Corte del servidor: deja de aceptar conexiones y corta las abiertas.
    pub fn parar(&self) {
        self.parar.store(true, Ordering::SeqCst);
        for s in self.estado.lock().unwrap().abiertas.drain(..) {
            let _ = s.shutdown(std::net::Shutdown::Both);
        }
        // Una conexión vacía para desbloquear el accept y que el hilo salga y cierre el puerto.
        let _ = TcpStream::connect((Ipv4Addr::LOCALHOST, self.puerto));
        std::thread::sleep(Duration::from_millis(150));
    }

    pub fn puerto(&self) -> u16 {
        self.puerto
    }

    pub fn fijar_difusion(&self, d: Difusion) {
        self.estado.lock().unwrap().difusion = d;
    }

    /// Cambia el protocolo que anuncia el servidor (por defecto "1.4").
    pub fn fijar_protocolo(&self, protocolo: &str) {
        self.estado.lock().unwrap().protocolo = protocolo.to_string();
    }

    /// La cadena RETROCEDE hasta `altura` (se descartan los bloques posteriores y la
    /// historia que apuntaba a ellos): un servidor que ha vuelto atrás respecto a lo que el
    /// cliente ya vio. No cambia el génesis ni el certificado.
    pub fn retroceder_a(&self, altura: u32) {
        let mut e = self.estado.lock().unwrap();
        e.cabeceras.truncate(altura as usize + 1);
        for lista in e.historia.values_mut() {
            lista.retain(|(_, h)| *h <= altura as i64);
        }
    }

    /// Añade un bloque cuya raíz de Merkle es `txid` (bloque de una sola transacción).
    pub fn anadir_bloque(&self, txid: Txid) -> (u32, Header) {
        let mut e = self.estado.lock().unwrap();
        let prev = e.cabeceras.last().unwrap();
        let altura = e.cabeceras.len() as u32;
        let h = Header {
            version: VersionBloque::ONE,
            prev_blockhash: prev.block_hash(),
            merkle_root: TxMerkleNode::from_byte_array(txid.to_byte_array()),
            time: 1_700_000_000 + altura,
            bits: prev.bits,
            nonce: 0,
        };
        e.cabeceras.push(h);
        (altura, h)
    }

    /// Apunta una transacción en la historia de sus scripts (salidas y entradas conocidas).
    pub fn registrar_tx(&self, tx: &Transaction, altura: i64) {
        let mut e = self.estado.lock().unwrap();
        registrar(&mut e, tx, altura);
    }

    /// Mete `txid` en un bloque nuevo y lo refleja en la historia.
    pub fn confirmar(&self, txid: Txid) -> u32 {
        let (altura, _) = self.anadir_bloque(txid);
        let mut e = self.estado.lock().unwrap();
        for lista in e.historia.values_mut() {
            for (t, h) in lista.iter_mut() {
                if *t == txid {
                    *h = altura as i64;
                }
            }
        }
        altura
    }

    pub fn difundidas(&self) -> Vec<Txid> {
        self.estado.lock().unwrap().difundidas.clone()
    }

    /// Cuántas veces se recibió un `broadcast`, cualquiera que fuese la respuesta.
    pub fn recibidas(&self) -> u64 {
        self.estado.lock().unwrap().recibidas
    }

    /// Identificador que recibirá la PRÓXIMA conexión que se acepte.
    pub fn proxima_conexion(&self) -> u64 {
        self.estado.lock().unwrap().siguiente_conexion
    }

    /// Todas las conexiones aceptadas hasta ahora: (identificador, métodos pedidos en orden).
    pub fn conexiones(&self) -> Vec<(u64, Vec<String>)> {
        self.estado.lock().unwrap().conexiones.clone()
    }

    /// A partir de la conexión `desde` (inclusive) el servidor se comporta mal según
    /// `maldad`; las anteriores siguen siendo buenas. Con `desde = proxima_conexion() + 1`
    /// la próxima conexión es buena y la siguiente mala.
    pub fn fijar_maldad_desde(&self, desde: u64, maldad: Maldad) {
        self.estado.lock().unwrap().maldad_desde = Some((desde, maldad));
    }

    /// Alturas que anuncia `headers.subscribe` por orden de petición dentro de cada conexión.
    pub fn fijar_alturas_por_respuesta(&self, alturas: Vec<u32>) {
        self.estado.lock().unwrap().alturas_por_respuesta = Some(alturas);
    }

    /// Servidor estricto con la negociación: rechaza un segundo `server.version` por conexión.
    pub fn fijar_estricto(&self, estricto: bool) {
        self.estado.lock().unwrap().estricto = estricto;
    }

    /// `server.version` negocia esta versión; `server.features` no cambia.
    pub fn fijar_protocolo_negociado(&self, protocolo: &str) {
        self.estado.lock().unwrap().protocolo_negociado = Some(protocolo.to_string());
    }

    /// `server.version` responde lo que propone el cliente (de un rango, lo más alto).
    pub fn fijar_negociar_segun_cliente(&self, si: bool) {
        self.estado.lock().unwrap().negociar_segun_cliente = si;
    }

    /// Exigir que `server.version` sea el primer mensaje de cada conexión.
    pub fn fijar_negociacion_primero(&self, si: bool) {
        self.estado.lock().unwrap().negociacion_primero = si;
    }

    pub fn capturas(&self) -> Vec<String> {
        self.estado.lock().unwrap().capturas.clone()
    }
}

fn registrar(e: &mut Estado, tx: &Transaction, altura: i64) {
    let txid = tx.compute_txid();
    let mut spks: Vec<ScriptBuf> = tx.output.iter().map(|o| o.script_pubkey.clone()).collect();
    for i in &tx.input {
        if let Some(prev) = e.txs.get(&i.previous_output.txid) {
            if let Some(o) = prev.output.get(i.previous_output.vout as usize) {
                spks.push(o.script_pubkey.clone());
            }
        }
    }
    for spk in spks {
        let lista = e.historia.entry(scripthash(&spk)).or_default();
        if !lista.iter().any(|(t, _)| *t == txid) {
            lista.push((txid, altura));
        }
    }
    e.txs.insert(txid, tx.clone());
}

fn nueva_conexion(estado: &Arc<Mutex<Estado>>) -> u64 {
    let mut e = estado.lock().unwrap();
    let id = e.siguiente_conexion;
    e.siguiente_conexion += 1;
    e.conexiones.push((id, Vec::new()));
    id
}

fn atender<S: Read + Write>(s: S, id_conexion: u64, estado: Arc<Mutex<Estado>>) {
    let mut lector = BufReader::new(s);
    let mut linea = String::new();
    loop {
        linea.clear();
        match lector.read_line(&mut linea) {
            Ok(0) | Err(_) => return,
            Ok(_) => {}
        }
        let Ok(req) = serde_json::from_str::<Value>(&linea) else {
            return;
        };
        let id = req["id"].clone();
        let metodo = req["method"].as_str().unwrap_or("");
        let params = req["params"].clone();
        if let Some((_, metodos)) = estado
            .lock()
            .unwrap()
            .conexiones
            .iter_mut()
            .find(|(id, _)| *id == id_conexion)
        {
            metodos.push(metodo.to_string());
        }
        let resp = match responder(metodo, &params, id_conexion, &estado) {
            Ok(Some(v)) => json!({"jsonrpc": "2.0", "id": id, "result": v}),
            Ok(None) => continue,
            Err((c, m)) => json!({"jsonrpc": "2.0", "id": id, "error": {"code": c, "message": m}}),
        };
        if writeln!(lector.get_mut(), "{resp}").is_err() {
            return;
        }
    }
}

/// Cuántas veces se ha pedido `metodo` en la conexión `id` (la petición en curso incluida).
fn veces(e: &Estado, id: u64, metodo: &str) -> usize {
    e.conexiones
        .iter()
        .find(|(i, _)| *i == id)
        .map(|(_, m)| m.iter().filter(|x| *x == metodo).count())
        .unwrap_or(0)
}

/// Maldad que corresponde a la conexión `id`, si la hay.
fn maldad_de(e: &Estado, id: u64) -> Option<&Maldad> {
    match &e.maldad_desde {
        Some((desde, m)) if id >= *desde => Some(m),
        _ => None,
    }
}

fn responder(
    metodo: &str,
    p: &Value,
    id_conexion: u64,
    estado: &Arc<Mutex<Estado>>,
) -> Result<Option<Value>, (i64, String)> {
    let mut e = estado.lock().unwrap();
    let protocolo = match maldad_de(&e, id_conexion) {
        Some(Maldad::Protocolo(p)) => p.clone(),
        _ => e.protocolo.clone(),
    };
    // Altura del último bloque que esta conexión ve (la cadena, o truncada por maldad).
    let tope = match maldad_de(&e, id_conexion) {
        Some(Maldad::AlturaTope(h)) => (*h as usize).min(e.cabeceras.len() - 1),
        _ => e.cabeceras.len() - 1,
    };
    if e.negociacion_primero
        && metodo != "server.version"
        && veces(&e, id_conexion, "server.version") == 0
    {
        return Err((1, "server.version must be the first message".to_string()));
    }
    let v = match metodo {
        "server.version" => {
            if e.estricto && veces(&e, id_conexion, "server.version") > 1 {
                return Err((1, "server.version already sent".to_string()));
            }
            let negociado = if e.negociar_segun_cliente {
                match &p[1] {
                    Value::String(s) => s.clone(),
                    Value::Array(rango) => rango
                        .last()
                        .and_then(|x| x.as_str())
                        .unwrap_or("")
                        .to_string(),
                    _ => protocolo.clone(),
                }
            } else {
                e.protocolo_negociado.clone().unwrap_or(protocolo.clone())
            };
            json!(["simulado 0.1", negociado])
        }
        "server.ping" => Value::Null,
        "server.features" => json!({
            "genesis_hash": e.cabeceras[0].block_hash().to_string(),
            "hash_function": "sha256",
            "server_version": "simulado 0.1",
            "protocol_min": protocolo,
            "protocol_max": protocolo,
            "pruning": null,
        }),
        "blockchain.headers.subscribe" => {
            let tope = match &e.alturas_por_respuesta {
                Some(seq) if !seq.is_empty() => {
                    let n = veces(&e, id_conexion, metodo).saturating_sub(1);
                    (seq[n.min(seq.len() - 1)] as usize).min(e.cabeceras.len() - 1)
                }
                _ => tope,
            };
            let ultimo = &e.cabeceras[tope];
            json!({"height": tope, "hex": consensus::encode::serialize_hex(ultimo)})
        }
        "blockchain.block.header" => {
            let h = p[0].as_u64().ok_or((1, "altura".to_string()))? as usize;
            let c = e
                .cabeceras
                .get(h)
                .filter(|_| h <= tope)
                .ok_or((1, "altura fuera de la cadena".to_string()))?;
            json!(consensus::encode::serialize_hex(c))
        }
        "blockchain.block.headers" => {
            let inicio = p[0].as_u64().ok_or((1, "inicio".to_string()))? as usize;
            let n = p[1].as_u64().ok_or((1, "cuenta".to_string()))? as usize;
            let fin = (inicio + n).min(tope + 1);
            let hex: String = e.cabeceras[inicio.min(fin)..fin]
                .iter()
                .map(consensus::encode::serialize_hex)
                .collect();
            json!({"count": fin.saturating_sub(inicio), "hex": hex, "max": 2016})
        }
        "blockchain.scripthash.get_history" => {
            let sh = p[0].as_str().ok_or((1, "scripthash".to_string()))?;
            let lista = e.historia.get(sh).cloned().unwrap_or_default();
            json!(lista
                .iter()
                .filter(|(_, h)| *h <= tope as i64)
                .map(|(t, h)| json!({"tx_hash": t.to_string(), "height": h}))
                .collect::<Vec<_>>())
        }
        "blockchain.transaction.get" => {
            let txid = Txid::from_str(p[0].as_str().ok_or((1, "txid".to_string()))?)
                .map_err(|_| (1, "txid".to_string()))?;
            let tx = e
                .txs
                .get(&txid)
                .ok_or((1, "transacción desconocida".to_string()))?;
            json!(consensus::encode::serialize_hex(tx))
        }
        "blockchain.transaction.get_merkle" => {
            let h = p[1].as_u64().ok_or((1, "altura".to_string()))?;
            json!({"block_height": h, "merkle": [], "pos": 0})
        }
        "blockchain.transaction.broadcast" => {
            let hex = p[0].as_str().ok_or((1, "hex".to_string()))?;
            let tx: Transaction = consensus::encode::deserialize_hex(hex)
                .map_err(|_| (1, "tx ilegible".to_string()))?;
            // Recibida: cuenta ANTES de decidir si se acepta, se rechaza o se cuelga.
            e.recibidas += 1;
            let mut modo = e.difusion.clone();
            loop {
                match modo {
                    Difusion::LeerAlRecibir(ruta, despues) => {
                        let copia = std::fs::read_to_string(&ruta).unwrap_or_default();
                        e.capturas.push(copia);
                        modo = *despues;
                    }
                    Difusion::Aceptar => {
                        registrar(&mut e, &tx, 0);
                        let txid = tx.compute_txid();
                        e.difundidas.push(txid);
                        break json!(txid.to_string());
                    }
                    Difusion::Rechazar(c, m) => return Err((c, m)),
                    Difusion::Colgar(d) => {
                        drop(e);
                        std::thread::sleep(d);
                        return Ok(None);
                    }
                }
            }
        }
        "blockchain.estimatefee" => json!(0.00002),
        "blockchain.relayfee" => json!(0.00001),
        _ => return Err((1, format!("método no soportado por el simulado: {metodo}"))),
    };
    Ok(Some(v))
}
