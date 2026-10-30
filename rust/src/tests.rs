//! Pruebas del motor. Las unitarias usan regtest con un servidor Electrum SIMULADO
//! (`mock_electrum`); las marcadas `#[ignore]` hablan con un servidor público de signet
//! (`cargo test -- --ignored`). Por defecto mainnet está prohibida y se prueba que lo
//! está; con `--features mainnet` se prueba lo contrario.

use super::mock_electrum::{Difusion, Simulado};
use super::*;
use bdk_wallet::chain::{BlockId, ConfirmationBlockTime, TxUpdate};
use bdk_wallet::Update;
use std::time::Duration;

/// Vector público de BIP39/BIP84: la única semilla que aparece en el código.
const MN: &str =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
/// Otro vector público de BIP39, para "otra billetera".
const OTRA: &str = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong";
/// Servidor público de signet para las pruebas `#[ignore]`.
const SIGNET: &str = "ssl://mempool.space:60602";
const FONDOS: u64 = 100_000;

fn mn() -> &'static [u8] {
    MN.as_bytes()
}

// Equivalentes a las utilidades de prueba de bdk_wallet (feature no activada): extienden
// la cadena local, insertan una transacción y la anclan a un bloque.
fn insert_checkpoint(w: &mut Wallet, bloque: BlockId) {
    let cp = w.latest_checkpoint().insert(bloque);
    w.apply_update(Update {
        chain: Some(cp),
        ..Default::default()
    })
    .unwrap();
}

fn insert_tx(w: &mut Wallet, tx: Transaction) {
    let mut tx_update = TxUpdate::default();
    tx_update.txs = vec![Arc::new(tx)];
    w.apply_update(Update {
        tx_update,
        ..Default::default()
    })
    .unwrap();
}

fn insert_anchor(w: &mut Wallet, txid: Txid, ancla: ConfirmationBlockTime) {
    let mut tx_update = TxUpdate::default();
    tx_update.anchors = [(ancla, txid)].into();
    w.apply_update(Update {
        tx_update,
        ..Default::default()
    })
    .unwrap();
}

/// Mete en la billetera (y en el simulado) una transacción confirmada de `sat` a su
/// primera dirección, en un bloque nuevo cuya raíz de Merkle es esa transacción.
fn fondear(b: &mut Billetera, sim: &Simulado, sat: u64) -> Txid {
    let (indice, dir) = b.nueva_direccion().unwrap();
    let addr = Address::from_str(&dir).unwrap().assume_checked();
    // Padre (una coinbase ajena, distinta para cada depósito): BDK pide al servidor las
    // transacciones previas de cada entrada para calcular comisiones, así que el simulado
    // tiene que conocerla.
    let padre = Transaction {
        version: Version::TWO,
        lock_time: LockTime::from_height(indice).unwrap(),
        input: vec![TxIn {
            previous_output: OutPoint::null(),
            script_sig: bitcoin::ScriptBuf::from_bytes(vec![0x51]),
            sequence: Sequence::MAX,
            witness: Witness::new(),
        }],
        output: vec![TxOut {
            value: Amount::from_sat(sat + 1_000),
            script_pubkey: bitcoin::ScriptBuf::from_bytes(vec![0x51]),
        }],
    };
    sim.registrar_tx(&padre, 1);
    let tx = Transaction {
        version: Version::TWO,
        lock_time: LockTime::ZERO,
        input: vec![TxIn {
            previous_output: OutPoint {
                txid: padre.compute_txid(),
                vout: 0,
            },
            script_sig: Default::default(),
            sequence: Sequence::MAX,
            witness: Witness::new(),
        }],
        output: vec![TxOut {
            value: Amount::from_sat(sat),
            script_pubkey: addr.script_pubkey(),
        }],
    };
    let txid = tx.compute_txid();
    let (h, cab) = sim.anadir_bloque(txid);
    sim.registrar_tx(&tx, h as i64);
    let bloque = BlockId {
        height: h,
        hash: cab.block_hash(),
    };
    insert_checkpoint(&mut b.cartera, bloque);
    insert_tx(&mut b.cartera, tx);
    insert_anchor(
        &mut b.cartera,
        txid,
        ConfirmationBlockTime {
            block_id: bloque,
            confirmation_time: cab.time as u64,
        },
    );
    b.guardar().unwrap();
    txid
}

fn billetera_regtest(dir: &Path, sim: &Simulado) -> Billetera {
    let mut b = Billetera::crear(mn(), Red::Regtest, &dir.join("b.dat")).unwrap();
    b.fijar_espera(Duration::from_secs(1));
    fondear(&mut b, sim, FONDOS);
    b
}

fn destino_regtest() -> String {
    direccion_recibir(OTRA.as_bytes(), Network::Regtest, 0).unwrap()
}

/// Prepara y firma un envío de 40 000 sat; devuelve la info ya firmada (sin difundir).
fn firmar_40k(b: &mut Billetera) -> EnvioInfo {
    let p = b
        .preparar_psbt(&destino_regtest(), 40_000, 2.0, 5_000)
        .unwrap();
    b.firmar_y_extraer(&p.id, mn()).unwrap()
}

// ----- Derivación y firma (sin red) -------------------------------------------------

#[test]
fn bip84_direccion_vector() {
    // Vector oficial de BIP84 (sale del descriptor de BDK). Se mira por dentro para que
    // la prueba valga también con la feature `lab`, que prohíbe mainnet en la API pública.
    let (ext, _) = descriptores_publicos(mn(), Network::Bitcoin).unwrap();
    let a = |i| {
        ext.at_derivation_index(i)
            .unwrap()
            .address(Network::Bitcoin)
            .unwrap()
            .to_string()
    };
    assert_eq!(a(0), "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu");
    assert_eq!(a(1), "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g");
}

#[test]
fn bip84_bdk_igual_que_a_mano() {
    // El descriptor de BDK y la derivación a mano dan lo mismo, externa e interna, en signet.
    let secp = Secp256k1::new();
    let red = Network::Signet;
    let r = raiz(mn(), red).unwrap();
    let (ext, int) = descriptores_publicos(mn(), red).unwrap();
    for i in 0..5 {
        for (rama, d) in [(0, &ext), (1, &int)] {
            let (_, cpk) = clave_en(&secp, &r, red, rama, i).unwrap();
            let a_mano = Address::p2wpkh(&cpk, red).to_string();
            let bdk = d
                .at_derivation_index(i)
                .unwrap()
                .address(red)
                .unwrap()
                .to_string();
            assert_eq!(a_mano, bdk, "rama {rama} índice {i}");
        }
    }
}

