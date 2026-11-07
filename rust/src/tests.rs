//! Pruebas del motor. Las unitarias usan regtest con un servidor Electrum SIMULADO
//! (`mock_electrum`); las marcadas `#[ignore]` hablan con un servidor público de signet
//! (`cargo test -- --ignored`). Por defecto mainnet está prohibida y se prueba que lo
//! está; con `--features mainnet` se prueba lo contrario.

use super::mock_electrum::{Difusion, Maldad, Simulado};
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
            altura_red(Red::Mainnet, "tcp://sin-servidor:1", None, 0, None).unwrap_err(),
            Error::RedProhibida
        );
        assert_eq!(
            estimar_comision(Red::Mainnet, "tcp://sin-servidor:1", 2, None, 0, None).unwrap_err(),
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
    assert_eq!(
        altura_red(Red::Regtest, &sim.url, None, 0, None).unwrap(),
        1
    );
    assert_eq!(
        estimar_comision(Red::Regtest, &sim.url, 2, None, 0, None).unwrap(),
        2.0
    );
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
        altura_red(Red::Regtest, &sim.url, None, 0, None).unwrap_err(),
        Error::RedServidor(_)
    ));
    assert!(matches!(
        estimar_comision(Red::Regtest, &sim.url, 2, None, 0, None).unwrap_err(),
        Error::RedServidor(_)
    ));
    // Pedido como signet, el mismo servidor vale.
    assert_eq!(altura_red(Red::Signet, &sim.url, None, 0, None).unwrap(), 1);
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
    let h = altura_red(Red::Signet, SIGNET, None, 0, None).unwrap();
    assert!(h > 300_000, "altura {h}");
    let c = estimar_comision(Red::Signet, SIGNET, 2, None, 0, None).unwrap();
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
    let e = sin_fallos_de_red(|| altura_red(Red::Testnet, SIGNET, None, 0, None)).unwrap_err();
    assert!(matches!(e, Error::RedServidor(_)), "{e}");
    let e = sin_fallos_de_red(|| altura_red(Red::Regtest, SIGNET, None, 0, None)).unwrap_err();
    assert!(matches!(e, Error::RedServidor(_)), "{e}");
    let dir = tempfile::tempdir().unwrap();
    let mut b = Billetera::crear(mn(), Red::Testnet, &dir.path().join("t.dat")).unwrap();
    let e = sin_fallos_de_red(|| b.sincronizar(SIGNET)).unwrap_err();
    assert!(matches!(e, Error::RedServidor(_)), "{e}");
}

// ----- NodeBridge: nodo del usuario con TLS y huella fijada (simulado) -----------------

#[test]
fn nodo_destino_se_analiza_bien() {
    use crate::nodo::{Destino, Esquema};
    let d = Destino::analizar("ssl://nodo.ejemplo.local:50002").unwrap();
    assert_eq!(
        (d.esquema, d.host.as_str(), d.puerto),
        (Esquema::Ssl, "nodo.ejemplo.local", 50002)
    );
    let d = Destino::analizar("tcp://localhost:60401").unwrap();
    assert_eq!((d.esquema, d.puerto), (Esquema::Tcp, 60401));
    assert!(Destino::analizar("localhost:50002").is_err(), "sin esquema");
    assert!(Destino::analizar("ssl://localhost").is_err(), "sin puerto");
    assert!(Destino::analizar("ssl://:50002").is_err(), "sin host");
}

#[test]
fn nodo_tls_con_huella_correcta_es_sano_y_la_billetera_sincroniza_por_el() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let huella = sim.huella();
    assert_eq!(huella.len(), 64);
    let info = comprobar_nodo(Red::Regtest, &sim.url, Some(&huella), 0, None).unwrap();
    assert!(info.cifrado);
    assert_eq!(info.huella_vista, huella);
    assert_eq!(info.protocolo, "1.4");
    assert_eq!(info.version_servidor, "simulado 0.1");
    assert_eq!(info.altura, 0);
    assert_eq!(info.red, Red::Regtest);
    // La billetera entera por la conexión con huella: escaneo, saldo, difusión.
    let mut b = billetera_regtest(dir.path(), &sim);
    b.fijar_huella_servidor(Some(huella.to_uppercase()));
    b.sincronizar(&sim.url).unwrap();
    assert_eq!(b.saldo().confirmado, FONDOS);
    let e = firmar_40k(&mut b);
    assert_eq!(
        b.difundir(&sim.url, &e.txid).unwrap().estado,
        EstadoEnvio::Enviada
    );
    assert_eq!(sim.difundidas().len(), 1);
    // Altura mínima: si el móvil ya vio más, el servidor va por detrás.
    let err = comprobar_nodo(Red::Regtest, &sim.url, Some(&huella), 5, None).unwrap_err();
    assert!(
        matches!(err, Error::Red(ref m) if m.contains("por detrás")),
        "{err}"
    );
}

#[test]
fn nodo_tls_con_huella_incorrecta_se_rechaza_sin_tocar_nada() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let mala = "00".repeat(32);
    let err = comprobar_nodo(Red::Regtest, &sim.url, Some(&mala), 0, None).unwrap_err();
    assert!(
        matches!(err, Error::HuellaServidor(ref m) if m.contains("huella")),
        "{err}"
    );
    // Mal formada.
    assert!(matches!(
        comprobar_nodo(Red::Regtest, &sim.url, Some("abc"), 0, None).unwrap_err(),
        Error::HuellaServidor(_)
    ));
    // Con tcp:// la huella no tiene sentido: se rechaza en vez de ignorarla.
    let plano = Simulado::arrancar(Network::Regtest);
    assert!(matches!(
        comprobar_nodo(Red::Regtest, &plano.url, Some(&sim.huella()), 0, None).unwrap_err(),
        Error::HuellaServidor(_)
    ));
    // La billetera con la huella mala no sincroniza ni difunde, y el intento no cuenta.
    let mut b = billetera_regtest(dir.path(), &sim);
    b.fijar_huella_servidor(Some(mala.clone()));
    assert!(matches!(
        b.sincronizar(&sim.url).unwrap_err(),
        Error::HuellaServidor(_)
    ));
    let e = firmar_40k(&mut b);
    assert!(matches!(
        b.difundir(&sim.url, &e.txid).unwrap_err(),
        Error::HuellaServidor(_)
    ));
    let e = b.estado_envio(&e.txid).unwrap();
    assert_eq!((e.estado, e.intentos_difusion), (EstadoEnvio::Firmada, 0));
    assert!(sim.difundidas().is_empty());
    // Con la buena, todo sigue.
    b.fijar_huella_servidor(Some(sim.huella()));
    assert_eq!(
        b.difundir(&sim.url, &e.txid).unwrap().estado,
        EstadoEnvio::Enviada
    );
}

#[test]
fn nodo_tls_de_otra_red_se_rechaza() {
    let sim = Simulado::arrancar_tls(Network::Signet);
    let err = comprobar_nodo(Red::Regtest, &sim.url, Some(&sim.huella()), 0, None).unwrap_err();
    assert!(matches!(err, Error::RedServidor(_)), "{err}");
    assert!(comprobar_nodo(Red::Signet, &sim.url, Some(&sim.huella()), 0, None).is_ok());
}

#[test]
fn nodo_tcp_sin_cifrar_se_informa_y_corte_y_vuelta() {
    let sim = Simulado::arrancar(Network::Regtest);
    let info = comprobar_nodo(Red::Regtest, &sim.url, None, 0, None).unwrap();
    assert!(
        !info.cifrado,
        "tcp:// se informa como sin cifrar (la capa de arriba avisa)"
    );
    assert!(info.huella_vista.is_empty());
    // Corte: el servidor deja de aceptar conexiones → error de red, nada más.
    sim.parar();
    let err = comprobar_nodo(Red::Regtest, &sim.url, None, 0, None).unwrap_err();
    assert!(matches!(err, Error::Red(_)), "{err}");
    // Vuelta: un servidor de nuevo en pie (en las pruebas, en otro puerto) → sano.
    let otro = Simulado::arrancar(Network::Regtest);
    assert!(comprobar_nodo(Red::Regtest, &otro.url, None, 0, None).is_ok());
}

/// Vector fijo: certificado de prueba y la huella SPKI (SHA-256 del SubjectPublicKeyInfo DER)
/// calculada con openssl aparte: `openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER | sha256sum`.
/// El cálculo del motor y el de la herramienta del nodo (E-2) tienen que coincidir siempre.
const VECTOR_CERT_PEM: &str = "-----BEGIN CERTIFICATE-----
MIIBjTCCATOgAwIBAgIUNsBCrU//8e0opMTKj0YivaIJCFkwCgYIKoZIzj0EAwIw
GzEZMBcGA1UEAwwQdmVjdG9yLWRlLXBydWViYTAgFw0yNjEwMzEwMTIyMTVaGA8y
MTI2MTAwNzAxMjIxNVowGzEZMBcGA1UEAwwQdmVjdG9yLWRlLXBydWViYTBZMBMG
ByqGSM49AgEGCCqGSM49AwEHA0IABEyv/2EInqxlGpKoRM6I7SR3jYVpOiiDwF/o
HVK40PP9Te2zyB2SGEZ5dmDhtYjCQunIQRJht5I+pqEKs9P8pk+jUzBRMB0GA1Ud
DgQWBBSM3TTwAFvxbQamEKLcr4+8TeEL+zAfBgNVHSMEGDAWgBSM3TTwAFvxbQam
EKLcr4+8TeEL+zAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA0gAMEUCIA0o
g8G6Votz6jSXiZLuEB7uD6M+TB7Ze07pOJ4xyCxhAiEA5TpSEl+oTddrbSinB53w
dMoDA2hI7Ikz9ojHLFIAnpY=
-----END CERTIFICATE-----
";
const VECTOR_HUELLA_SPKI: &str = "d69cfd0cee1cb723598e98e240ec62f14cc0ef834f47872dd6c29d4da6e230de";

#[test]
fn huella_spki_vector_fijo() {
    use bitcoin::hex::DisplayHex;
    use rustls::pki_types::pem::PemObject;
    let cert =
        rustls::pki_types::CertificateDer::from_pem_slice(VECTOR_CERT_PEM.as_bytes()).unwrap();
    let h = crate::nodo::huella_spki(&cert)
        .unwrap()
        .to_lower_hex_string();
    assert_eq!(h, VECTOR_HUELLA_SPKI);
}

#[test]
fn nodo_la_huella_viaja_en_altura_y_comision() {
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let buena = sim.huella();
    let mala = "11".repeat(32);
    // Con la huella buena, las dos funciones libres funcionan por TLS con huella.
    assert_eq!(
        altura_red(Red::Regtest, &sim.url, Some(&buena), 0, None).unwrap(),
        0
    );
    assert_eq!(
        estimar_comision(Red::Regtest, &sim.url, 2, Some(&buena), 0, None).unwrap(),
        2.0
    );
    // Con huella mala, se niegan (no caen a «aceptar cualquier certificado»).
    assert!(matches!(
        altura_red(Red::Regtest, &sim.url, Some(&mala), 0, None).unwrap_err(),
        Error::HuellaServidor(_)
    ));
    assert!(matches!(
        estimar_comision(Red::Regtest, &sim.url, 2, Some(&mala), 0, None).unwrap_err(),
        Error::HuellaServidor(_)
    ));
    // Sin huella, un certificado autofirmado no pasa la validación por raíces públicas.
    assert!(matches!(
        altura_red(Red::Regtest, &sim.url, None, 0, None).unwrap_err(),
        Error::Red(_)
    ));
}

#[test]
fn nodo_corte_tras_validar_genesis_y_otra_red_al_volver() {
    use crate::nodo::Conexion;
    let sim = Simulado::arrancar(Network::Regtest);
    let puerto = sim.puerto();
    let url = sim.url.clone();
    let c = Conexion::abrir(
        &url,
        Network::Regtest,
        None,
        Duration::from_secs(2),
        0,
        None,
    )
    .unwrap();
    assert_eq!(c.altura().unwrap(), 0);
    // Corte del servidor; en la misma dirección aparece un servidor de OTRA red.
    sim.parar();
    let otro = Simulado::arrancar_en(puerto, Network::Signet);
    // La conexión antigua NO reintenta por su cuenta (retry 0): falla, no devuelve datos del impostor.
    assert!(
        matches!(c.altura(), Err(Error::Red(_))),
        "sin reintento interno"
    );
    // Un reintento de la capa de arriba repite toda la validación y descubre la red equivocada.
    assert!(matches!(
        Conexion::abrir(
            &url,
            Network::Regtest,
            None,
            Duration::from_secs(2),
            0,
            None
        ),
        Err(Error::RedServidor(_))
    ));
    assert!(matches!(
        comprobar_nodo(Red::Regtest, &url, None, 0, None).unwrap_err(),
        Error::RedServidor(_)
    ));
    // Pedido como signet, el nuevo sí vale.
    assert_eq!(
        altura_red(Red::Signet, &otro.url, None, 0, None).unwrap(),
        0
    );
}

