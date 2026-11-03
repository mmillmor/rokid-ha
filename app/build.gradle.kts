plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { localAppData ->
    layout.buildDirectory.set(file("$localAppData/RokidHomeAssistantBuild/app"))
}

android {
    namespace = "com.milliesoft.rokid_ha"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.milliesoft.rokid_ha"
        // YodaOS-Sprite on Rokid Glasses runs Android 12 (API 31+); API 28+ ensures broad compatibility
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Optional default build-time Home Assistant configuration (can be overridden via QR, UI, or JSON file)
        buildConfigField("String", "DEFAULT_HA_URL", "\"http://homeassistant.local:8123\"")
        buildConfigField("String", "DEFAULT_HA_TOKEN", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // OkHttp for Home Assistant REST & real-time WebSocket API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // CameraX + ZXing Core for QR code configuration scanning (works on AOSP/YodaOS-Sprite without Google Play Services)
    val cameraVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