#[test]
fn nueva_semilla_12_palabras_en_bytes() {
    let s = nueva_semilla().unwrap();
    let texto = std::str::from_utf8(&s).unwrap();
    assert_eq!(texto.split_whitespace().count(), 12);
    // y deriva una dirección válida
    assert!(direccion_recibir(&s, Network::Testnet, 0)
        .unwrap()
        .starts_with("tb1"));
    // bytes que no son un mnemónico: Semilla
    assert_eq!(
        direccion_recibir(b"esto no es una semilla", Network::Signet, 0).unwrap_err(),
        Error::Semilla
    );
    assert_eq!(
        direccion_recibir(&[0xff, 0xfe], Network::Signet, 0).unwrap_err(),
        Error::Semilla
    );
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
    let destino = direccion_recibir(mn(), red, 5).unwrap(); // a otra de las nuestras, da igual
    let hex = firmar_envio(mn(), red, &utxos, &destino, 60_000, 1_000).unwrap();

    // Reconstruir la tx y VERIFICAR la firma del testigo de la entrada 0.
    let bytes = hex::decode(&hex).unwrap();
    let tx: Transaction = bitcoin::consensus::encode::deserialize(&bytes).unwrap();
    assert_eq!(tx.input.len(), 1);
    assert_eq!(tx.output.len(), 2, "destino + cambio");

    let secp = Secp256k1::new();
    let raiz = raiz(mn(), red).unwrap();
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
        let mut b = Billetera::crear(mn(), Red::Signet, &f).unwrap();
        d0 = b.nueva_direccion().unwrap();
        d1 = b.nueva_direccion().unwrap();
        assert_ne!(d0.1, d1.1, "cada petición da una dirección nueva");
        assert_eq!((d0.0, d1.0), (0, 1));
        assert!(d0.1.starts_with("tb1q"));
    }
    // El fichero no lleva claves privadas extendidas.
    let bytes = std::fs::read(&f).unwrap();
    let txt = String::from_utf8_lossy(&bytes);
    assert!(
        !txt.contains("tprv") && !txt.contains("xprv"),
        "el estado no debe llevar claves privadas"
    );
    assert!(txt.contains("tpub"), "sí lleva los descriptores públicos");
    // Al reabrir (sin semilla) sigue por donde iba.
    let mut b = Billetera::abrir(&f, Red::Signet).unwrap();
    assert_eq!(b.nueva_direccion().unwrap().0, 2);
    // Y no se abre como si fuera de otra red.
    drop(b);
    assert!(Billetera::abrir(&f, Red::Mainnet).is_err());
    assert!(matches!(
        Billetera::abrir(&f, Red::Regtest),
        Err(Error::Fichero(_))
    ));
    // Ni se crea encima de una existente.
    assert!(Billetera::crear(mn(), Red::Signet, &f).is_err());
}

#[test]
fn firmar_exige_la_semilla_de_esta_billetera() {
    let dir = tempfile::tempdir().unwrap();
    let f = dir.path().join("b.dat");
    let mut b = Billetera::crear(mn(), Red::Signet, &f).unwrap();
    let otra = nueva_semilla().unwrap();
    let dest = direccion_recibir(&otra, Network::Signet, 0).unwrap();
    assert_eq!(
        b.preparar_envio(&otra, &dest, 10_000, 1.0).unwrap_err(),
        Error::Semilla
    );
    // Con la buena, sin fondos: Fondos.
    assert_eq!(
        b.preparar_envio(mn(), &dest, 10_000, 1.0).unwrap_err(),
        Error::Fondos
    );
    // Dirección de mainnet en una billetera de signet: rechazada.
    assert_eq!(
        b.preparar_envio(
            mn(),
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            10_000,
            1.0
        )
        .unwrap_err(),
        Error::Direccion
    );
    // Lo mismo por la vía nueva, sin semilla.
    assert_eq!(
        b.preparar_psbt(&dest, 10_000, 1.0, 1_000).unwrap_err(),
        Error::Fondos
    );
    assert_eq!(
        b.preparar_psbt(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            10_000,
            1.0,
            1_000
        )
        .unwrap_err(),
        Error::Direccion
    );
}

#[test]
fn una_sola_billetera_por_fichero_en_el_proceso() {
    let dir = tempfile::tempdir().unwrap();
    let f = dir.path().join("b.dat");
    let b1 = Billetera::crear(mn(), Red::Signet, &f).unwrap();
    // Segunda instancia sobre el mismo fichero: no.
    assert_eq!(
        Billetera::abrir(&f, Red::Signet).err(),
        Some(Error::Fichero("ya abierta en este proceso".into()))
    );
    // Por otra ruta equivalente al mismo fichero, tampoco.
    let otra_ruta = dir.path().join(".").join("b.dat");
    assert_eq!(
        Billetera::abrir(&otra_ruta, Red::Signet).err(),
        Some(Error::Fichero("ya abierta en este proceso".into()))
    );
    // Por un enlace simbólico al mismo fichero, tampoco.
    #[cfg(unix)]
    {
        let enlace = dir.path().join("enlace.dat");
        std::os::unix::fs::symlink(&f, &enlace).unwrap();
        assert_eq!(
            Billetera::abrir(&enlace, Red::Signet).err(),
            Some(Error::Fichero("ya abierta en este proceso".into()))
        );
        // Y crear sobre un enlace simbólico, aunque esté colgando, se niega.
        let colgando = dir.path().join("colgando.dat");
        std::os::unix::fs::symlink(dir.path().join("no-existe.dat"), &colgando).unwrap();
        assert_eq!(
            Billetera::crear(mn(), Red::Signet, &colgando).err(),
            Some(Error::Fichero("la ruta es un enlace simbólico".into()))
        );
        assert_eq!(
            Billetera::crear(mn(), Red::Signet, &enlace).err(),
            Some(Error::Fichero("la ruta es un enlace simbólico".into()))
        );
    }
    // Soltar la primera libera el fichero.
    drop(b1);
    let b2 = Billetera::abrir(&f, Red::Signet).unwrap();
    drop(b2);
    // Un crear que falla (fichero existente) no deja el fichero reservado.
    assert!(matches!(
        Billetera::crear(mn(), Red::Signet, &f),
        Err(Error::Fichero(_))
    ));
    assert!(Billetera::abrir(&f, Red::Signet).is_ok());
    assert_eq!(
        REDES_PERMITIDAS,
        if cfg!(feature = "mainnet") {
            "x39wallet-redes=mainnet"
        } else {
            "x39wallet-redes=solo-prueba"
        }
    );
}

#[test]
fn coincide_semilla_sin_firmar_ni_guardar() {
    let dir = tempfile::tempdir().unwrap();
    let f = dir.path().join("b.dat");
    let b = Billetera::crear(mn(), Red::Signet, &f).unwrap();
    let antes = std::fs::read(&f).unwrap();
    assert!(b.coincide_semilla(mn()));
    assert!(!b.coincide_semilla(OTRA.as_bytes()));
    assert!(!b.coincide_semilla(b"esto no es un mnemonico"));
    assert!(!b.coincide_semilla(&[0xff, 0xfe]));
    assert_eq!(std::fs::read(&f).unwrap(), antes, "no escribe nada");
    assert!(!envios::Registro::ruta_de(&f).exists());
}

#[test]
fn red_desde_texto() {
    assert_eq!("signet".parse::<Red>().unwrap(), Red::Signet);
    assert_eq!("testnet".parse::<Red>().unwrap(), Red::Testnet);
    assert_eq!("regtest".parse::<Red>().unwrap(), Red::Regtest);
    assert!("nada".parse::<Red>().is_err());
    assert_eq!(Red::Regtest.network(), Network::Regtest);
    assert_eq!(Red::de_network(Network::Regtest), Some(Red::Regtest));
}

#[cfg(feature = "mainnet")]
#[test]
fn con_feature_mainnet_se_puede_nombrar() {
    assert_eq!("mainnet".parse::<Red>().unwrap(), Red::Mainnet);
}

// ----- Por defecto (sin la feature `mainnet`): mainnet prohibida en toda la API pública --

