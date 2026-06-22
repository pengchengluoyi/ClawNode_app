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
        versionCode = 22
        versionName = "1.7.10"
    }

    signingConfigs {
        // 固定的 debug 签名：keystore 文件随仓库分发（app/debug.keystore），
        // 保证 CI 与本地、以及历次构建产物的签名一致，避免
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE（覆盖安装时签名不匹配）。
        // 使用 Android 约定的标准 debug 凭证，非敏感信息。
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
