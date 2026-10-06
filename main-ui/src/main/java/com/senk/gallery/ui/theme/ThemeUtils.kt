package com.senk.gallery.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.senk.gallery.ui.R

/**
 * 主题工具类 —— **代码中取色的唯一入口**。
 *
 * 项目约定（与 `values/colors.xml` + `values-night/colors.xml` 配套）：
 * - XML 里不在控件上直接写颜色或 `@color/...`，统一在 `styles.xml` 定义 style 后挂上去
 * - Kotlin/Java 里**不要**直接 `ContextCompat.getColor(...)`，更不要写 `Color.WHITE`
 *   之类的字面量，一律经由本类。好处：颜色名集中、浅色/深色成对不会漏、
 *   一眼能看出某处用的是「语义色」还是「有意恒定」的颜色
 *
 * 加新颜色的流程：在 `main-ui/src/main/res/values/colors.xml` 与
 * `values-night/colors.xml` **同时**加同名条目（`scripts/check-color-pairs.py` 会校验），
 * 然后在本类加一个对应的取值方法。
 */
object ThemeUtils {

    // ===================== 文字 =====================

    /** 主要文字：标题、列表主文案。 */
    @ColorInt
    fun textPrimary(context: Context): Int = resolve(context, R.color.gallery_text_primary)

    /** 次要文字：说明、副标题。 */
    @ColorInt
    fun textSecondary(context: Context): Int = resolve(context, R.color.gallery_text_secondary)

    /** 画在深色遮罩/媒体层上的文字（视频控件、上划后的标题栏）。 */
    @ColorInt
    fun textOnScrim(context: Context): Int = resolve(context, R.color.gallery_text_on_scrim)

    // ===================== 容器底色 =====================

    /**
     * chrome 底色：标题栏、状态栏底条、各页根布局。
     *
     * 标题栏自身是透明的，透出的就是这层，因此切换标题栏明暗时要连它一起换。
     */
    @ColorInt
    fun surfaceChrome(context: Context): Int = resolve(context, R.color.gallery_surface_chrome)

    /** 内容底色：宫格 GL surface 的清屏色、全屏页面背景。 */
    @ColorInt
    fun surfaceContent(context: Context): Int = resolve(context, R.color.gallery_surface_content)

    /** 查看器底部详情面板 / 地图卡片底色。 */
    @ColorInt
    fun surfaceSheet(context: Context): Int = resolve(context, R.color.gallery_surface_sheet)

    /** 缩略图未解码出来时的占位底色。 */
    @ColorInt
    fun surfacePlaceholder(context: Context): Int =
        resolve(context, R.color.gallery_surface_placeholder)

    /** 底部胶囊导航卡片底色。 */
    @ColorInt
    fun surfaceNavCard(context: Context): Int = resolve(context, R.color.gallery_surface_nav_card)

    // ===================== 图标 =====================

    /** 工具条/按钮上的图标（返回、收藏、分享、信息等）。 */
    @ColorInt
    fun iconOnSurface(context: Context): Int = resolve(context, R.color.gallery_icon_on_surface)

    /** 画在深色遮罩/媒体层上的图标（视频控件、上划后的标题栏返回键）。 */
    @ColorInt
    fun iconOnScrim(context: Context): Int = resolve(context, R.color.gallery_icon_on_scrim)

    // ===================== 分隔/装饰 =====================

    /** 查看器底部详情面板的把手。 */
    @ColorInt
    fun dividerOnSurface(context: Context): Int =
        resolve(context, R.color.gallery_divider_on_surface)

    // ===================== 品牌色 =====================

    /** 选中态主色（底部导航选中、主按钮）。 */
    @ColorInt
    fun brandPrimary(context: Context): Int = resolve(context, R.color.gallery_brand_primary)

    /** 画在主色按钮上的文字/图标。 */
    @ColorInt
    fun brandOnPrimary(context: Context): Int = resolve(context, R.color.gallery_brand_on_primary)

    // ===================== 媒体层（有意恒定，不跟随主题） =====================

    /**
     * 视频播放器 / 全屏图片查看器的深色遮罩底。
     *
     * **有意不跟随主题**：媒体观看场景按惯例恒定深色。若要改这一行为，
     * 只需改 `values-night` 里的值，调用方无需变更。
     */
    @ColorInt
    fun mediaScrim(context: Context): Int = resolve(context, R.color.gallery_media_scrim)

    /** 视频控件圆形按钮的底色（半透明深色）。 */
    @ColorInt
    fun mediaControlBg(context: Context): Int = resolve(context, R.color.gallery_media_control_bg)

    /** 查看器「亮底」观看模式下的占位底色（有意不跟随主题）。 */
    @ColorInt
    fun mediaPlaceholderLight(context: Context): Int =
        resolve(context, R.color.gallery_media_placeholder_light)

    /** 查看器「深底」观看模式下的占位底色（有意不跟随主题）。 */
    @ColorInt
    fun mediaPlaceholderDark(context: Context): Int =
        resolve(context, R.color.gallery_media_placeholder_dark)

    // ===================== 透明 =====================

    /** 透明。用于「背景交给下层/由遮罩绘制」的场合，比写 `Color.TRANSPARENT` 语义更明确。 */
    @ColorInt
    fun transparent(): Int = Color.TRANSPARENT

    // ===================== 明暗判断与系统栏 =====================

    /**
     * 当前是否处于深色模式。
     *
     * 取自 `uiMode` 配置位，与资源限定符用的是同一依据，因此**不会与
     * `values-night/colors.xml` 的生效状态不一致**。
     */
    fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 按背景色的明暗设置系统栏图标。
     *
     * 这与颜色是同一件事的两面：底色浅就要深色图标，反之亦然。
     * 需要「上划后临时转为深色遮罩」这类场景时，直接传遮罩实色即可。
     */
    fun applySystemBarAppearance(activity: Activity, @ColorInt background: Int) {
        val controller = WindowInsetsControllerCompat(
            activity.window,
            activity.window.decorView,
        )
        val light = isLightColor(background)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    /** 判断颜色是否偏亮（用于决定系统栏图标用深色还是浅色）。 */
    fun isLightColor(@ColorInt color: Int): Boolean {
        // 相对亮度（sRGB 近似），阈值与 Material 的做法一致
        val r = ((color shr 16) and 0xFF) / 255.0
        val g = ((color shr 8) and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        val alpha = ((color ushr 24) and 0xFF) / 255.0
        // 半透明色按「透出下层」处理：与 alpha 加权后再判断
        return luminance * alpha + (1 - alpha) * 0.5 > 0.5
    }

    /** 统一取色入口，避免各处重复 `ContextCompat.getColor`。 */
    @ColorInt
    fun resolve(context: Context, @ColorRes resId: Int): Int = ContextCompat.getColor(context, resId)
}
