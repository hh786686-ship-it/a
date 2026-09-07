plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.mandarincallcaptions"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mandarincallcaptions"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4")
}
