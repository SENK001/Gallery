package com.senk.gallery.data.pojo;

import com.senk.gallery.data.entity.MediaItem;

public class LocalImage extends MediaItem {

    public LocalImage() {
        setMediaType(TYPE_IMAGE);
    }

    public static LocalImage from(MediaItem item) {
        LocalImage image = new LocalImage();
        image.copyFrom(item);
        image.setMediaType(TYPE_IMAGE);
        return image;
    }
}
