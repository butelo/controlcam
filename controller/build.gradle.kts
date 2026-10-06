plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.controlcam.controller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.controlcam.controller"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Release signing is only wired up if you have a local keystore/ dir
    // (not committed). Without it, release builds simply stay unsigned.
    val keystoreFile = rootProject.file("keystore/controlcam.jks")
    if (keystoreFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("CONTROLCAM_STORE_PASSWORD")
                keyAlias = System.getenv("CONTROLCAM_KEY_ALIAS")
                keyPassword = System.getenv("CONTROLCAM_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":common"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
