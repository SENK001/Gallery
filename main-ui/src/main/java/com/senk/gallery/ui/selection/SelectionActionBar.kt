package com.senk.gallery.ui.selection

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.senk.gallery.ui.R

/**
 * 多选模式下的底部动作栏（分享 / 收藏 / 添加到 / 删除）。
 *
 * 只做「按钮容器 + 点击回调」，具体动作逻辑在 [SelectionActions] 里 ——
 * 这样照片页（Fragment）与相册详情页（Activity）可以共用同一套按钮与动作实现。
 *
 * 无障碍：每个动作项的 contentDescription 用可见文字（布局里已设置），可聚焦、可点击，
 * TalkBack 逐个读出动作名称。
 */
class SelectionActionBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onShareClick: (() -> Unit)? = null
    var onFavoriteClick: (() -> Unit)? = null
    var onAddToAlbumClick: (() -> Unit)? = null
    var onDeleteClick: (() -> Unit)? = null

    private val shareItem: View
    private val favoriteItem: View
    private val addToItem: View
    private val deleteItem: View

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_selection_action_bar, this)
        shareItem = findViewById(R.id.selection_action_share)
        favoriteItem = findViewById(R.id.selection_action_favorite)
        addToItem = findViewById(R.id.selection_action_add_to)
        deleteItem = findViewById(R.id.selection_action_delete)
        shareItem.setOnClickListener { onShareClick?.invoke() }
        favoriteItem.setOnClickListener { onFavoriteClick?.invoke() }
        addToItem.setOnClickListener { onAddToAlbumClick?.invoke() }
        deleteItem.setOnClickListener { onDeleteClick?.invoke() }
    }
}
