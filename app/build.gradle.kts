plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.trademail.app"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("trademail.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.trademail.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "1.0.42"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
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



    packaging {
        resources {
            excludes += "/META-INF/{*.md,NOTICE,LICENSE,DEPENDENCIES,AL2.0,LGPL2.1}"
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Jakarta Mail (IMAP/SMTP)
    implementation("com.sun.mail:jakarta.mail:2.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")


    // ONNX Runtime (Hy-MT 离线翻译)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // OkHttp (模型下载)
    implementation("com.sun.mail:jakarta.mail:2.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // DataStore (账户配置持久化)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Room (翻译缓存)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}
