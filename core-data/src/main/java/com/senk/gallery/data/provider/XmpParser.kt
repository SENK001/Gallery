package com.senk.gallery.data.provider

object XmpParser {

    private val MOTION_MARKERS = arrayOf(
        "MotionPhoto",
        "MicroVideo",
        "MotionPhotoVersion",
        "MicroVideoVersion",
    )

    private val PANORAMA_MARKERS = arrayOf(
        "GPano:ProjectionType",
        "GPano:UsePanoramaViewer",
        "GPano:CroppedAreaImageWidthPixels",
    )

    private val CONTAINER_ITEM = Regex("<Container:Item\\b.*?/?>", RegexOption.DOT_MATCHES_ALL)

    private val ITEM_LENGTH = Regex("Item:Length=\"(\\d+)\"")

    private val MICRO_VIDEO_OFFSET = Regex("(?:GCamera|Camera):MicroVideoOffset=\"(\\d+)\"")

    fun isMotionPhoto(xmp: String?): Boolean {
        if (xmp.isNullOrEmpty()) {
            return false
        }
        return MOTION_MARKERS.any { xmp.contains(it) }
    }

    /**
     * Length in bytes of the video embedded at the tail of a motion photo file.
     * Container format (newer): the `Container:Item` whose mime is `video/mp4`
     * carries `Item:Length`. Legacy format: `GCamera:MicroVideoOffset` (distance
     * from EOF to the video start == video length). Returns 0 when unknown.
     */
    fun motionVideoLength(xmp: String?): Long {
        if (xmp.isNullOrEmpty()) {
            return 0L
        }
        for (match in CONTAINER_ITEM.findAll(xmp)) {
            val item = match.value
            if (!item.contains("Item:Mime=\"video/") && !item.contains("Semantic=\"MotionPhoto\"")) {
                continue
            }
            val length = ITEM_LENGTH.find(item)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            if (length > 0L) {
                return length
            }
        }
        val offset = MICRO_VIDEO_OFFSET.find(xmp)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return if (offset > 0L) offset else 0L
    }

    fun isPanorama(xmp: String?): Boolean {
        if (xmp.isNullOrEmpty()) {
            return false
        }
        return PANORAMA_MARKERS.any { xmp.contains(it) }
    }

    /**
     * Fallback heuristic for panoramas missing XMP metadata: very wide captured images.
     */
    fun isPanoramaBySize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) {
            return false
        }
        return width >= 6000 && width >= height * 3
    }
}