#[cfg(not(feature = "mainnet"))]
mod sin_mainnet {
    use super::*;

    #[test]
    fn mainnet_prohibida_en_toda_la_api() {
        let dir = tempfile::tempdir().unwrap();
        let f = dir.path().join("m.dat");
        assert_eq!("mainnet".parse::<Red>().unwrap_err(), Error::RedProhibida);
        assert_eq!("bitcoin".parse::<Red>().unwrap_err(), Error::RedProhibida);
        assert_eq!(
            Billetera::crear(mn(), Red::Mainnet, &f).err(),
            Some(Error::RedProhibida)
        );
        assert!(!f.exists(), "no se crea ningún fichero");
        assert_eq!(
            direccion_recibir(mn(), Network::Bitcoin, 0).unwrap_err(),
            Error::RedProhibida
        );
        assert_eq!(
            firmar_envio(
                mn(),
                Network::Bitcoin,
                &[],
                "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
                1_000,
                100
            )
            .unwrap_err(),
            Error::RedProhibida
        );
        // Se rechaza ANTES de intentar conectar con nadie.
        assert_eq!(
            altura_red(Red::Mainnet, "tcp://sin-servidor:1").unwrap_err(),
            Error::RedProhibida
        );
        assert_eq!(
            estimar_comision(Red::Mainnet, "tcp://sin-servidor:1", 2).unwrap_err(),
            Error::RedProhibida
        );
        // Abrir un estado (de signet) como mainnet: prohibido también.
        let s = dir.path().join("s.dat");
        Billetera::crear(mn(), Red::Signet, &s).unwrap();
        assert_eq!(
            Billetera::abrir(&s, Red::Mainnet).err(),
            Some(Error::RedProhibida)
        );
    }
}

// ----- Regtest con servidor simulado ---------------------------------------------------

#[test]
fn regtest_sincroniza_con_el_simulado() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    b.sincronizar(&sim.url).unwrap();
    assert_eq!(b.altura(), 1);
    assert_eq!(b.saldo().confirmado, FONDOS);
    assert_eq!(b.movimientos().len(), 1);
    assert_eq!(altura_red(Red::Regtest, &sim.url).unwrap(), 1);
    assert_eq!(estimar_comision(Red::Regtest, &sim.url, 2).unwrap(), 2.0);
}

#[test]
fn regtest_sincronizar_y_escanear_persisten_sin_envios() {
    // Sin ningún envío en curso, sincronizar y escanear deben dejar en disco la altura,
    // los índices y las transacciones: al reabrir no se vuelve a empezar de cero ni se
    // repite una dirección ya usada. (Fallo visto en el banco regtest al restaurar.)
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let f = dir.path().join("b.dat");
    {
        let mut b = billetera_regtest(dir.path(), &sim);
        b.sincronizar(&sim.url).unwrap();
        assert_eq!(b.altura(), 1);
    }
    {
        let mut b = Billetera::abrir(&f, Red::Regtest).unwrap();
        assert_eq!(b.altura(), 1, "la altura sincronizada se persistió");
        assert_eq!(b.saldo().confirmado, FONDOS);
        assert_eq!(b.movimientos().len(), 1);
        assert_eq!(
            b.nueva_direccion().unwrap().0,
            1,
            "la dirección 0 ya se reveló antes"
        );
    }
    // Restaurar desde la semilla en otro fichero: el escaneo encuentra la dirección usada y
    // al reabrir la siguiente dirección nueva es posterior a ella.
    let g = dir.path().join("restaurada.dat");
    {
        let mut r = Billetera::crear(mn(), Red::Regtest, &g).unwrap();
        r.fijar_espera(Duration::from_secs(1));
        r.escanear(&sim.url).unwrap();
        assert_eq!(r.saldo().confirmado, FONDOS);
        assert_eq!(r.indices().0, Some(0));
    }
    {
        let mut r = Billetera::abrir(&g, Red::Regtest).unwrap();
        assert_eq!(r.altura(), 1);
        assert_eq!(r.saldo().confirmado, FONDOS, "el escaneo se persistió");
        assert_eq!(r.movimientos().len(), 1);
        assert_eq!(
            r.nueva_direccion().unwrap().0,
            1,
            "no se repite la dirección usada"
        );
    }
}

#[test]
fn servidor_de_otra_red_rechazado() {
    let dir = tempfile::tempdir().unwrap();
    // El simulado anuncia el génesis de SIGNET; la billetera es de regtest.
    let sim = Simulado::arrancar(Network::Signet);
    let mut b = billetera_regtest(dir.path(), &sim);
    assert!(matches!(
        b.sincronizar(&sim.url).unwrap_err(),
        Error::RedServidor(_)
    ));
    assert!(matches!(
        b.escanear(&sim.url).unwrap_err(),
        Error::RedServidor(_)
    ));
    assert!(matches!(
        altura_red(Red::Regtest, &sim.url).unwrap_err(),
        Error::RedServidor(_)
    ));
    assert!(matches!(
        estimar_comision(Red::Regtest, &sim.url, 2).unwrap_err(),
        Error::RedServidor(_)
    ));
    // Pedido como signet, el mismo servidor vale.
    assert_eq!(altura_red(Red::Signet, &sim.url).unwrap(), 1);
    // Y una tx firmada no se intenta difundir a un servidor de otra red: estado intacto.
    let e = firmar_40k(&mut b);
    assert!(matches!(
        b.difundir(&sim.url, &e.txid).unwrap_err(),
        Error::RedServidor(_)
    ));
    let e = b.estado_envio(&e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Firmada);
    assert_eq!(e.intentos_difusion, 0, "no conectar no cuenta como intento");
    assert!(e.puede_cancelar, "nunca salió del aparato");
}

#[test]
fn regtest_corte_entre_transmitir_y_anotar_deja_incierta() {
    // Se anota el inicio del intento (Difundiendo) y el proceso "muere" antes de anotar el
    // resultado: al reabrir es incierta con el intento contado; nunca "nunca difundida".
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let f = dir.path().join("b.dat");
    let (txid, cambio_sat) = {
        let mut b = billetera_regtest(dir.path(), &sim);
        let e = firmar_40k(&mut b);
        b.envios.iniciar_difusion(&e.txid, ahora_s()).unwrap();
        assert_eq!(
            b.estado_envio(&e.txid).unwrap().estado,
            EstadoEnvio::Difundiendo
        );
        assert!(b.envio_en_curso().is_some());
        (e.txid, e.cambio_sat)
    };
    let txt = std::fs::read_to_string(envios::Registro::ruta_de(&f)).unwrap();
    assert!(
        txt.contains("Difundiendo"),
        "el estado intermedio está en disco"
    );
    let mut b = Billetera::abrir(&f, Red::Regtest).unwrap();
    b.fijar_espera(Duration::from_secs(1));
    let e = b.estado_envio(&txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Incierta);
    assert!(e.motivo.contains("interrumpida"), "{}", e.motivo);
    assert_eq!(e.intentos_difusion, 1, "el intento cuenta");
    assert!(!e.puede_cancelar && e.puede_reemplazar && e.puede_reintentar);
    assert_eq!(b.saldo().total, cambio_sat, "monedas gastadas");
    assert!(matches!(b.cancelar(&txid).unwrap_err(), Error::Envio(_)));
    assert!(matches!(
        b.preparar_psbt(&destino_regtest(), 1_000, 1.0, 1_000)
            .unwrap_err(),
        Error::EnvioEnCurso(_)
    ));
    // Reintentar la misma funciona y cuenta el segundo intento.
    let e = b.difundir(&sim.url, &txid).unwrap();
    assert_eq!((e.estado, e.intentos_difusion), (EstadoEnvio::Enviada, 2));
}

