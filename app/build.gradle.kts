import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load keystore config from keystore.properties or use CI env vars
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.robloxblocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.robloxblocker"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
    }

    signingConfigs {
        create("release") {
            // CI: use env vars; Local: use keystore.properties
            storeFile = rootProject.file((keystoreProperties["storeFile"] as? String) ?: System.getenv("KEYSTORE_FILE") ?: "release.jks")
            storePassword = (keystoreProperties["storePassword"] as? String) ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = (keystoreProperties["keyAlias"] as? String) ?: System.getenv("KEY_ALIAS") ?: ""
            keyPassword = (keystoreProperties["keyPassword"] as? String) ?: System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    buildFeatures {
        viewBinding = true
    }

    // Ensure APK Signature Scheme v3 is used (default for minSdk 26+)
    // v1 (JAR), v2 (APK Signature Scheme v2), v3 (APK Signature Scheme v3) all applied
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.ApkVariantOutputImpl).outputFileName =
                "SecureBrowse-${versionName}-${versionCode}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.android.gms:play-services-base:18.3.0")
}
