import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
}

// 签名配置从 local.properties 读取（已被 .gitignore 排除，不会提交到 GitHub）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("jiepi.storeFile") ?: "")
            storePassword = localProps.getProperty("jiepi.storePassword") ?: ""
            keyAlias = localProps.getProperty("jiepi.keyAlias") ?: ""
            keyPassword = localProps.getProperty("jiepi.keyPassword") ?: ""
        }
    }
    namespace = "com.jiepi.player"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.jiepi.player"
        minSdk = 34
        targetSdk = 36
        versionCode = 8
        versionName = "1.1.7"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_CPP_FEATURES=rtti exceptions")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation("com.belerweb:pinyin4j:2.5.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Media3 ExoPlayer：应用内 DSP 音效管线（EQ/伴奏/混响等绕开系统 audiofx，任何 ROM 生效）
    // 1.5.x 起 ExoPlayer 提供 setAudioProcessors 挂载自研 DSP，需要 compileSdk 35
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    // Media3 Session：车载/蓝牙/通知栏统一控制通道（MediaSessionService + AVRCP 方向盘）
    implementation("androidx.media3:media3-session:1.5.1")
    // Media3 Session 依赖 androidx.media 的 MediaSessionCompat / MediaStyle（通知卡样式），显式声明
    implementation("androidx.media:media:1.7.0")
    // 下拉刷新（歌曲列表手动激活扫描）
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // 注：Rubber Band 变速变调库（GPL-2.0-or-later，可按 GPL-3.0 使用）不走 gradle 依赖，
    // 由 externalNativeBuild / CMake 直接编译源码集成：src/main/cpp/CMakeLists.txt 生成 librubberband.so，
    // abiFilters 仅打包 arm64-v8a；上层通过 RubberBandStream.kt + JiepiRubberBandJNI.cpp 调用。
}