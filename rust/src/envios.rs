//! Envíos firmados y su estado: el contrato de DIFUSIÓN INCIERTA.
//!
//! Una transacción firmada pasa por estos estados, que se PERSISTEN en un fichero
//! lateral JSON junto al estado de la billetera (`<estado>.envios.json`):
//!
//! * `Firmada`: firmada y aplicada localmente como no confirmada (las monedas quedan
//!   gastadas en la billetera) ANTES de intentar difundirla. Todavía no se ha enviado.
//! * `Enviada`: el servidor aceptó la difusión (o ya la conocía), o la ha visto en una
//!   sincronización.
//! * `Fallida`: el servidor la RECHAZÓ explícitamente. Las monedas se liberan.
//! * `Incierta`: la difusión no obtuvo respuesta (timeout, conexión perdida). No se sabe
//!   si llegó. NUNCA se reconstruye otra transacción con esas monedas: se reintenta la
//!   MISMA transacción firmada o se la REEMPLAZA (ver abajo).
//! * `Confirmada`: vista en un bloque. También si antes se había dado por cancelada o
//!   rechazada: el registro no puede llamar cancelada a lo que la red confirmó.
//! * `Cancelada`: o bien nunca salió del aparato y el usuario la canceló (monedas
//!   liberadas), o bien su REEMPLAZO se confirmó.
//!
//! Cancelar algo que ha tocado un servidor NO es liberar las monedas: no se sabe si la
//! transacción está ya en la red, y gastar esas monedas en otra cosa sería pagar dos
//! veces. Cancelar es entonces un reemplazo RBF: otra transacción que gasta TODAS las
//! entradas de la original hacia una dirección propia con más comisión. Las dos entran en
//! conflicto y la red confirma como mucho una. Mientras ninguna esté confirmada, ambas
//! cuentan como "en curso" (bloquean construir otro envío). Si confirma el reemplazo, la
//! original pasa a `Cancelada`; si confirma la original, el reemplazo pasa a `Fallida`
//! ("reemplazo perdido").

use crate::{Error, Red};
use bdk_wallet::chain::{ConfirmationBlockTime, TxUpdate};
use bitcoin::{consensus, Transaction, Txid};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::str::FromStr;

/// Versión del fichero lateral. La 1 (misma estructura, solo motores de prueba
/// anteriores a esta versión) se lee y se vuelve a guardar como 2.
const VERSION_FICHERO: u32 = 2;
const VERSIONES_LEGIBLES: [u32; 2] = [1, 2];

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum EstadoEnvio {
    Firmada,
    Enviada,
    Fallida,
    Incierta,
    Confirmada,
    Cancelada,
}

impl std::fmt::Display for EstadoEnvio {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            EstadoEnvio::Firmada => "firmada",
            EstadoEnvio::Enviada => "enviada",
            EstadoEnvio::Fallida => "fallida",
            EstadoEnvio::Incierta => "difusión incierta",
            EstadoEnvio::Confirmada => "confirmada",
            EstadoEnvio::Cancelada => "cancelada",
        })
    }
}

/// Todo lo que se sabe de un envío firmado. Tipo PLANO: la transacción cruda no sale.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct EnvioInfo {
    /// Id del PSBT pendiente del que salió.
    pub id: String,
    pub txid: String,
    pub red: Red,
    pub destino: String,
    pub importe_sat: u64,
    pub comision_sat: u64,
    /// Tamaño REAL de la transacción firmada.
    pub vbytes: u64,
    pub sat_vb: f64,
    /// Dirección de cambio (propia), vacía si no hubo cambio.
    pub cambio: String,
    pub cambio_sat: u64,
    /// Si es un reemplazo (cancelación RBF), el txid de la transacción original; si no, vacío.
    pub reemplaza_a: String,
    pub estado: EstadoEnvio,
    /// Explicación del último cambio de estado (rechazo del servidor, timeout…).
    pub motivo: String,
    pub intentos_difusion: u32,
    /// Segundos desde la época Unix.
    pub firmada_en: u64,
    pub actualizada_en: u64,
    /// Se puede volver a difundir LA MISMA transacción (`Firmada` o `Incierta`).
    pub puede_reintentar: bool,
    /// Se puede cancelar liberando las monedas: solo si nunca salió del aparato.
    pub puede_cancelar: bool,
    /// Se puede cancelar por REEMPLAZO (ha tocado un servidor y no está confirmada).
    pub puede_reemplazar: bool,
}