#[test]
#[ignore]
fn signet_publico_con_huella_equivocada_se_rechaza_tambien_en_comision() {
    // Certificado público válido pero huella fijada distinta: la huella manda, no la CA.
    let mala = "22".repeat(32);
    assert!(matches!(
        estimar_comision(Red::Signet, SIGNET, 2, Some(&mala), 0, None).unwrap_err(),
        Error::HuellaServidor(_)
    ));
    assert!(matches!(
        altura_red(Red::Signet, SIGNET, Some(&mala), 0, None).unwrap_err(),
        Error::HuellaServidor(_)
    ));
}

// ===== B4 (PRUEBAS.md §7): batería NEGATIVA de la puerta del nodo ==========================
//
// Puertas, en el orden real de `Conexion::abrir` (el mismo para la sonda `comprobar_nodo` y
// para cada operación): huella SPKI → protocolo 1.4 (`server.version`, primer mensaje) →
// génesis → altura que no baja. Las de huella y red ya tienen pruebas más arriba; aquí
// están las que faltaban: protocolo distinto, altura que retrocede respecto a la última vista,
// conservación del estado en cada rechazo (antes de transmitir / con la difusión ya empezada)
// y, operación por operación, que la conexión de la PROPIA operación rechaza al servidor malo
// aunque la sonda `comprobar_nodo` lo haya dado por sano un instante antes.

/// Instantánea byte a byte de los ficheros de la billetera (estado BDK y registro de envíos).
fn instantanea(dir: &Path) -> std::collections::BTreeMap<String, Vec<u8>> {
    std::fs::read_dir(dir)
        .unwrap()
        .flatten()
        .filter(|e| e.path().is_file())
        .map(|e| {
            (
                e.file_name().to_string_lossy().into_owned(),
                std::fs::read(e.path()).unwrap(),
            )
        })
        .collect()
}

/// Nombres de los ficheros que difieren entre dos instantáneas (vacío = idénticas byte a byte).
fn ficheros_distintos(
    antes: &std::collections::BTreeMap<String, Vec<u8>>,
    despues: &std::collections::BTreeMap<String, Vec<u8>>,
) -> Vec<String> {
    let mut nombres: Vec<&String> = antes.keys().chain(despues.keys()).collect();
    nombres.sort();
    nombres.dedup();
    nombres
        .into_iter()
        .filter(|n| antes.get(*n) != despues.get(*n))
        .cloned()
        .collect()
}

fn txid_cero() -> Txid {
    <Txid as bitcoin::hashes::Hash>::all_zeros()
}

/// Billetera regtest emparejada con el simulado TLS (huella fijada) y sincronizada con una
/// cadena de tres bloques: la «última altura vista» por la billetera queda en 3.
fn billetera_emparejada(dir: &Path, sim: &Simulado) -> Billetera {
    let mut b = billetera_regtest(dir, sim); // el depósito va en el bloque 1
    b.fijar_huella_servidor(huella_de(sim));
    sim.anadir_bloque(txid_cero()); // 2
    sim.anadir_bloque(txid_cero()); // 3
    b.sincronizar(&sim.url).unwrap();
    assert_eq!(b.altura(), 3, "la billetera ha visto la altura 3");
    assert_eq!(b.saldo().confirmado, FONDOS);
    b
}

/// Huella del simulado si es TLS; `None` con `tcp://`.
fn huella_de(sim: &Simulado) -> Option<String> {
    sim.cert_der.as_ref().map(|_| sim.huella())
}

/// Lo que la billetera tiene EN MEMORIA y que un rechazo no debe alterar.
#[derive(Debug, Clone, PartialEq)]
struct Memoria {
    altura: u32,
    saldo: Saldo,
    movimientos: usize,
    indices: (Option<u32>, Option<u32>),
    en_curso: Option<(String, EstadoEnvio, u32)>,
}

fn memoria(b: &Billetera) -> Memoria {
    Memoria {
        altura: b.altura(),
        saldo: b.saldo(),
        movimientos: b.movimientos().len(),
        indices: b.indices(),
        en_curso: b
            .envio_en_curso()
            .map(|e| (e.txid, e.estado, e.intentos_difusion)),
    }
}

fn es_rechazo_huella(e: &Error) -> bool {
    matches!(e, Error::HuellaServidor(_))
}
fn es_rechazo_red(e: &Error) -> bool {
    matches!(e, Error::RedServidor(_))
}
/// El rechazo de la puerta del protocolo, con el texto que ya usa `comprobar_nodo`.
fn es_rechazo_protocolo(e: &Error) -> bool {
    matches!(e, Error::Red(m) if m.contains("protocolo"))
}
/// El rechazo de la puerta de la altura, con el texto que ya usa `comprobar_nodo`.
fn es_rechazo_altura(e: &Error) -> bool {
    matches!(e, Error::Red(m) if m.contains("por detrás"))
}

/// Exige que `r` sea el rechazo de la puerta `es_la_puerta`; cualquier otra cosa (éxito, u
/// otro error) es un fallo con el motivo a la vista.
fn exigir_rechazo<T: std::fmt::Debug>(
    op: &str,
    r: Result<T, Error>,
    es_la_puerta: fn(&Error) -> bool,
) {
    match r {
        Err(e) if es_la_puerta(&e) => {}
        Err(e) => panic!("{op}: rechazado, pero NO por la puerta esperada: {e}"),
        Ok(v) => panic!("{op}: la conexión de la operación ACEPTÓ al servidor malo: {v:?}"),
    }
}

// ----- 1 y 2: las puertas que faltaban, en la sonda ---------------------------------------

#[test]
fn puerta_sonda_rechaza_protocolo_distinto_de_1_4() {
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let huella = sim.huella();
    assert_eq!(
        comprobar_nodo(Red::Regtest, &sim.url, Some(&huella), 0, None)
            .unwrap()
            .protocolo,
        "1.4"
    );
    // Mismo servidor, misma huella, mismo génesis: solo cambia el protocolo que anuncia.
    for proto in ["1.0", "1.2", "1.3", "1.5", "2.0", "14", ""] {
        sim.fijar_protocolo(proto);
        let err = comprobar_nodo(Red::Regtest, &sim.url, Some(&huella), 0, None).unwrap_err();
        assert!(es_rechazo_protocolo(&err), "protocolo {proto:?}: {err}");
    }
    // También por tcp:// sin huella: la puerta no depende del transporte.
    let plano = Simulado::arrancar(Network::Regtest);
    plano.fijar_protocolo("1.2");
    let err = comprobar_nodo(Red::Regtest, &plano.url, None, 0, None).unwrap_err();
    assert!(es_rechazo_protocolo(&err), "{err}");
    // Las revisiones de 1.4 (1.4.1, 1.4.2) sí valen.
    sim.fijar_protocolo("1.4.2");
    assert_eq!(
        comprobar_nodo(Red::Regtest, &sim.url, Some(&huella), 0, None)
            .unwrap()
            .protocolo,
        "1.4.2"
    );
}

#[test]
fn puerta_sonda_rechaza_altura_que_retrocede_respecto_a_la_vista() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let huella = sim.huella();
    let b = billetera_emparejada(dir.path(), &sim);
    let vista = b.altura();
    assert_eq!(vista, 3);
    // Igual que la vista: sano. Un bloque más: sano.
    assert_eq!(
        comprobar_nodo(Red::Regtest, &sim.url, Some(&huella), vista, None)
            .unwrap()
            .altura,
        3
    );
    sim.anadir_bloque(txid_cero());
    assert_eq!(
        comprobar_nodo(Red::Regtest, &sim.url, Some(&huella), vista, None)
            .unwrap()
            .altura,
        4
    );
    // El mismo servidor (misma huella, mismo génesis) retrocede por debajo de lo visto, en
    // cualquier medida: rechazado.
    for atras in [2u32, 1, 0] {
        sim.retroceder_a(atras);
        let err = comprobar_nodo(Red::Regtest, &sim.url, Some(&huella), vista, None).unwrap_err();
        assert!(es_rechazo_altura(&err), "retrocede a {atras}: {err}");
    }
    // Un servidor recién instalado (cadena desde cero) tampoco vale si ya se vio más.
    let nuevo = Simulado::arrancar(Network::Regtest);
    let err = comprobar_nodo(Red::Regtest, &nuevo.url, None, vista, None).unwrap_err();
    assert!(es_rechazo_altura(&err), "{err}");
    // Sin nada visto (0), ese mismo servidor es aceptable.
    assert_eq!(
        comprobar_nodo(Red::Regtest, &nuevo.url, None, 0, None)
            .unwrap()
            .altura,
        0
    );
}

// ----- 3a: rechazo ANTES de transmitir → fondos y fichero de envíos intactos byte a byte -----

const TODAS: [&str; 3] = ["sincronizar", "escanear", "difundir"];

/// Con un envío firmado a la espera, cada operación de `ops` contra el servidor malo debe
/// fallar con el rechazo de ESA puerta y no tocar nada, ni en disco ni en memoria: ficheros
/// idénticos byte a byte, misma altura, saldo, movimientos e índices, el envío sigue Firmada
/// con 0 intentos y cancelable, y el simulado no recibió ninguna transacción. Se recogen TODAS
/// las violaciones antes de fallar, para verlas juntas.
fn exigir_rechazo_previo_sin_tocar_nada(
    puerta: &str,
    dir: &Path,
    b: &mut Billetera,
    url: &str,
    sim: &Simulado,
    txid: Option<&str>,
    es_la_puerta: fn(&Error) -> bool,
    ops: &[&str],
) {
    let antes = instantanea(dir);
    let saldo = b.saldo();
    let mem = memoria(b);
    if let Some(txid) = txid {
        let e = b.estado_envio(txid).unwrap();
        assert_eq!((e.estado, e.intentos_difusion), (EstadoEnvio::Firmada, 0));
        assert!(e.puede_cancelar);
    }
    assert_eq!(sim.recibidas(), 0);

    let mut fallos: Vec<String> = Vec::new();
    // Cada operación se compara con el estado ORIGINAL inmediatamente después de ejecutarse:
    // una operación posterior no puede tapar lo que alteró la anterior.
    for &op in ops {
        let r: Result<String, Error> = match op {
            "sincronizar" => b.sincronizar(url).map(|_| String::new()),
            "escanear" => b.escanear(url).map(|_| String::new()),
            _ => b
                .difundir(url, txid.expect("difundir exige un envío firmado"))
                .map(|i| format!("estado {:?}, intentos {}", i.estado, i.intentos_difusion)),
        };
        match r {
            Err(e) if es_la_puerta(&e) => {}
            Err(e) => fallos.push(format!("{op}: rechazado, pero no por la puerta: {e}")),
            Ok(v) => fallos.push(format!("{op}: ACEPTÓ al servidor malo ({v})")),
        }
        let distintos = ficheros_distintos(&antes, &instantanea(dir));
        if !distintos.is_empty() {
            fallos.push(format!("tras {op}: ficheros modificados: {distintos:?}"));
        }
        if b.saldo() != saldo {
            fallos.push(format!(
                "tras {op}: saldo cambió: {saldo:?} → {:?}",
                b.saldo()
            ));
        }
        if memoria(b) != mem {
            fallos.push(format!(
                "tras {op}: estado en memoria alterado: {mem:?} → {:?}",
                memoria(b)
            ));
        }
        if let Some(txid) = txid {
            let e = b.estado_envio(txid).unwrap();
            if (e.estado, e.intentos_difusion, e.puede_cancelar) != (EstadoEnvio::Firmada, 0, true)
            {
                fallos.push(format!(
                    "tras {op}: envío: estado {:?}, intentos {}, puede_cancelar {}",
                    e.estado, e.intentos_difusion, e.puede_cancelar
                ));
            }
        }
        if sim.recibidas() != 0 {
            fallos.push(format!(
                "tras {op}: el simulado RECIBIÓ la transacción ({} veces)",
                sim.recibidas()
            ));
        }
    }
    assert!(
        fallos.is_empty(),
        "puerta «{puerta}»: el rechazo no conservó el estado:\n  {}",
        fallos.join("\n  ")
    );
}

