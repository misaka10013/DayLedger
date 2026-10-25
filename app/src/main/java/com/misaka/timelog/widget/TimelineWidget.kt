package com.misaka.timelog.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.misaka.timelog.MainActivity
import com.misaka.timelog.R

/**
 * 桌面小部件 —— 「现在在干什么」。
 *
 * ## 长什么样
 *
 * ```
 *   ● 打工                        ┌──┐
 *     已持续 2时13分 · 08:30 开始   │ ■│     ← 宽版
 *                               └──┘
 *   ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬              ← 拉高才有
 *   已记录 10时43分 · 未记录 3时17分
 * ```
 *
 * ## ★ 自适应一档：拉大就多显示
 *
 * 三种尺寸都声明出来，系统挑最接近的那个（[SizeMode.Responsive]，需要 Android 12+）：
 *
 * | 档位 | 样子 | 多显示什么 |
 * |---|---|---|
 * | 窄（2×1） | `● 打工 2时13分 ■` 一行 | 就这些 |
 * | 宽（4×1） | 名字 + 详情两行 | 起点时间 |
 * | 高（4×2） | 再加色条和今日合计 | 一整天长什么样、漏记了没有 |
 *
 * ⚠️ 尺寸判断**只影响画什么，不影响算什么** —— 时长、合计全都由
 * `WidgetModel` 一次算好，三档用的是**同一个数**。不会出现
 * 「窄版和宽版对同一时刻给出两个时长」。
 *
 * ## ★★ 为什么这里一行业务判断都没有
 *
 * 「空闲中该显示什么」「事项被删了怎么办」「色条怎么分段」全在 `WidgetModel`
 * （纯函数，JVM 秒级测）。这个文件只做两件事：**量尺寸、照着画**。
 * 理由跟时间线那边一样 —— 小部件没法自动化验收（Robolectric 模拟不了桌面宿主），
 * 所以能推出纯函数的逻辑一个都别留在这儿。
 *
 * ## ⚠️ 三条被 Glance 1.1.1 的 API 逼出来的写法（都实测过）
 *
 * ① **`Text` 只有 4 个参数**（`text / modifier / style / maxLines`），
 *    **没有 `overflow`、也没有 `TextOverflow`** —— 想让名字不换行，只能给
 *    `maxLines = 1`，超出部分直接被裁（没有省略号）。所以事项名太长的样子是
 *    「写代码写代…」变成「写代码写代」—— 能接受，但别指望有 `…`。
 * ② **`defaultWeight()` 不带参数**（它挂在 `RowScope`/`ColumnScope` 上，恒为 1）
 *    —— 想按比例铺色条，只能**自己拿实例宽度当分母算 dp**。
 *    这反而比 weight 更可控（见 [Bars]）。
 * ③ **`actionStartActivity` 没有 reified 版本**，只收 `Intent`。
 *    所以 Action 在 [provideGlance] 里就造好（那里有 `context`），
 *    别在 composition 里 new —— 那样每次重组都会新建一个对象。
 */
class TimelineWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(NARROW, WIDE, TALL))

    /**
     * ★ 状态用的就是「偏好设置」那一套（Glance 最常用的实现）。
     *
     * 里面只存一个 `rev` 计数 —— 它**不代表任何数据**，作用只有一个：
     * 每次刷新把它 +1，让组合依赖的那个值发生变化，从而**逼 Glance 重组**。
     * 理由见 `WidgetSnapshot` 里 `latest` 的注释（`updateAll()` 不保证重组，实测过）。
     */
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // ★ 数据在进 composition **之前**取好 —— 小部件的 UI 树会被序列化进
        //   RemoteViews，在 composition 里做 IO 是官方明确不建议的
        //   （会拖慢渲染，严重时系统直接放弃这次更新）。
        //   ⚠️ 这一步只在「这个实例第一次画 / 进程被回收后重画」时跑；
        //      平时的刷新走的是「状态变了 → 只重跑组合」，所以下面还要从
        //      `WidgetSnapshot.current()` 拿缓存里那份新的。
        val model = WidgetSnapshot.load(context)

        // ★ 真实尺寸也在这里读（见 [readRealSize]）。放这儿而不是组合里，理由同上：
        //   `GlanceAppWidgetManager.getAppWidgetId()` 是 suspend 的，组合里调不了。
        //   尺寸变了系统必然会重新 provideGlance，所以这个快照不会有「过期」问题。
        val real = readRealSize(context, id)

        // ★ Action 也提前造好（见类注释 ③）。点整块 = 打开 App；点圆按钮 = 浮出选事项窗。
        val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
        val quickSwitch = actionStartActivity(Intent(context, QuickSwitchActivity::class.java))

        provideContent {
            // ★★ 读这一下**就是在建立依赖**：`WidgetRefresh` 每次刷新都会把 rev 改掉，
            //    于是这里必然重新执行一遍 —— 这就是「切完事项小部件当场变」的实现方式。
            //    值本身不用（`@Suppress` 那行只是为了让人看清楚它故意没被用到）。
            @Suppress("UNUSED_VARIABLE")
            val rev = currentState<Preferences>()[WidgetSnapshot.REV] ?: 0L
            Body(
                m = WidgetSnapshot.current(model) ?: model,
                real = real,
                openApp = openApp,
                quickSwitch = quickSwitch,
            )
        }
    }
}

/**
 * 读这个实例的**真实**尺寸（dp）。
 *
 * ## ★★ 为什么不能只靠 `LocalSize.current`
 *
 * `LocalSize` 给的是 [SizeMode.Responsive] **声明档位**的尺寸（我们声明的是 110/250），
 * 不是桌面实际给的格子大小。实测（2026-10-17，`6ae3b15d`／1080×2400＠420dpi）：
 *
 * - 高栏档声明 250dp，桌面实际给了 **357dp** → `Bars` 按 `250-2×10=230dp` 算总宽，
 *   而部件有 357dp → **色条右侧空了整整 1/3**；
 * - 窄档声明 110dp，桌面把它拉到 ~200dp 时 → 布局还按「窄」画，
 *   于是「名字在左、■ 在右、中间一大片空」。
 *
 * 两个症状根因是同一条：**拿声明值当真实值用**。
 *
 * ## 取值口径
 *
 * 用 `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT`（竖屏时的可用空间）。手机上基本恒竖屏，
 * 取 `MIN_` 就是当前真实尺寸；`MAX_` 是横屏上界，拿来判断会偏大。
 *
 * ⚠️ **读不到时返回 null，让调用方退回 `LocalSize`** —— 有的桌面（尤其第三方 launcher）
 * 不填这个 Bundle。宁可回到旧行为，也不能 0dp 导致整块画不出来。
 */
private suspend fun readRealSize(context: Context, id: GlanceId): DpSize? = runCatching {
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
    val opts = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
    val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
    if (w > 0) DpSize(w.dp, (if (h > 0) h else 40).dp) else null
}.getOrNull()

// ------------------------------------------------------------------ 尺寸档位
// 放文件顶层而不是 companion：SizeMode 在类初始化时就要它们，顺序上更省心。

private val NARROW = DpSize(110.dp, 40.dp)
private val WIDE = DpSize(250.dp, 40.dp)
private val TALL = DpSize(250.dp, 110.dp)

/** 宽到能并排放「名字 + 详情」两行。 */
private val WIDE_AT = 170.dp

/** 高到能多塞色条 + 今日合计。 */
private val TALL_AT = 90.dp

/** 整块四周的内边距。色条算宽度时要把它减掉（见 [Bars]）。 */
private val PAD = 14.dp

/** 卡片圆角。跟系统桌面那种大圆角卡片对齐（原来这里是直角）。 */
private val CORNER = 22.dp

