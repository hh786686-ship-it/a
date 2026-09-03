plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.mandarincallcaptions"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.mandarincallcaptions"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