#[test]
fn regtest_rechazo_en_un_reintento_no_libera() {
    // Primer intento colgado (incierta), segundo rechazado con un texto genérico: el
    // rechazo de ahora no demuestra que el primero no llegara → incierta, sin liberar.
    for texto in ["bad-txns-inputs-missingorspent", "error interno"] {
        let dir = tempfile::tempdir().unwrap();
        let sim = Simulado::arrancar(Network::Regtest);
        let mut b = billetera_regtest(dir.path(), &sim);
        let e = firmar_40k(&mut b);
        sim.fijar_difusion(Difusion::Colgar(Duration::from_secs(4)));
        let e = b.difundir(&sim.url, &e.txid).unwrap();
        assert_eq!((e.estado, e.intentos_difusion), (EstadoEnvio::Incierta, 1));
        sim.fijar_difusion(Difusion::Rechazar(1, texto.into()));
        let e = b.difundir(&sim.url, &e.txid).unwrap();
        assert_eq!(e.estado, EstadoEnvio::Incierta, "{texto}");
        assert_eq!(e.intentos_difusion, 2);
        assert!(
            e.motivo.starts_with("rechazada:") && e.motivo.contains("no se liberan monedas"),
            "{}",
            e.motivo
        );
        assert_eq!(b.saldo().total, e.cambio_sat, "saldo sin liberar ({texto})");
        assert_eq!(b.saldo().confirmado, 0);
        assert!(!e.puede_cancelar && e.puede_reemplazar);
        assert!(b.envio_en_curso().is_some());
    }
}

#[test]
fn regtest_rechazo_en_el_primer_intento_no_libera() {
    for texto in ["bad-txns-inputs-missingorspent", "error interno"] {
        let dir = tempfile::tempdir().unwrap();
        let sim = Simulado::arrancar(Network::Regtest);
        let mut b = billetera_regtest(dir.path(), &sim);
        let e = firmar_40k(&mut b);
        sim.fijar_difusion(Difusion::Rechazar(1, texto.into()));
        let e = b.difundir(&sim.url, &e.txid).unwrap();
        assert_eq!(
            (e.estado, e.intentos_difusion),
            (EstadoEnvio::Incierta, 1),
            "{texto}"
        );
        assert_eq!(b.saldo().total, e.cambio_sat, "saldo reservado ({texto})");
        assert_eq!(b.saldo().confirmado, 0);
        assert!(e.puede_reemplazar && e.puede_reintentar && !e.puede_cancelar);
        assert!(b.envio_en_curso().is_some());
    }
}

#[test]
fn regtest_el_intento_esta_en_disco_antes_de_transmitir() {
    // Por el camino real: el simulado, al recibir la transacción, lee el fichero de envíos
    // tal como está en ese instante. Debe contener ya Difundiendo e intentos_difusion = 1.
    for despues in [Difusion::Aceptar, Difusion::Colgar(Duration::from_secs(3))] {
        let dir = tempfile::tempdir().unwrap();
        let sim = Simulado::arrancar(Network::Regtest);
        let f = dir.path().join("b.dat");
        let mut b = billetera_regtest(dir.path(), &sim);
        let e = firmar_40k(&mut b);
        let colgado = matches!(despues, Difusion::Colgar(_));
        sim.fijar_difusion(Difusion::LeerAlRecibir(
            envios::Registro::ruta_de(&f),
            Box::new(despues),
        ));
        let e = b.difundir(&sim.url, &e.txid).unwrap();
        assert_eq!(
            e.estado,
            if colgado {
                EstadoEnvio::Incierta
            } else {
                EstadoEnvio::Enviada
            }
        );
        let capturas = sim.capturas();
        assert!(
            !capturas.is_empty(),
            "el simulado leyó el fichero al recibir la transacción"
        );
        let json: serde_json::Value = serde_json::from_str(&capturas[0]).unwrap();
        let apunte = json["envios"]
            .as_array()
            .unwrap()
            .iter()
            .find(|a| a["info"]["txid"] == e.txid)
            .expect("la transacción estaba en el fichero");
        assert_eq!(
            apunte["info"]["estado"], "Difundiendo",
            "anotada antes de que el servidor responda"
        );
        assert_eq!(apunte["info"]["intentos_difusion"], 1);
        assert_eq!(json["version"], 3);
    }
}

#[test]
fn regtest_preparar_revisar_firmar() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    let dest = destino_regtest();

    let p = b.preparar_psbt(&dest, 40_000, 2.0, 5_000).unwrap();
    assert_eq!(p.destino, dest);
    assert_eq!(p.importe_sat, 40_000);
    assert_eq!(p.red, Red::Regtest);
    assert!(
        p.comision_sat > 0 && p.comision_sat <= 5_000,
        "comisión {}",
        p.comision_sat
    );
    assert!(
        p.cambio.starts_with("bcrt1q"),
        "el cambio es una dirección propia de regtest"
    );
    assert_eq!(p.cambio_sat, FONDOS - 40_000 - p.comision_sat);
    assert!(
        p.sat_vb >= 1.9 && p.sat_vb <= 2.6,
        "≈2 sat/vB: {}",
        p.sat_vb
    );
    assert_eq!(b.psbt_pendiente(), Some(p.clone()));

    // Id equivocado: no se firma y el pendiente se descarta.
    assert!(matches!(
        b.firmar_y_extraer("otro", mn()).unwrap_err(),
        Error::Pendiente(_)
    ));
    assert_eq!(b.psbt_pendiente(), None);
    assert!(matches!(
        b.firmar_y_extraer(&p.id, mn()).unwrap_err(),
        Error::Pendiente(_)
    ));

    // Semilla de otra billetera: Semilla.
    let p2 = b.preparar_psbt(&dest, 40_000, 2.0, 5_000).unwrap();
    assert_ne!(p2.id, p.id);
    assert_eq!(
        b.firmar_y_extraer(&p2.id, OTRA.as_bytes()).unwrap_err(),
        Error::Semilla
    );

    // Con la buena: firmada y gastada localmente ANTES de difundir.
    let p3 = b.preparar_psbt(&dest, 40_000, 2.0, 5_000).unwrap();
    let e = b.firmar_y_extraer(&p3.id, mn()).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Firmada);
    assert_eq!(e.id, p3.id);
    assert_eq!(e.destino, dest);
    assert_eq!(e.importe_sat, 40_000);
    assert_eq!(
        e.comision_sat, p3.comision_sat,
        "la comisión firmada es la revisada"
    );
    assert!(
        e.vbytes <= p3.vbytes,
        "el tamaño real ({}) no supera el estimado ({})",
        e.vbytes,
        p3.vbytes
    );
    assert_eq!(
        (e.cambio.as_str(), e.cambio_sat),
        (p3.cambio.as_str(), p3.cambio_sat)
    );
    assert!(e.puede_reintentar && e.puede_cancelar);
    let s = b.saldo();
    assert_eq!(
        (s.confirmado, s.pendiente, s.total),
        (0, e.cambio_sat, e.cambio_sat),
        "monedas gastadas localmente"
    );
    assert_eq!(b.envio_en_curso().unwrap().txid, e.txid);
    assert!(b.transaccion_hex(&e.txid).is_some());
    // Un segundo envío con las mismas monedas: imposible mientras no se resuelva.
    assert_eq!(
        b.preparar_psbt(&dest, 1_000, 1.0, 1_000).unwrap_err(),
        Error::EnvioEnCurso(e.txid.clone())
    );
    // La transacción firmada tiene exactamente las salidas revisadas.
    let tx: Transaction =
        bitcoin::consensus::encode::deserialize_hex(&b.transaccion_hex(&e.txid).unwrap()).unwrap();
    assert_eq!(tx.compute_txid().to_string(), e.txid);
    assert_eq!(tx.output.len(), 2);
    assert!(tx.output.iter().any(|o| o.value.to_sat() == 40_000));
    assert!(tx.output.iter().any(|o| o.value.to_sat() == e.cambio_sat));
}