#[test]
fn puerta_rechazo_previo_por_huella_no_toca_fondos_ni_envios() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let mut b = billetera_emparejada(dir.path(), &sim);
    let txid = firmar_40k(&mut b).txid;
    // El servidor presenta otro certificado del que la billetera tiene fijado.
    b.fijar_huella_servidor(Some("00".repeat(32)));
    exigir_rechazo_previo_sin_tocar_nada(
        "huella",
        dir.path(),
        &mut b,
        &sim.url,
        &sim,
        Some(&txid),
        es_rechazo_huella,
        &TODAS,
    );
}

#[test]
fn puerta_rechazo_previo_por_otra_red_no_toca_fondos_ni_envios() {
    let dir = tempfile::tempdir().unwrap();
    // Huella correcta, pero el servidor es de signet y la billetera de regtest.
    let sim = Simulado::arrancar_tls(Network::Signet);
    let mut b = billetera_regtest(dir.path(), &sim);
    b.fijar_huella_servidor(Some(sim.huella()));
    let txid = firmar_40k(&mut b).txid;
    exigir_rechazo_previo_sin_tocar_nada(
        "génesis",
        dir.path(),
        &mut b,
        &sim.url,
        &sim,
        Some(&txid),
        es_rechazo_red,
        &TODAS,
    );
}

#[test]
fn puerta_rechazo_previo_por_protocolo_no_toca_fondos_ni_envios() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let mut b = billetera_emparejada(dir.path(), &sim);
    let txid = firmar_40k(&mut b).txid;
    assert!(comprobar_nodo(
        Red::Regtest,
        &sim.url,
        Some(&sim.huella()),
        b.altura(),
        None
    )
    .is_ok());
    // Mismo servidor, misma huella y génesis: ahora anuncia otro protocolo.
    sim.fijar_protocolo("1.5");
    exigir_rechazo_previo_sin_tocar_nada(
        "protocolo",
        dir.path(),
        &mut b,
        &sim.url,
        &sim,
        Some(&txid),
        es_rechazo_protocolo,
        &TODAS,
    );
}

#[test]
fn puerta_rechazo_previo_por_altura_no_toca_fondos_ni_envios() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let mut b = billetera_emparejada(dir.path(), &sim); // vista: 3
    let txid = firmar_40k(&mut b).txid;
    assert!(comprobar_nodo(
        Red::Regtest,
        &sim.url,
        Some(&sim.huella()),
        b.altura(),
        None
    )
    .is_ok());
    // Mismo servidor: su cadena retrocede por debajo de lo que la billetera ya vio.
    sim.retroceder_a(1);
    exigir_rechazo_previo_sin_tocar_nada(
        "altura",
        dir.path(),
        &mut b,
        &sim.url,
        &sim,
        Some(&txid),
        es_rechazo_altura,
        &TODAS,
    );
}

// ----- 3b: la difusión YA empezó → Incierta y monedas reservadas, también si el reintento -----
// ----- choca con una puerta -------------------------------------------------------------

/// Difunde contra un servidor que se queda mudo → Incierta (1 intento, monedas reservadas).
/// Devuelve la info del envío y la instantánea de disco en ese estado.
fn dejar_incierta(
    dir: &Path,
    b: &mut Billetera,
    sim: &Simulado,
) -> (EnvioInfo, std::collections::BTreeMap<String, Vec<u8>>) {
    let e = firmar_40k(b);
    sim.fijar_difusion(Difusion::Colgar(Duration::from_secs(3)));
    let e = b.difundir(&sim.url, &e.txid).unwrap();
    assert_eq!((e.estado, e.intentos_difusion), (EstadoEnvio::Incierta, 1));
    assert!(!e.puede_cancelar && e.puede_reemplazar && e.puede_reintentar);
    assert_eq!(
        b.saldo().total,
        e.cambio_sat,
        "las monedas del envío están reservadas"
    );
    assert_eq!(b.saldo().confirmado, 0);
    assert_eq!(
        sim.recibidas(),
        1,
        "el servidor RECIBIÓ la transacción una vez antes de callarse"
    );
    assert!(sim.difundidas().is_empty(), "colgada: no la aceptó");
    (e, instantanea(dir))
}

/// Tras un reintento rechazado en la puerta `es_la_puerta`, el envío sigue EXACTAMENTE como
/// estaba: Incierta con 1 intento, monedas reservadas (nunca liberadas), no cancelable, y los
/// ficheros idénticos byte a byte a los de antes del reintento. `recibidas_total`: suma de
/// recepciones de broadcast en todos los servidores implicados; debe seguir siendo la del
/// primer intento (1), es decir, el reintento rechazado no llegó a transmitir.
fn exigir_incierta_intacta(
    puerta: &str,
    dir: &Path,
    b: &mut Billetera,
    antes: &std::collections::BTreeMap<String, Vec<u8>>,
    e: &EnvioInfo,
    reintento: Result<EnvioInfo, Error>,
    es_la_puerta: fn(&Error) -> bool,
    recibidas_total: u64,
) {
    let mut fallos: Vec<String> = Vec::new();
    match reintento {
        Err(err) if es_la_puerta(&err) => {}
        Err(err) => fallos.push(format!("reintento rechazado, pero no por la puerta: {err}")),
        Ok(i) => fallos.push(format!(
            "el reintento ACEPTÓ al servidor malo: estado {:?}, intentos {}",
            i.estado, i.intentos_difusion
        )),
    }
    let ahora = b.estado_envio(&e.txid).unwrap();
    if (ahora.estado, ahora.intentos_difusion) != (EstadoEnvio::Incierta, 1) {
        fallos.push(format!(
            "envío: estado {:?}, intentos {} (se esperaba Incierta, 1)",
            ahora.estado, ahora.intentos_difusion
        ));
    }
    if ahora.puede_cancelar || !ahora.puede_reemplazar {
        fallos.push("el envío se volvió cancelable o dejó de ser reemplazable".into());
    }
    if b.saldo().total != e.cambio_sat || b.saldo().confirmado != 0 {
        fallos.push(format!(
            "monedas liberadas o movidas: saldo {:?}, cambio {}",
            b.saldo(),
            e.cambio_sat
        ));
    }
    if b.envio_en_curso().is_none() {
        fallos.push("ya no hay envío en curso".into());
    }
    if !matches!(b.cancelar(&e.txid), Err(Error::Envio(_))) {
        fallos.push("cancelar liberando monedas fue posible".into());
    }
    let distintos = ficheros_distintos(antes, &instantanea(dir));
    if !distintos.is_empty() {
        fallos.push(format!(
            "ficheros modificados por el reintento: {distintos:?}"
        ));
    }
    if recibidas_total != 1 {
        fallos.push(format!(
            "recepciones de broadcast: {recibidas_total} (se esperaba 1: solo el primer intento)"
        ));
    }
    assert!(
        fallos.is_empty(),
        "puerta «{puerta}» en el reintento: el estado Incierta no se conservó:\n  {}",
        fallos.join("\n  ")
    );
}

#[test]
fn puerta_rechazo_tras_difusion_incierta_por_huella_sigue_incierta_y_reservada() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let puerto = sim.puerto();
    let mut b = billetera_emparejada(dir.path(), &sim);
    let (e, antes) = dejar_incierta(dir.path(), &mut b, &sim);
    // El servidor cae y en la MISMA dirección vuelve uno con otro certificado: la huella
    // fijada ya no cuadra. El reintento se rechaza en el apretón TLS.
    sim.parar();
    let otro = Simulado::arrancar_tls_en(puerto, Network::Regtest);
    assert_eq!(otro.url, sim.url);
    assert_ne!(otro.huella(), sim.huella());
    let r = b.difundir(&otro.url, &e.txid);
    let recibio = sim.recibidas() + otro.recibidas();
    exigir_incierta_intacta(
        "huella",
        dir.path(),
        &mut b,
        &antes,
        &e,
        r,
        es_rechazo_huella,
        recibio,
    );
}

#[test]
fn puerta_rechazo_tras_difusion_incierta_por_otra_red_sigue_incierta_y_reservada() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let puerto = sim.puerto();
    let mut b = billetera_regtest(dir.path(), &sim);
    let (e, antes) = dejar_incierta(dir.path(), &mut b, &sim);
    // En la misma dirección aparece un servidor de OTRA red.
    sim.parar();
    let otro = Simulado::arrancar_en(puerto, Network::Signet);
    assert_eq!(otro.url, sim.url);
    let r = b.difundir(&otro.url, &e.txid);
    let recibio = sim.recibidas() + otro.recibidas();
    exigir_incierta_intacta(
        "génesis",
        dir.path(),
        &mut b,
        &antes,
        &e,
        r,
        es_rechazo_red,
        recibio,
    );
}

#[test]
fn puerta_rechazo_tras_difusion_incierta_por_protocolo_sigue_incierta_y_reservada() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let mut b = billetera_emparejada(dir.path(), &sim);
    let (e, antes) = dejar_incierta(dir.path(), &mut b, &sim);
    // El mismo servidor pasa a anunciar otro protocolo y ahora sí respondería: el reintento
    // tiene que rechazarse en la puerta, no llegar a transmitir.
    sim.fijar_protocolo("1.5");
    sim.fijar_difusion(Difusion::Aceptar);
    let r = b.difundir(&sim.url, &e.txid);
    let recibio = sim.recibidas();
    exigir_incierta_intacta(
        "protocolo",
        dir.path(),
        &mut b,
        &antes,
        &e,
        r,
        es_rechazo_protocolo,
        recibio,
    );
}

#[test]
fn puerta_rechazo_tras_difusion_incierta_por_altura_sigue_incierta_y_reservada() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let mut b = billetera_emparejada(dir.path(), &sim); // vista: 3
    let (e, antes) = dejar_incierta(dir.path(), &mut b, &sim);
    // El mismo servidor retrocede por debajo de lo visto y ahora sí respondería.
    sim.retroceder_a(1);
    sim.fijar_difusion(Difusion::Aceptar);
    let r = b.difundir(&sim.url, &e.txid);
    let recibio = sim.recibidas();
    exigir_incierta_intacta(
        "altura",
        dir.path(),
        &mut b,
        &antes,
        &e,
        r,
        es_rechazo_altura,
        recibio,
    );
}

// ----- 4: operación por operación, la conexión de la PROPIA operación rechaza al malo --------
//
// Escena común: billetera emparejada por TLS con huella y sincronizada (vista = 3) con un
// envío firmado; la sonda `comprobar_nodo` pasa; justo después el mismo servidor (misma
// huella, mismo génesis) se vuelve malo. Comprobar el nodo y después abrir OTRA conexión no
// demuestra nada: lo que se exige es que la conexión que abre cada operación rechace al
// servidor por su cuenta.

struct Escena {
    /// Mantiene vivo el directorio temporal mientras dura la escena.
    _dir: tempfile::TempDir,
    sim: Simulado,
    b: Billetera,
    txid: String,
    vista: u32,
}

fn escena_sonda_pasa_y_luego_servidor_malo(estropear: fn(&Simulado)) -> Escena {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let mut b = billetera_emparejada(dir.path(), &sim);
    let vista = b.altura();
    let txid = firmar_40k(&mut b).txid;
    let info = comprobar_nodo(Red::Regtest, &sim.url, Some(&sim.huella()), vista, None).unwrap();
    assert_eq!((info.protocolo.as_str(), info.altura), ("1.4", vista));
    estropear(&sim);
    // La sonda, repetida ahora, lo rechaza: el servidor es malo de verdad.
    assert!(comprobar_nodo(Red::Regtest, &sim.url, Some(&sim.huella()), vista, None).is_err());
    Escena {
        _dir: dir,
        sim,
        b,
        txid,
        vista,
    }
}

fn protocolo_distinto(sim: &Simulado) {
    sim.fijar_protocolo("1.5");
}
fn altura_retrocede(sim: &Simulado) {
    sim.retroceder_a(1);
}

fn exigir_difusion_no_salio(s: &Escena) {
    let e = s.b.estado_envio(&s.txid).unwrap();
    assert_eq!((e.estado, e.intentos_difusion), (EstadoEnvio::Firmada, 0));
    assert_eq!(
        s.sim.recibidas(),
        0,
        "el servidor malo recibió la transacción"
    );
}

