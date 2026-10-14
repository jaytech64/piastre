import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The upload key lives outside the repository, so no worktree can lose it and
// no commit can carry it. Without the file a release build is left unsigned.
val uploadKeyFile = File(System.getProperty("user.home"), ".config/piastre/upload-keystore.properties")
val uploadKey = Properties().apply { if (uploadKeyFile.exists()) uploadKeyFile.inputStream().use(::load) }

android {
    namespace = "io.github.jaytech64.piastre.billingspike"
    compileSdk = 36

    defaultConfig {
        // Deliberately the production id: Play only sells to the package it lists.
        // Emulator only, never the phone.
        applicationId = "io.github.jaytech64.piastre"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-billing-spike"
    }

    signingConfigs {
        if (uploadKeyFile.exists()) {
            create("upload") {
                storeFile = file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    // The one question of the spike: does billing work once the network
    // permissions its library merges in are stripped back out?
    flavorDimensions += "net"
    productFlavors {
        create("asis") { dimension = "net" }
        create("stripped") { dimension = "net" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation("com.android.billingclient:billing:9.1.0")
}
