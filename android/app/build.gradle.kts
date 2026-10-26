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
                // Aurora cross-compiles Dawn from google/dawn by default ("vendor"), and that tag 404s.
                // "package" downloads the prebuilt Dawn for android-aarch64 with a pinned SHA-256.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release",
                    "-DAURORA_DAWN_PROVIDER=package",
                    "-DMKW_TRANSLATED_COMPILE_JOBS=" + (System.getenv("WARIO_JOBS") ?: "2"))
                // CI sets WARIO_CCACHE=1 to cache C/C++ objects between runs. Local builds are unchanged.
                if (System.getenv("WARIO_CCACHE") == "1") {
                    arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache",
                        "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
                }
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
