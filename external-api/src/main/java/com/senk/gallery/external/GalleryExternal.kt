package com.senk.gallery.external

import android.content.Intent
import android.net.Uri

/**
 * 「相册」对外调用契约 —— 第三方应用与自身壳层（`main` 模块）共用的唯一入口。
 *
 * 设计要点：
 * - 所有 action / extra **都以字符串常量公开**，第三方应用不依赖本模块也能按文档拼接 Intent；
 *   本模块只是把这些常量收敛到一处，供本应用与有意集成的工程直接引用。
 * - 调用统一落到 `com.senk.gallery.ExternalEntryActivity`（`exported=true` 的唯一外部入口），
 *   由它负责隐私同意、媒体权限与后续页面路由，第三方**不应**直接启动内部 Activity
 *   （内部 Activity 全部 `exported=false`）。
 * - 返回值统一放在 Activity result 里；多选同时写入 `ClipData`（与系统选图器一致），
 *   单选写入 `Intent.data`。返回的 URI 是本应用代理 URI（`content://com.senk.gallery.external/media/{id}`），
 *   已对调用方临时授权，`ContentResolver.openInputStream` / `openFileDescriptor` 可直接读取。
 *
 * 完整调用示例与返回约定见 `docs/external-api.md`。
 */
object GalleryExternal {

    /** 本应用包名（外部调用方可用它显式指定目标应用：`intent.setPackage(APP_PACKAGE)`）。 */
    const val APP_PACKAGE = "com.senk.gallery"

    /** 选图：`startActivityForResult`，结果见 [EXTRA_SELECTED_URIS] / ClipData / data。 */
    const val ACTION_PICK_IMAGE = "com.senk.gallery.action.PICK_IMAGE"

    /** 看大图：`Intent.data`（或 [EXTRA_VIEW_URIS]）传入待查看的 content URI。 */
    const val ACTION_VIEW_IMAGE = "com.senk.gallery.action.VIEW_IMAGE"

    /** Boolean：选图是否允许多选（默认 false，即单选）。 */
    const val EXTRA_ALLOW_MULTIPLE = "com.senk.gallery.extra.ALLOW_MULTIPLE"

    /** Int：多选上限（默认 [DEFAULT_MAX_COUNT]；单选模式忽略此项）。 */
    const val EXTRA_MAX_COUNT = "com.senk.gallery.extra.MAX_COUNT"

    /** String：选图页 / 大图页标题（可选）。 */
    const val EXTRA_TITLE = "com.senk.gallery.extra.TITLE"

    /** ArrayList&lt;String&gt;：看大图时可选的多图 URI 列表（第一项为当前项）。 */
    const val EXTRA_VIEW_URIS = "com.senk.gallery.extra.VIEW_URIS"

    /** Int：看大图时的初始位置（配合 [EXTRA_VIEW_URIS]，越界会收敛到合法范围）。 */
    const val EXTRA_VIEW_POSITION = "com.senk.gallery.extra.VIEW_POSITION"

    /** 选图结果：ArrayList&lt;String&gt;，所选媒体的代理 URI（单选时仅一项）。 */
    const val EXTRA_SELECTED_URIS = "com.senk.gallery.extra.SELECTED_URIS"

    /** 选图结果：Int，所选数量。 */
    const val EXTRA_SELECTED_COUNT = "com.senk.gallery.extra.SELECTED_COUNT"

    /** 多选默认上限。 */
    const val DEFAULT_MAX_COUNT = 9

    /**
     * 构造选图 Intent（调用方仍需 `startActivityForResult`，并按需 `setPackage(APP_PACKAGE)`）。
     *
     * @param allowMultiple 是否多选；false 时点选一张立即返回。
     * @param maxCount 多选上限（≤0 时用 [DEFAULT_MAX_COUNT]）。
     * @param title 选图页标题（可选）。
     */
    fun pickImageIntent(
        allowMultiple: Boolean = false,
        maxCount: Int = if (allowMultiple) DEFAULT_MAX_COUNT else 1,
        title: String? = null,
    ): Intent = Intent(ACTION_PICK_IMAGE)
        .addCategory(Intent.CATEGORY_DEFAULT)
        .putExtra(EXTRA_ALLOW_MULTIPLE, allowMultiple)
        .putExtra(EXTRA_MAX_COUNT, if (maxCount <= 0) DEFAULT_MAX_COUNT else maxCount)
        .apply { title?.let { putExtra(EXTRA_TITLE, it) } }

    /**
     * 构造看大图 Intent。
     *
     * @param uris 待查看的 content URI（至少 1 个；调用方需带 `FLAG_GRANT_READ_URI_PERMISSION`）。
     * @param position 初始位置。
     */
    fun viewImageIntent(uris: List<Uri>, position: Int = 0): Intent {
        require(uris.isNotEmpty()) { "uris must not be empty" }
        val safePosition = position.coerceIn(uris.indices)
        return Intent(ACTION_VIEW_IMAGE)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setData(uris[safePosition])
            .putStringArrayListExtra(EXTRA_VIEW_URIS, ArrayList(uris.map(Uri::toString)))
            .putExtra(EXTRA_VIEW_POSITION, safePosition)
    }
}
