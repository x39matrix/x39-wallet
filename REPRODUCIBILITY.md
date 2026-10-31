# Guía de Compilación Reproducible (Criterio B5)

## 1. Compilar librería núcleo (x39-wallet)
```bash
cd ~/x39-wallet/android
./gradlew assembleRelease --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx3584m -XX:+UseParallelGC"
```

## 2. Compilar aplicación Android (sample-app)
```bash
cd ~/client-sdk-android
./gradlew :sample-app:assembleRelease --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx3584m -XX:+UseParallelGC"
```

## 3. Verificación de huellas SHA-256
```text
c252b6f3751ae253ef8eee3d2cbf814e291c9c3815123fcab346432bba610673  /home/x39matrix/x39-wallet/android/wallet/build/outputs/aar/wallet-release.aar
36e827034e929b592e7df940a14501dc389cfe2a5b4fc880a9de7057e9ce8d3e  sample-app/build/outputs/apk/comunicaciones/release/sample-app-comunicaciones-release-unsigned.apk
18f86ee08e2c2569baca268fc492baa73ae24fe2d9976f5cb81a3deb5f77ecde  sample-app/build/outputs/apk/general/release/sample-app-general-release-unsigned.apk
```