#[test]
fn puerta_protocolo_la_conexion_de_escanear_rechaza_al_servidor() {
    let mut s = escena_sonda_pasa_y_luego_servidor_malo(protocolo_distinto);
    let r = s.b.escanear(&s.sim.url);
    exigir_rechazo("escanear", r, es_rechazo_protocolo);
}

#[test]
fn puerta_protocolo_la_conexion_de_sincronizar_rechaza_al_servidor() {
    let mut s = escena_sonda_pasa_y_luego_servidor_malo(protocolo_distinto);
    let r = s.b.sincronizar(&s.sim.url);
    exigir_rechazo("sincronizar", r, es_rechazo_protocolo);
}

#[test]
fn puerta_protocolo_la_conexion_de_difundir_rechaza_al_servidor() {
    let mut s = escena_sonda_pasa_y_luego_servidor_malo(protocolo_distinto);
    let r = s.b.difundir(&s.sim.url, &s.txid);
    exigir_rechazo("difundir", r, es_rechazo_protocolo);
    exigir_difusion_no_salio(&s);
}

#[test]
fn puerta_protocolo_la_conexion_de_altura_rechaza_al_servidor() {
    let s = escena_sonda_pasa_y_luego_servidor_malo(protocolo_distinto);
    let r = altura_red(
        Red::Regtest,
        &s.sim.url,
        Some(&s.sim.huella()),
        s.vista,
        None,
    );
    exigir_rechazo("altura_red", r, es_rechazo_protocolo);
}

#[test]
fn puerta_protocolo_la_conexion_de_comision_rechaza_al_servidor() {
    let s = escena_sonda_pasa_y_luego_servidor_malo(protocolo_distinto);
    let r = estimar_comision(
        Red::Regtest,
        &s.sim.url,
        2,
        Some(&s.sim.huella()),
        s.vista,
        None,
    );
    exigir_rechazo("estimar_comision", r, es_rechazo_protocolo);
}

#[test]
fn puerta_altura_la_conexion_de_escanear_rechaza_al_servidor() {
    let mut s = escena_sonda_pasa_y_luego_servidor_malo(altura_retrocede);
    let r = s.b.escanear(&s.sim.url);
    let op = format!(
        "escanear (vista antes: {}, altura local después: {})",
        s.vista,
        s.b.altura()
    );
    exigir_rechazo(&op, r, es_rechazo_altura);
}

#[test]
fn puerta_altura_la_conexion_de_sincronizar_rechaza_al_servidor() {
    let mut s = escena_sonda_pasa_y_luego_servidor_malo(altura_retrocede);
    let r = s.b.sincronizar(&s.sim.url);
    let op = format!(
        "sincronizar (vista antes: {}, altura local después: {})",
        s.vista,
        s.b.altura()
    );
    exigir_rechazo(&op, r, es_rechazo_altura);
}

#[test]
fn puerta_altura_la_conexion_de_difundir_rechaza_al_servidor() {
    let mut s = escena_sonda_pasa_y_luego_servidor_malo(altura_retrocede);
    let r = s.b.difundir(&s.sim.url, &s.txid);
    exigir_rechazo("difundir", r, es_rechazo_altura);
    exigir_difusion_no_salio(&s);
}

#[test]
fn puerta_altura_la_conexion_de_altura_rechaza_al_servidor() {
    // `altura_red` recibe la última altura vista y la comprueba en su propia conexión.
    let s = escena_sonda_pasa_y_luego_servidor_malo(altura_retrocede);
    let r = altura_red(
        Red::Regtest,
        &s.sim.url,
        Some(&s.sim.huella()),
        s.vista,
        None,
    );
    exigir_rechazo(
        &format!("altura_red (vista: {})", s.vista),
        r,
        es_rechazo_altura,
    );
}

#[test]
fn puerta_altura_la_conexion_de_comision_rechaza_al_servidor() {
    // Igual que `altura_red`: `estimar_comision` recibe la última altura vista.
    let s = escena_sonda_pasa_y_luego_servidor_malo(altura_retrocede);
    let r = estimar_comision(
        Red::Regtest,
        &s.sim.url,
        2,
        Some(&s.sim.huella()),
        s.vista,
        None,
    );
    exigir_rechazo(
        &format!("estimar_comision (vista: {})", s.vista),
        r,
        es_rechazo_altura,
    );
}

// ----- 2: el defecto del prefijo ------------------------------------------------------------

#[test]
fn puerta_sonda_rechaza_protocolos_que_solo_empiezan_por_1_4() {
    // "1.40", "1.45", "1.4basura", "1.4." y "1.4.basura" no son el protocolo 1.4 ni una
    // revisión suya (1.4.x):
    // una comprobación por prefijo los deja pasar. Los positivos siguen en
    // `puerta_sonda_rechaza_protocolo_distinto_de_1_4` ("1.4" y "1.4.2").
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let huella = sim.huella();
    let mut aceptados: Vec<String> = Vec::new();
    for proto in ["1.40", "1.45", "1.4basura", "1.4.", "1.4.basura"] {
        sim.fijar_protocolo(proto);
        match comprobar_nodo(Red::Regtest, &sim.url, Some(&huella), 0, None) {
            Err(e) if es_rechazo_protocolo(&e) => {}
            Err(e) => panic!("protocolo {proto:?}: rechazado, pero no por la puerta: {e}"),
            Ok(info) => aceptados.push(format!("{proto:?} (anunciado como {:?})", info.protocolo)),
        }
    }
    assert!(
        aceptados.is_empty(),
        "la sonda ACEPTÓ protocolos que no son 1.4: {}",
        aceptados.join(", ")
    );
}

// ----- 1: misma conexión, de verdad --------------------------------------------------------
//
// El simulado numera las conexiones y apunta la secuencia de métodos de cada una. Escena
// adversaria: la sonda pasa; después el servidor es BUENO en la próxima conexión y MALO en
// la siguiente (maldad por conexión, no global). Un motor que comprueba por una conexión y
// opera por otra valida la buena y opera por la mala; un motor que valida su propia conexión
// opera por la buena y la operación sale bien. Se exige: que la operación salga bien, que
// viaje por UNA sola conexión, que esa misma conexión haya corrido antes `server.features`
// (génesis), `server.version` (protocolo) y `blockchain.headers.subscribe` (altura), y que
// ninguna petición haya llegado a una conexión mala.

const GENESIS: &str = "server.features";
const VERSION: &str = "server.version";
const ALTURA: &str = "blockchain.headers.subscribe";
const OP_HISTORIA: &str = "blockchain.scripthash.get_history";
const OP_DIFUSION: &str = "blockchain.transaction.broadcast";
const OP_COMISION: &str = "blockchain.estimatefee";

fn escena_buena_en_la_proxima_y_mala_en_la_siguiente(maldad: Maldad) -> (Escena, u64, u64) {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let mut b = billetera_emparejada(dir.path(), &sim);
    let vista = b.altura();
    let txid = firmar_40k(&mut b).txid;
    let info = comprobar_nodo(Red::Regtest, &sim.url, Some(&sim.huella()), vista, None).unwrap();
    assert_eq!((info.protocolo.as_str(), info.altura), ("1.4", vista));
    let desde = sim.proxima_conexion();
    let mala_desde = desde + 1;
    sim.fijar_maldad_desde(mala_desde, maldad);
    (
        Escena {
            _dir: dir,
            sim,
            b,
            txid,
            vista,
        },
        desde,
        mala_desde,
    )
}

/// Entre las conexiones abiertas desde `desde`: exactamente una llevó `operacion`; esa misma
/// corrió las tres validaciones antes de la operación; ninguna conexión mala recibió nada.
fn exigir_validada_y_operada_en_la_misma_conexion(
    sim: &Simulado,
    desde: u64,
    mala_desde: u64,
    operacion: &str,
) {
    let conexiones: Vec<(u64, Vec<String>)> = sim
        .conexiones()
        .into_iter()
        .filter(|(id, _)| *id >= desde)
        .collect();
    let mut fallos: Vec<String> = Vec::new();
    for (id, metodos) in &conexiones {
        if *id >= mala_desde && !metodos.is_empty() {
            fallos.push(format!(
                "la conexión {id} (MALA) recibió peticiones: {metodos:?}"
            ));
        }
    }
    let con_op: Vec<&(u64, Vec<String>)> = conexiones
        .iter()
        .filter(|(_, m)| m.iter().any(|x| x == operacion))
        .collect();
    if con_op.len() != 1 {
        fallos.push(format!(
            "{operacion} viajó por {} conexiones (se esperaba exactamente 1)",
            con_op.len()
        ));
    } else {
        let (id, metodos) = con_op[0];
        let pos_op = metodos.iter().position(|m| m == operacion).unwrap();
        for v in [GENESIS, VERSION, ALTURA] {
            match metodos.iter().position(|m| m == v) {
                Some(p) if p < pos_op || v == operacion => {}
                Some(_) => fallos.push(format!("conexión {id}: {v} llegó DESPUÉS de {operacion}")),
                None => fallos.push(format!(
                    "conexión {id}: operó ({operacion}) sin haber corrido {v}"
                )),
            }
        }
    }
    assert!(
        fallos.is_empty(),
        "la operación no viajó por una conexión validada por ella misma:\n  {}\n  conexiones desde la sonda: {conexiones:?}",
        fallos.join("\n  ")
    );
}

fn maldad_protocolo() -> Maldad {
    Maldad::Protocolo("1.5".into())
}
fn maldad_altura() -> Maldad {
    Maldad::AlturaTope(1)
}

fn escena_escanear(maldad: Maldad) {
    let (mut s, desde, mala) = escena_buena_en_la_proxima_y_mala_en_la_siguiente(maldad);
    s.b.escanear(&s.sim.url)
        .expect("la próxima conexión es buena");
    assert_eq!(s.b.altura(), s.vista);
    exigir_validada_y_operada_en_la_misma_conexion(&s.sim, desde, mala, OP_HISTORIA);
}

fn escena_sincronizar(maldad: Maldad) {
    let (mut s, desde, mala) = escena_buena_en_la_proxima_y_mala_en_la_siguiente(maldad);
    s.b.sincronizar(&s.sim.url)
        .expect("la próxima conexión es buena");
    assert_eq!(s.b.altura(), s.vista);
    exigir_validada_y_operada_en_la_misma_conexion(&s.sim, desde, mala, OP_HISTORIA);
}

fn escena_difundir(maldad: Maldad) {
    let (mut s, desde, mala) = escena_buena_en_la_proxima_y_mala_en_la_siguiente(maldad);
    let e =
        s.b.difundir(&s.sim.url, &s.txid)
            .expect("la próxima conexión es buena");
    assert_eq!((e.estado, e.intentos_difusion), (EstadoEnvio::Enviada, 1));
    assert_eq!(s.sim.recibidas(), 1);
    exigir_validada_y_operada_en_la_misma_conexion(&s.sim, desde, mala, OP_DIFUSION);
}

fn escena_altura(maldad: Maldad) {
    let (s, desde, mala) = escena_buena_en_la_proxima_y_mala_en_la_siguiente(maldad);
    let h = altura_red(
        Red::Regtest,
        &s.sim.url,
        Some(&s.sim.huella()),
        s.vista,
        None,
    )
    .expect("la próxima conexión es buena");
    assert_eq!(h, s.vista);
    exigir_validada_y_operada_en_la_misma_conexion(&s.sim, desde, mala, ALTURA);
}

fn escena_comision(maldad: Maldad) {
    let (s, desde, mala) = escena_buena_en_la_proxima_y_mala_en_la_siguiente(maldad);
    let c = estimar_comision(
        Red::Regtest,
        &s.sim.url,
        2,
        Some(&s.sim.huella()),
        s.vista,
        None,
    )
    .expect("la próxima conexión es buena");
    assert_eq!(c, 2.0);
    exigir_validada_y_operada_en_la_misma_conexion(&s.sim, desde, mala, OP_COMISION);
}

