package com.senk.gallery.ui.grid

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.senk.gallery.data.entity.MediaItem
import com.senk.gallery.ui.ImageViewThumbLoader
import com.senk.gallery.ui.R
import com.senk.gallery.ui.selection.SelectionState
import com.senk.gallery.util.DateFormats

/**
 * 宫格适配器：一格一个 View（缩略图 + 视频时长/动态照片角标 + 多选选择框）。
 *
 * 与 [MediaGridView] 的分工：
 * - 本类只负责「按状态画格子」与把点击事件上报（不直接改选择状态）；
 * - 选择状态由 [SelectionState] 持有，宫格监听它后通过 [notifyItemChanged] 做局部刷新。
 *
 * 无障碍：每格根 View 挂 contentDescription（类型 + 拍摄时间 + 已选/未选），
 * 多选态下通过 AccessibilityDelegate 暴露 CheckBox 语义（TalkBack 会读「已选中/未选中」）。
 */
class MediaGridAdapter(
    private val context: Context,
    private val selection: SelectionState,
    private val cellSizeProvider: () -> Int,
    private val emptyHeightProvider: () -> Int,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<MediaItem>()

    /** 空态文案；为 null 时即使没有数据也不展示空态项。 */
    var emptyText: String? = null

    /** 点击/长按回调（参数为适配器位置；是否进入选择逻辑由 [MediaGridView] 决定）。 */
    var onCellClick: ((Int) -> Unit)? = null
    var onCellLongClick: ((Int) -> Unit)? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int =
        if (items.isEmpty() && !emptyText.isNullOrEmpty()) 1 else items.size

    override fun getItemId(position: Int): Long =
        if (isShowingEmpty() && position == 0) {
            Long.MIN_VALUE
        } else {
            items.getOrNull(position)?.id ?: RecyclerView.NO_ID
        }

    override fun getItemViewType(position: Int): Int =
        if (isShowingEmpty() && position == 0) TYPE_EMPTY else TYPE_CELL

    fun submitItems(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun appendItems(newItems: List<MediaItem>) {
        if (newItems.isEmpty()) {
            return
        }
        if (items.isEmpty()) {
            // 之前是空态项占着第 0 位，直接整体刷新最稳
            items.addAll(newItems)
            notifyDataSetChanged()
            return
        }
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }

    fun itemAt(position: Int): MediaItem? = items.getOrNull(position)

    fun positionOf(id: Long): Int = items.indexOfFirst { it.id == id }

    fun isShowingEmpty(): Boolean = items.isEmpty() && !emptyText.isNullOrEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_EMPTY) {
            val textView = TextView(context, null, 0, R.style.Widget_Gallery_GridEmptyText)
            textView.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            return EmptyHolder(textView)
        }
        val view = LayoutInflater.from(context).inflate(R.layout.item_media_cell, parent, false)
        return CellHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is EmptyHolder) {
            holder.textView.text = emptyText.orEmpty()
            // 空态项高度取宫格可视高度，让文案大致居中（bind 时宫格已完成测量）
            val height = emptyHeightProvider()
            holder.textView.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height.coerceAtLeast(dp2px(EMPTY_MIN_HEIGHT_DP)),
            )
            return
        }
        val cell = holder as CellHolder
        val item = items.getOrNull(position) ?: return
        val selectionMode = selection.isActive
        val selected = selection.isSelected(item.id)

        ImageViewThumbLoader.load(
            cell.thumb,
            item.uri?.toString(),
            cellSizeProvider().coerceAtLeast(dp2px(THUMB_SIZE_BUCKET_DP)),
        )

        val isVideo = item.isVideo && item.duration > 0L
        cell.duration.isVisible = isVideo && !item.isMotionPhoto
        if (isVideo) {
            cell.duration.text = DateFormats.formatDuration(item.duration)
        }
        cell.motion.isVisible = item.isMotionPhoto

        cell.border.isVisible = selectionMode && selected
        cell.border.isSelected = selected
        cell.check.isVisible = selectionMode
        cell.check.isSelected = selected
        cell.checkIcon.isVisible = selected
        cell.thumb.alpha = if (selectionMode && selected) SELECTED_ALPHA else 1f

        cell.itemView.isSelected = selectionMode && selected
        cell.itemView.contentDescription = buildContentDescription(item, selected)
        cell.checkable = selectionMode
        cell.checked = selected
    }

    /** 每格的无障碍描述：类型，拍摄时间，选中状态。 */
    private fun buildContentDescription(item: MediaItem, selected: Boolean): String {
        val type = context.getString(
            if (item.isVideo) R.string.grid_cell_video else R.string.grid_cell_photo,
        )
        val time = DateFormats.formatDateTime(item.dateTaken)
            .ifEmpty { context.getString(R.string.grid_cell_time_unknown) }
        val state = context.getString(
            if (selected) R.string.grid_cell_selected else R.string.grid_cell_unselected,
        )
        return context.getString(R.string.grid_cell_description, type, time, state)
    }

    private fun dp2px(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    private inner class EmptyHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    private inner class CellHolder(view: View) : RecyclerView.ViewHolder(view) {

        val thumb: ImageView = view.findViewById(R.id.media_cell_thumb)
        val duration: TextView = view.findViewById(R.id.media_cell_duration)
        val motion: ImageView = view.findViewById(R.id.media_cell_motion)
        val border: View = view.findViewById(R.id.media_cell_border)
        val check: View = view.findViewById(R.id.media_cell_check)
        val checkIcon: ImageView = view.findViewById(R.id.media_cell_check_icon)

        var checkable = false
        var checked = false

        private val accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfo,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (checkable) {
                    info.isCheckable = true
                    info.isChecked = checked
                }
            }
        }

        init {
            view.accessibilityDelegate = accessibilityDelegate
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onCellClick?.invoke(position)
                }
            }
            view.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onCellLongClick?.invoke(position)
                }
                true
            }
        }
    }

    private companion object {
        const val TYPE_CELL = 0
        const val TYPE_EMPTY = 1

        /** 选中后缩略图略暗，让右上角的选中框与描边更突出。 */
        const val SELECTED_ALPHA = 0.72f

        /** 缩略图解码尺寸下限（dp）：屏幕密度再低也保证清晰度。 */
        const val THUMB_SIZE_BUCKET_DP = 96

        /** 空态项最小高度（dp）：宫格尚未完成测量时的兜底。 */
        const val EMPTY_MIN_HEIGHT_DP = 220
    }
}
