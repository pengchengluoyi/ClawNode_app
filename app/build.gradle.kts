plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clawnode.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clawnode.agent"
        // takeScreenshot() 是 API 30 引入的硬约束，因此 minSdk 不能低于 30
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
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

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // 生命周期感知收集（repeatOnLifecycle）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // 配置持久化
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WebSocket 长连接
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON 解析
    implementation("com.google.code.gson:gson:2.11.0")
}