#[test]
fn puerta_protocolo_escanear_valida_y_opera_por_la_misma_conexion() {
    escena_escanear(maldad_protocolo());
}
#[test]
fn puerta_protocolo_sincronizar_valida_y_opera_por_la_misma_conexion() {
    escena_sincronizar(maldad_protocolo());
}
#[test]
fn puerta_protocolo_difundir_valida_y_opera_por_la_misma_conexion() {
    escena_difundir(maldad_protocolo());
}
#[test]
fn puerta_protocolo_altura_valida_y_opera_por_la_misma_conexion() {
    escena_altura(maldad_protocolo());
}
#[test]
fn puerta_protocolo_comision_valida_y_opera_por_la_misma_conexion() {
    escena_comision(maldad_protocolo());
}
#[test]
fn puerta_altura_escanear_valida_y_opera_por_la_misma_conexion() {
    escena_escanear(maldad_altura());
}
#[test]
fn puerta_altura_sincronizar_valida_y_opera_por_la_misma_conexion() {
    escena_sincronizar(maldad_altura());
}
#[test]
fn puerta_altura_difundir_valida_y_opera_por_la_misma_conexion() {
    escena_difundir(maldad_altura());
}
#[test]
fn puerta_altura_altura_red_valida_y_opera_por_la_misma_conexion() {
    escena_altura(maldad_altura());
}
#[test]
fn puerta_altura_comision_valida_y_opera_por_la_misma_conexion() {
    escena_comision(maldad_altura());
}

/// Cliente JSON-RPC mínimo de las pruebas, independiente del motor y de electrum-client:
/// una conexión `tcp://` al simulado por la que se pide exactamente lo que se escribe.
struct ClientePlano {
    lector: std::io::BufReader<std::net::TcpStream>,
    id: u64,
}

impl ClientePlano {
    fn conectar(sim: &Simulado) -> ClientePlano {
        let s = std::net::TcpStream::connect(("127.0.0.1", sim.puerto())).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        ClientePlano {
            lector: std::io::BufReader::new(s),
            id: 0,
        }
    }

    fn pedir(&mut self, metodo: &str, params: serde_json::Value) -> serde_json::Value {
        use std::io::{BufRead, Write};
        self.id += 1;
        let req = serde_json::json!({"jsonrpc": "2.0", "id": self.id, "method": metodo, "params": params});
        writeln!(self.lector.get_mut(), "{req}").unwrap();
        let mut linea = String::new();
        self.lector.read_line(&mut linea).unwrap();
        let resp: serde_json::Value = serde_json::from_str(&linea).unwrap();
        assert_eq!(resp["id"], self.id);
        resp["result"].clone()
    }
}

#[test]
fn simulado_la_maldad_por_conexion_distingue_conexiones() {
    // Autocomprobación del simulado, SIN pasar por el motor (`Conexion::abrir` rechazará
    // protocolo y altura en cada conexión cuando llegue el arreglo, y esta prueba debe seguir
    // verde): la conexión N ve el servidor bueno y la N+1 el malo, y cada conexión queda
    // apuntada con sus métodos en orden.
    let sim = Simulado::arrancar(Network::Regtest);
    sim.anadir_bloque(txid_cero());
    sim.anadir_bloque(txid_cero()); // altura 2
    let desde = sim.proxima_conexion();
    sim.fijar_maldad_desde(desde + 1, Maldad::Protocolo("1.5".into()));
    let mut buena = ClientePlano::conectar(&sim);
    let mut mala = ClientePlano::conectar(&sim);
    let version = serde_json::json!(["prueba", "1.4"]);
    assert_eq!(
        buena.pedir(GENESIS, serde_json::json!([]))["protocol_max"],
        "1.4"
    );
    assert_eq!(buena.pedir(VERSION, version.clone())[1], "1.4");
    assert_eq!(
        mala.pedir(GENESIS, serde_json::json!([]))["protocol_max"],
        "1.5"
    );
    assert_eq!(mala.pedir(VERSION, version)[1], "1.5");
    // Misma pareja de conexiones, ahora con la cadena truncada para la mala.
    sim.fijar_maldad_desde(desde + 1, Maldad::AlturaTope(0));
    assert_eq!(buena.pedir(ALTURA, serde_json::json!([]))["height"], 2);
    assert_eq!(
        mala.pedir(ALTURA, serde_json::json!([]))["height"],
        0,
        "la conexión mala ve la cadena truncada"
    );
    // La mala no sirve cabeceras por encima del tope; la buena sí.
    assert!(buena
        .pedir("blockchain.block.header", serde_json::json!([2]))
        .is_string());
    assert!(mala
        .pedir("blockchain.block.header", serde_json::json!([2]))
        .is_null());
    // Contabilidad: identificadores en orden y secuencia exacta de cada conexión.
    let conexiones = sim.conexiones();
    let de = |id: u64| conexiones.iter().find(|(i, _)| *i == id).unwrap().1.clone();
    let esperada = vec![GENESIS, VERSION, ALTURA, "blockchain.block.header"];
    assert_eq!(de(desde), esperada);
    assert_eq!(de(desde + 1), esperada);
    assert_eq!(sim.proxima_conexion(), desde + 2);
    assert_eq!(sim.recibidas(), 0);
    // Una tercera conexión también es mala (la maldad es «desde», no «solo esa»).
    let mut tercera = ClientePlano::conectar(&sim);
    assert_eq!(tercera.pedir(ALTURA, serde_json::json!([]))["height"], 0);
    let ultima = sim.conexiones().last().unwrap().clone();
    assert_eq!(ultima, (desde + 2, vec![ALTURA.to_string()]));
}

#[test]
fn simulado_el_cliente_electrum_normal_negocia_version_por_su_cuenta() {
    // Comportamiento de la BIBLIOTECA, no del motor: `electrum_client::Client` negocia
    // `server.version` POR SU CUENTA al conectar, proponiendo el rango 1.4–1.6 con nombre de
    // cliente vacío, guarda lo que el servidor responda en un campo privado sin lector y no
    // lo compara con nada. Por eso el motor NO usa `Client::new`/`from_config`: con él habría
    // dos negociaciones por sesión (la suya y la del motor; un servidor estricto rechaza la
    // segunda), el servidor podría negociar 1.6 sin que el motor lo viera, y el motor no
    // podría proponer 1.4 a secas. El motor abre el flujo él mismo y usa `RawClient::from`, que
    // no negocia, y hace UNA negociación propia que valida. Esta prueba documenta el porqué.
    use bdk_electrum::electrum_client::{Client, ConfigBuilder, ElectrumApi};
    let sim = Simulado::arrancar(Network::Regtest);
    sim.fijar_protocolo("1.5");
    let id = sim.proxima_conexion();
    let cfg = ConfigBuilder::new()
        .timeout(Some(Duration::from_secs(2)))
        .retry(0)
        .build();
    let c = Client::from_config(&sim.url, cfg).expect("electrum-client no rechaza el 1.5");
    assert!(c.server_features().is_ok());
    let metodos = sim
        .conexiones()
        .into_iter()
        .find(|(i, _)| *i == id)
        .unwrap()
        .1;
    assert_eq!(
        metodos,
        vec![VERSION, GENESIS],
        "el primer server.version no lo pidió nadie"
    );
}

// ----- B4, ajuste: retroceso recibido DURANTE BDK, una sola negociación, versión negociada -----

fn metodos_desde(sim: &Simulado, desde: u64) -> Vec<(u64, Vec<String>)> {
    sim.conexiones()
        .into_iter()
        .filter(|(id, _)| *id >= desde)
        .collect()
}

/// El servidor valida bien al abrir (3) y, dentro de la MISMA conexión, responde a la petición
/// de BDK con una altura inferior (`secuencia`, p. ej. 3→1 o 3→1→3). El motor debe detectar la
/// altura inferior realmente recibida y abortar ANTES de `envios.observar`, `apply_update` y
/// cualquier persistencia: estado idéntico en disco y en memoria. Una consulta final que
/// volviera a dar 3 no vale: la secuencia 3→1→3 lo demuestra (la petición tercera nunca llega
/// a hacerse).
fn exigir_aborto_por_altura_durante_bdk(op: &str, secuencia: Vec<u32>) {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let mut b = billetera_emparejada(dir.path(), &sim); // vista: 3
    let txid = firmar_40k(&mut b).txid;
    assert!(comprobar_nodo(
        Red::Regtest,
        &sim.url,
        huella_de(&sim).as_deref(),
        b.altura(),
        None
    )
    .is_ok());
    let desde = sim.proxima_conexion();
    sim.fijar_alturas_por_respuesta(secuencia.clone());
    exigir_rechazo_previo_sin_tocar_nada(
        &format!("altura durante BDK {secuencia:?}"),
        dir.path(),
        &mut b,
        &sim.url,
        &sim,
        Some(&txid),
        es_rechazo_altura,
        &[op],
    );
    // La conexión de la operación pidió la altura dos veces (validar y BDK) y se cortó ahí:
    // nada después de la altura inferior, y la tercera respuesta (3) nunca se pidió.
    let conexiones = metodos_desde(&sim, desde);
    assert_eq!(conexiones.len(), 1, "{conexiones:?}");
    assert_eq!(
        conexiones[0].1,
        vec![VERSION, GENESIS, ALTURA, ALTURA],
        "secuencia {secuencia:?}"
    );
}

#[test]
fn puerta_altura_inferior_recibida_durante_sincronizar_3_1_aborta_sin_tocar_nada() {
    exigir_aborto_por_altura_durante_bdk("sincronizar", vec![3, 1]);
}
#[test]
fn puerta_altura_inferior_recibida_durante_sincronizar_3_1_3_aborta_sin_tocar_nada() {
    exigir_aborto_por_altura_durante_bdk("sincronizar", vec![3, 1, 3]);
}
#[test]
fn puerta_altura_inferior_recibida_durante_escanear_3_1_aborta_sin_tocar_nada() {
    exigir_aborto_por_altura_durante_bdk("escanear", vec![3, 1]);
}
#[test]
fn puerta_altura_inferior_recibida_durante_escanear_3_1_3_aborta_sin_tocar_nada() {
    exigir_aborto_por_altura_durante_bdk("escanear", vec![3, 1, 3]);
}

#[test]
fn puerta_altura_red_devuelve_la_altura_validada_sin_segunda_consulta() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let b = billetera_emparejada(dir.path(), &sim);
    let desde = sim.proxima_conexion();
    assert_eq!(
        altura_red(
            Red::Regtest,
            &sim.url,
            huella_de(&sim).as_deref(),
            b.altura(),
            None
        )
        .unwrap(),
        3
    );
    let conexiones = metodos_desde(&sim, desde);
    assert_eq!(conexiones.len(), 1);
    assert_eq!(
        conexiones[0].1,
        vec![VERSION, GENESIS, ALTURA],
        "la altura devuelta es la validada; no se consulta dos veces"
    );
}

/// Servidor estricto que además negocia como uno real (responde lo que propone el cliente):
/// todas las operaciones salen bien y cada conexión lleva EXACTAMENTE un `server.version`,
/// porque el motor propone 1.4 a secas una sola vez. Con dos negociaciones por sesión (la de la
/// biblioteca con rango 1.4–1.6 y la del motor) la segunda falla; y con solo la de la
/// biblioteca, el servidor negociaría 1.6.
fn exigir_una_sola_negociacion(sim: &Simulado) {
    let dir = tempfile::tempdir().unwrap();
    sim.fijar_estricto(true);
    sim.fijar_negociar_segun_cliente(true);
    let mut b = billetera_emparejada(dir.path(), sim); // sincroniza ya con el servidor estricto
    let huella = huella_de(sim);
    let desde = sim.proxima_conexion();
    b.escanear(&sim.url).unwrap();
    b.sincronizar(&sim.url).unwrap();
    let txid = firmar_40k(&mut b).txid;
    assert_eq!(
        b.difundir(&sim.url, &txid).unwrap().estado,
        EstadoEnvio::Enviada
    );
    assert_eq!(
        altura_red(Red::Regtest, &sim.url, huella.as_deref(), b.altura(), None).unwrap(),
        3
    );
    assert_eq!(
        estimar_comision(
            Red::Regtest,
            &sim.url,
            2,
            huella.as_deref(),
            b.altura(),
            None
        )
        .unwrap(),
        2.0
    );
    let info = comprobar_nodo(Red::Regtest, &sim.url, huella.as_deref(), b.altura(), None).unwrap();
    assert_eq!(
        info.protocolo, "1.4",
        "la versión negociada es la que el motor propone"
    );
    for (id, metodos) in metodos_desde(sim, desde) {
        assert_eq!(
            metodos.iter().filter(|m| *m == VERSION).count(),
            1,
            "conexión {id}: una sola negociación; secuencia {metodos:?}"
        );
    }
}

