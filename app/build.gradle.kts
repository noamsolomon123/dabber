plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dabber"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dabber"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.3"

        ndk {
            // arm64-v8a runs on the phone; x86_64 lets the same APK install on the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }

    packaging {
        jniLibs {
            excludes += setOf("**/libQnnHtpPrepare.so")
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.service)
    // ONNX Runtime with the Qualcomm QNN execution provider — bundles the Hexagon HTP .so
    // libs (libQnnHtp.so, etc.) for arm64-v8a. This drives com.dabber.npu.QnnWhisperEngine
    // (NPU path). NOTE: the -qnn AAR ships arm64-v8a ONLY (no x86_64 QNN/HTP libs), so on the
    // x86_64 emulator the ORT-backed engines (Qnn + the CPU OnnxWhisperEngine) are unavailable;
    // the whisper.cpp engine still runs there. NPU transcription is arm64/phone only.
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.27.0") {
        // ORT 1.27.0 pins qnn-runtime 2.42.0; our AI Hub *_qairt_context.bin were built with
        // QAIRT 2.45 and will not deserialize on 2.42 HTP/System libs. Force-match 2.45.
        exclude(group = "com.qualcomm.qti", module = "qnn-runtime")
    }
    implementation("com.qualcomm.qti:qnn-runtime:2.45.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
