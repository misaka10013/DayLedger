// ============================================================================
// app 模块构建脚本
// ----------------------------------------------------------------------------
// 里程碑 M0：只搭一个能跑起来的壳（3 个 tab 的底部导航）。
// 数据层（Room）、时间线、模板、统计等在 M1 之后逐步加入。
// ============================================================================

import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // KSP：Room 的注解处理器（比老的 kapt 快很多）
    alias(libs.plugins.ksp)
}

/* ---------------------------------------------------------------------------
 * 正式签名的凭证从 `key.properties` 读（该文件不进仓库）
 *
 * ★ 关键设计：**没有这个文件时不能构挂**。
 *   开源仓库里当然没有它（那是作者的私钥），别人 clone 之后要能
 *   直接 `./gradlew assembleRelease` 打出一个能装的包来试。
 *   所以下面 buildTypes 里做了回退：缺凭证就退回 debug 签名 ——
 *   包能装、能跑，只是**不能拿去发版**（签名不同，老用户升级不上）。
 * ------------------------------------------------------------------------- */
val keystorePropsFile = rootProject.file("key.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}
val hasReleaseKey = keystorePropsFile.exists()

android {
    // 代码包名空间（与 applicationId 可以不同，这里保持一致好认）
    namespace = "com.misaka.timelog"

    // 用 Android 15（API 35）的 SDK 编译
    compileSdk = 35

    defaultConfig {
        applicationId = "com.misaka.timelog"

        // 最低支持 Android 8.0（API 26）。选 26 的原因：
        // ① 通知渠道、前台服务等现代 API 从 26 起才规范；
        // ② 覆盖率已足够高，且不必为远古版本写兼容分支。
        minSdk = 26

        // 目标版本跟随最新，才能享受新系统的行为优化
        targetSdk = 35

        // ⚠️ 发版之后**只能往上加**，不能减也不能重复。
        //    v1.0.0 新增：本地导出 / 分享 / 从文件导入。
        versionCode = 2
        versionName = "1.0.0"

        // 单元测试用的 JUnit 运行器（M1 写数据层测试时会用到）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                // ⚠️ `rootProject.file` 而不是 `file` —— key.properties 里的路径
                //    是相对**工程根目录**写的，直接 `file()` 会解析成 app/ 下，找不到。
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // M0 阶段先不混淆，方便看崩溃堆栈；正式发版前再打开
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有私钥 → 用正式签名；没有（别人 clone 的仓库）→ 退回 debug 签名，
            // 保证「clone 下来能打包」这件事不被私钥卡住。
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
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

    buildFeatures {
        // 打开 Compose 支持
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // Robolectric 要在 JVM 上模拟 Android 环境（Room 的内存数据库需要 Context），
        // 它需要真实读到资源，所以这一项必须打开
        unitTests.isIncludeAndroidResources = true

        unitTests.all {
            // Robolectric 第一次跑会去下载 android-all（安卓运行时的模拟包，
            // 按 SDK 版本每个 100~200MB）。它默认落在 C:\Users\<你>\.m2\repository，
            // 而这台机器 C 盘只剩 8.5GB —— 显式挪到 D 盘，跟工具链其它东西放一起。
            it.systemProperty("robolectric.dependency.dir", "D:\\Android\\robolectric")

            // 默认的下载源是 repo1.maven.org。实测从这台机器拉那个域名的包很慢
            // （第一次跑挂了 17 分钟没下完），换成阿里云的 Maven Central 代理。
            // 实测过的镜像：dl.google.com / mirrors.cloud.tencent.com / maven.aliyun.com 都通，
            // 而 mirrors.tuna.tsinghua.edu.cn 会反爬直接拒。
            it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/central")
            it.systemProperty("robolectric.dependency.repo.id", "aliyun-central")
        }
    }
}

dependencies {
    // --- AndroidX 基础 ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // --- Compose ---
    // platform() 让 BOM 决定下面这些库的版本，所以它们都不用写版本号
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // --- 桌面小部件（Glance）---
    // 用 Compose 的写法画小部件。不引它的话得手写 RemoteViews + XML 布局，
    // 而且「同一件事的时长怎么算」会在小部件里再写一遍 —— 那正是要避免的。
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // --- 仅调试期生效 ---
    // ui-tooling 只在 debug 包里，用来支持 Android Studio 的布局预览
    debugImplementation(libs.androidx.ui.tooling)

    // --- Room（本地数据库）---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // KSP 在编译期根据 @Entity / @DAO 生成代码
    ksp(libs.androidx.room.compiler)

    // --- 单元测试 ---
    // 纯 JVM 测试：密铺校验这类纯函数直接跑（秒级）
    testImplementation(libs.junit)
    // Robolectric：给 Room 的内存数据库提供 Android 环境，不必插真机
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
