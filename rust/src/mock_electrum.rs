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
use std::io::{BufRead, BufReader, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::str::FromStr;
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

pub struct Estado {
    pub cabeceras: Vec<Header>,
    pub txs: HashMap<Txid, Transaction>,
    /// scripthash Electrum (hex) → [(txid, altura)]; altura 0 = mempool.
    pub historia: HashMap<String, Vec<(Txid, i64)>>,
    pub difusion: Difusion,
    pub difundidas: Vec<Txid>,
    /// Copias de ficheros leídas con `Difusion::LeerAlRecibir`.
    pub capturas: Vec<String>,
}

pub struct Simulado {
    /// `tcp://<local>:<puerto>`, para pasar como servidor.
    pub url: String,
    pub estado: Arc<Mutex<Estado>>,
}

pub fn scripthash(spk: &ScriptBuf) -> String {
    let mut h = sha256::Hash::hash(spk.as_bytes()).to_byte_array();
    h.reverse();
    h.to_lower_hex_string()
}

impl Simulado {
    pub fn arrancar(red: Network) -> Simulado {
        let escucha = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("puerto local");
        let puerto = escucha.local_addr().unwrap().port();
        let estado = Arc::new(Mutex::new(Estado {
            cabeceras: vec![genesis_block(red).header],
            txs: HashMap::new(),
            historia: HashMap::new(),
            difusion: Difusion::Aceptar,
            difundidas: Vec::new(),
            capturas: Vec::new(),
        }));
        let e2 = estado.clone();
        std::thread::spawn(move || {
            for s in escucha.incoming().flatten() {
                let e = e2.clone();
                std::thread::spawn(move || atender(s, e));
            }
        });
        Simulado {
            url: format!("tcp://{}:{puerto}", Ipv4Addr::LOCALHOST),
            estado,
        }
    }

    pub fn fijar_difusion(&self, d: Difusion) {
        self.estado.lock().unwrap().difusion = d;
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

fn atender(s: TcpStream, estado: Arc<Mutex<Estado>>) {
    let mut lector = BufReader::new(s.try_clone().unwrap());
    let mut escritor = s;
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
        let resp = match responder(metodo, &params, &estado) {
            Ok(Some(v)) => json!({"jsonrpc": "2.0", "id": id, "result": v}),
            Ok(None) => continue,
            Err((c, m)) => json!({"jsonrpc": "2.0", "id": id, "error": {"code": c, "message": m}}),
        };
        if writeln!(escritor, "{resp}").is_err() {
            return;
        }
    }
}

fn responder(
    metodo: &str,
    p: &Value,
    estado: &Arc<Mutex<Estado>>,
) -> Result<Option<Value>, (i64, String)> {
    let mut e = estado.lock().unwrap();
    let v = match metodo {
        "server.version" => json!(["simulado 0.1", "1.4"]),
        "server.ping" => Value::Null,
        "server.features" => json!({
            "genesis_hash": e.cabeceras[0].block_hash().to_string(),
            "hash_function": "sha256",
            "server_version": "simulado 0.1",
            "protocol_min": "1.4",
            "protocol_max": "1.4",
            "pruning": null,
        }),
        "blockchain.headers.subscribe" => {
            let ultimo = e.cabeceras.last().unwrap();
            json!({"height": e.cabeceras.len() - 1, "hex": consensus::encode::serialize_hex(ultimo)})
        }
        "blockchain.block.header" => {
            let h = p[0].as_u64().ok_or((1, "altura".to_string()))? as usize;
            let c = e
                .cabeceras
                .get(h)
                .ok_or((1, "altura fuera de la cadena".to_string()))?;
            json!(consensus::encode::serialize_hex(c))
        }
        "blockchain.block.headers" => {
            let inicio = p[0].as_u64().ok_or((1, "inicio".to_string()))? as usize;
            let n = p[1].as_u64().ok_or((1, "cuenta".to_string()))? as usize;
            let fin = (inicio + n).min(e.cabeceras.len());
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