#[test]
fn regtest_un_solo_pendiente_y_caducidad() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    let dest = destino_regtest();
    let a = b.preparar_psbt(&dest, 40_000, 2.0, 5_000).unwrap();
    let bb = b.preparar_psbt(&dest, 30_000, 2.0, 5_000).unwrap();
    assert_eq!(
        b.psbt_pendiente().unwrap().id,
        bb.id,
        "el segundo invalida al primero"
    );
    assert!(matches!(
        b.firmar_y_extraer(&a.id, mn()).unwrap_err(),
        Error::Pendiente(_)
    ));
    // Caducidad.
    let c = b.preparar_psbt(&dest, 30_000, 2.0, 5_000).unwrap();
    let creado = b.pendiente.as_ref().unwrap().creado;
    b.pendiente.as_mut().unwrap().creado = creado
        .checked_sub(CADUCIDAD_PSBT + Duration::from_secs(1))
        .unwrap_or(creado);
    if b.pendiente.as_ref().unwrap().creado != creado {
        assert_eq!(b.psbt_pendiente(), None);
        assert!(matches!(
            b.firmar_y_extraer(&c.id, mn()).unwrap_err(),
            Error::Pendiente(_)
        ));
    }
    // Nada se firmó: las monedas siguen libres.
    assert_eq!(b.saldo().confirmado, FONDOS);
    assert!(b.envio_en_curso().is_none());
}

#[test]
fn regtest_revision_detecta_psbt_alterado() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    let dest = destino_regtest();

    // 1) Cambia el PSBT por debajo: el hash ya no cuadra.
    let p = b.preparar_psbt(&dest, 40_000, 2.0, 5_000).unwrap();
    {
        let pend = b.pendiente.as_mut().unwrap();
        let o = pend
            .psbt
            .unsigned_tx
            .output
            .iter_mut()
            .find(|o| o.value.to_sat() == 40_000)
            .unwrap();
        o.value = Amount::from_sat(40_001);
    }
    let err = b.firmar_y_extraer(&p.id, mn()).unwrap_err();
    assert!(
        matches!(err, Error::Revision(ref m) if m.contains("cambiado")),
        "{err}"
    );

    // 2) Mismo truco pero con el hash "arreglado": las salidas ya no son las declaradas.
    let p = b.preparar_psbt(&dest, 40_000, 2.0, 5_000).unwrap();
    {
        let pend = b.pendiente.as_mut().unwrap();
        let o = pend
            .psbt
            .unsigned_tx
            .output
            .iter_mut()
            .find(|o| o.value.to_sat() == 40_000)
            .unwrap();
        o.script_pubkey =
            Address::from_str(&direccion_recibir(OTRA.as_bytes(), Network::Regtest, 7).unwrap())
                .unwrap()
                .assume_checked()
                .script_pubkey();
        pend.info.hash_psbt = Billetera::hash_psbt(&pend.psbt);
    }
    let err = b.firmar_y_extraer(&p.id, mn()).unwrap_err();
    assert!(
        matches!(err, Error::Revision(ref m) if m.contains("destino")),
        "{err}"
    );

    // 3) Comisión declarada distinta de la real.
    let p = b.preparar_psbt(&dest, 40_000, 2.0, 5_000).unwrap();
    b.pendiente.as_mut().unwrap().info.comision_sat += 1;
    let err = b.firmar_y_extraer(&p.id, mn()).unwrap_err();
    assert!(
        matches!(err, Error::Revision(ref m) if m.contains("comisión")),
        "{err}"
    );

    // Nada se firmó.
    assert_eq!(b.saldo().confirmado, FONDOS);
    assert!(b.envios().is_empty());
}

#[test]
fn regtest_comision_sobre_el_maximo_no_se_prepara() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    let err = b
        .preparar_psbt(&destino_regtest(), 40_000, 2.0, 10)
        .unwrap_err();
    assert!(
        matches!(err, Error::Envio(ref m) if m.contains("supera")),
        "{err}"
    );
    assert_eq!(b.psbt_pendiente(), None);
    // Importe bajo el polvo y tasa fuera de rango.
    assert!(b
        .preparar_psbt(&destino_regtest(), POLVO_SAT, 2.0, 5_000)
        .is_err());
    assert!(b
        .preparar_psbt(&destino_regtest(), 40_000, 0.5, 5_000)
        .is_err());
    assert!(b
        .preparar_psbt(&destino_regtest(), 40_000, 2_000.0, 5_000)
        .is_err());
}

#[test]
fn regtest_difusion_aceptada_y_confirmada() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    let e = firmar_40k(&mut b);

    let e = b.difundir(&sim.url, &e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Enviada);
    assert_eq!(e.intentos_difusion, 1);
    assert!(!e.puede_reintentar && !e.puede_cancelar);
    assert!(
        e.puede_reemplazar,
        "enviada pero sin confirmar: se puede cancelar por reemplazo"
    );
    assert_eq!(sim.difundidas(), vec![Txid::from_str(&e.txid).unwrap()]);
    assert!(
        b.envio_en_curso().is_none(),
        "enviada no bloquea nuevos envíos"
    );
    // Difundir de nuevo algo ya enviado no hace nada.
    assert_eq!(b.difundir(&sim.url, &e.txid).unwrap().intentos_difusion, 1);

    // El servidor la ve en la mempool.
    b.sincronizar(&sim.url).unwrap();
    let e = b.estado_envio(&e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Enviada);
    assert_eq!(b.saldo().pendiente, e.cambio_sat);
    assert_eq!(b.movimientos().len(), 2);

    // Se confirma.
    sim.confirmar(Txid::from_str(&e.txid).unwrap());
    b.sincronizar(&sim.url).unwrap();
    let e = b.estado_envio(&e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Confirmada);
    assert!(!e.puede_reemplazar && !e.puede_cancelar && !e.puede_reintentar);
    assert!(matches!(
        b.preparar_cancelacion(&e.txid, 3.0).unwrap_err(),
        Error::Envio(_)
    ));
    let s = b.saldo();
    assert_eq!((s.confirmado, s.pendiente), (e.cambio_sat, 0));
    assert_eq!(b.altura(), 2);
    let mov = b.movimientos();
    assert_eq!(mov[0].txid, e.txid);
    assert_eq!(mov[0].altura, Some(2));
    assert_eq!(mov[0].enviado_sat, FONDOS);
    assert_eq!(mov[0].recibido_sat, e.cambio_sat);
    assert_eq!(mov[0].comision_sat, Some(e.comision_sat));
    // Y se puede preparar otro.
    assert!(b
        .preparar_psbt(&destino_regtest(), 10_000, 1.0, 5_000)
        .is_ok());
}

