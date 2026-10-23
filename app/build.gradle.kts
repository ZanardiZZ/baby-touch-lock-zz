import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.babytouchlock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.babytouchlock.pin"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ksPath = keystoreProperties.getProperty("RELEASE_STORE_FILE")
                ?: System.getenv("KEYSTORE_PATH")
                ?: project.findProperty("KEYSTORE_PATH") as? String

            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = keystoreProperties.getProperty("RELEASE_STORE_PASSWORD")
                    ?: System.getenv("KEYSTORE_PASSWORD")
                    ?: project.findProperty("KEYSTORE_PASSWORD") as? String
                keyAlias = keystoreProperties.getProperty("RELEASE_KEY_ALIAS")
                    ?: System.getenv("KEY_ALIAS")
                    ?: project.findProperty("KEY_ALIAS") as? String
                keyPassword = keystoreProperties.getProperty("RELEASE_KEY_PASSWORD")
                    ?: System.getenv("KEY_PASSWORD")
                    ?: project.findProperty("KEY_PASSWORD") as? String
            }
        }
    }

        dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            vcsInfo {
                include = false
            }
            signingConfigs.findByName("release")?.let { releaseSigning ->
                if (releaseSigning.storeFile != null) {
                    signingConfig = releaseSigning
                }
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
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
