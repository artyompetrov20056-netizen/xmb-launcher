import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xmb.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xmb.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Подписываем тем же отладочным ключом, чтобы release-APK тоже ставился без настройки
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
