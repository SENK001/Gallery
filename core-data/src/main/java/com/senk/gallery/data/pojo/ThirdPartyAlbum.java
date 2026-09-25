package com.senk.gallery.data.pojo;

import com.senk.gallery.data.entity.MediaSet;

public class ThirdPartyAlbum extends MediaSet {

    private String packageName;
    private String appLabel;

    public ThirdPartyAlbum() {
        setAlbumType(TYPE_THIRD_PARTY);
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getAppLabel() {
        return appLabel;
    }

    public void setAppLabel(String appLabel) {
        this.appLabel = appLabel;
    }
}
