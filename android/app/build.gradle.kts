plugins {
    id("com.android.application")
}

/* the release the engine is from, as the camera build gets it */
val mfilmVersion: String = runCatching { rootProject.file("../VERSION").readText().trim() }
    .getOrDefault("dev")

/*
 * One signing key for every APK this project ever ships, so a new build
 * installs over the last one. The debug variant would have each CI runner
 * invent a key of its own, and Android refuses an update whose signature
 * changed: a release build signed with the repository's key is the only way
 * an update goes in without uninstalling. The CI writes the keystore out of
 * the repository's secrets and points these at it; nothing here is a secret.
 */
val signingKeystore = System.getenv("MFILM_KEYSTORE")?.let { file(it) }
val signingPassword = System.getenv("MFILM_STORE_PASSWORD")
val signingAlias = System.getenv("MFILM_KEY_ALIAS")
val canSign = signingKeystore?.isFile == true
        && !signingPassword.isNullOrEmpty() && !signingAlias.isNullOrEmpty()

android {
    namespace = "app.mfilm.darkroom"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "app.mfilm.darkroom"
        minSdk = 29                 // MediaStore needs no storage permission from Android 10
        targetSdk = 34
        versionCode = 9
        versionName = "1.2.6"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none", "-DMFILM_VERSION=$mfilmVersion")
                cFlags += listOf("-O3")
            }
        }

        ndk {
            // phones, and the emulator/CI
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (canSign) {
            create("mfilm") {
                storeFile = signingKeystore!!
                storeType = "PKCS12"
                storePassword = signingPassword
                keyAlias = signingAlias
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("mfilm")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
