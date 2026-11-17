plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hugocodings.voicetyper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hugocodings.voicetyper.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.8.2"
        // Whisper (whisper.cpp) for ARM phones, 64-bit and 32-bit (some phones run 32-bit Android):
        // an APK whose native code matches no ABI of the phone cannot be installed at all.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // c++_shared: whisper, ggml and its per-processor backends are separate .so files sharing one C++ runtime
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // One fixed key for every build: Android refuses an update signed with another key ("App not installed"),
    // and CI runners create a new random debug key each time. The key comes from GitHub secrets
    // (VT_KEYSTORE_B64 → file VT_KEYSTORE, VT_KEYSTORE_PASSWORD); without them, the debug key is used.
    val sideloadKey = System.getenv("VT_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (sideloadKey != null) create("sideload") {
            storeFile = sideloadKey
            storePassword = System.getenv("VT_KEYSTORE_PASSWORD")
            keyAlias = "voicetyper"
            keyPassword = System.getenv("VT_KEYSTORE_PASSWORD")
        }
    }
    val signing = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            signingConfig = signing
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signing
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // ggml scans the native library folder for its per-processor backends (libggml-cpu-*.so): they must be
    // real files there, so native libraries are extracted at install instead of being read from the APK
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
