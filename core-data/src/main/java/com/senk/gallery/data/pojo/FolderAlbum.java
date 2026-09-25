package com.senk.gallery.data.pojo;

import com.senk.gallery.data.entity.MediaSet;

public class FolderAlbum extends MediaSet {

    private String bucketId;
    private String folderPath;

    public FolderAlbum() {
        setAlbumType(TYPE_FOLDER);
    }

    public String getBucketId() {
        return bucketId;
    }

    public void setBucketId(String bucketId) {
        this.bucketId = bucketId;
    }

    public String getFolderPath() {
        return folderPath;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }
}
