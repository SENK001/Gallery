package com.senk.gallery.data.pojo;

import com.senk.gallery.data.entity.MediaItem;

public class LocalVideo extends MediaItem {

    public LocalVideo() {
        setMediaType(TYPE_VIDEO);
    }

    public static LocalVideo from(MediaItem item) {
        LocalVideo video = new LocalVideo();
        video.copyFrom(item);
        video.setMediaType(TYPE_VIDEO);
        return video;
    }
}
