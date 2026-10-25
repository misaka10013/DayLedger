// ============================================================================
// 时间日志 App · 工程设置
// ----------------------------------------------------------------------------
// 这里只做两件事：① 声明插件/依赖从哪些仓库下载；② 声明有哪些模块。
// 仓库顺序说明：官方源实测在国内可直连（dl.google.com 6MB/s），故放在前面；
// 阿里云镜像作为兜底，避免偶发抽风时整个构建卡死。
// ============================================================================

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 兜底镜像
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
    }
}

dependencyResolutionManagement {
    // 禁止子模块自己声明仓库，统一在这里管，避免版本来源混乱
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 兜底镜像
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "TimeLog"

// 目前只有一个 app 模块；将来若拆出 wear / widget 再加
include(":app")
