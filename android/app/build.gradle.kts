plugins {
    id("com.android.application")
}

/* the release the engine is from, as the camera build gets it */
val mfilmVersion: String = runCatching { rootProject.file("../VERSION").readText().trim() }
    .getOrDefault("dev")

android {
    namespace = "app.mfilm.darkroom"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "app.mfilm.darkroom"
        minSdk = 29                 // MediaStore needs no storage permission from Android 10
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"

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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
