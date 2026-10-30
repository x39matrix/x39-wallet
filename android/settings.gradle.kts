// Compilación independiente del módulo Android :wallet. La app privada lo consumirá con
// includeBuild("<ruta>/x39-wallet/android") y la coordenada x39.wallet:wallet.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.library") version "8.7.2"
        id("org.jetbrains.kotlin.android") version "1.9.25"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "x39-wallet-android"
include(":wallet")
