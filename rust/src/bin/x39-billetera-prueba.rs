//! Herramienta de PRUEBAS de x39-wallet. Solo signet o testnet: con mainnet se niega.
//!
//! Cada billetera de prueba vive en un directorio con:
//!   SEMILLA-DE-PRUEBA.txt  (0600; la semilla, marcada como de prueba y sin valor)
//!   estado.dat             (estado de BDK: solo descriptores públicos)
//! La semilla nunca se imprime ni va en la línea de comandos: se lee del fichero.
//!
//! Uso (servidor en X39_ELECTRUM, por defecto ssl://mempool.space:60602):
//!   x39-billetera-prueba crear       <dir> [signet|testnet]
//!   x39-billetera-prueba direccion   <dir>
//!   x39-billetera-prueba sincronizar <dir> [--completo]
//!   x39-billetera-prueba comision    [bloques]
//!   x39-billetera-prueba enviar      <dir> <destino> <sat> <sat/vB> [--sin-difundir]

use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use x39_wallet::{altura_red, estimar_comision, nueva_semilla, Billetera, Red};
use zeroize::Zeroizing;

const SEMILLA: &str = "SEMILLA-DE-PRUEBA.txt";
const ESTADO: &str = "estado.dat";
const RED_FICHERO: &str = "red.txt";

fn servidor() -> String {
    std::env::var("X39_ELECTRUM").unwrap_or_else(|_| "ssl://mempool.space:60602".into())
}

fn red_de(dir: &Path) -> Result<Red, String> {
    let r: Red = std::fs::read_to_string(dir.join(RED_FICHERO))
        .map_err(|e| format!("{}: {e}", dir.join(RED_FICHERO).display()))?
        .trim()
        .parse()
        .map_err(|e| format!("{e}"))?;
    if r == Red::Mainnet {
        return Err("herramienta de pruebas: mainnet no".into());
    }
    Ok(r)
}

fn leer_semilla(dir: &Path) -> Result<Zeroizing<String>, String> {
    let txt = Zeroizing::new(
        std::fs::read_to_string(dir.join(SEMILLA)).map_err(|e| format!("semilla: {e}"))?,
    );
    let linea = txt
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty() && !l.starts_with('#'))
        .ok_or("semilla: fichero sin semilla")?;
    Ok(Zeroizing::new(linea.to_string()))
}

fn explorador(red: Red) -> &'static str {
    match red {
        Red::Signet => "https://mempool.space/signet",
        Red::Testnet => "https://mempool.space/testnet",
        Red::Mainnet => "https://mempool.space",
    }
}

fn informe(b: &Billetera) {
    let s = b.saldo();
    println!("altura sincronizada: {}", b.altura());
    println!(
        "saldo: {} sat (confirmado {}, pendiente {}, inmaduro {})",
        s.total, s.confirmado, s.pendiente, s.inmaduro
    );
    let (e, i) = b.indices();
    println!("índices revelados: externo {e:?}, interno {i:?}");
    let mov = b.movimientos();
    println!("movimientos: {}", mov.len());
    for m in mov {
        println!(
            "  {}  +{} -{} comisión {:?}  {}",
            m.txid,
            m.recibido_sat,
            m.enviado_sat,
            m.comision_sat,
            m.altura.map(|h| format!("bloque {h}")).unwrap_or("en mempool".into())
        );
    }
}

fn ejecutar(args: &[String]) -> Result<(), String> {
    let orden = args.get(1).map(String::as_str).unwrap_or("");
    let dir = args.get(2).map(PathBuf::from);
    let e = |x: x39_wallet::Error| x.to_string();
    match orden {
        "crear" => {
            let dir = dir.ok_or("falta <dir>")?;
            let red: Red = args.get(3).map(String::as_str).unwrap_or("signet").parse().map_err(e)?;
            if red == Red::Mainnet {
                return Err("herramienta de pruebas: mainnet no".into());
            }
            std::fs::create_dir_all(&dir).map_err(|x| x.to_string())?;
            std::fs::set_permissions(&dir, std::os::unix::fs::PermissionsExt::from_mode(0o700))
                .map_err(|x| x.to_string())?;
            let s = Zeroizing::new(nueva_semilla().map_err(e)?);
            let mut f = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(dir.join(SEMILLA))
                .map_err(|x| format!("semilla: {x}"))?;
            writeln!(
                f,
                "# SEMILLA DE PRUEBA — {:?} — SIN VALOR. Generada por x39-billetera-prueba.\n# No usar nunca en mainnet ni para fondos reales.\n{}",
                red, s.as_str()
            )
            .map_err(|x| x.to_string())?;
            std::fs::write(dir.join(RED_FICHERO), format!("{}\n", format!("{red:?}").to_lowercase()))
                .map_err(|x| x.to_string())?;
            Billetera::crear(&s, "", red, &dir.join(ESTADO)).map_err(e)?;
            println!("billetera de prueba creada en {} ({red:?})", dir.display());
        }
        "direccion" => {
            let dir = dir.ok_or("falta <dir>")?;
            let mut b = Billetera::abrir(&dir.join(ESTADO), red_de(&dir)?).map_err(e)?;
            let (i, a) = b.nueva_direccion().map_err(e)?;
            println!("dirección nueva (índice {i}): {a}");
        }
        "sincronizar" => {
            let dir = dir.ok_or("falta <dir>")?;
            let red = red_de(&dir)?;
            let mut b = Billetera::abrir(&dir.join(ESTADO), red).map_err(e)?;
            if args.iter().any(|a| a == "--completo") {
                b.escanear(&servidor()).map_err(e)?;
            } else {
                b.sincronizar(&servidor()).map_err(e)?;
            }
            println!("punta de la red: {}", altura_red(&servidor()).map_err(e)?);
            informe(&b);
        }
        "comision" => {
            let n: usize = args.get(2).and_then(|x| x.parse().ok()).unwrap_or(2);
            println!("{:.2} sat/vB para {n} bloques", estimar_comision(&servidor(), n).map_err(e)?);
        }
        "enviar" => {
            let dir = dir.ok_or("falta <dir>")?;
            let destino = args.get(3).ok_or("falta <destino>")?;
            let sat: u64 = args.get(4).and_then(|x| x.parse().ok()).ok_or("falta <sat>")?;
            let tasa: f64 = args.get(5).and_then(|x| x.parse().ok()).ok_or("falta <sat/vB>")?;
            let red = red_de(&dir)?;
            let mut b = Billetera::abrir(&dir.join(ESTADO), red).map_err(e)?;
            b.sincronizar(&servidor()).map_err(e)?;
            let envio = {
                let s = leer_semilla(&dir)?;
                b.preparar_envio(&s, "", destino, sat, tasa).map_err(e)?
            };
            println!("txid: {}", envio.txid);
            println!(
                "comisión: {} sat, {} vB, {:.2} sat/vB",
                envio.comision_sat, envio.vbytes, envio.sat_vb
            );
            println!("cambio a: {}", if envio.cambio.is_empty() { "(sin cambio)" } else { &envio.cambio });
            if args.iter().any(|a| a == "--sin-difundir") {
                println!("hex: {}", envio.hex);
                return Ok(());
            }
            let txid = b.difundir(&servidor(), &envio).map_err(e)?;
            println!("DIFUNDIDA: {}/tx/{txid}", explorador(red));
            informe(&b);
        }
        _ => return Err("orden desconocida (crear, direccion, sincronizar, comision, enviar)".into()),
    }
    Ok(())
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if let Err(m) = ejecutar(&args) {
        eprintln!("error: {m}");
        std::process::exit(1);
    }
}
