//! Herramienta de PRUEBAS de x39-wallet. Solo signet, testnet o regtest: con mainnet se niega.
//!
//! Cada billetera de prueba vive en un directorio con:
//!   SEMILLA-DE-PRUEBA.txt   (0600; la semilla, marcada como de prueba y sin valor)
//!   estado.dat              (estado de BDK: solo descriptores públicos)
//!   estado.dat.envios.json  (estados de los envíos firmados)
//!   red.txt
//! La semilla nunca se imprime ni va en la línea de comandos: se lee del fichero.
//!
//! Servidor Electrum: variable X39_ELECTRUM (tcp://host:puerto o ssl://host:puerto). Solo en
//! signet hay un servidor público por defecto; en las demás redes la variable es obligatoria.
//! Todas las órdenes de red imprimen el servidor que usan.
//!
//! Uso:
//!   x39-billetera-prueba crear       <dir> [signet|testnet|regtest]
//!   x39-billetera-prueba direccion   <dir>
//!   x39-billetera-prueba sincronizar <dir> [--completo]
//!   x39-billetera-prueba comision    <dir> [bloques]
//!   x39-billetera-prueba enviar      <dir> <destino> <sat> <sat/vB> [--max-comision <sat>] [--sin-difundir]
//!   x39-billetera-prueba envios      <dir>
//!   x39-billetera-prueba difundir    <dir> <txid>
//!   x39-billetera-prueba cancelar    <dir> <txid> [sat/vB]
//!
//! `enviar` prepara el PSBT, muestra la REVISIÓN, firma y (salvo --sin-difundir) difunde.
//! Con --sin-difundir la transacción queda firmada y sus monedas reservadas: después
//! `difundir <dir> <txid>` la envía (la misma, no otra) o `cancelar` las libera.
//! `cancelar` sobre algo que ya se intentó difundir no libera nada: construye, revisa,
//! firma y difunde un REEMPLAZO (RBF) hacia una dirección propia con la tasa indicada.

use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use x39_wallet::{
    altura_red, estimar_comision, nueva_semilla, Billetera, EnvioInfo, EstadoEnvio, PsbtPendiente,
    Red,
};
use zeroize::Zeroizing;

const SEMILLA: &str = "SEMILLA-DE-PRUEBA.txt";
const ESTADO: &str = "estado.dat";
const RED_FICHERO: &str = "red.txt";
/// Servidor público, SOLO para signet.
const SIGNET_PUBLICO: &str = "ssl://mempool.space:60602";
/// Tope de comisión si no se indica --max-comision.
const MAX_COMISION_DEFECTO: u64 = 10_000;

fn servidor(red: Red) -> Result<String, String> {
    match std::env::var("X39_ELECTRUM") {
        Ok(s) if !s.trim().is_empty() => Ok(s.trim().to_string()),
        _ if red == Red::Signet => Ok(SIGNET_PUBLICO.into()),
        _ => Err(format!(
            "X39_ELECTRUM no definida: para {red} hay que indicar el servidor Electrum \
             (tcp://host:puerto o ssl://host:puerto). Solo signet tiene un servidor público por defecto."
        )),
    }
}

fn servidor_anunciado(red: Red) -> Result<String, String> {
    let s = servidor(red)?;
    println!("servidor: {s} ({red})");
    Ok(s)
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

fn leer_semilla(dir: &Path) -> Result<Zeroizing<Vec<u8>>, String> {
    let txt = Zeroizing::new(
        std::fs::read_to_string(dir.join(SEMILLA)).map_err(|e| format!("semilla: {e}"))?,
    );
    let linea = txt
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty() && !l.starts_with('#'))
        .ok_or("semilla: fichero sin semilla")?;
    Ok(Zeroizing::new(linea.as_bytes().to_vec()))
}

fn explorador(red: Red) -> Option<&'static str> {
    match red {
        Red::Signet => Some("https://mempool.space/signet"),
        Red::Testnet => Some("https://mempool.space/testnet"),
        Red::Regtest | Red::Mainnet => None,
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
            m.altura
                .map(|h| format!("bloque {h}"))
                .unwrap_or("en mempool".into())
        );
    }
    if let Some(e) = b.envio_en_curso() {
        println!(
            "ENVÍO SIN RESOLVER: {} ({}) — `difundir` para reintentar o `cancelar`",
            e.txid, e.estado
        );
    }
}