#[test]
fn puerta_una_sola_negociacion_server_version_por_conexion_tcp() {
    exigir_una_sola_negociacion(&Simulado::arrancar(Network::Regtest));
}
#[test]
fn puerta_una_sola_negociacion_server_version_por_conexion_tls_con_huella() {
    exigir_una_sola_negociacion(&Simulado::arrancar_tls(Network::Regtest));
}

/// `server.features` anuncia 1.4 como mínimo y máximo, pero la versión efectivamente negociada
/// en `server.version` es 1.6: lo que manda es la negociada. Todas las operaciones se niegan y
/// nada cambia.
fn exigir_rechazo_por_version_negociada(sim: &Simulado) {
    let dir = tempfile::tempdir().unwrap();
    let mut b = billetera_emparejada(dir.path(), sim);
    let txid = firmar_40k(&mut b).txid;
    let huella = huella_de(sim);
    sim.fijar_protocolo_negociado("1.6");
    let err = comprobar_nodo(Red::Regtest, &sim.url, huella.as_deref(), 0, None).unwrap_err();
    assert!(es_rechazo_protocolo(&err), "{err}");
    exigir_rechazo(
        "altura_red",
        altura_red(Red::Regtest, &sim.url, huella.as_deref(), 0, None),
        es_rechazo_protocolo,
    );
    exigir_rechazo(
        "estimar_comision",
        estimar_comision(Red::Regtest, &sim.url, 2, huella.as_deref(), 0, None),
        es_rechazo_protocolo,
    );
    exigir_rechazo_previo_sin_tocar_nada(
        "versión negociada 1.6 con features 1.4",
        dir.path(),
        &mut b,
        &sim.url,
        sim,
        Some(&txid),
        es_rechazo_protocolo,
        &TODAS,
    );
}

#[test]
fn puerta_version_negociada_no_admitida_aunque_features_compatibles_tcp() {
    exigir_rechazo_por_version_negociada(&Simulado::arrancar(Network::Regtest));
}
#[test]
fn puerta_version_negociada_no_admitida_aunque_features_compatibles_tls_con_huella() {
    exigir_rechazo_por_version_negociada(&Simulado::arrancar_tls(Network::Regtest));
}

// ----- B4, marca de altura máxima vista; server.version primero; sin reconexión interna -----

/// Deja el simulado con `n` bloques y la billetera (si se pasa) sincronizada hasta ahí.
fn crecer_hasta(sim: &Simulado, b: Option<&mut Billetera>, n: u32) {
    while sim.estado.lock().unwrap().cabeceras.len() as u32 <= n {
        sim.anadir_bloque(txid_cero());
    }
    if let Some(b) = b {
        b.sincronizar(&sim.url).unwrap();
        assert_eq!(b.altura(), n);
    }
}

/// La altura mínima no basta: dentro de una sesión el servidor anuncia 500 y después 200. Las
/// dos superan la mínima (`minima`), pero la segunda retrocede de lo que el PROPIO servidor
/// ya dijo en esa conexión. La marca de altura máxima vista debe rechazarla, y el rechazo
/// deja estado y ficheros intactos (ayudante de siempre).
fn exigir_rechazo_por_retroceso_dentro_de_la_sesion(op: &str, minima: u32) {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let mut b = if minima == 0 {
        // Billetera recién creada: nada visto todavía (altura 0, sin fondos ni envíos).
        let mut b = Billetera::crear(mn(), Red::Regtest, &dir.path().join("b.dat")).unwrap();
        b.fijar_espera(Duration::from_secs(1));
        b.fijar_huella_servidor(huella_de(&sim));
        b
    } else {
        let mut b = billetera_emparejada(dir.path(), &sim);
        crecer_hasta(&sim, Some(&mut b), minima);
        b
    };
    assert_eq!(b.altura(), minima);
    let txid = (minima > 0).then(|| firmar_40k(&mut b).txid);
    crecer_hasta(&sim, None, 500);
    let desde = sim.proxima_conexion();
    sim.fijar_alturas_por_respuesta(vec![500, 200]);
    exigir_rechazo_previo_sin_tocar_nada(
        &format!("altura {minima} → 500 → 200 en la misma sesión"),
        dir.path(),
        &mut b,
        &sim.url,
        &sim,
        txid.as_deref(),
        es_rechazo_altura,
        &[op],
    );
    // Una sola conexión, cortada en la segunda altura.
    let conexiones = metodos_desde(&sim, desde);
    assert_eq!(conexiones.len(), 1, "{conexiones:?}");
    assert_eq!(
        conexiones[0].1.iter().filter(|m| *m == ALTURA).count(),
        2,
        "{:?}",
        conexiones[0].1
    );
    assert_eq!(conexiones[0].1.last().map(String::as_str), Some(ALTURA));
}

#[test]
fn puerta_marca_altura_100_500_200_rechaza_en_sincronizar() {
    exigir_rechazo_por_retroceso_dentro_de_la_sesion("sincronizar", 100);
}
#[test]
fn puerta_marca_altura_100_500_200_rechaza_en_escanear() {
    exigir_rechazo_por_retroceso_dentro_de_la_sesion("escanear", 100);
}
#[test]
fn puerta_marca_altura_0_500_200_rechaza_en_sincronizar() {
    exigir_rechazo_por_retroceso_dentro_de_la_sesion("sincronizar", 0);
}
#[test]
fn puerta_marca_altura_0_500_200_rechaza_en_escanear() {
    exigir_rechazo_por_retroceso_dentro_de_la_sesion("escanear", 0);
}

/// Servidor que exige `server.version` como PRIMER mensaje de cada sesión (especificación
/// Electrum): todas las operaciones salen bien y en cada conexión el primer método es la
/// negociación.
fn exigir_negociacion_como_primer_mensaje(sim: &Simulado) {
    let dir = tempfile::tempdir().unwrap();
    sim.fijar_negociacion_primero(true);
    let mut b = billetera_emparejada(dir.path(), sim);
    let huella = huella_de(sim);
    let desde = sim.proxima_conexion();
    b.escanear(&sim.url).unwrap();
    b.sincronizar(&sim.url).unwrap();
    let txid = firmar_40k(&mut b).txid;
    assert_eq!(
        b.difundir(&sim.url, &txid).unwrap().estado,
        EstadoEnvio::Enviada
    );
    assert_eq!(
        altura_red(Red::Regtest, &sim.url, huella.as_deref(), b.altura(), None).unwrap(),
        3
    );
    assert_eq!(
        estimar_comision(
            Red::Regtest,
            &sim.url,
            2,
            huella.as_deref(),
            b.altura(),
            None
        )
        .unwrap(),
        2.0
    );
    assert!(comprobar_nodo(Red::Regtest, &sim.url, huella.as_deref(), b.altura(), None).is_ok());
    for (id, metodos) in metodos_desde(sim, desde) {
        assert_eq!(
            metodos.first().map(String::as_str),
            Some(VERSION),
            "conexión {id}: la negociación no fue el primer mensaje: {metodos:?}"
        );
    }
}

#[test]
fn puerta_server_version_es_el_primer_mensaje_de_la_sesion_tcp() {
    exigir_negociacion_como_primer_mensaje(&Simulado::arrancar(Network::Regtest));
}
#[test]
fn puerta_server_version_es_el_primer_mensaje_de_la_sesion_tls_con_huella() {
    exigir_negociacion_como_primer_mensaje(&Simulado::arrancar_tls(Network::Regtest));
}

#[test]
fn puerta_una_conexion_caida_no_se_reabre_sola_y_la_siguiente_pasa_las_cuatro_puertas() {
    // Propiedad que antes garantizaba `retry(0)` del cliente de electrum-client y ahora
    // garantiza la construcción: `RawClient` no tiene reconexión. Una conexión cuyo servidor
    // cae NO se reabre por dentro (el servidor que vuelve en la misma dirección no ve ninguna
    // conexión nueva de la antigua), y la operación siguiente abre una conexión nueva que pasa
    // las cuatro puertas antes de operar.
    use crate::nodo::Conexion;
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let puerto = sim.puerto();
    let mut b = billetera_emparejada(dir.path(), &sim); // vista: 3
    let c = Conexion::abrir(
        &sim.url,
        Network::Regtest,
        None,
        Duration::from_secs(2),
        3,
        None,
    )
    .unwrap();
    assert_eq!(c.altura().unwrap(), 3);
    sim.parar();
    let otro = Simulado::arrancar_en(puerto, Network::Regtest);
    assert_eq!(otro.url, sim.url);
    // La conexión antigua falla y sigue fallando: ningún intento de reabrir por dentro.
    for _ in 0..3 {
        assert!(matches!(c.altura(), Err(Error::Red(_))));
        assert!(matches!(c.comision_btc_kvb(2), Err(Error::Red(_))));
    }
    assert!(
        otro.conexiones().is_empty(),
        "la conexión caída abrió sola otra: {:?}",
        otro.conexiones()
    );
    // El servidor nuevo empieza de cero (altura 0 < 3 vista): la operación siguiente abre una
    // conexión nueva, pasa las puertas y lo rechaza por altura.
    exigir_rechazo("sincronizar", b.sincronizar(&otro.url), es_rechazo_altura);
    let conexiones = otro.conexiones();
    assert_eq!(conexiones.len(), 1, "{conexiones:?}");
    assert_eq!(conexiones[0].1, vec![VERSION, GENESIS, ALTURA]);
    // Y cuando el servidor nuevo alcanza lo visto, la operación siguiente vuelve a pasar las
    // cuatro puertas en su propia conexión y opera.
    crecer_hasta(&otro, None, 3);
    let desde = otro.proxima_conexion();
    assert_eq!(
        altura_red(Red::Regtest, &otro.url, None, b.altura(), None).unwrap(),
        3
    );
    assert_eq!(
        metodos_desde(&otro, desde)[0].1,
        vec![VERSION, GENESIS, ALTURA]
    );
}

#[test]
fn vigilado_marca_de_altura_maxima_es_atomica_y_sube() {
    // Unidad del vigilante, sin red: la marca empieza en la mínima, sube con cada altura
    // anunciada y rechaza cualquier altura inferior a la marca, no solo a la mínima.
    use crate::nodo::Vigilado;
    let v = Vigilado::nuevo((), 100);
    assert!(v.comprobar_altura(100).is_ok(), "igual a la mínima");
    assert!(v.comprobar_altura(500).is_ok(), "sube a 500");
    let err = v.comprobar_altura(200).unwrap_err().to_string();
    assert!(
        err.contains("por detrás") && err.contains("200") && err.contains("500"),
        "{err}"
    );
    assert!(v.comprobar_altura(500).is_ok(), "igual a la marca");
    assert!(v.comprobar_altura(501).is_ok());
    assert!(v.comprobar_altura(499).is_err(), "la marca ya es 501");
    // Desde cero: 500 y después 200 también se rechaza (la mínima 0 no bastaría).
    let v = Vigilado::nuevo((), 0);
    assert!(v.comprobar_altura(500).is_ok());
    assert!(v.comprobar_altura(200).is_err());
    // Fuera de u32: error, y la marca no cambia.
    if usize::BITS > 32 {
        let err = v
            .comprobar_altura(u32::MAX as usize + 1)
            .unwrap_err()
            .to_string();
        assert!(err.contains("fuera de rango"), "{err}");
        assert!(v.comprobar_altura(500).is_ok(), "la marca sigue en 500");
    }
}

// ----- Selector de nodo: proxy SOCKS5 (.onion) y huella observada del certificado -----------

/// Proxy SOCKS5 falso en la interfaz local: acepta CONNECT sin autenticación, apunta el destino
/// (nombre o IP y puerto) que pidió el cliente y reenvía al simulado `destino_real` IGNORANDO el
/// nombre pedido (como haría Tor con un `.onion`). Con `rechazar` responde ese código de error.
struct ProxyFalso {
    direccion: String,
    pedidos: Arc<Mutex<Vec<(String, u16)>>>,
    vivo: Arc<std::sync::atomic::AtomicBool>,
}

