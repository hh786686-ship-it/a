plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.mandarincallcaptions"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mandarincallcaptions"
        minSdk = 31
        targetSdk = 35
        versionCode = 8
        versionName = "0.8.1"

        ndk {
            // Samsung Galaxy A16 uses ARM64.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        // ONNX model is already compressed internally.
        noCompress += "onnx"
    }

    packaging {
        jniLibs {
            // More compatible with Samsung package installation/loading.
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
