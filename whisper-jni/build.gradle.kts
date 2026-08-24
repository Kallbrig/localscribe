plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.chaseallbright.localscribe.bridge.whisper"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += "arm64-v8a"
            // x86_64 is built too so this runs on the emulator (and any x86_64 device);
            // arm64-v8a alone covers the overwhelming majority of real hardware since 2019.
            abiFilters += "x86_64"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
