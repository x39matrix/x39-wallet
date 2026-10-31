//! Capa FFI (UniFFI) de la billetera: lo ÚNICO que ve Android.
//!
//! Solo tipos planos: números, textos, listas y los registros de abajo. El PSBT crudo y las
//! transacciones (`Arc<Transaction>`) nunca cruzan; de un envío solo sale su `id` o su `txid`.
//!
//! CONTRATO DEL MNEMÓNICO. Entra siempre como bytes (`&[u8]`), nunca como texto:
//!
//! * Kotlin reserva un `ByteBuffer` DIRECTO (`ByteBuffer.allocateDirect`) con `position()`
//!   en 0 y `limit()` en la longitud exacta (el conversor no suma `position` al puntero), lo
//!   rellena con el mnemónico en UTF-8, llama, y lo pone a cero en un `finally`. UniFFI 0.32
//!   pasa un `&[u8]` directo sin copiarlo (`FfiConverterByRefBytes`, que exige `isDirect`);
//!   Rust lo recibe prestado solo durante la llamada y lo que el motor controla (semilla de
//!   64 bytes, descriptores con clave privada, mnemónico analizado) va en `Zeroizing`.
//! * Nunca `String` ni `CharSequence` con el mnemónico en ninguno de los dos lados.
//! * LIMITACIÓN de `nueva_semilla`: al devolver la semilla nueva, UniFFI deja DOS copias en
//!   memoria nativa que no se ponen a cero: el `Vec<u8>` que se entrega al serializador y el
//!   `RustBuffer` que Kotlin libera sin borrar. La copia `Zeroizing` del motor sí se borra.
//!   Kotlin pone a cero su `ByteArray` en cuanto lo ha mostrado y cifrado. Misma redacción
//!   en el README. Trabajo futuro: implementar `Lower` a mano para un tipo `Semilla` que
//!   escriba directamente en el búfer de salida (dejaría una sola copia, la de UniFFI).
//!
//! Mainnet queda prohibida en esta capa igual que en el motor (feature `mainnet` no activa).

use crate as motor;
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub use motor::{EnvioInfo, Error, EstadoEnvio, Movimiento, NodoInfo, PsbtPendiente, Red, Saldo};

/// Una dirección de recibir recién revelada y su índice de derivación.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DireccionInfo {
    pub indice: u32,
    pub direccion: String,
}

/// Versión del motor, para que la app la muestre y la compare con los bindings.
#[uniffi::export]
pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Mnemónico nuevo de 12 palabras como bytes UTF-8. Ver la LIMITACIÓN en la cabecera: de
/// este valor quedan dos copias nativas sin borrar (la que se entrega aquí al serializador
/// de UniFFI y el RustBuffer que Kotlin libera).
#[uniffi::export]
pub fn nueva_semilla() -> Result<Vec<u8>, Error> {
    let s = motor::nueva_semilla()?; // Zeroizing: esta copia sí se borra
    Ok(s.to_vec())
}

/// Marcador de redes de ESTA compilación de la biblioteca, para que la app lo muestre y
/// exija el de solo redes de prueba. (La docstring no cita los valores: UniFFI la incrusta
/// en la biblioteca y rompería la comprobación por `strings` de build-native.sh.)
#[uniffi::export]
pub fn redes_permitidas() -> String {
    motor::REDES_PERMITIDAS.to_string()
}

fn huella_opt(h: &Option<String>) -> Option<&str> {
    h.as_deref().map(str::trim).filter(|s| !s.is_empty())
}

/// Altura del servidor Electrum, verificando que es de la red indicada. `huella_spki`: la
/// misma huella que en el resto de operaciones (obligatoria con nodo autofirmado).
/// `altura_vista`: la última altura que la app ya vio (0 si ninguna); por debajo se rechaza.
#[uniffi::export]
pub fn altura_red(
    red: Red,
    servidor: String,
    huella_spki: Option<String>,
    altura_vista: u32,
) -> Result<u32, Error> {
    motor::altura_red(red, &servidor, huella_opt(&huella_spki), altura_vista)
}

/// Comisión recomendada en sat/vB para confirmar en `bloques` bloques.
#[uniffi::export]
pub fn estimar_comision(
    red: Red,
    servidor: String,
    bloques: u32,
    huella_spki: Option<String>,
    altura_vista: u32,
) -> Result<f64, Error> {
    motor::estimar_comision(
        red,
        &servidor,
        bloques as usize,
        huella_opt(&huella_spki),
        altura_vista,
    )
}

/// Comprobación completa del nodo del usuario (NodeBridge), con las mismas cuatro puertas y en
/// el mismo orden que cualquier otra conexión del motor: identidad por huella SPKI (con
/// `ssl://` y huella), protocolo 1.4 y latencia (`server.version`, primer mensaje), red por
/// génesis y altura no inferior a `altura_minima`. No sincroniza ni difunde nada.
#[uniffi::export]
pub fn comprobar_nodo(
    red: Red,
    servidor: String,
    huella_spki: Option<String>,
    altura_minima: u32,
) -> Result<NodoInfo, Error> {
    motor::comprobar_nodo(red, &servidor, huella_opt(&huella_spki), altura_minima)
}

