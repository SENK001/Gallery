package com.senk.gallery.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.senk.gallery.data.provider.GalleryCursorReader

class AlbumListAdapter(
    private val onAlbumClick: (GalleryCursorReader.AlbumInfo) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val title: String) : Row()
        data class Album(
            val info: GalleryCursorReader.AlbumInfo,
            val isCommon: Boolean,
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
            row is Row.Album && row.isCommon -> 1
            else -> SPAN_FULL
        }
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Album -> {
            if ((rows[position] as Row.Album).isCommon) TYPE_GRID_ALBUM else TYPE_ROW_ALBUM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_album_header, parent, false))
            TYPE_GRID_ALBUM -> AlbumGridHolder(inflater.inflate(R.layout.item_album_grid, parent, false))
            else -> AlbumRowHolder(inflater.inflate(R.layout.item_album_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (holder) {
            is HeaderHolder -> holder.bind((row as Row.Header).title)
            is AlbumGridHolder -> holder.bind((row as Row.Album).info, onAlbumClick)
            is AlbumRowHolder -> holder.bind((row as Row.Album).info, onAlbumClick)
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.header_title)
        fun bind(text: String) {
            title.text = text
        }
    }

    class AlbumGridHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.album_cover)
        private val name: TextView = itemView.findViewById(R.id.album_name)
        private val count: TextView = itemView.findViewById(R.id.album_count)

        fun bind(
            info: GalleryCursorReader.AlbumInfo,
            onClick: (GalleryCursorReader.AlbumInfo) -> Unit,
        ) {
            name.text = info.name
            count.text = itemView.context.getString(R.string.gallery_count_format, info.count)
            ImageViewThumbLoader.load(cover, info.coverUri)
            itemView.setOnClickListener { onClick(info) }
        }
    }

    class AlbumRowHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ShapeableImageView = itemView.findViewById(R.id.album_cover)
        private val name: TextView = itemView.findViewById(R.id.album_name)
        private val count: TextView = itemView.findViewById(R.id.album_count)

        fun bind(
            info: GalleryCursorReader.AlbumInfo,
            onClick: (GalleryCursorReader.AlbumInfo) -> Unit,
        ) {
            name.text = info.name
            count.text = itemView.context.getString(R.string.gallery_count_format, info.count)
            ImageViewThumbLoader.load(cover, info.coverUri, 160)
            itemView.setOnClickListener { onClick(info) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_GRID_ALBUM = 1
        const val TYPE_ROW_ALBUM = 2
        const val SPAN_FULL = 3
    }
}
