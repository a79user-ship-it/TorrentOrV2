import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Reads the 4 RELEASE_* values you added to gradle.properties, so no password is ever
// typed directly into this file.
val releaseProps = Properties().apply {
    val propsFile = rootProject.file("gradle.properties")
    if (propsFile.exists()) {
        load(FileInputStream(propsFile))
    }
}

android {
    namespace = "com.example.torrentorv2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.torrentorv2"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = releaseProps.getProperty("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = releaseProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = releaseProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = releaseProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}