impl ProxyFalso {
    fn arrancar(destino_real: &str, rechazar: Option<u8>) -> ProxyFalso {
        use std::sync::atomic::{AtomicBool, Ordering};
        let escucha = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        escucha.set_nonblocking(true).unwrap();
        let direccion = escucha.local_addr().unwrap().to_string();
        let pedidos = Arc::new(Mutex::new(Vec::new()));
        let vivo = Arc::new(AtomicBool::new(true));
        let (p2, v2, destino) = (pedidos.clone(), vivo.clone(), destino_real.to_string());
        std::thread::spawn(move || {
            while v2.load(Ordering::SeqCst) {
                match escucha.accept() {
                    Ok((s, _)) => {
                        let (p, d) = (p2.clone(), destino.clone());
                        std::thread::spawn(move || atender_socks5(s, &d, rechazar, &p));
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(5))
                    }
                    Err(_) => break,
                }
            }
        });
        ProxyFalso {
            direccion,
            pedidos,
            vivo,
        }
    }

    fn pedidos(&self) -> Vec<(String, u16)> {
        self.pedidos.lock().unwrap().clone()
    }
}

impl Drop for ProxyFalso {
    fn drop(&mut self) {
        self.vivo.store(false, std::sync::atomic::Ordering::SeqCst);
    }
}

