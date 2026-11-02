import java.util.Properties

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
        val esMainnet = providers.gradleProperty("x39.mainnet").orNull == "true" || System.getenv("X39_MAINNET") == "1"
        val redesPermitidas = if (esMainnet) "mainnet" else "signet,regtest"
        val marcadorEsperado = if (esMainnet) "x39wallet-redes=mainnet" else "x39wallet-redes=solo-prueba"
        buildConfigField("String", "REDES_PERMITIDAS", "\"$redesPermitidas\"")
        buildConfigField("String", "MARCADOR_REDES_ESPERADO", "\"$marcadorEsperado\"")
        // Token de la API de la Pi 5 (TelemetriaPi5, :8000). Nunca en el repo: se lee de
        // -Px39.pi5.token, de X39_PI5_TOKEN o de android/local.properties (ignorado por git).
        // Sin token el módulo compila igual y la pantalla avisa de que falta.
        val propiedadesLocales = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.isFile) f.inputStream().use { load(it) }
        }
        val tokenPi5 = (providers.gradleProperty("x39.pi5.token").orNull
            ?: System.getenv("X39_PI5_TOKEN")
            ?: propiedadesLocales.getProperty("x39.pi5.token")
            ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "PI5_TOKEN", "\"$tokenPi5\"")
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
    // QR de la dirección de recibir y lectura del QR del paquete de emparejamiento.
    implementation("com.google.zxing:core:3.5.3")
    // Cámara para leer el QR del paquete (E-2). Misma versión en los cuatro artefactos.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    testImplementation("junit:junit:4.13.2")
    // org.json real para las pruebas JVM del analizador del paquete (el android.jar de pruebas lo trae vacío).
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
