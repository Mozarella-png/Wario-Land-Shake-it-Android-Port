plugins {
    id("com.android.application")
}

android {
    namespace = "com.wario.shakeit"
    compileSdk = 35
    // Same NDK as the CI workflow (.github/workflows/android.yml).
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.wario.shakeit"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // Translated shards compile in parallel; CI sets WARIO_JOBS for the runner size.
                // The runtime only builds in Release. AGP passes its own CMAKE_BUILD_TYPE first, so this later -D wins.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release",
                    "-DMKW_TRANSLATED_COMPILE_JOBS=" + (System.getenv("WARIO_JOBS") ?: "2"))
            }
        }
    }

    externalNativeBuild {
        cmake {
            // The whole WarioRecomp runtime (and Aurora) builds from the repo's runtime/ folder.
            path = file("../../runtime/CMakeLists.txt")
            version = "3.31.6"
            // No "targets" here: CMake's default target builds WiiCompiled (the product, libmain.so).
        }
    }

    sourceSets {
        getByName("main") {
            // Runtime support files (DSP ROM, pipeline cache, Wii bootstrap NAND, CA bundle).
            assets.srcDirs("../../runtime/assets")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug key so the APK installs without a keystore. Replace before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
