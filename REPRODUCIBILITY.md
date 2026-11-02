# Guía de Compilación Reproducible — Edición Mainnet (Criterio B5)

## 1. Compilar biblioteca nativa (Rust + UniFFI)
```bash
cd ~/x39-wallet
X39_MAINNET=1 ./android/build-native.sh
```

## 2. Compilar librería núcleo (x39-wallet)
```bash
cd ~/x39-wallet/android
X39_MAINNET=1 ./gradlew assembleRelease -Px39.mainnet=true --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx3584m -XX:+UseParallelGC"
```

## 3. Compilar aplicación Android (sample-app)
```bash
cd ~/client-sdk-android
X39_MAINNET=1 ./gradlew :sample-app:assembleRelease -Px39.mainnet=true --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx3584m -XX:+UseParallelGC"
```

## 4. Verificación de huellas SHA-256 (Mainnet)
```text
c0bd8904db236036a6eadbe1cd652718829b94e3a3c463b0cd8246f9a09fb301  /home/x39matrix/x39-wallet/android/wallet/build/outputs/aar/wallet-release.aar
36e827034e929b592e7df940a14501dc389cfe2a5b4fc880a9de7057e9ce8d3e  sample-app/build/outputs/apk/comunicaciones/release/sample-app-comunicaciones-release-unsigned.apk
eeae16c8e9ee544b4d7e1aa3df2613138e86247bcef7e2abfae0d16f88a82fa8  sample-app/build/outputs/apk/general/release/sample-app-general-release-unsigned.apk
a1e5368580fde16365c20a14753d886dcc38d2176504557ab62db28cea2d3f71  x39-wallet-comunicaciones-mainnet-signed.apk
8d2198222d1ae7dead9201fd35a30fa922939214983f9a08c699783f3e50ac6e  x39-wallet-general-mainnet-signed.apk
```
