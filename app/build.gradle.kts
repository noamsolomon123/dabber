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
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Ship arm64 for the phone; x86_64 added by the emulator build variant when needed.
            abiFilters += listOf("arm64-v8a")
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
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.service)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