#[test]
fn regtest_rechazo_explicito_no_libera_las_monedas() {
    // Un rechazo del servidor no demuestra que la transacción no circule (puede haberla
    // pasado al nodo y fallar después): incierta, monedas reservadas, reintento o reemplazo.
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    let e = firmar_40k(&mut b);
    assert_eq!(b.saldo().confirmado, 0);

    sim.fijar_difusion(Difusion::Rechazar(
        1,
        "rechazada por las reglas de la red".into(),
    ));
    let e = b.difundir(&sim.url, &e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Incierta);
    assert_eq!(e.intentos_difusion, 1);
    assert!(
        e.motivo.starts_with("rechazada:") && e.motivo.contains("no se liberan monedas"),
        "{}",
        e.motivo
    );
    assert!(e.puede_reintentar && e.puede_reemplazar && !e.puede_cancelar);
    assert_eq!(b.saldo().total, e.cambio_sat, "monedas reservadas");
    assert_eq!(b.saldo().confirmado, 0);
    assert!(b.envio_en_curso().is_some());
    assert!(sim.difundidas().is_empty());
    assert!(matches!(b.cancelar(&e.txid).unwrap_err(), Error::Envio(_)));
    assert!(matches!(
        b.preparar_psbt(&destino_regtest(), 1_000, 1.0, 1_000)
            .unwrap_err(),
        Error::EnvioEnCurso(_)
    ));
    // Sincronizar no la libera; el reintento de la misma, cuando el servidor acepta, llega.
    b.sincronizar(&sim.url).unwrap();
    assert_eq!(b.saldo().total, e.cambio_sat);
    sim.fijar_difusion(Difusion::Aceptar);
    let e = b.difundir(&sim.url, &e.txid).unwrap();
    assert_eq!((e.estado, e.intentos_difusion), (EstadoEnvio::Enviada, 2));
    assert_eq!(sim.difundidas(), vec![Txid::from_str(&e.txid).unwrap()]);
}

#[test]
fn regtest_difusion_incierta_nunca_reconstruye_y_reintenta_la_misma() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    let e = firmar_40k(&mut b);

    sim.fijar_difusion(Difusion::Colgar(Duration::from_secs(4)));
    let e = b.difundir(&sim.url, &e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Incierta, "{}", e.motivo);
    assert_eq!(e.intentos_difusion, 1);
    assert!(e.puede_reintentar && !e.puede_cancelar);
    assert_eq!(b.saldo().total, e.cambio_sat, "las monedas siguen gastadas");
    assert_eq!(
        b.preparar_psbt(&destino_regtest(), 1_000, 1.0, 1_000)
            .unwrap_err(),
        Error::EnvioEnCurso(e.txid.clone())
    );

    // El servidor no la tiene: por muchas sincronizaciones que pasen, las monedas NO se
    // liberan y no se puede cancelar liberándolas (ha tocado un servidor).
    sim.fijar_difusion(Difusion::Aceptar);
    for n in 1..=3 {
        b.sincronizar(&sim.url).unwrap();
        let e = b.estado_envio(&e.txid).unwrap();
        assert_eq!(e.estado, EstadoEnvio::Incierta);
        assert!(!e.puede_cancelar && e.puede_reemplazar && e.puede_reintentar);
        assert_eq!(
            b.saldo().total,
            e.cambio_sat,
            "tras sincronizar {n} veces siguen gastadas"
        );
        assert!(matches!(b.cancelar(&e.txid).unwrap_err(), Error::Envio(_)));
    }
    // Reintentar LA MISMA transacción firmada (mismo txid) cuando el servidor responde.
    let hex_antes = b.transaccion_hex(&e.txid).unwrap();
    let e2 = b.difundir(&sim.url, &e.txid).unwrap();
    assert_eq!(e2.estado, EstadoEnvio::Enviada);
    assert_eq!(e2.txid, e.txid);
    assert_eq!(e2.intentos_difusion, 2);
    assert_eq!(b.transaccion_hex(&e.txid).unwrap(), hex_antes);
    assert_eq!(sim.difundidas(), vec![Txid::from_str(&e.txid).unwrap()]);
    b.sincronizar(&sim.url).unwrap();
    assert_eq!(
        b.estado_envio(&e.txid).unwrap().estado,
        EstadoEnvio::Enviada
    );
    assert_eq!(b.saldo().pendiente, e.cambio_sat);
}

#[test]
fn regtest_ya_conocida_es_exito_y_no_libera() {
    // «txn-already-known», «already in mempool», «already in block chain» o código -27:
    // la transacción está en la red. Enviada, y las monedas siguen gastadas.
    let casos: [(i64, &str); 6] = [
        (1, "txn-already-known"),
        (1, "Transaction already in mempool"),
        (1, "transaction already in block chain"),
        (1, "already in blockchain"),
        // Bitcoin Core 28+ vía electrs 0.11 (visto en el banco regtest): código 2 y este texto.
        (2, "Transaction outputs already in utxo set"),
        (-27, "cualquier texto"),
    ];
    for (codigo, mensaje) in casos {
        let dir = tempfile::tempdir().unwrap();
        let sim = Simulado::arrancar(Network::Regtest);
        let mut b = billetera_regtest(dir.path(), &sim);
        let e = firmar_40k(&mut b);
        sim.fijar_difusion(Difusion::Rechazar(codigo, mensaje.into()));
        let e = b.difundir(&sim.url, &e.txid).unwrap();
        assert_eq!(e.estado, EstadoEnvio::Enviada, "{codigo} {mensaje}");
        assert!(e.motivo.contains("ya la conocía"), "{}", e.motivo);
        assert_eq!(e.intentos_difusion, 1);
        assert_eq!(
            b.saldo().total,
            e.cambio_sat,
            "monedas NO liberadas ({mensaje})"
        );
        assert_eq!(b.saldo().confirmado, 0);
        assert!(sim.difundidas().is_empty());
        assert!(b.envio_en_curso().is_none(), "enviada: no bloquea");
    }
    // Un rechazo de verdad con código 1 no es "ya conocida": incierta, sin liberar.
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    let e = firmar_40k(&mut b);
    sim.fijar_difusion(Difusion::Rechazar(
        1,
        "bad-txns-inputs-missingorspent".into(),
    ));
    let e = b.difundir(&sim.url, &e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Incierta);
    assert!(!e.motivo.contains("ya la conocía"));
    assert_eq!(b.saldo().total, e.cambio_sat);
}

