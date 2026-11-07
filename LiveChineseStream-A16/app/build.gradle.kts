plugins { id("com.android.application") }

android {
 namespace = "com.example.livechinesea16"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.example.livechinesea16"
  minSdk = 28
  targetSdk = 34
  versionCode = 7
  versionName = "1.4.1"
  ndk { abiFilters += listOf(providers.gradleProperty("smokeAbi").orElse("arm64-v8a").get()) }
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 androidResources { noCompress += "onnx" }
 packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
  implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4")
  // Required at runtime: the sherpa-onnx Java-facing API is written in Kotlin.
  implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}