fn mostrar_envio(e: &EnvioInfo) {
    println!("txid: {}", e.txid);
    println!(
        "estado: {}{}",
        e.estado,
        if e.motivo.is_empty() {
            String::new()
        } else {
            format!(" — {}", e.motivo)
        }
    );
    println!(
        "  destino {} · importe {} sat · comisión {} sat ({} vB, {:.2} sat/vB) · cambio {} ({} sat)",
        e.destino,
        e.importe_sat,
        e.comision_sat,
        e.vbytes,
        e.sat_vb,
        if e.cambio.is_empty() { "(sin cambio)" } else { &e.cambio },
        e.cambio_sat
    );
    if !e.reemplaza_a.is_empty() {
        println!("  REEMPLAZO (cancelación) de {}", e.reemplaza_a);
    }
    println!(
        "  intentos de difusión {} · reintentar {} · cancelar liberando {} · cancelar por reemplazo {}",
        e.intentos_difusion,
        if e.puede_reintentar { "sí" } else { "no" },
        if e.puede_cancelar { "sí" } else { "no" },
        if e.puede_reemplazar { "sí" } else { "no" }
    );
    if matches!(e.estado, EstadoEnvio::Enviada | EstadoEnvio::Confirmada) {
        if let Some(x) = explorador(e.red) {
            println!("  explorador: {x}/tx/{}", e.txid);
        }
    }
}

fn mostrar_revision(p: &PsbtPendiente) {
    println!(
        "REVISIÓN (pendiente {}, caduca en {} s):",
        p.id, p.caduca_en_s
    );
    println!("  red:      {}", p.red);
    println!("  destino:  {}", p.destino);
    println!("  importe:  {} sat", p.importe_sat);
    println!(
        "  comisión: {} sat (≈{} vB, {:.2} sat/vB; máximo aceptado {} sat)",
        p.comision_sat, p.vbytes, p.sat_vb, p.max_comision_sat
    );
    println!(
        "  cambio:   {} ({} sat)",
        if p.cambio.is_empty() {
            "(sin cambio)"
        } else {
            &p.cambio
        },
        p.cambio_sat
    );
    if !p.reemplaza_a.is_empty() {
        println!(
            "  REEMPLAZA A: {} (gasta todas sus entradas; el destino es propio)",
            p.reemplaza_a
        );
    }
}

/// Separa las opciones (--algo [valor]) de los argumentos posicionales.
fn opciones(args: &[String]) -> (Vec<String>, bool, bool, Option<u64>) {
    let mut pos = Vec::new();
    let (mut completo, mut sin_difundir, mut max) = (false, false, None);
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--completo" => completo = true,
            "--sin-difundir" => sin_difundir = true,
            "--max-comision" => {
                max = args.get(i + 1).and_then(|v| v.parse().ok());
                i += 1;
            }
            a => pos.push(a.to_string()),
        }
        i += 1;
    }
    (pos, completo, sin_difundir, max)
}