/// La billetera. Las llamadas se serializan con un mutex: una operación a la vez.
#[derive(uniffi::Object)]
pub struct Billetera {
    interior: Mutex<motor::Billetera>,
}

impl Billetera {
    fn con<T>(&self, f: impl FnOnce(&mut motor::Billetera) -> T) -> T {
        let mut g = self.interior.lock().unwrap_or_else(|p| p.into_inner());
        f(&mut g)
    }
}

#[uniffi::export]
impl Billetera {
    /// Crea (o restaura) una billetera con su fichero de estado; falla si el fichero existe.
    /// `mnemonico`: bytes UTF-8 en un ByteBuffer directo (ver contrato).
    #[uniffi::constructor]
    pub fn crear(mnemonico: &[u8], red: Red, fichero: String) -> Result<Arc<Self>, Error> {
        let b = motor::Billetera::crear(mnemonico, red, std::path::Path::new(&fichero))?;
        Ok(Arc::new(Billetera {
            interior: Mutex::new(b),
        }))
    }

    /// Abre una billetera existente sin la semilla y reanuda los envíos sin resolver.
    #[uniffi::constructor]
    pub fn abrir(fichero: String, red: Red) -> Result<Arc<Self>, Error> {
        let b = motor::Billetera::abrir(std::path::Path::new(&fichero), red)?;
        Ok(Arc::new(Billetera {
            interior: Mutex::new(b),
        }))
    }

    /// ¿Estas palabras (ByteBuffer directo) son la semilla de esta billetera? No firma ni guarda.
    /// Para recuperar la clave del Keystore sin tocar el estado de la billetera.
    pub fn coincide_semilla(&self, mnemonico: &[u8]) -> bool {
        self.con(|b| b.coincide_semilla(mnemonico))
    }

    /// Espera máxima por respuesta del servidor, en segundos.
    pub fn fijar_espera_s(&self, segundos: u64) {
        self.con(|b| b.fijar_espera(Duration::from_secs(segundos.max(1))))
    }

    /// Huella SPKI (hex) del certificado del servidor con `ssl://`; `null` = sin fijar.
    pub fn fijar_huella_servidor(&self, huella_spki: Option<String>) {
        self.con(|b| b.fijar_huella_servidor(huella_spki))
    }

    pub fn nueva_direccion(&self) -> Result<DireccionInfo, Error> {
        self.con(|b| b.nueva_direccion())
            .map(|(indice, direccion)| DireccionInfo { indice, direccion })
    }

    /// Escaneo completo con gap limit (primera vez o tras restaurar).
    pub fn escanear(&self, servidor: String) -> Result<(), Error> {
        self.con(|b| b.escanear(&servidor))
    }

    /// Sincronización de lo ya revelado; actualiza también el estado de los envíos.
    pub fn sincronizar(&self, servidor: String) -> Result<(), Error> {
        self.con(|b| b.sincronizar(&servidor))
    }

    pub fn saldo(&self) -> Saldo {
        self.con(|b| b.saldo())
    }

    pub fn altura(&self) -> u32 {
        self.con(|b| b.altura())
    }

    pub fn movimientos(&self) -> Vec<Movimiento> {
        self.con(|b| b.movimientos())
    }

    /// Paso 1 de enviar: construye sin semilla y devuelve la revisión (solo sale el id).
    pub fn preparar_psbt(
        &self,
        destino: String,
        cantidad_sat: u64,
        sat_vb: f64,
        max_comision_sat: u64,
    ) -> Result<PsbtPendiente, Error> {
        self.con(|b| b.preparar_psbt(&destino, cantidad_sat, sat_vb, max_comision_sat))
    }

    pub fn psbt_pendiente(&self) -> Option<PsbtPendiente> {
        self.con(|b| b.psbt_pendiente())
    }

    /// Paso 2: re-verifica el pendiente `id` y firma. `mnemonico` en ByteBuffer directo.
    pub fn firmar_y_extraer(&self, id: String, mnemonico: &[u8]) -> Result<EnvioInfo, Error> {
        self.con(|b| b.firmar_y_extraer(&id, mnemonico))
    }

    /// Difunde o reintenta la MISMA transacción firmada.
    pub fn difundir(&self, servidor: String, txid: String) -> Result<EnvioInfo, Error> {
        self.con(|b| b.difundir(&servidor, &txid))
    }

    /// Cancelación por reemplazo RBF de un envío que ya tocó un servidor.
    pub fn preparar_cancelacion(&self, txid: String, sat_vb: f64) -> Result<PsbtPendiente, Error> {
        self.con(|b| b.preparar_cancelacion(&txid, sat_vb))
    }

    /// Cancela liberando monedas SOLO un envío firmado que nunca se intentó difundir.
    pub fn cancelar(&self, txid: String) -> Result<EnvioInfo, Error> {
        self.con(|b| b.cancelar(&txid))
    }

    pub fn estado_envio(&self, txid: String) -> Option<EnvioInfo> {
        self.con(|b| b.estado_envio(&txid))
    }

    pub fn envios(&self) -> Vec<EnvioInfo> {
        self.con(|b| b.envios())
    }

    /// El envío sin resolver que bloquea construir otro, si lo hay.
    pub fn envio_en_curso(&self) -> Option<EnvioInfo> {
        self.con(|b| b.envio_en_curso())
    }
}
