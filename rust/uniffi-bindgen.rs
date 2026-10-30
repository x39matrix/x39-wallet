//! Generador de bindings de UniFFI. Se compila desde este crate (`--features uniffi/cli`)
//! para que los bindings salgan exactamente de la misma versión de uniffi que la biblioteca.
fn main() {
    uniffi::uniffi_bindgen_main()
}
