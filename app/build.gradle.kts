plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val hasKeystore = System.getenv("KEYSTORE_PATH")?.let { File(it).exists() } == true

android {
    namespace = "app.rclonemgr"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.rclonemgr"
        minSdk = 30
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.4.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = File(System.getenv("KEYSTORE_PATH")!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasKeystore) "release" else "debug")
        }
    }

    // rclone dieksekusi dari nativeLibraryDir (satu-satunya lokasi exec yg diizinkan Android 10+)
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/librclone.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
