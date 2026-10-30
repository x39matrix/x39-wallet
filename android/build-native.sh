#!/usr/bin/env bash
# Compila libx39wallet.so para Android (solo arm64-v8a) y genera los bindings Kotlin de
# UniFFI DESDE ESA MISMA biblioteca. Reproducible: versiones fijadas y comprobadas; falla
# si algo no coincide.
#
# Uso:
#   android/build-native.sh               compila en android/wallet/src/main/jniLibs y exige que
#                                         la suma coincida con la de android/build-native.lock
#   android/build-native.sh --actualizar  igual, pero escribe build-native.lock con la suma nueva
#   android/build-native.sh --verificar   compila en un CARGO_TARGET_DIR temporal LIMPIO y
#                                         compara la suma con la del lock; no toca el repo
#   android/build-native.sh --autoprueba  comprueba que el detector de rutas/usuario falla de
#                                         verdad con una .so adulterada a propósito
set -euo pipefail

# ---- versiones fijadas (cambiarlas aquí es un cambio revisable) -----------------------
NDK_VERSION="30.0.16248370"      # NDK r30 (LTS): 16 KB por defecto, API mínima 21
RUST_VERSION="1.98.1"            # también en rust/rust-toolchain.toml
CARGO_NDK_VERSION="4.1.2"
UNIFFI_VERSION="0.32.2"          # también en rust/Cargo.toml (=0.32.2)
PLATFORM="21"                    # minSdk de la app y de la biblioteca
ABI="arm64-v8a"
TARGET="aarch64-linux-android"
MARCADOR_OK="x39wallet-redes=solo-prueba"   # ver REDES_PERMITIDAS en rust/src/lib.rs
MARCADOR_MAL="x39wallet-redes=mainnet"

MODO="${1:-}"
case "$MODO" in ""|--actualizar|--verificar|--autoprueba) ;; *) echo "modo desconocido: $MODO" >&2; exit 2;; esac

RAIZ="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="$RAIZ/rust"
REGISTRO="$RAIZ/android/build-native.lock"
USUARIO="$(basename "$HOME")"

fallo() { echo "build-native: $*" >&2; exit 1; }

# ---- NDK: explícito, nunca "el más reciente que haya" ----------------------------------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/sdk}}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}"
[ -f "$ANDROID_NDK_HOME/source.properties" ] || fallo "no hay NDK en $ANDROID_NDK_HOME"
REV="$(sed -n 's/^Pkg.Revision = //p' "$ANDROID_NDK_HOME/source.properties")"
[ "$REV" = "$NDK_VERSION" ] || fallo "el NDK en $ANDROID_NDK_HOME es $REV, hace falta $NDK_VERSION"
MIN_API="$(python3 -Ic 'import json,sys;print(json.load(open(sys.argv[1]))["min"])' "$ANDROID_NDK_HOME/meta/platforms.json")"
[ "$MIN_API" -le "$PLATFORM" ] || fallo "el NDK exige API mínima $MIN_API, la biblioteca pide $PLATFORM"
LLVM_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$LLVM_BIN/llvm-strip" ] || fallo "falta llvm-strip en el NDK"

# ---- comprobaciones sobre una .so (sin tuberías: pipefail no las enmascara) --------------
comprobar_so() {
    local so="$1" cadenas linea align
    "$LLVM_BIN/llvm-readelf" -h "$so" | grep -q 'AArch64' || fallo "la biblioteca no es AArch64"
    while read -r linea; do
        align="$(echo "$linea" | awk '{print $NF}')"
        [ "$((align))" -ge 16384 ] || fallo "segmento LOAD con alineación < 16 KB: $linea"
    done < <("$LLVM_BIN/llvm-readelf" -lW "$so" | awk '$1=="LOAD"')
    cadenas="$(mktemp)"
    strings "$so" > "$cadenas"
    if grep -qiE -- "$USUARIO|/home/|$RAIZ" "$cadenas"; then
        rm -f "$cadenas"; fallo "la biblioteca contiene rutas o el nombre de usuario de esta máquina"
    fi
    grep -qF -- "$MARCADOR_OK" "$cadenas" || { rm -f "$cadenas"; fallo "falta el marcador $MARCADOR_OK"; }
    if grep -qF -- "$MARCADOR_MAL" "$cadenas"; then rm -f "$cadenas"; fallo "la biblioteca permite mainnet ($MARCADOR_MAL)"; fi
    rm -f "$cadenas"
}

# ---- autoprueba del detector ----------------------------------------------------------
if [ "$MODO" = "--autoprueba" ]; then
    SO_BUENA="$RAIZ/android/wallet/src/main/jniLibs/$ABI/libx39wallet.so"
    [ -f "$SO_BUENA" ] || fallo "primero compila (sin argumentos o --actualizar)"
    TMP="$(mktemp -d)"
    cp "$SO_BUENA" "$TMP/adulterada.so"
    printf '\n/home/%s/x39-wallet/rust/src/lib.rs\n' "$USUARIO" >> "$TMP/adulterada.so"
    echo "== autoprueba: la .so buena debe pasar"
    comprobar_so "$SO_BUENA" && echo "   pasa"
    echo "== autoprueba: la .so adulterada (con una ruta de esta máquina añadida) debe FALLAR"
    if ( comprobar_so "$TMP/adulterada.so" ) 2>"$TMP/err"; then
        rm -rf "$TMP"; fallo "la adulterada ha pasado: el detector no funciona"
    fi
    echo "   falla como debe: $(cat "$TMP/err")"
    cp "$SO_BUENA" "$TMP/mainnet.so"; printf '\n%s\n' "$MARCADOR_MAL" >> "$TMP/mainnet.so"
    echo "== autoprueba: una .so con el marcador de mainnet debe FALLAR"
    if ( comprobar_so "$TMP/mainnet.so" ) 2>"$TMP/err"; then
        rm -rf "$TMP"; fallo "la de mainnet ha pasado: el detector no funciona"
    fi
    echo "   falla como debe: $(cat "$TMP/err")"
    rm -rf "$TMP"
    exit 0
