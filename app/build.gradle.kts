import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val buildDate: String = ZonedDateTime.now(ZoneId.of("Europe/Bratislava"))
    .format(DateTimeFormatter.ofPattern("d.M.yyyy HH:mm"))

android {
    namespace = "sk.skladnik.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "sk.skladnik.app"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.1.$buildNumber"
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
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

    buildFeatures {
        buildConfig = true
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