fn ejecutar(args: &[String]) -> Result<(), String> {
    let (pos, completo, sin_difundir, max_comision) = opciones(args);
    let orden = pos.get(1).map(String::as_str).unwrap_or("");
    let dir = pos.get(2).map(PathBuf::from);
    let e = |x: x39_wallet::Error| x.to_string();
    match orden {
        "crear" => {
            let dir = dir.ok_or("falta <dir>")?;
            let red: Red = pos.get(3).map(String::as_str).unwrap_or("signet").parse().map_err(e)?;
            if red == Red::Mainnet {
                return Err("herramienta de pruebas: mainnet no".into());
            }
            std::fs::create_dir_all(&dir).map_err(|x| x.to_string())?;
            std::fs::set_permissions(&dir, std::os::unix::fs::PermissionsExt::from_mode(0o700))
                .map_err(|x| x.to_string())?;
            let s = nueva_semilla().map_err(e)?;
            // Solo aquí, en la herramienta de pruebas, el mnemónico se trata como texto.
            let texto = Zeroizing::new(String::from_utf8(s.to_vec()).map_err(|_| "semilla no UTF-8")?);
            let mut f = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(dir.join(SEMILLA))
                .map_err(|x| format!("semilla: {x}"))?;
            writeln!(
                f,
                "# SEMILLA DE PRUEBA — {red} — SIN VALOR. Generada por x39-billetera-prueba.\n# No usar nunca en mainnet ni para fondos reales.\n{}",
                texto.as_str()
            )
            .map_err(|x| x.to_string())?;
            std::fs::write(dir.join(RED_FICHERO), format!("{red}\n")).map_err(|x| x.to_string())?;
            Billetera::crear(&s, red, &dir.join(ESTADO)).map_err(e)?;
            println!("billetera de prueba creada en {} ({red})", dir.display());
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
            let srv = servidor_anunciado(red)?;
            let mut b = Billetera::abrir(&dir.join(ESTADO), red).map_err(e)?;
            if completo {
                b.escanear(&srv).map_err(e)?;
            } else {
                b.sincronizar(&srv).map_err(e)?;
            }
            println!("punta de la red: {}", altura_red(red, &srv).map_err(e)?);
            informe(&b);
        }
        "comision" => {
            let dir = dir.ok_or("falta <dir>")?;
            let red = red_de(&dir)?;
            let srv = servidor_anunciado(red)?;
            let n: usize = pos.get(3).and_then(|x| x.parse().ok()).unwrap_or(2);
            println!("{:.2} sat/vB para {n} bloques", estimar_comision(red, &srv, n).map_err(e)?);
        }
        "enviar" => {
            let dir = dir.ok_or("falta <dir>")?;
            let destino = pos.get(3).ok_or("falta <destino>")?;
            let sat: u64 = pos.get(4).and_then(|x| x.parse().ok()).ok_or("falta <sat>")?;
            let tasa: f64 = pos.get(5).and_then(|x| x.parse().ok()).ok_or("falta <sat/vB>")?;
            let max = max_comision.unwrap_or(MAX_COMISION_DEFECTO);
            let red = red_de(&dir)?;
            let srv = servidor_anunciado(red)?;
            let mut b = Billetera::abrir(&dir.join(ESTADO), red).map_err(e)?;
            b.sincronizar(&srv).map_err(e)?;
            let p = b.preparar_psbt(destino, sat, tasa, max).map_err(e)?;
            mostrar_revision(&p);
            let info = {
                let s = leer_semilla(&dir)?;
                b.firmar_y_extraer(&p.id, &s).map_err(e)?
            };
            println!("FIRMADA (re-verificada contra la revisión):");
            mostrar_envio(&info);
            if sin_difundir {
                println!("hex: {}", b.transaccion_hex(&info.txid).unwrap_or_default());
                println!(
                    "no difundida: las monedas quedan reservadas. `difundir {} {}` la envía (la misma), `cancelar` las libera.",
                    dir.display(),
                    info.txid
                );
                return Ok(());
            }
            let info = b.difundir(&srv, &info.txid).map_err(e)?;
            println!("DIFUSIÓN:");
            mostrar_envio(&info);
            informe(&b);
        }
        "envios" => {
            let dir = dir.ok_or("falta <dir>")?;
            let b = Billetera::abrir(&dir.join(ESTADO), red_de(&dir)?).map_err(e)?;
            let v = b.envios();
            println!("envíos firmados: {}", v.len());
            for en in v {
                mostrar_envio(&en);
            }
        }
        "difundir" => {
            let dir = dir.ok_or("falta <dir>")?;
            let txid = pos.get(3).ok_or("falta <txid>")?;
            let red = red_de(&dir)?;
            let srv = servidor_anunciado(red)?;
            let mut b = Billetera::abrir(&dir.join(ESTADO), red).map_err(e)?;
            let info = b.difundir(&srv, txid).map_err(e)?;
            mostrar_envio(&info);
        }
        "cancelar" => {
            let dir = dir.ok_or("falta <dir>")?;
            let txid = pos.get(3).ok_or("falta <txid>")?;
            let red = red_de(&dir)?;
            let mut b = Billetera::abrir(&dir.join(ESTADO), red).map_err(e)?;
            let actual = b.estado_envio(txid).ok_or("envío desconocido")?;
            if actual.puede_cancelar {
                println!("nunca se difundió: se cancela liberando las monedas");
                let info = b.cancelar(txid).map_err(e)?;
                mostrar_envio(&info);
                informe(&b);
                return Ok(());
            }
            if !actual.puede_reemplazar {
                return Err(format!("no se puede cancelar: está {} — {}", actual.estado, actual.motivo));
            }
            let srv = servidor_anunciado(red)?;
            let tasa: f64 = match pos.get(4) {
                Some(x) => x.parse().map_err(|_| "sat/vB inválido")?,
                None => {
                    let t = estimar_comision(red, &srv, 2).map_err(e)?.max(2.0);
                    println!("tasa del reemplazo: {t:.2} sat/vB (estimación del servidor)");
                    t
                }
            };
            println!("ya se intentó difundir: se cancela con un REEMPLAZO hacia una dirección propia");
            b.sincronizar(&srv).map_err(e)?;
            let p = b.preparar_cancelacion(txid, tasa).map_err(e)?;
            mostrar_revision(&p);
            let info = {
                let s = leer_semilla(&dir)?;
                b.firmar_y_extraer(&p.id, &s).map_err(e)?
            };
            println!("REEMPLAZO FIRMADO:");
            mostrar_envio(&info);
            let info = b.difundir(&srv, &info.txid).map_err(e)?;
            println!("DIFUSIÓN DEL REEMPLAZO:");
            mostrar_envio(&info);
            informe(&b);
        }
        _ => {
            return Err(
                "orden desconocida (crear, direccion, sincronizar, comision, enviar, envios, difundir, cancelar)".into(),
            )
        }
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
