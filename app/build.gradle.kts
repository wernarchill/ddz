plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ddz"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ddz"
        minSdk = 26          // Android 8.0：可用自适应图标与通知渠道
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // 不自定义 signingConfigs：直接复用 AGP 内置的 debug 签名配置。
    // AGP 会在首次构建时自动生成 ~/.android/debug.keystore，
    // 所以 assembleRelease 产出的 APK 可以直接安装（正式上架前请换成自己的 keystore）。

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false          // 无混淆需求，避免反射/序列化被裁掉
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
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

    // 贴图直接从 assets 读取，不做压缩，避免 PNG 被二次处理后失真
    androidResources {
        noCompress += listOf("png", "ogg", "json")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    buildFeatures {
        buildConfig = true
    }
}

// 本项目刻意做到零第三方依赖：只用 Android 框架 + Kotlin 标准库。
// 这样 Gradle 只需下载 AGP 与 Kotlin 插件，构建最快、体积最小、离线最友好。
dependencies {
}

/**
 * 纯 JVM 运行规则引擎自测，**不需要手机也不需要模拟器**：
 *
 *     gradlew :app:rulesSelfTest
 *
 * com.ddz.core 包刻意不引用任何 Android API，所以只要 Kotlin 标准库在 classpath 上就能跑。
 * 250+ 条断言覆盖 16 种牌型的识别、比大小、重复牌校验、提示与算分。
 * 改规则相关代码后请先跑这个任务，比装到手机上试快得多。
 */
tasks.register<JavaExec>("rulesSelfTest") {
    group = "verification"
    description = "运行斗地主规则引擎的自测断言（纯 JVM）"
    dependsOn("compileDebugKotlin")
    mainClass.set("com.ddz.core.RulesSelfTest")
    classpath = files(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) +
        configurations.getByName("debugRuntimeClasspath")
}
