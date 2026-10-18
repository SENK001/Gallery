package com.senk.gallery.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.senk.gallery.data.provider.GalleryCursorReader

/**
 * 相册列表适配器（GridLayoutManager，3 列）。
 *
 * 行的类型：
 * - [Row.Header]：分组标题，占满整行；
 * - [Row.Album]：相册卡。[Row.Album.isCommon] = true 用宫格卡（1 格），false 用整行卡；
 * - [Row.Action]：动作行（如「新建相册」），占满整行。
 *
 * 兼容性约定：构造函数的第一个参数 `onAlbumClick` 与 `Row.Header` / `Row.Album` 的语义保持不变
 * （选图页 `ImagePickerActivity` 仍在用），后两个回调为可选参数，不传时行为与旧版一致。
 */
class AlbumListAdapter(
    private val onAlbumClick: (GalleryCursorReader.AlbumInfo) -> Unit,
    private val onActionClick: ((Row.Action) -> Unit)? = null,
    private val onAlbumLongClick: ((GalleryCursorReader.AlbumInfo) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val title: String) : Row()

        data class Album(
            val info: GalleryCursorReader.AlbumInfo,
            val isCommon: Boolean,
        ) : Row()

        /** 动作行：如「新建相册」。[id] 供调用方分派（标题可能随文案变化），可省略。 */
        data class Action(
            val title: String,
            val iconRes: Int,
            val id: String = "",
        ) : Row()
    }

    private val rows = ArrayList<Row>()

    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    fun spanSize(position: Int): Int {
        val row = rows.getOrNull(position) ?: return 1
        return when {
            row is Row.Header -> SPAN_FULL
            row is Row.Action -> SPAN_FULL
            row is Row.Album && row.isCommon -> 1
            else -> SPAN_FULL
        }
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Action -> TYPE_ACTION
        is Row.Album -> {
            if ((rows[position] as Row.Album).isCommon) TYPE_GRID_ALBUM else TYPE_ROW_ALBUM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_album_header, parent, false))
            TYPE_ACTION -> ActionHolder(inflater.inflate(R.layout.item_add_to_action, parent, false))
            TYPE_GRID_ALBUM -> AlbumGridHolder(inflater.inflate(R.layout.item_album_grid, parent, false))
            else -> AlbumRowHolder(inflater.inflate(R.layout.item_album_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (holder) {
            is HeaderHolder -> holder.bind((row as Row.Header).title)
            is ActionHolder -> holder.bind((row as Row.Action), onActionClick)
            is AlbumGridHolder -> holder.bind(
                (row as Row.Album).info,
                onAlbumClick,
                onAlbumLongClick,
            )
            is AlbumRowHolder -> holder.bind(
                (row as Row.Album).info,
                onAlbumClick,
                onAlbumLongClick,
            )
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.header_title)
        fun bind(text: String) {
            title.text = text
        }
    }

    class ActionHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.addto_action_icon)
        private val title: TextView = itemView.findViewById(R.id.addto_action_title)

        fun bind(action: Row.Action, onClick: ((Row.Action) -> Unit)?) {
            icon.setImageResource(action.iconRes)
            title.text = action.title
            itemView.contentDescription = action.title
            itemView.isClickable = onClick != null
            itemView.setOnClickListener { onClick?.invoke(action) }
        }
    }

    class AlbumGridHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.album_cover)
        private val name: TextView = itemView.findViewById(R.id.album_name)
        private val count: TextView = itemView.findViewById(R.id.album_count)

        fun bind(
            info: GalleryCursorReader.AlbumInfo,
            onClick: (GalleryCursorReader.AlbumInfo) -> Unit,
            onLongClick: ((GalleryCursorReader.AlbumInfo) -> Unit)? = null,
        ) {
            name.text = info.name
            count.text = itemView.context.getString(R.string.gallery_count_format, info.count)
            ImageViewThumbLoader.load(cover, info.coverUri)
            itemView.setOnClickListener { onClick(info) }
            bindLongClick(info, onLongClick)
        }

        private fun bindLongClick(
            info: GalleryCursorReader.AlbumInfo,
            onLongClick: ((GalleryCursorReader.AlbumInfo) -> Unit)?,
        ) {
            if (onLongClick == null) {
                itemView.setOnLongClickListener(null)
                itemView.isLongClickable = false
            } else {
                itemView.setOnLongClickListener {
                    onLongClick.invoke(info)
                    true
                }
            }
        }
    }

    class AlbumRowHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.album_cover)
        private val name: TextView = itemView.findViewById(R.id.album_name)
        private val count: TextView = itemView.findViewById(R.id.album_count)

        fun bind(
            info: GalleryCursorReader.AlbumInfo,
            onClick: (GalleryCursorReader.AlbumInfo) -> Unit,
            onLongClick: ((GalleryCursorReader.AlbumInfo) -> Unit)? = null,
        ) {
            name.text = info.name
            count.text = itemView.context.getString(R.string.gallery_count_format, info.count)
            ImageViewThumbLoader.load(cover, info.coverUri, 160)
            itemView.setOnClickListener { onClick(info) }
            if (onLongClick == null) {
                itemView.setOnLongClickListener(null)
                itemView.isLongClickable = false
            } else {
                itemView.setOnLongClickListener {
                    onLongClick.invoke(info)
                    true
                }
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_GRID_ALBUM = 1
        const val TYPE_ROW_ALBUM = 2
        const val TYPE_ACTION = 3
        const val SPAN_FULL = 3
    }
}