fn atender_socks5(
    mut c: std::net::TcpStream,
    destino_real: &str,
    rechazar: Option<u8>,
    pedidos: &Mutex<Vec<(String, u16)>>,
) {
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, Ipv6Addr, Shutdown, TcpStream};
    let mut saludo = [0u8; 2];
    if c.read_exact(&mut saludo).is_err() {
        return;
    }
    assert_eq!(saludo[0], 5, "versión SOCKS5");
    let mut metodos = vec![0u8; saludo[1] as usize];
    c.read_exact(&mut metodos).unwrap();
    assert!(
        metodos.contains(&0),
        "el motor debe ofrecer «sin autenticación»"
    );
    c.write_all(&[5, 0]).unwrap();
    let mut cab = [0u8; 4];
    c.read_exact(&mut cab).unwrap();
    assert_eq!(&cab[..3], &[5, 1, 0], "CONNECT");
    let host = match cab[3] {
        1 => {
            let mut ip = [0u8; 4];
            c.read_exact(&mut ip).unwrap();
            Ipv4Addr::from(ip).to_string()
        }
        3 => {
            let mut l = [0u8; 1];
            c.read_exact(&mut l).unwrap();
            let mut h = vec![0u8; l[0] as usize];
            c.read_exact(&mut h).unwrap();
            String::from_utf8(h).unwrap()
        }
        4 => {
            let mut ip = [0u8; 16];
            c.read_exact(&mut ip).unwrap();
            Ipv6Addr::from(ip).to_string()
        }
        otro => panic!("tipo de dirección {otro}"),
    };
    let mut p = [0u8; 2];
    c.read_exact(&mut p).unwrap();
    pedidos.lock().unwrap().push((host, u16::from_be_bytes(p)));
    if let Some(codigo) = rechazar {
        c.write_all(&[5, codigo, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
        return;
    }
    let mut s = match TcpStream::connect(destino_real) {
        Ok(s) => s,
        Err(_) => {
            c.write_all(&[5, 5, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
            return;
        }
    };
    c.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).unwrap();
    let (mut c2, mut s2) = (c.try_clone().unwrap(), s.try_clone().unwrap());
    let ida = std::thread::spawn(move || {
        let _ = std::io::copy(&mut s2, &mut c2);
        let _ = c2.shutdown(Shutdown::Write);
    });
    let _ = std::io::copy(&mut c, &mut s);
    let _ = s.shutdown(Shutdown::Write);
    let _ = ida.join();
}

fn host_puerto(url: &str) -> String {
    url.split("://").nth(1).unwrap().to_string()
}

#[test]
fn nodo_onion_sin_proxy_se_rechaza_antes_de_abrir_socket() {
    use crate::nodo::Destino;
    assert!(Destino::analizar("tcp://abcdefghijklmnop.ONION:50001")
        .unwrap()
        .es_onion());
    assert!(!Destino::analizar("tcp://nodo.onion.ejemplo.local:50001")
        .unwrap()
        .es_onion());
    // Sin proxy: error claro, y ni un socket: el nombre no se resuelve (no hay DNS que lo
    // resuelva) y el tiempo es inmediato.
    let t0 = std::time::Instant::now();
    for (res, que) in [
        (
            comprobar_nodo(
                Red::Regtest,
                "tcp://abcdefghijklmnop.onion:50001",
                None,
                0,
                None,
            )
            .map(|_| ()),
            "sonda",
        ),
        (
            altura_red(
                Red::Regtest,
                "tcp://abcdefghijklmnop.onion:50001",
                None,
                0,
                None,
            )
            .map(|_| ()),
            "altura",
        ),
        (
            huella_servidor("ssl://abcdefghijklmnop.onion:50002", None).map(|_| ()),
            "huella",
        ),
    ] {
        let err = res.unwrap_err();
        assert!(
            matches!(err, Error::Red(ref m) if m.contains("proxy SOCKS5") && m.contains(".onion")),
            "{que}: {err}"
        );
    }
    // Un proxy en blanco es «sin proxy».
    assert!(matches!(
        comprobar_nodo(Red::Regtest, "tcp://abcdefghijklmnop.onion:50001", None, 0, Some("  ")).unwrap_err(),
        Error::Red(ref m) if m.contains("proxy SOCKS5")
    ));
    assert!(
        t0.elapsed() < Duration::from_secs(2),
        "no debe haber intentado nada por la red"
    );
    // La billetera entera: con un .onion y sin proxy, ninguna operación abre socket.
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    let antes = sim.proxima_conexion();
    let err = b
        .sincronizar("tcp://abcdefghijklmnop.onion:50001")
        .unwrap_err();
    assert!(
        matches!(err, Error::Red(ref m) if m.contains("proxy SOCKS5")),
        "{err}"
    );
    assert_eq!(sim.proxima_conexion(), antes, "ninguna conexión nueva");
}

#[test]
fn nodo_por_proxy_socks5_llega_al_servidor_y_el_nombre_lo_resuelve_el_proxy() {
    let sim = Simulado::arrancar(Network::Regtest);
    let proxy = ProxyFalso::arrancar(&host_puerto(&sim.url), None);
    // El nombre .onion viaja tal cual al proxy (ATYP 3); este proceso no lo resuelve.
    let info = comprobar_nodo(
        Red::Regtest,
        "tcp://nodo-de-prueba.onion:50001",
        None,
        0,
        Some(&proxy.direccion),
    )
    .unwrap();
    assert_eq!(info.altura, 0);
    assert_eq!(info.red, Red::Regtest);
    assert!(!info.cifrado);
    assert_eq!(info.servidor, "tcp://nodo-de-prueba.onion:50001");
    assert_eq!(
        proxy.pedidos(),
        vec![("nodo-de-prueba.onion".to_string(), 50001)]
    );
    // Las funciones libres también pasan por el proxy.
    assert_eq!(
        altura_red(
            Red::Regtest,
            "tcp://otro.onion:1",
            None,
            0,
            Some(&proxy.direccion)
        )
        .unwrap(),
        0
    );
    assert_eq!(
        estimar_comision(
            Red::Regtest,
            "tcp://otro.onion:1",
            2,
            None,
            0,
            Some(&proxy.direccion)
        )
        .unwrap(),
        2.0
    );
    // Una IP literal se pide como IP (ATYP 1), no como nombre.
    comprobar_nodo(Red::Regtest, &sim.url, None, 0, Some(&proxy.direccion)).unwrap();
    let pedidos = proxy.pedidos();
    assert_eq!(pedidos.len(), 4);
    assert_eq!(pedidos[3].0, "127.0.0.1");
    assert_eq!(pedidos[3].1, sim.puerto());
    // La billetera: TODAS sus operaciones van por el proxy una vez fijado (escaneo con fondos,
    // sincronización, difusión), y las puertas se pasan igual (génesis de otra red → rechazo).
    let dir = tempfile::tempdir().unwrap();
    let mut b = billetera_regtest(dir.path(), &sim);
    b.fijar_proxy(Some(proxy.direccion.clone()));
    let txid = fondear(&mut b, &sim, FONDOS);
    let antes = proxy.pedidos().len();
    b.sincronizar("tcp://billetera.onion:50001").unwrap();
    // billetera_regtest ya trae un depósito de FONDOS; este es el segundo.
    assert_eq!(b.saldo().total, 2 * FONDOS);
    assert!(b.movimientos().iter().any(|m| m.txid == txid.to_string()));
    assert!(
        proxy.pedidos().len() > antes,
        "la sincronización pasó por el proxy"
    );
    assert!(proxy
        .pedidos()
        .iter()
        .skip(antes)
        .all(|(h, p)| h == "billetera.onion" && *p == 50001));
    assert!(matches!(
        b.sincronizar("tcp://billetera.onion:50001")
            .and_then(|_| comprobar_nodo(
                Red::Signet,
                "tcp://x.onion:1",
                None,
                0,
                Some(&proxy.direccion)
            )
            .map(|_| ()))
            .unwrap_err(),
        Error::RedServidor(_)
    ));
}

#[test]
fn nodo_por_proxy_socks5_con_tls_la_huella_sigue_mandando() {
    let sim = Simulado::arrancar_tls(Network::Regtest);
    let proxy = ProxyFalso::arrancar(&host_puerto(&sim.url), None);
    let buena = sim.huella();
    let info = comprobar_nodo(
        Red::Regtest,
        "ssl://nodo-tls.onion:50002",
        Some(&buena),
        0,
        Some(&proxy.direccion),
    )
    .unwrap();
    assert!(info.cifrado);
    assert_eq!(info.huella_vista, buena);
    // Huella equivocada: el apretón TLS falla dentro del túnel del proxy, igual que en directo.
    let err = comprobar_nodo(
        Red::Regtest,
        "ssl://nodo-tls.onion:50002",
        Some(&"11".repeat(32)),
        0,
        Some(&proxy.direccion),
    )
    .unwrap_err();
    assert!(matches!(err, Error::HuellaServidor(_)), "{err}");
    // Sin huella, el autofirmado no pasa por raíces públicas, tampoco por el proxy.
    assert!(matches!(
        comprobar_nodo(
            Red::Regtest,
            "ssl://nodo-tls.onion:50002",
            None,
            0,
            Some(&proxy.direccion)
        )
        .unwrap_err(),
        Error::Red(_)
    ));
    // Pero su huella se puede OBSERVAR a través del proxy para mostrársela al usuario.
    assert_eq!(
        huella_servidor("ssl://nodo-tls.onion:50002", Some(&proxy.direccion)).unwrap(),
        buena
    );
    assert_eq!(proxy.pedidos().len(), 4);
}

#[test]
fn nodo_proxy_que_rechaza_falta_o_esta_mal_escrito_da_error_claro() {
    let sim = Simulado::arrancar(Network::Regtest);
    // El proxy responde «servidor inalcanzable» (0x04): el texto lo dice y nombra el destino.
    let rechaza = ProxyFalso::arrancar(&host_puerto(&sim.url), Some(4));
    let err = comprobar_nodo(
        Red::Regtest,
        "tcp://caido.onion:50001",
        None,
        0,
        Some(&rechaza.direccion),
    )
    .unwrap_err();
    assert!(
        matches!(err, Error::Red(ref m) if m.contains("proxy SOCKS5") && m.contains("inalcanzable") && m.contains("caido.onion:50001")),
        "{err}"
    );
    assert_eq!(sim.proxima_conexion(), 1, "el simulado no recibió nada");
    // Nadie escucha donde se dice que está el proxy (un puerto local que acabamos de soltar).
    let libre = {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        l.local_addr().unwrap().to_string()
    };
    let err = comprobar_nodo(Red::Regtest, "tcp://x.onion:1", None, 0, Some(&libre)).unwrap_err();
    assert!(
        matches!(err, Error::Red(ref m) if m.contains(&format!("proxy SOCKS5 {libre}"))),
        "{err}"
    );
    // Proxy mal escrito: se dice la forma esperada.
    for malo in ["127.0.0.1", "127.0.0.1:abc", ":9050"] {
        let err = comprobar_nodo(Red::Regtest, "tcp://x.onion:1", None, 0, Some(malo)).unwrap_err();
        assert!(
            matches!(err, Error::Red(ref m) if m.contains("proxy SOCKS5")),
            "{malo}: {err}"
        );
    }
    // Y algo que escucha pero no habla SOCKS5 (acepta y cuelga): no se confunde con éxito.
    let mudo = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let dir_mudo = mudo.local_addr().unwrap().to_string();
    std::thread::spawn(move || {
        if let Ok((s, _)) = mudo.accept() {
            drop(s);
        }
    });
    let err =
        comprobar_nodo(Red::Regtest, "tcp://x.onion:1", None, 0, Some(&dir_mudo)).unwrap_err();
    assert!(
        matches!(err, Error::Red(ref m) if m.contains("proxy SOCKS5") && m.contains("saludo")),
        "{err}"
    );
}

/// Auditoría del cierre, punto 1 (proxy): en modo «Mi nodo» con proxy el NOMBRE del servidor no se
/// resuelve en este proceso. Un nombre bajo `.invalid` (RFC 6761: ningún resolutor lo resuelve
/// jamás) llega tal cual al proxy (ATYP 3), con `tcp://` y con `ssl://` (SNI con el nombre, sin
/// DNS), para la sonda, las funciones libres y TODAS las conexiones de la billetera. Si el motor
/// resolviera localmente, la conexión fallaría en «resolviendo …» y la prueba también.
#[test]
fn nodo_por_proxy_el_nombre_de_mi_nodo_no_se_resuelve_en_este_proceso() {
    let sim = Simulado::arrancar(Network::Regtest);
    let proxy = ProxyFalso::arrancar(&host_puerto(&sim.url), None);
    let info = comprobar_nodo(
        Red::Regtest,
        "tcp://mi-nodo.invalid:50001",
        None,
        0,
        Some(&proxy.direccion),
    )
    .unwrap();
    assert_eq!(info.servidor, "tcp://mi-nodo.invalid:50001");
    assert_eq!(
        proxy.pedidos(),
        vec![("mi-nodo.invalid".to_string(), 50001)],
        "el proxy recibe el nombre tal cual"
    );
    altura_red(
        Red::Regtest,
        "tcp://MI-NODO.INVALID:50001",
        None,
        0,
        Some(&proxy.direccion),
    )
    .unwrap();
    estimar_comision(
        Red::Regtest,
        "tcp://mi-nodo.invalid:50001",
        2,
        None,
        0,
        Some(&proxy.direccion),
    )
    .unwrap();
    // La billetera entera, con el proxy fijado: escaneo, sincronización y difusión.
    let dir = tempfile::tempdir().unwrap();
    let mut b = billetera_regtest(dir.path(), &sim);
    b.fijar_proxy(Some(proxy.direccion.clone()));
    fondear(&mut b, &sim, FONDOS);
    b.sincronizar("tcp://mi-nodo.invalid:50001").unwrap();
    assert_eq!(b.saldo().total, 2 * FONDOS);
    let e = firmar_40k(&mut b);
    assert_eq!(
        b.difundir("tcp://mi-nodo.invalid:50001", &e.txid)
            .unwrap()
            .estado,
        EstadoEnvio::Enviada
    );
    let pedidos = proxy.pedidos();
    assert!(pedidos.len() >= 5, "{pedidos:?}");
    assert!(
        pedidos
            .iter()
            .all(|(h, p)| h.eq_ignore_ascii_case("mi-nodo.invalid") && *p == 50001),
        "ningún pedido al proxy lleva una IP: nadie resolvió el nombre: {pedidos:?}"
    );
    // Lo mismo con TLS: el nombre va en el SNI y al proxy, la huella se comprueba igual.
    let tls = Simulado::arrancar_tls(Network::Regtest);
    let proxy_tls = ProxyFalso::arrancar(&host_puerto(&tls.url), None);
    let info = comprobar_nodo(
        Red::Regtest,
        "ssl://mi-nodo.invalid:50002",
        Some(&tls.huella()),
        0,
        Some(&proxy_tls.direccion),
    )
    .unwrap();
    assert!(info.cifrado);
    assert_eq!(info.huella_vista, tls.huella());
    assert_eq!(
        huella_servidor("ssl://mi-nodo.invalid:50002", Some(&proxy_tls.direccion)).unwrap(),
        tls.huella()
    );
    assert!(proxy_tls
        .pedidos()
        .iter()
        .all(|(h, p)| h == "mi-nodo.invalid" && *p == 50002));
    // Contraste: sin proxy el nombre tendría que resolverse aquí y `.invalid` no resuelve nunca;
    // el error es el de la resolución y el simulado no recibe ninguna conexión más.
    let antes = sim.conexiones().len();
    let err =
        comprobar_nodo(Red::Regtest, "tcp://mi-nodo.invalid:50001", None, 0, None).unwrap_err();
    assert!(
        matches!(err, Error::Red(ref m) if m.contains("resolviendo") || m.contains("no se pudo conectar")),
        "{err}"
    );
    assert_eq!(sim.conexiones().len(), antes);
}

/// Auditoría del cierre, punto 2 (génesis): un servidor que contesta bien a `server.version` pero
/// falla en `server.features` (el mensaje que trae el `genesis_hash`) se rechaza en la sonda y en
/// cada operación de la billetera, y NO se toca nada: ni un byte de los ficheros ni la memoria de
/// la billetera; la conexión rechazada pidió exactamente `server.version` y `server.features` y
/// nada más (ni cabeceras ni historial). Cuando `server.features` vuelve a funcionar, todo sigue.
#[test]
fn puerta_genesis_servidor_que_negocia_pero_falla_en_features_se_rechaza_sin_tocar_nada() {
    let dir = tempfile::tempdir().unwrap();
    let sim = Simulado::arrancar(Network::Regtest);
    let mut b = billetera_regtest(dir.path(), &sim);
    fondear(&mut b, &sim, FONDOS);
    b.sincronizar(&sim.url).unwrap();
    let e = firmar_40k(&mut b);
    let (antes_disco, antes_mem) = (instantanea(dir.path()), memoria(&b));
    let conexiones_antes = sim.conexiones().len();
    sim.fijar_features_falla(true);
    // La sonda.
    let err = comprobar_nodo(Red::Regtest, &sim.url, None, 0, None).unwrap_err();
    assert!(
        matches!(err, Error::Red(ref m) if m.contains("server.features")),
        "{err}"
    );
    assert!(
        !es_rechazo_red(&err),
        "no es «otra red»: es que no pudo comprobarse"
    );
    // Cada operación de la billetera.
    assert!(b.sincronizar(&sim.url).is_err());
    assert!(b.escanear(&sim.url).is_err());
    assert!(b.difundir(&sim.url, &e.txid).is_err());
    assert!(altura_red(Red::Regtest, &sim.url, None, 0, None).is_err());
    assert!(estimar_comision(Red::Regtest, &sim.url, 2, None, 0, None).is_err());
    // Nada tocado: disco byte a byte, memoria, y el envío sigue Firmada con 0 intentos.
    assert_eq!(
        ficheros_distintos(&antes_disco, &instantanea(dir.path())),
        Vec::<String>::new()
    );
    assert_eq!(memoria(&b), antes_mem);
    let ee = b.estado_envio(&e.txid).unwrap();
    assert_eq!((ee.estado, ee.intentos_difusion), (EstadoEnvio::Firmada, 0));
    assert!(sim.difundidas().is_empty());
    // Cada conexión rechazada habló `server.version` y `server.features`, y nada más.
    let nuevas: Vec<_> = sim
        .conexiones()
        .into_iter()
        .skip(conexiones_antes)
        .collect();
    assert_eq!(nuevas.len(), 6, "{nuevas:?}");
    for (_, metodos) in &nuevas {
        assert_eq!(
            metodos,
            &vec!["server.version".to_string(), "server.features".to_string()],
            "{nuevas:?}"
        );
    }
    // Arreglado el servidor, la misma billetera sigue sin más.
    sim.fijar_features_falla(false);
    b.sincronizar(&sim.url).unwrap();
    assert_eq!(
        b.difundir(&sim.url, &e.txid).unwrap().estado,
        EstadoEnvio::Enviada
    );
}

#[test]
fn nodo_huella_servidor_observa_el_spki_sin_hablar_electrum() {
    let sim = Simulado::arrancar_tls(Network::Regtest);
    assert_eq!(huella_servidor(&sim.url, None).unwrap(), sim.huella());
    // Solo el apretón de manos: la conexión que vio el simulado no pidió ningún método.
    let conexiones = sim.conexiones();
    assert_eq!(conexiones.len(), 1, "{conexiones:?}");
    assert!(
        conexiones[0].1.is_empty(),
        "sin mensajes Electrum: {conexiones:?}"
    );
    // Con tcp:// no hay certificado que observar.
    let plano = Simulado::arrancar(Network::Regtest);
    assert!(matches!(
        huella_servidor(&plano.url, None).unwrap_err(),
        Error::Red(ref m) if m.contains("solo existe con ssl://")
    ));
    assert_eq!(plano.proxima_conexion(), 1);
    // Observar NO es validar: la huella observada solo sirve una vez FIJADA por quien llama.
    let mala = "33".repeat(32);
    assert!(matches!(
        comprobar_nodo(Red::Regtest, &sim.url, Some(&mala), 0, None).unwrap_err(),
        Error::HuellaServidor(_)
    ));
    assert!(comprobar_nodo(
        Red::Regtest,
        &sim.url,
        Some(&huella_servidor(&sim.url, None).unwrap()),
        0,
        None
    )
    .is_ok());
}

/// Banco regtest local real (Bitcoin Core + electrs 0.11.1 en `tcp://127.0.0.1:60401`, ver
/// `~/x39-regtest`): `cargo test --features mainnet -- --ignored banco_regtest`. Mismas puertas
/// que con el simulado, ahora contra un electrs de verdad: sonda sana con versión, altura y red;
/// otra red rechazada por génesis; `.onion` sin proxy rechazado; y la misma sonda a través de un
/// proxy SOCKS5 (falso, local) que reenvía al electrs real.
#[test]
#[ignore]
fn banco_regtest_selector_nodo_sonda_otra_red_y_proxy() {
    const BANCO: &str = "tcp://127.0.0.1:60401";
    let info = comprobar_nodo(Red::Regtest, BANCO, None, 0, None).unwrap();
    println!(
        "electrs real: servidor «{}», protocolo {}, altura {}, red {}, {} ms, cifrado {}",
        info.version_servidor,
        info.protocolo,
        info.altura,
        info.red,
        info.latencia_ms,
        info.cifrado
    );
    assert!(
        info.version_servidor.to_lowercase().contains("electrs"),
        "{}",
        info.version_servidor
    );
    assert!(crate::nodo::protocolo_admitido(&info.protocolo));
    assert_eq!(info.red, Red::Regtest);
    assert!(!info.cifrado);
    // P3: pedido como signet (o mainnet), el génesis no cuadra → rechazado.
    let err = comprobar_nodo(Red::Signet, BANCO, None, 0, None).unwrap_err();
    println!("pedido como signet: {err}");
    assert!(matches!(err, Error::RedServidor(_)), "{err}");
    let err = comprobar_nodo(Red::Mainnet, BANCO, None, 0, None).unwrap_err();
    println!("pedido como mainnet: {err}");
    assert!(
        matches!(err, Error::RedServidor(_)) || matches!(err, Error::RedProhibida),
        "{err}"
    );
    // La altura validada coincide con la de la sonda.
    assert_eq!(
        altura_red(Red::Regtest, BANCO, None, 0, None).unwrap(),
        info.altura
    );
    // Un servidor por debajo de lo ya visto se rechaza.
    let err = comprobar_nodo(Red::Regtest, BANCO, None, info.altura + 10, None).unwrap_err();
    println!("altura exigida +10: {err}");
    assert!(
        matches!(err, Error::Red(ref m) if m.contains("va por detrás")),
        "{err}"
    );
    // .onion sin proxy: ni un socket.
    let err = comprobar_nodo(
        Red::Regtest,
        "tcp://abcdefghijklmnop.onion:50001",
        None,
        0,
        None,
    )
    .unwrap_err();
    println!(".onion sin proxy: {err}");
    assert!(matches!(err, Error::Red(ref m) if m.contains("proxy SOCKS5")));
    // Y la misma sonda por un proxy SOCKS5 local que reenvía al electrs real.
    let proxy = ProxyFalso::arrancar("127.0.0.1:60401", None);
    let por_proxy = comprobar_nodo(
        Red::Regtest,
        "tcp://banco-regtest.onion:60401",
        None,
        0,
        Some(&proxy.direccion),
    )
    .unwrap();
    println!(
        "por proxy SOCKS5 {}: altura {}, servidor «{}»",
        proxy.direccion, por_proxy.altura, por_proxy.version_servidor
    );
    assert_eq!(por_proxy.altura, info.altura);
    assert_eq!(por_proxy.version_servidor, info.version_servidor);
    assert_eq!(
        proxy.pedidos(),
        vec![("banco-regtest.onion".to_string(), 60401)]
    );
}
