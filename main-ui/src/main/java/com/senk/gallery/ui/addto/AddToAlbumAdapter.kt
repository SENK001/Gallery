package com.senk.gallery.ui.addto

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.senk.gallery.data.provider.GalleryCursorReader
import com.senk.gallery.ui.ImageViewThumbLoader
import com.senk.gallery.ui.R
import com.senk.gallery.ui.theme.ThemeUtils

/**
 * 「添加到」目标选择页的列表适配器。
 *
 * 列表结构（分组由 Activity 组装）：
 * - 分节标题（复用 `item_album_header`）；
 * - 动作行（「新建相册」）；
 * - 自建相册行：点击 = 逻辑加入（不复制文件）；
 * - 文件夹相册行：点击 = 物理复制到该目录（相机行用相机图标，目录相册用封面）。
 *
 * 每一行的 `contentDescription` 都带「加入 / 复制」语义，读屏能区分两种目标类型。
 */
class AddToAlbumAdapter(
    private val onAction: (Row.Action) -> Unit,
    private val onCustomAlbumClick: (GalleryCursorReader.AlbumInfo) -> Unit,
    private val onFolderAlbumClick: (GalleryCursorReader.AlbumInfo, Boolean) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val title: String) : Row()
        data class Caption(val text: String) : Row()
        data class Action(val id: String, val title: String, val iconRes: Int) : Row()

        /** 自建相册：逻辑加入。 */
        data class CustomAlbum(val info: GalleryCursorReader.AlbumInfo) : Row()

        /** 相机 / 目录相册：物理复制；[isCamera] 决定图标与目标路径。 */
        data class FolderAlbum(
            val info: GalleryCursorReader.AlbumInfo,
            val isCamera: Boolean,
        ) : Row()
    }

    private val rows = ArrayList<Row>()

    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Caption -> TYPE_CAPTION
        is Row.Action -> TYPE_ACTION
        is Row.CustomAlbum, is Row.FolderAlbum -> TYPE_ALBUM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_album_header, parent, false))
            TYPE_CAPTION -> CaptionHolder(inflater.inflate(R.layout.item_add_to_caption, parent, false))
            TYPE_ACTION -> ActionHolder(inflater.inflate(R.layout.item_add_to_action, parent, false))
            else -> AlbumHolder(inflater.inflate(R.layout.item_add_to_album, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> holder.bind((rows[position] as Row.Header).title)
            is CaptionHolder -> holder.bind((rows[position] as Row.Caption).text)
            is ActionHolder -> {
                val row = rows[position] as Row.Action
                holder.bind(row) { onAction(row) }
            }
            is AlbumHolder -> {
                when (val row = rows[position]) {
                    is Row.CustomAlbum -> holder.bind(row.info, isCamera = false) {
                        onCustomAlbumClick(row.info)
                    }
                    is Row.FolderAlbum -> holder.bind(row.info, isCamera = row.isCamera) {
                        onFolderAlbumClick(row.info, row.isCamera)
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    private class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.header_title)
        fun bind(text: String) {
            title.text = text
        }
    }

    private class CaptionHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val caption: TextView = itemView.findViewById(R.id.addto_caption)
        fun bind(text: String) {
            caption.text = text
        }
    }

    private class ActionHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.addto_action_icon)
        private val title: TextView = itemView.findViewById(R.id.addto_action_title)

        fun bind(action: Row.Action, onClick: () -> Unit) {
            icon.setImageResource(action.iconRes)
            title.text = action.title
            itemView.contentDescription = action.title
            itemView.setOnClickListener { onClick() }
        }
    }

    private class AlbumHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.addto_album_cover)
        private val name: TextView = itemView.findViewById(R.id.addto_album_name)
        private val subtitle: TextView = itemView.findViewById(R.id.addto_album_subtitle)

        fun bind(
            info: GalleryCursorReader.AlbumInfo,
            isCamera: Boolean,
            onClick: () -> Unit,
        ) {
            name.text = info.name
            val context = itemView.context
            if (isCamera) {
                subtitle.text = context.getString(R.string.addto_album_meta_copy, info.count)
                itemView.contentDescription =
                    context.getString(R.string.addto_cd_copy_to_folder, info.name)
                bindIconCover(R.drawable.album_ic_camera)
            } else if (info.albumType == com.senk.gallery.data.entity.MediaSet.TYPE_CUSTOM) {
                subtitle.text = context.getString(R.string.addto_album_meta_custom, info.count)
                itemView.contentDescription =
                    context.getString(R.string.addto_cd_add_to_custom, info.name)
                bindThumbnailCover(info.coverUri)
            } else {
                subtitle.text = context.getString(R.string.addto_album_meta_copy, info.count)
                itemView.contentDescription =
                    context.getString(R.string.addto_cd_copy_to_folder, info.name)
                bindThumbnailCover(info.coverUri)
            }
            itemView.setOnClickListener { onClick() }
        }

        private fun bindThumbnailCover(coverUri: String?) {
            cover.imageTintList = null
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            ImageViewThumbLoader.load(cover, coverUri, 160)
        }

        private fun bindIconCover(iconRes: Int) {
            cover.imageTintList = null
            cover.scaleType = ImageView.ScaleType.CENTER_INSIDE
            cover.setImageResource(iconRes)
            cover.setBackgroundColor(ThemeUtils.surfacePlaceholder(cover.context))
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CAPTION = 1
        const val TYPE_ACTION = 2
        const val TYPE_ALBUM = 3
    }
}
