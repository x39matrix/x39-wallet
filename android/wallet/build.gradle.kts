plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Coordenada para includeBuild desde la app: x39.wallet:wallet
group = "x39.wallet"
version = "0.2.0"

android {
    namespace = "x39.wallet"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        // Única ABI: la biblioteca nativa solo se compila para arm64-v8a.
        ndk { abiFilters += "arm64-v8a" }
        // Redes que la capa Android acepta (además de la feature de Rust: sin `mainnet`).
        buildConfigField("String", "REDES_PERMITIDAS", "\"signet,regtest\"")
        buildConfigField("String", "MARCADOR_REDES_ESPERADO", "\"x39wallet-redes=solo-prueba\"")
    }

    buildFeatures { buildConfig = true }

    // El APK de pruebas instrumentadas apunta a la API actual: con targetSdk 21 Android 13+ abre
    // la «revisión de permisos» al lanzar la primera pantalla y después bloquea el relanzamiento
    // desde segundo plano, y ActivityScenario se queda esperando.
    testOptions { targetSdk = 35 }
    lint { targetSdk = 35 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // .so sin comprimir y alineadas en el APK (páginas de 16 KB).
    packaging { jniLibs { useLegacyPackaging = false } }

    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        warningsAsErrors = false
    }

    // La biblioteca nativa y los bindings los genera android/build-native.sh:
    //   src/main/jniLibs/arm64-v8a/libx39wallet.so  y  src/main/java/x39/wallet/x39wallet.kt
}

dependencies {
    // Puente nativo de UniFFI. 5.19.1 exacta: 5.19.0 rompe Android < API 26.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.9.0")
    // BiometricPrompt.CryptoObject con minSdk 21.
    implementation("androidx.biometric:biometric:1.1.0")
    // Pantallas (vistas programáticas) y lifecycleScope.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    // QR de la dirección de recibir.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
