// ============================================================================
// 根工程构建脚本
// ----------------------------------------------------------------------------
// 这里只声明「插件有哪些」，真正的应用在各模块（app/build.gradle.kts）里。
// ============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
