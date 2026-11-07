plugins { id("com.android.application") }

android {
 namespace = "com.example.livechinesea16"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.example.livechinesea16"
  minSdk = 28
  targetSdk = 34
  versionCode = 1
  versionName = "1.0.0"
  ndk { abiFilters += listOf("arm64-v8a") }
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 androidResources { noCompress += "onnx" }
 packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies { implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4") }
