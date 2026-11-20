plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "ar.stereo.desenchufe"
    compileSdk = 35

    defaultConfig {
        applicationId = "ar.stereo.desenchufe"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("app") {
            storeFile = file("desenchufe.jks")
            storePassword = "desenchufe123"
            keyAlias = "desenchufe"
            keyPassword = "desenchufe123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
        }
        debug {
            signingConfig = signingConfigs.getByName("app")
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
    implementation("androidx.webkit:webkit:1.12.1")
}
