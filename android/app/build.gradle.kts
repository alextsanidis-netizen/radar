plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "gr.alexandros.radar"
    compileSdk = 35
    defaultConfig {
        applicationId = "gr.alexandros.radar"
        minSdk = 26
        targetSdk = 35
        versionCode = run
        versionName = "1.0.$run"
    }
    signingConfigs {
        create("release") {
            storeFile = file("radar.jks")
            storePassword = System.getenv("RADAR_KEY_PASS")
            keyAlias = "radar"
            keyPassword = System.getenv("RADAR_KEY_PASS")
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
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
