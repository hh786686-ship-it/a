plugins { id("com.android.application") }

android {
 namespace = "com.example.livechinesea16"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.example.livechinesea16"
  minSdk = 28
  targetSdk = 34
  versionCode = 18
  versionName = "1.14.0-android-go-no-overlay"
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
  implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}
