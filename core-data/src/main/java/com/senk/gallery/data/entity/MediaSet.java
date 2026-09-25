package com.senk.gallery.data.entity;

import java.util.ArrayList;
import java.util.List;

public class MediaSet extends MediaObject {

    public static final String TYPE_ALL = "all";
    public static final String TYPE_CAMERA = "camera";
    public static final String TYPE_VIDEO = "video";
    public static final String TYPE_SCREENSHOT = "screenshot";
    public static final String TYPE_SCREEN_RECORD = "screen_record";
    public static final String TYPE_LIVE_PHOTO = "live";
    public static final String TYPE_FAVORITE = "favorite";
    public static final String TYPE_PANORAMA = "panorama";
    public static final String TYPE_FOLDER = "folder";
    public static final String TYPE_THIRD_PARTY = "third_party";
    public static final String TYPE_RECENTLY_DELETED = "recently_deleted";

    private String albumType = TYPE_ALL;
    private String coverUri;
    private int count;
    private List<MediaItem> data = new ArrayList<>();

    public String getAlbumType() {
        return albumType;
    }

    public void setAlbumType(String albumType) {
        this.albumType = albumType;
    }

    public String getCoverUri() {
        return coverUri;
    }

    public void setCoverUri(String coverUri) {
        this.coverUri = coverUri;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public List<MediaItem> getData() {
        return data;
    }

    public void setData(List<MediaItem> data) {
        this.data = data == null ? new ArrayList<>() : data;
    }
}
