plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "sk.skladnik.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "sk.skladnik.app"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("skladnik") {
            storeFile = rootProject.file("keystore/skladnik.keystore")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "skladnik"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("skladnik")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("skladnik")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Skener čiarových kódov od Google (fotoaparát, bez potreby povolenia kamery)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