/// Billetera con DOS depósitos confirmados y un envío que gasta los dos, firmado y con
/// difusión colgada (Incierta). Devuelve (billetera, envío).
fn incierta_con_dos_entradas(dir: &Path, sim: &Simulado) -> (Billetera, EnvioInfo) {
    let mut b = Billetera::crear(mn(), Red::Regtest, &dir.join("b.dat")).unwrap();
    b.fijar_espera(Duration::from_secs(1));
    fondear(&mut b, sim, FONDOS);
    fondear(&mut b, sim, FONDOS);
    b.sincronizar(&sim.url).unwrap();
    assert_eq!(b.saldo().confirmado, 2 * FONDOS);
    let p = b
        .preparar_psbt(&destino_regtest(), 150_000, 2.0, 5_000)
        .unwrap();
    let e = b.firmar_y_extraer(&p.id, mn()).unwrap();
    assert_eq!(tx_de(&b, &e.txid).input.len(), 2, "gasta las dos entradas");
    sim.fijar_difusion(Difusion::Colgar(Duration::from_secs(4)));
    let e = b.difundir(&sim.url, &e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Incierta);
    sim.fijar_difusion(Difusion::Aceptar);
    (b, e)
}

fn tx_de(b: &Billetera, txid: &str) -> Transaction {
    bitcoin::consensus::encode::deserialize_hex(&b.transaccion_hex(txid).unwrap()).unwrap()
}

#[test]
fn regtest_cancelar_tras_difundir_es_un_reemplazo_rbf() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let (mut b, e) = incierta_con_dos_entradas(dir.path(), &sim);
    assert!(!e.puede_cancelar && e.puede_reemplazar);
    assert!(
        matches!(b.cancelar(&e.txid).unwrap_err(), Error::Envio(_)),
        "ha tocado un servidor: no se libera"
    );

    // El reemplazo: todas las entradas de la original, a una dirección propia, más comisión.
    let p = b.preparar_cancelacion(&e.txid, 3.0).unwrap();
    assert_eq!(p.reemplaza_a, e.txid);
    assert!(p.cambio.is_empty() && p.cambio_sat == 0);
    assert!(p.destino.starts_with("bcrt1q"));
    let spk_dest = Address::from_str(&p.destino)
        .unwrap()
        .assume_checked()
        .script_pubkey();
    assert!(b.cartera.is_mine(spk_dest), "paga a una dirección propia");
    assert_eq!(p.importe_sat, 2 * FONDOS - p.comision_sat);
    assert!(
        p.comision_sat >= e.comision_sat + p.vbytes,
        "comisión {} ≥ original {} + {} vB",
        p.comision_sat,
        e.comision_sat,
        p.vbytes
    );
    assert!(
        p.sat_vb >= 3.0 - 0.01,
        "al menos la tasa pedida: {}",
        p.sat_vb
    );
    let orig = tx_de(&b, &e.txid);
    let repl = &b.pendiente.as_ref().unwrap().psbt.unsigned_tx;
    let a: std::collections::BTreeSet<_> = repl.input.iter().map(|i| i.previous_output).collect();
    let o: std::collections::BTreeSet<_> = orig.input.iter().map(|i| i.previous_output).collect();
    assert_eq!(a, o, "gasta exactamente las entradas de la original");
    assert_eq!(repl.output.len(), 1);

    // Firmado: las dos cuentan como en curso.
    let r = b.firmar_y_extraer(&p.id, mn()).unwrap();
    assert_eq!(r.reemplaza_a, e.txid);
    assert_eq!(r.estado, EstadoEnvio::Firmada);
    assert!(
        r.comision_sat >= e.comision_sat + r.vbytes,
        "comisión real {} ≥ original {} + {} vB reales (estimados {})",
        r.comision_sat,
        e.comision_sat,
        r.vbytes,
        p.vbytes
    );
    assert!(matches!(
        b.preparar_psbt(&destino_regtest(), 1_000, 1.0, 1_000)
            .unwrap_err(),
        Error::EnvioEnCurso(_)
    ));
    assert!(
        matches!(
            b.preparar_cancelacion(&e.txid, 4.0).unwrap_err(),
            Error::Envio(_)
        ),
        "no hay segundo reemplazo mientras vive el primero"
    );
    assert!(!b.estado_envio(&e.txid).unwrap().puede_reemplazar);
    assert!(
        !b.estado_envio(&r.txid).unwrap().puede_reemplazar,
        "un reemplazo no se reemplaza"
    );

    // Se difunde el reemplazo; el servidor lo ve; siguen las dos en curso.
    let r = b.difundir(&sim.url, &r.txid).unwrap();
    assert_eq!(r.estado, EstadoEnvio::Enviada);
    b.sincronizar(&sim.url).unwrap();
    assert!(
        b.envio_en_curso().is_some(),
        "hasta que confirme una, bloqueado"
    );
    assert!(matches!(
        b.preparar_psbt(&destino_regtest(), 1_000, 1.0, 1_000)
            .unwrap_err(),
        Error::EnvioEnCurso(_)
    ));
    assert_eq!(
        b.saldo().confirmado,
        0,
        "las monedas de las entradas no están libres"
    );

    // Confirma el reemplazo: la original queda cancelada; el saldo vuelve (menos comisión).
    sim.confirmar(Txid::from_str(&r.txid).unwrap());
    b.sincronizar(&sim.url).unwrap();
    assert_eq!(
        b.estado_envio(&r.txid).unwrap().estado,
        EstadoEnvio::Confirmada
    );
    let e = b.estado_envio(&e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Cancelada);
    assert!(e.motivo.contains("reemplazada"), "{}", e.motivo);
    assert!(b.envio_en_curso().is_none());
    assert_eq!(b.saldo().confirmado, r.importe_sat);
    assert_eq!(b.saldo().confirmado, 2 * FONDOS - r.comision_sat);
    assert!(b
        .preparar_psbt(&destino_regtest(), 10_000, 1.0, 5_000)
        .is_ok());
}

#[test]
fn regtest_si_confirma_la_original_el_reemplazo_queda_fallido() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let (mut b, e) = incierta_con_dos_entradas(dir.path(), &sim);
    let p = b.preparar_cancelacion(&e.txid, 3.0).unwrap();
    let r = b.firmar_y_extraer(&p.id, mn()).unwrap();
    let r = b.difundir(&sim.url, &r.txid).unwrap();
    assert_eq!(r.estado, EstadoEnvio::Enviada);
    // La original sí había llegado a la red y se confirma antes.
    let orig = tx_de(&b, &e.txid);
    sim.registrar_tx(&orig, 0);
    sim.confirmar(Txid::from_str(&e.txid).unwrap());
    b.sincronizar(&sim.url).unwrap();
    let e = b.estado_envio(&e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Confirmada);
    let r = b.estado_envio(&r.txid).unwrap();
    assert_eq!(r.estado, EstadoEnvio::Fallida);
    assert!(r.motivo.contains("reemplazo perdido"), "{}", r.motivo);
    assert!(b.envio_en_curso().is_none());
    assert_eq!(
        b.saldo().confirmado,
        e.cambio_sat,
        "queda el cambio de la original"
    );
    assert_eq!(b.saldo().pendiente, 0);
}