// ------------------------------------------------------------------ 配色
// ★ 全部走资源（`values/colors.xml` + `values-night/colors.xml`）。
//   ⚠️ 不是「风格选择」而是必须 —— Glance 1.1.1 的 `ColorProvider` 只有
//      「固定色」和「资源色」两种工厂函数（实测过 jar 里的签名），
//      并没有「白天/夜晚」双参那个重载。想跟系统深色模式，只能靠资源限定符。

private val Surface = ColorProvider(R.color.widget_surface)
private val TitleInk = ColorProvider(R.color.widget_title_ink)
private val SubInk = ColorProvider(R.color.widget_sub_ink)
private val StopBg = ColorProvider(R.color.widget_stop_bg)
private val StopInk = ColorProvider(R.color.widget_stop_ink)
private val GoBg = ColorProvider(R.color.widget_go_bg)
private val GoInk = ColorProvider(R.color.widget_go_ink)

/** 空闲时那行字。跟时间线底部状态条用同一个词 —— 同一个状态两个说法会让人犯嘀咕。 */
private const val IDLE = "空闲中"

// ------------------------------------------------------------------ 界面

@Composable
private fun Body(m: WidgetModel, real: DpSize?, openApp: Action, quickSwitch: Action) {
    // ★ 优先用桌面给的**真实**尺寸；读不到才退回声明档位（理由见 readRealSize）。
    val size = real ?: LocalSize.current
    val wide = size.width >= WIDE_AT
    val tall = size.height >= TALL_AT

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            // ★ 圆角卡片。⚠️ 必须写在 `background` **之前** —— RemoteViews 是靠
            //   outline 裁剪的，顺序反了就是一整块方的底色把圆角盖掉。
            .cornerRadius(CORNER)
            .background(Surface)
            // 点整块（圆按钮自己会盖掉这一层动作）= 打开 App 的时间线
            .clickable(openApp)
            .padding(PAD),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            // 分类色小竖条。空闲时是灰的。
            // ⚠️ 用 4×18 的竖条而不是圆点：圆点会被误读成「一个状态灯」，
            //    竖条读起来更像「这一类」的色标，跟统计图里的色块也是一套语言。
            Box(
                modifier = GlanceModifier
                    .size(4.dp, 18.dp)
                    .cornerRadius(2.dp)
                    .background(m.live?.let { solid(it.colorHex) } ?: SubInk)
            ) {}

            Spacer(GlanceModifier.width(9.dp))

            // ⚠️ 名字这一列要 defaultWeight() 占满「除去右边按钮之后」的全部宽度。
            //    不占满的话，长事项名会把圆按钮一路挤出小部件外面
            //    （RemoteViews 里没有 Compose 那套 intrinsic 测量，
            //      Android 8+ 也不给同句 "wrap_content 但最多占一半" 这种约束）。
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = m.live?.name ?: IDLE,
                    style = TextStyle(
                        color = TitleInk,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                // 窄版没有这一行（放不下）—— 它的时长挪到右边跟名字并排
                if (wide) {
                    Text(
                        text = detail(m),
                        style = TextStyle(color = SubInk, fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }

            if (wide) {
                Spacer(GlanceModifier.width(6.dp))
            } else {
                // 窄版：时长挤在名字右边
                Text(
                    text = m.live?.durationLabel.orEmpty(),
                    style = TextStyle(color = SubInk, fontSize = 12.sp),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(6.dp))
            }

            ActionButton(active = m.live != null, action = quickSwitch)
        }

        if (tall) {
            Spacer(GlanceModifier.height(12.dp))
            // ★ 传**真实**宽度进去 —— 色条总宽必须等于卡片实际可用宽度，
            //   否则右侧会空出一条（见 readRealSize 的注释）。
            Bars(m.bars, size.width)
            Spacer(GlanceModifier.height(7.dp))
            Text(
                text = summary(m),
                style = TextStyle(color = SubInk, fontSize = 11.sp),
                maxLines = 1,
            )
        }
    }
}

/**
 * 右边那个圆按钮 —— **点它就浮出选事项窗**。
 *
 * 在记着的时候是 ■（结束并换下一件），空闲的时候是 ▶（开始记）。
 * 两种状态**弹的是同一个窗、走的是同一条落库路径** —— 图标不同只是给个提示，
 * 不存在「▶ 是开始、■ 是结束」两条不同的代码路径。
 *
 * ⚠️ 这正是御坂说的那个用法：「点了就会在桌面弹悬浮下一个事项的悬浮窗口，
 * 我之前就一直这样用，很少进 app」。
 */
@Composable
private fun ActionButton(active: Boolean, action: Action) {
    Box(
        modifier = GlanceModifier
            .size(34.dp)
            .cornerRadius(17.dp)
            .background(if (active) StopBg else GoBg)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // U+25A0 / U+25B6 —— 都在 WGL4 字符集里，安卓系统字体一定有。
            // ⚠️ 不用图标资源：小部件的 RemoteViews 对 vector drawable 在低版本上很挑。
            // ⚠️ 也**不要**用 U+25A8（▨）那种——它不在 WGL4，真机上就是个豆腐块
            //    （时间线那边的 `GapMark` 就是被这个坑逼成自绘的）。
            text = if (active) "■" else "▶",
            style = TextStyle(
                color = if (active) StopInk else GoInk,
                fontSize = 11.sp,
            ),
        )
    }
}

