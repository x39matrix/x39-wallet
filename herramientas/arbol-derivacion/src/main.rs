//! Tabla de direcciones BIP-84 para la página «Árbol de derivación» de la billetera x39.
//!
//! Mnemónico: SOLO el vector de prueba público de BIP-84. Deriva m/84'/0'/cuenta'/rama/índice
//! para cuenta' 0–3, rama 0 y 1, índice 0–19 (160 direcciones) y la huella del maestro, igual
//! que el motor (rust/src/lib.rs: `raiz` + `clave_en`): BIP-39 con passphrase vacía,
//! `Xpriv::new_master`, `derive_priv` y `Address::p2wpkh`.
//!
//! Antes de escribir nada comprueba las tres direcciones publicadas en el propio BIP-84; si
//! alguna no coincide, termina con error y sin salida.

use bip39::Mnemonic;
use bitcoin::bip32::{DerivationPath, Xpriv};
use bitcoin::secp256k1::Secp256k1;
use bitcoin::{Address, CompressedPublicKey, Network};
use std::str::FromStr;

/// Vector de prueba público de BIP-84 (no es la semilla de nadie).
const VECTOR: &str =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

/// Referencia publicada en BIP-84 (sección «Test vectors»).
const REFERENCIA: [(&str, &str); 3] = [
    ("m/84'/0'/0'/0/0", "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"),
    ("m/84'/0'/0'/0/1", "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"),
    ("m/84'/0'/0'/1/0", "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el"),
];

const CUENTAS: u32 = 4;
const RAMAS: u32 = 2;
const INDICES: u32 = 20;

/// Versión de un paquete según el Cargo.lock con el que se compiló este programa.
fn version_fijada(paquete: &str) -> &'static str {
    let lock = include_str!("../Cargo.lock");
    let marca = format!("name = \"{paquete}\"\nversion = \"");
    let desde = lock.find(&marca).expect("paquete en Cargo.lock") + marca.len();
    let resto = &lock[desde..];
    &resto[..resto.find('"').expect("comilla de cierre")]
}

fn main() {
    let secp = Secp256k1::new();
    let mn = Mnemonic::parse(VECTOR).expect("vector BIP-84");
    let semilla = mn.to_seed("");
    let maestro = Xpriv::new_master(Network::Bitcoin, &semilla).expect("maestro");
    let huella = maestro.fingerprint(&secp);

    let direccion = |ruta: &str| -> String {
        let r = DerivationPath::from_str(ruta).expect("ruta");
        let x = maestro.derive_priv(&secp, &r).expect("derivación");
        let cpk = CompressedPublicKey(x.private_key.public_key(&secp));
        Address::p2wpkh(&cpk, Network::Bitcoin).to_string()
    };

    for (ruta, esperada) in REFERENCIA {
        let obtenida = direccion(ruta);
        if obtenida != esperada {
            eprintln!("NO COINCIDE con BIP-84: {ruta}: {obtenida} != {esperada}");
            std::process::exit(1);
        }
        eprintln!("coincide con BIP-84: {ruta} = {obtenida}");
    }

    let mut json = String::new();
    json.push_str(&format!(
        "{{\"origen\":\"vector de prueba BIP-84\",\"bitcoin\":\"{}\",\"bip39\":\"{}\",\"huella\":\"{}\",\"moneda\":0,\"cuentas\":{CUENTAS},\"ramas\":{RAMAS},\"indices\":{INDICES},\"dir\":[",
        version_fijada("bitcoin"),
        version_fijada("bip39"),
        huella
    ));
    for cuenta in 0..CUENTAS {
        if cuenta > 0 {
            json.push(',');
        }
        json.push('[');
        for rama in 0..RAMAS {
            if rama > 0 {
                json.push(',');
            }
            json.push('[');
            for i in 0..INDICES {
                if i > 0 {
                    json.push(',');
                }
                json.push('"');
                json.push_str(&direccion(&format!("m/84'/0'/{cuenta}'/{rama}/{i}")));
                json.push('"');
            }
            json.push(']');
        }
        json.push(']');
    }
    json.push_str("]}");
    println!("{json}");
}
