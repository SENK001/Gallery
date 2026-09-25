package com.senk.gallery.data.pojo;

import com.senk.gallery.data.entity.MediaSet;

public class FavoriteAlbum extends MediaSet {

    public FavoriteAlbum() {
        setAlbumType(TYPE_FAVORITE);
    }
}