/**
 * 迷你色条：今天从 0 点到此刻，一眼看出「今天长什么样」。
 *
 * ## ⚠️ 为什么自己算 dp 而不用 weight
 *
 * Glance 的 `defaultWeight()` **没有参数**（见类注释 ②），铺出来每段一样宽 ——
 * 那就不是「今天长什么样」了，是一条假图。
 *
 * 所以这里拿 [width] 当分母，按 `WidgetModel` 里算好的比例乘出每段的 dp。
 * ★ 换句话说：**比例是纯函数算的，这里只做一次乘法** —— 换了尺寸也不会算歪。
 *
 * ## ★★ [width] 必须传**真实**宽度，不能传 `LocalSize`
 *
 * 实测（1080×2400＠420dpi、高栏档）：声明档位 250dp、桌面实际给 357dp，
 * 按 250 算出来 604px 而部件有 936px → **右边空 1/3**。
 * 调用方（[Body]）负责把 `AppWidgetManager` 读回来的真实值传进来。
 */
@Composable
private fun Bars(bars: List<WidgetBar>, width: Dp) {
    if (bars.isEmpty()) return

    // 真实宽度扣掉我们自己的左右 padding
    val total = width - PAD * 2
    if (total.value <= 0f) return

    Row(modifier = GlanceModifier.fillMaxWidth().height(6.dp)) {
        bars.forEach { b ->
            Box(
                modifier = GlanceModifier
                    .width(total * b.weight)
                    .fillMaxHeight()
                    .background(solid(b.colorHex))
            ) {}
        }
    }
}

// ------------------------------------------------------------------ 文案

/** 详情行 —— **跟时间线底部状态条逐字一致**。 */
private fun detail(m: WidgetModel): String = m.live?.let {
    "已持续 ${it.durationLabel} · ${it.startLabel} 开始"
} ?: "未在记录"

/** 今日合计。没有空白时不提「未记录」—— 记全了的日子不用反复说。 */
private fun summary(m: WidgetModel): String =
    "已记录 ${m.recordedLabel}" + if (m.hasGap) " · 未记录 ${m.gapLabel}" else ""

// ------------------------------------------------------------------ 小工具

/**
 * 分类色不跟日夜走 —— 它就是用户自己挑的那个颜色，原样画。
 * ⚠️ 所以这里用「固定色」那个重载（传一个 compose Color），而不是资源 ID：
 *   运行时才知道的 hex 字符串没办法指向资源。
 */
private fun solid(hex: String): ColorProvider = ColorProvider(hexColor(hex))

/** `#RRGGBB` → Color。解不出来退回空白段的灰（宁可颜色不对，不能崩）。 */
private fun hexColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFF94A3B8))
