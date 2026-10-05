plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bobo.touping"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bobo.touping"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.3.0"
    }

    // 固定签名：CI 通过环境变量提供 keystore。缺省时 storeFile 为 null，自动回退 debug 签名。
    signingConfigs {
        create("release") {
            val storePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 云构建通过环境变量注入固定 keystore，保证每次签名一致：
            // 这样新包可以直接覆盖安装，不用先卸载旧版。本地没配置时回退 debug 签名。
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
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
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 扫码连接：直接用现成的扫码 Activity，省掉自己写 CameraX + 解码的活。
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
