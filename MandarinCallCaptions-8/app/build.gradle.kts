plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.mandarincallcaptions"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mandarincallcaptions"
        minSdk = 31
        targetSdk = 35
        versionCode = 8
        versionName = "0.8.0"

        ndk {
            // Samsung Galaxy A16 is ARM64. Keeping only this ABI makes the APK
            // much smaller and avoids packaging unnecessary desktop/x86 libs.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        // SenseVoice ONNX model is already compressed internally; keep it
        // uncompressed for predictable mmap/loading.
        noCompress += "onnx"
    }

    packaging {
        jniLibs {
            // Extract native libraries at install time. This is more tolerant
            // across Samsung/Android combinations than mmap-only packaging.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4")
}
