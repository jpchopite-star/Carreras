plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.juandiaz.proximacarrera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.juandiaz.proximacarrera"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("pc") {
            storeFile = file("proximacarrera.keystore")
            storePassword = "proximacarrera"
            keyAlias = "proximacarrera"
            keyPassword = "proximacarrera"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Fixed key in the repo, so every new build installs over the old one as an update.
            signingConfig = signingConfigs.getByName("pc")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
