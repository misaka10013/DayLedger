package com.misaka.timelog.domain

/**
 * 一次写操作的结果 —— 要么成功，要么**带着一句人话被拦下**。
 *
 * ## 为什么不直接抛异常
 *
 * 这个 App 里被拦下的情况**不是意外，是正常业务**：
 * 「分类下还有 3 个事项，不能删」「这是过去的一天，不能从现在开始记」——
 * 这些是**规则**，不是错误。用异常表达会逼着调用方到处 try/catch，
 * 而且很容易漏掉某一条。
 *
 * 做成返回值之后，编译器会**强制**调用方处理「被拦下」这一支 ——
 * 想装作没看见都难。界面层拿到 [Blocked] 就弹提示条，拿到 [Ok] 就刷新。
 *
 * ## 为什么守卫必须在数据层
 *
 * ⚠️ **只在界面上把删除按钮灰掉不算数**。
 * 「先搬空、才可删」这条规则如果只活在 UI 里，将来任何一处新代码
 * （批量操作、导入备份、云同步拉取）绕过界面直连数据库，就能造出
 * 「一堆指向不存在分类的事项」这种**修都没法修**的脏数据。
 *
 * 所以：**数据层硬拦 + 给理由**，界面层只是把人话显示出来。
 */
sealed interface Outcome<out T> {

    /** 做成了。[value] 是产物（新建出来的实体、受影响的条数……）。 */
    data class Ok<T>(val value: T) : Outcome<T>

    /**
     * 被规则拦下。
     *
     * [message] 是**直接可以显示给用户**的中文，不要在这里放技术术语。
     */
    data class Blocked(val message: String) : Outcome<Nothing>

    val isOk: Boolean get() = this is Ok
}

/** 成功时取出产物，被拦下时给 `null`。 */
fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Ok)?.value

/** 被拦下时取出那句话，成功时给 `null`。 */
fun Outcome<*>.blockedReason(): String? = (this as? Outcome.Blocked)?.message

/** 转换成功时的产物，被拦下时原样透传。 */
inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Blocked -> this
}