impl EnvioInfo {
    fn refrescar(&mut self, ahora: u64, con_reemplazo_vivo: bool) {
        self.actualizada_en = ahora;
        self.puede_reintentar = matches!(self.estado, EstadoEnvio::Firmada | EstadoEnvio::Incierta);
        self.puede_cancelar = self.estado == EstadoEnvio::Firmada && self.intentos_difusion == 0;
        self.puede_reemplazar = matches!(
            self.estado,
            EstadoEnvio::Firmada | EstadoEnvio::Enviada | EstadoEnvio::Incierta
        ) && self.intentos_difusion > 0
            && !con_reemplazo_vivo
            && self.reemplaza_a.is_empty();
    }

    fn abierta(&self) -> bool {
        matches!(
            self.estado,
            EstadoEnvio::Firmada | EstadoEnvio::Enviada | EstadoEnvio::Incierta
        )
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
struct Apunte {
    info: EnvioInfo,
    /// Transacción firmada completa (hex). Necesaria para reintentar.
    hex: String,
    /// Último `seen_at` aplicado localmente a la billetera.
    visto_en: u64,
}

#[derive(Serialize, Deserialize, Default)]
struct Fichero {
    version: u32,
    envios: Vec<Apunte>,
}

pub(crate) struct Registro {
    ruta: PathBuf,
    envios: Vec<Apunte>,
}

fn fichero_err(e: impl std::fmt::Display) -> Error {
    Error::Fichero(format!("envíos: {e}"))
}

impl Registro {
    /// `estado.dat` → `estado.dat.envios.json`.
    pub fn ruta_de(estado: &Path) -> PathBuf {
        let mut nombre = estado
            .file_name()
            .map(|s| s.to_os_string())
            .unwrap_or_default();
        nombre.push(".envios.json");
        estado.with_file_name(nombre)
    }

    pub fn cargar(estado: &Path) -> Result<Self, Error> {
        let ruta = Self::ruta_de(estado);
        let envios = match std::fs::read(&ruta) {
            Ok(bytes) => {
                let f: Fichero = serde_json::from_slice(&bytes).map_err(fichero_err)?;
                if !VERSIONES_LEGIBLES.contains(&f.version) {
                    return Err(Error::Fichero(format!(
                        "envíos: versión {} desconocida",
                        f.version
                    )));
                }
                f.envios
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Vec::new(),
            Err(e) => return Err(fichero_err(e)),
        };
        Ok(Registro { ruta, envios })
    }

    /// Escritura atómica (fichero temporal + renombrado), permisos 0600.
    pub fn guardar(&self) -> Result<(), Error> {
        let f = Fichero {
            version: VERSION_FICHERO,
            envios: self.envios.clone(),
        };
        let bytes = serde_json::to_vec_pretty(&f).map_err(fichero_err)?;
        let tmp = self.ruta.with_extension("json.tmp");
        {
            let mut opciones = std::fs::OpenOptions::new();
            opciones.write(true).create(true).truncate(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                opciones.mode(0o600);
            }
            let mut fh = opciones.open(&tmp).map_err(fichero_err)?;
            std::io::Write::write_all(&mut fh, &bytes).map_err(fichero_err)?;
            fh.sync_all().map_err(fichero_err)?;
        }
        std::fs::rename(&tmp, &self.ruta).map_err(fichero_err)
    }

    fn indice(&self, txid: &str) -> Result<usize, Error> {
        self.envios
            .iter()
            .position(|a| a.info.txid == txid)
            .ok_or_else(|| Error::Envio(format!("envío desconocido: {txid}")))
    }

    /// Un reemplazo de `txid` todavía abierto (ni confirmado ni fallido).
    pub fn reemplazo_vivo_de(&self, txid: &str) -> Option<&EnvioInfo> {
        self.envios
            .iter()
            .map(|a| &a.info)
            .find(|i| i.reemplaza_a == txid && i.abierta())
    }

    fn refrescar(&mut self, n: usize, ahora: u64) {
        let txid = self.envios[n].info.txid.clone();
        let vivo = self.reemplazo_vivo_de(&txid).is_some();
        self.envios[n].info.refrescar(ahora, vivo);
    }

    /// Vuelve a calcular los indicadores de todos (cambian con los reemplazos).
    fn refrescar_todos(&mut self, ahora: u64) {
        for n in 0..self.envios.len() {
            self.refrescar(n, ahora);
        }
    }

    pub fn registrar(
        &mut self,
        mut info: EnvioInfo,
        tx: &Transaction,
        ahora: u64,
    ) -> Result<EnvioInfo, Error> {
        info.estado = EstadoEnvio::Firmada;
        info.firmada_en = ahora;
        if !info.reemplaza_a.is_empty() {
            let o = self.indice(&info.reemplaza_a)?;
            self.envios[o].info.motivo = format!("reemplazo en curso: {}", info.txid);
        }
        self.envios.push(Apunte {
            info,
            hex: consensus::encode::serialize_hex(tx),
            visto_en: ahora,
        });
        self.refrescar_todos(ahora);
        self.guardar()?;
        Ok(self.envios.last().unwrap().info.clone())
    }

    pub fn get(&self, txid: &str) -> Option<&EnvioInfo> {
        self.envios
            .iter()
            .find(|a| a.info.txid == txid)
            .map(|a| &a.info)
    }

    pub fn hex(&self, txid: &str) -> Option<String> {
        self.envios
            .iter()
            .find(|a| a.info.txid == txid)
            .map(|a| a.hex.clone())
    }

    pub fn tx(&self, txid: &str) -> Result<Transaction, Error> {
        let a = &self.envios[self.indice(txid)?];
        consensus::encode::deserialize_hex(&a.hex)
            .map_err(|e| Error::Fichero(format!("envíos: tx ilegible: {e}")))
    }

    /// Último `seen_at` aplicado localmente.
    pub fn visto_en(&self, txid: &str) -> u64 {
        self.envios
            .iter()
            .find(|a| a.info.txid == txid)
            .map(|a| a.visto_en)
            .unwrap_or(0)
    }

    pub fn anotar_visto(&mut self, txid: &str, visto_en: u64) {
        if let Some(a) = self.envios.iter_mut().find(|a| a.info.txid == txid) {
            a.visto_en = a.visto_en.max(visto_en);
        }
    }

    pub fn marcar(
        &mut self,
        txid: &str,
        estado: EstadoEnvio,
        motivo: &str,
        intento: bool,
        ahora: u64,
    ) -> Result<EnvioInfo, Error> {
        let n = self.indice(txid)?;
        let a = &mut self.envios[n];
        a.info.estado = estado;
        a.info.motivo = motivo.to_string();
        if intento {
            a.info.intentos_difusion += 1;
        }
        self.refrescar_todos(ahora);
        self.guardar()?;
        Ok(self.envios[n].info.clone())
    }

    /// Un envío sin resolver que bloquea construir otro: firmado o incierto; enviado
    /// con un reemplazo vivo; o un reemplazo todavía abierto.
    pub fn en_curso(&self) -> Option<&EnvioInfo> {
        self.envios.iter().map(|a| &a.info).find(|i| {
            matches!(i.estado, EstadoEnvio::Firmada | EstadoEnvio::Incierta)
                || (i.abierta() && !i.reemplaza_a.is_empty())
                || (i.estado == EstadoEnvio::Enviada && self.reemplazo_vivo_de(&i.txid).is_some())
        })
    }

    pub fn todos(&self) -> Vec<EnvioInfo> {
        self.envios.iter().map(|a| a.info.clone()).collect()
    }

    /// Transacciones firmadas no resueltas (`Firmada` o `Incierta`): deben seguir
    /// gastadas localmente.
    pub fn no_resueltas(&self) -> Vec<(Txid, Transaction)> {
        self.envios
            .iter()
            .filter(|a| matches!(a.info.estado, EstadoEnvio::Firmada | EstadoEnvio::Incierta))
            .filter_map(|a| {
                let txid = Txid::from_str(&a.info.txid).ok()?;
                let tx = consensus::encode::deserialize_hex(&a.hex).ok()?;
                Some((txid, tx))
            })
            .collect()
    }

    /// Mira qué dice el servidor (la actualización de una sincronización) de cada envío.
    /// Devuelve `true` si cambió algo. No guarda: lo hace quien llama.
    pub fn observar(&mut self, upd: &TxUpdate<ConfirmationBlockTime>, ahora: u64) -> bool {
        let mut cambio = false;
        // 1) Lo que la red dice de cada transacción, incluidas las dadas por canceladas o
        //    rechazadas: si la red las confirma, confirmadas están.
        for a in self.envios.iter_mut() {
            let i = &mut a.info;
            if i.estado == EstadoEnvio::Confirmada {
                continue;
            }
            let Ok(txid) = Txid::from_str(&i.txid) else {
                continue;
            };
            let confirmada = upd.anchors.iter().any(|(_, t)| *t == txid);
            let vista = upd.seen_ats.iter().any(|(t, _)| *t == txid);
            let antes = i.estado;
            let pese_a = match antes {
                EstadoEnvio::Cancelada => " pese a la cancelación",
                EstadoEnvio::Fallida => " pese al rechazo",
                _ => "",
            };
            if confirmada {
                i.estado = EstadoEnvio::Confirmada;
                i.motivo = format!("vista en un bloque{pese_a}");
            } else if vista {
                i.estado = EstadoEnvio::Enviada;
                i.motivo = format!("vista en la mempool del servidor{pese_a}");
            } else if antes == EstadoEnvio::Enviada {
                i.estado = EstadoEnvio::Incierta;
                i.motivo = "ya no está en la mempool del servidor".into();
            }
            if antes != i.estado {
                cambio = true;
            }
        }
        // 2) Conflictos entre original y reemplazo: la red confirma como mucho una.
        let confirmadas: Vec<(String, String)> = self
            .envios
            .iter()
            .filter(|a| a.info.estado == EstadoEnvio::Confirmada)
            .map(|a| (a.info.txid.clone(), a.info.reemplaza_a.clone()))
            .collect();
        for (txid, reemplaza_a) in confirmadas {
            for a in self.envios.iter_mut() {
                let i = &mut a.info;
                if !reemplaza_a.is_empty()
                    && i.txid == reemplaza_a
                    && i.estado != EstadoEnvio::Confirmada
                {
                    // Confirmó el reemplazo: la original queda cancelada de verdad.
                    i.estado = EstadoEnvio::Cancelada;
                    i.motivo = format!("reemplazada por {txid}, confirmada");
                    cambio = true;
                } else if i.reemplaza_a == txid
                    && i.estado != EstadoEnvio::Confirmada
                    && i.estado != EstadoEnvio::Fallida
                {
                    // Confirmó la original: su reemplazo ya no puede entrar.
                    i.estado = EstadoEnvio::Fallida;
                    i.motivo = "reemplazo perdido: se confirmó la original".into();
                    cambio = true;
                }
            }
        }
        if cambio {
            self.refrescar_todos(ahora);
        }
        cambio
    }
}