#[test]
fn regtest_cancelada_que_aparece_en_un_bloque_pasa_a_confirmada() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    let e = firmar_40k(&mut b);
    // Nunca difundida: se cancela liberando.
    let e = b.cancelar(&e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Cancelada);
    assert_eq!(b.saldo().confirmado, FONDOS);
    // ...pero una copia llegó a la red por otro camino y se confirma.
    let tx = tx_de(&b, &e.txid);
    sim.registrar_tx(&tx, 0);
    sim.confirmar(Txid::from_str(&e.txid).unwrap());
    b.sincronizar(&sim.url).unwrap();
    let e = b.estado_envio(&e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Confirmada);
    assert!(e.motivo.contains("pese a la cancelación"), "{}", e.motivo);
    assert_eq!(
        b.saldo().confirmado,
        e.cambio_sat,
        "el registro y el saldo dicen lo mismo que la red"
    );
    // Y una rechazada (incierta) que luego se confirma pasa a confirmada.
    let dir2 = tempfile::tempdir().unwrap();
    let sim2 = Simulado::arrancar(Network::Regtest);
    let mut b2 = billetera_regtest(dir2.path(), &sim2);
    let f = firmar_40k(&mut b2);
    sim2.fijar_difusion(Difusion::Rechazar(1, "rechazada".into()));
    let f = b2.difundir(&sim2.url, &f.txid).unwrap();
    assert_eq!(f.estado, EstadoEnvio::Incierta);
    sim2.fijar_difusion(Difusion::Aceptar);
    let tx = tx_de(&b2, &f.txid);
    sim2.registrar_tx(&tx, 0);
    sim2.confirmar(Txid::from_str(&f.txid).unwrap());
    b2.sincronizar(&sim2.url).unwrap();
    let f = b2.estado_envio(&f.txid).unwrap();
    assert_eq!(f.estado, EstadoEnvio::Confirmada);
    assert!(b2.envio_en_curso().is_none());
    assert_eq!(b2.saldo().confirmado, f.cambio_sat);
}

#[test]
fn regtest_firmada_sin_difundir_se_puede_cancelar() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    let e = firmar_40k(&mut b);
    assert_eq!(b.saldo().confirmado, 0);
    // Sincronizar no la libera (el servidor nunca la vio, pero está firmada y reservada).
    b.sincronizar(&sim.url).unwrap();
    let e = b.estado_envio(&e.txid).unwrap();
    assert_eq!(e.estado, EstadoEnvio::Firmada);
    assert_eq!(b.saldo().total, e.cambio_sat);
    // Cancelar una firmada nunca difundida sí se puede siempre.
    assert_eq!(b.cancelar(&e.txid).unwrap().estado, EstadoEnvio::Cancelada);
    assert_eq!(b.saldo().confirmado, FONDOS);
}

#[test]
fn regtest_reabrir_reanuda_el_envio_en_curso() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let f = dir.path().join("b.dat");
    let txid;
    let cambio_sat;
    {
        let mut b = billetera_regtest(dir.path(), &sim);
        let e = firmar_40k(&mut b);
        sim.fijar_difusion(Difusion::Colgar(Duration::from_secs(4)));
        let e = b.difundir(&sim.url, &e.txid).unwrap();
        assert_eq!(e.estado, EstadoEnvio::Incierta);
        txid = e.txid;
        cambio_sat = e.cambio_sat;
    }
    // El fichero lateral existe, con permisos 0600 y sin la semilla.
    let lateral = envios::Registro::ruta_de(&f);
    assert!(lateral.exists());
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            std::fs::metadata(&lateral).unwrap().permissions().mode() & 0o777,
            0o600
        );
    }
    let txt = std::fs::read_to_string(&lateral).unwrap();
    assert!(!txt.contains("abandon") && !txt.contains("tprv"));

    sim.fijar_difusion(Difusion::Aceptar);
    let mut b = Billetera::abrir(&f, Red::Regtest).unwrap();
    b.fijar_espera(Duration::from_secs(1));
    let e = b.envio_en_curso().expect("se reanuda el envío incierto");
    assert_eq!(
        (e.txid.as_str(), e.estado),
        (txid.as_str(), EstadoEnvio::Incierta)
    );
    assert_eq!(
        b.saldo().total,
        cambio_sat,
        "las monedas siguen gastadas tras reabrir"
    );
    assert_eq!(
        b.preparar_psbt(&destino_regtest(), 1_000, 1.0, 1_000)
            .unwrap_err(),
        Error::EnvioEnCurso(txid.clone())
    );
    b.sincronizar(&sim.url).unwrap();
    assert_eq!(b.saldo().total, cambio_sat);
    // Y el reintento desde la billetera reabierta llega.
    assert_eq!(
        b.difundir(&sim.url, &txid).unwrap().estado,
        EstadoEnvio::Enviada
    );
}

// ----- Contra un servidor Electrum de signet (necesitan red: `cargo test -- --ignored`) -----

#[test]
#[ignore]
fn signet_billetera_nueva_saldo_cero() {
    let dir = tempfile::tempdir().unwrap();
    let s = nueva_semilla().unwrap(); // semilla de PRUEBA, generada ahora y tirada al acabar
    let mut b = Billetera::crear(&s, Red::Signet, &dir.path().join("b.dat")).unwrap();
    b.escanear(SIGNET).unwrap();
    assert_eq!(b.saldo().total, 0);
    assert!(b.movimientos().is_empty());
    assert!(
        b.altura() > 300_000,
        "la billetera conoce la punta de signet"
    );
}

#[test]
#[ignore]
fn signet_restaurar_encuentra_direcciones() {
    // El vector BIP84 es público y tiene historia en signet (rama m/84'/1'/0').
    let dir = tempfile::tempdir().unwrap();
    let mut b = Billetera::crear(mn(), Red::Signet, &dir.path().join("b.dat")).unwrap();
    assert_eq!(b.indices(), (None, None));
    b.escanear(SIGNET).unwrap();
    let (ext, _int) = b.indices();
    assert!(
        ext.is_some(),
        "el escaneo con gap limit encuentra direcciones usadas"
    );
    assert!(!b.movimientos().is_empty());
}

#[test]
#[ignore]
fn signet_altura_y_comision() {
    let h = altura_red(Red::Signet, SIGNET).unwrap();
    assert!(h > 300_000, "altura {h}");
    let c = estimar_comision(Red::Signet, SIGNET, 2).unwrap();
    assert!((1.0..10_000.0).contains(&c), "comisión {c} sat/vB");
}

/// Las pruebas de red van en paralelo contra el mismo servidor público: un fallo de
/// transporte (`Error::Red`) no dice nada de lo que se prueba, así que se reintenta.
fn sin_fallos_de_red<T>(mut f: impl FnMut() -> Result<T, Error>) -> Result<T, Error> {
    let mut r = f();
    for _ in 0..3 {
        match r {
            Err(Error::Red(_)) => {
                std::thread::sleep(Duration::from_secs(2));
                r = f();
            }
            _ => break,
        }
    }
    r
}

#[test]
#[ignore]
fn signet_servidor_publico_rechazado_si_se_pide_otra_red() {
    // El genesis_hash del servidor de signet no es el de testnet ni el de regtest.
    let e = sin_fallos_de_red(|| altura_red(Red::Testnet, SIGNET)).unwrap_err();
    assert!(matches!(e, Error::RedServidor(_)), "{e}");
    let e = sin_fallos_de_red(|| altura_red(Red::Regtest, SIGNET)).unwrap_err();
    assert!(matches!(e, Error::RedServidor(_)), "{e}");
    let dir = tempfile::tempdir().unwrap();
    let mut b = Billetera::crear(mn(), Red::Testnet, &dir.path().join("t.dat")).unwrap();
    let e = sin_fallos_de_red(|| b.sincronizar(SIGNET)).unwrap_err();
    assert!(matches!(e, Error::RedServidor(_)), "{e}");
}
