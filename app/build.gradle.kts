// The Structure — app module build.
// Kotlin + Jetpack Compose, llama.cpp via NDK/JNI (arm64 only — the target
// phone is a Realme Narzo 80 Pro, arm64-v8a), Vosk for offline speech,
// ONNX Runtime for the speaker-verification embedding model.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.heygobi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.heygobi.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.4.0"

        // arm64 only: the target phone is arm64-v8a, and llama.cpp is built
        // for that ABI only. This keeps the APK smaller and the CI build fast.
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    // Committed debug keystore (v1.2): every CI build signs with this same
    // key, so updates install over the previous build instead of failing
    // with a signature mismatch. This is a DEBUG key with the public
    // default password — never use it for a release build.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug APK is what GitHub Actions builds and the user installs.
            // No applicationId suffix: the package stays com.heygobi.app in
            // every build, so the accessibility service, the README and the
            // install steps never have to quote two different package names.
            isDebuggable = true
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
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.2.12479018"

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = "2024.12.01"
    implementation(platform("androidx.compose:compose-bom:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Encrypted on-device storage (passcode hash, screen-lock credential,
    // voiceprint centroid). Nothing leaves the phone.
    implementation("androidx.security:security-crypto:1.1.0-alpha07")

    // Room: routines, command history (for learning frequent commands),
    // chat history.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Vosk — fully offline speech recognition + grammar keyword spotting
    // ("The Structure"; "the" is often reduced to "da"/"de" or dropped,
    // so the matcher accepts those variants too). Apache-2.0, no account, no key.
    implementation("com.alphacephei:vosk-android:0.3.75")

    // ONNX Runtime — runs the WeSpeaker ECAPA-TDNN speaker-embedding model
    // for voiceprint verification. MIT licence.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
}
