plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.denis.naturalcam"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.denis.naturalcam"
        minSdk = 30           // Android 11 — нужно для CONTROL_ZOOM_RATIO (плавный зум кольцом)
        targetSdk = 35
        versionCode = 17
        versionName = "0.17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Подписываем debug-ключом: обновления ставятся поверх без переустановки,
            // и не нужен отдельный keystore (это личное приложение, не Play Store).
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true   // нужен BuildConfig.VERSION_CODE для проверки обновлений
    }

    // Отдельный APK только под arm64 (телефоны Xiaomi/Samsung) — легче универсального
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