fi

# ---- Rust, cargo-ndk, uniffi ----------------------------------------------------------
cd "$RUST_DIR"
RUSTC_V="$(rustc --version | awk '{print $2}')"
[ "$RUSTC_V" = "$RUST_VERSION" ] || fallo "rustc es $RUSTC_V, hace falta $RUST_VERSION (rust-toolchain.toml)"
CARGO_NDK_V="$(cargo ndk --version | awk '{print $2}')"
[ "$CARGO_NDK_V" = "$CARGO_NDK_VERSION" ] || fallo "cargo-ndk es $CARGO_NDK_V, hace falta $CARGO_NDK_VERSION"
UNIFFI_V="$(awk '/^name = "uniffi"$/{getline; sub(/^version = "/,""); sub(/"$/,""); print}' Cargo.lock)"
[ "$UNIFFI_V" = "$UNIFFI_VERSION" ] || fallo "uniffi en Cargo.lock es $UNIFFI_V, hace falta $UNIFFI_VERSION"
rustup target list --installed | grep -qx "$TARGET" || fallo "falta el target $TARGET (rustup target add $TARGET)"

# ---- destinos según el modo -----------------------------------------------------------
if [ "$MODO" = "--verificar" ]; then
    TMPV="$(mktemp -d)"
    export CARGO_TARGET_DIR="$TMPV/target"     # limpio: nada reutilizado
    SALIDA_JNI="$TMPV/jniLibs"
    SALIDA_KT="$TMPV/kotlin"
    echo "== --verificar: compilación limpia en $TMPV"
else
    export CARGO_TARGET_DIR="$RUST_DIR/target"
    SALIDA_JNI="$RAIZ/android/wallet/src/main/jniLibs"
    SALIDA_KT="$RAIZ/android/wallet/src/main/java"
fi

# ---- compilación ----------------------------------------------------------------------
# Sin rutas de la máquina dentro de la biblioteca y alineación de 16 KB explícita (además
# del valor por defecto del NDK r28+). --locked: el Cargo.lock del repo manda.
export RUSTFLAGS="--remap-path-prefix=$HOME=/build --remap-path-prefix=$RAIZ=/build/x39-wallet -C link-arg=-Wl,-z,max-page-size=16384"
rm -rf "$SALIDA_JNI/$ABI"
mkdir -p "$SALIDA_JNI" "$SALIDA_KT"
echo "== cargo ndk --platform $PLATFORM -t $ABI build --release --locked"
cargo ndk --platform "$PLATFORM" -t "$ABI" -o "$SALIDA_JNI" build --release --locked
SO="$SALIDA_JNI/$ABI/libx39wallet.so"
[ -f "$SO" ] || fallo "no se generó $SO"

# ---- bindings Kotlin desde ESTA biblioteca (antes de quitarle los símbolos) -------------
echo "== uniffi-bindgen generate --library … --language kotlin"
cargo run --quiet --release --locked --features uniffi/cli --bin uniffi-bindgen -- \
    generate --library "$SO" --language kotlin --no-format --out-dir "$SALIDA_KT"

# ---- símbolos fuera (strip) después de generar los bindings ----------------------------
"$LLVM_BIN/llvm-strip" --strip-all "$SO"

echo "== comprobaciones"
comprobar_so "$SO"
"$LLVM_BIN/llvm-readelf" -lW "$SO" | grep LOAD
SHA="$(sha256sum "$SO" | awk '{print $1}')"
echo "sha256 $SHA  bytes $(stat -c %s "$SO")"

# ---- lock: comparar o actualizar -------------------------------------------------------
if [ "$MODO" = "--actualizar" ]; then
    {
        echo "libx39wallet.so  sha256 $SHA  bytes $(stat -c %s "$SO")"
        echo "ndk $NDK_VERSION (API mínima del NDK $MIN_API, --platform $PLATFORM)"
        echo "clang $("$LLVM_BIN/clang" --version | head -1)"
        echo "rustc $RUSTC_V  cargo-ndk $CARGO_NDK_V  uniffi $UNIFFI_V  target $TARGET  abi $ABI"
        echo "rustflags ${RUSTFLAGS//$RAIZ/<repo>}" | sed "s|$HOME|\$HOME|g"
        echo "bindings kotlin: $(find "$SALIDA_KT" -name '*.kt' | sed "s|$RAIZ/||" | sort | tr '\n' ' ')"
    } > "$REGISTRO"
    echo "== lock actualizado:"; cat "$REGISTRO"
else
    [ -f "$REGISTRO" ] || fallo "no hay $REGISTRO; ejecuta --actualizar"
    ESPERADA="$(awk '/^libx39wallet.so/{print $3}' "$REGISTRO")"
    if [ "$SHA" != "$ESPERADA" ]; then
        [ "$MODO" = "--verificar" ] && rm -rf "$TMPV"
        fallo "la suma $SHA NO coincide con la del lock ($ESPERADA)"
    fi
    echo "== la suma coincide con android/build-native.lock"
    [ "$MODO" = "--verificar" ] && { rm -rf "$TMPV"; echo "== --verificar: REPRODUCIBLE (directorio temporal borrado)"; }
fi
echo "== hecho"
