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
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
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
