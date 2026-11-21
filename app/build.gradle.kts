plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.voicenotes"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.voicenoteswolf.nllb"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.7-nllb"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        // libonnxruntime.so есть и в sherpa-onnx, и в onnxruntime-android (версии совпадают)
        jniLibs.pickFirsts += listOf("**/libonnxruntime.so")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")

    implementation(files("libs/sherpa-onnx.aar"))

    // Перевод текста на устройстве: NLLB-200 (distilled 600M) через ONNX Runtime.
    // Версия подбирается под libonnxruntime.so из sherpa-onnx (см. build.yml), чтобы не было конфликта.
    val ortVersion = (project.findProperty("ortVersion") as String?) ?: "1.23.0"
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$ortVersion")

    // Распознавание ключевых фраз (JNA подтягивается автоматически)
    implementation("com.alphacephei:vosk-android:0.3.47")

    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.biometric:biometric:1.1.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
}
