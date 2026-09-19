import java.io.File

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.breathwork.pacer"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.breathwork.pacer"
        minSdk = 26          // VibrationEffect.createWaveform(timings, amplitudes) needs API 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        // The pinned identity for this app, supplied by CI as a keystore secret. Every
        // ephemeral debug key is a NEW app identity, so without this each build demands
        // an uninstall before it will install.
        create("stable") {
            val path = System.getenv("BP_KEYSTORE")
            if (path != null && File(path).exists()) {
                storeFile = File(path)
                storePassword = System.getenv("BP_STORE_PASSWORD")
                keyAlias = System.getenv("BP_KEY_ALIAS")
                keyPassword = System.getenv("BP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            val path = System.getenv("BP_KEYSTORE")
            if (path != null && File(path).exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
