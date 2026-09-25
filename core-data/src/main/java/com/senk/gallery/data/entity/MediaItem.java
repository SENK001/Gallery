package com.senk.gallery.data.entity;

import com.senk.gallery.data.pojo.ExifData;

public class MediaItem extends MediaObject {

    private int mediaType = TYPE_UNKNOWN;
    private long dateTaken;
    private int width;
    private int height;
    private long duration;
    private int orientation;
    private boolean favorite;
    private boolean trashed;
    private boolean pending;
    private boolean motionPhoto;
    private long motionVideoLength;
    private boolean panorama;
    private String bucketId;
    private String bucketName;
    private String relativePath;
    private String ownerPackage;
    private ExifData exif;

    public int getMediaType() {
        return mediaType;
    }

    public void setMediaType(int mediaType) {
        this.mediaType = mediaType;
    }

    public boolean isVideo() {
        return mediaType == TYPE_VIDEO;
    }

    public long getDateTaken() {
        return dateTaken;
    }

    public void setDateTaken(long dateTaken) {
        this.dateTaken = dateTaken;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public int getOrientation() {
        return orientation;
    }

    public void setOrientation(int orientation) {
        this.orientation = orientation;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public boolean isTrashed() {
        return trashed;
    }

    public void setTrashed(boolean trashed) {
        this.trashed = trashed;
    }

    public boolean isPending() {
        return pending;
    }

    public void setPending(boolean pending) {
        this.pending = pending;
    }

    public boolean isMotionPhoto() {
        return motionPhoto;
    }

    public void setMotionPhoto(boolean motionPhoto) {
        this.motionPhoto = motionPhoto;
    }

    public long getMotionVideoLength() {
        return motionVideoLength;
    }

    public void setMotionVideoLength(long motionVideoLength) {
        this.motionVideoLength = motionVideoLength;
    }

    public boolean isPanorama() {
        return panorama;
    }

    public void setPanorama(boolean panorama) {
        this.panorama = panorama;
    }

    public String getBucketId() {
        return bucketId;
    }

    public void setBucketId(String bucketId) {
        this.bucketId = bucketId;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getOwnerPackage() {
        return ownerPackage;
    }

    public void setOwnerPackage(String ownerPackage) {
        this.ownerPackage = ownerPackage;
    }

    public ExifData getExif() {
        return exif;
    }

    public void setExif(ExifData exif) {
        this.exif = exif;
    }

    public void copyFrom(MediaItem other) {
        if (other == null) {
            return;
        }
        setId(other.getId());
        setUri(other.getUri());
        setName(other.getName());
        setMimeType(other.getMimeType());
        setSize(other.getSize());
        setDateModified(other.getDateModified());
        mediaType = other.mediaType;
        dateTaken = other.dateTaken;
        width = other.width;
        height = other.height;
        duration = other.duration;
        orientation = other.orientation;
        favorite = other.favorite;
        trashed = other.trashed;
        pending = other.pending;
        motionPhoto = other.motionPhoto;
        motionVideoLength = other.motionVideoLength;
        panorama = other.panorama;
        bucketId = other.bucketId;
        bucketName = other.bucketName;
        relativePath = other.relativePath;
        ownerPackage = other.ownerPackage;
        exif = other.exif;
    }
}
