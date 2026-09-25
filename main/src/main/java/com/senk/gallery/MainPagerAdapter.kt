package com.senk.gallery

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.senk.gallery.ui.AlbumsFragment
import com.senk.gallery.ui.PhotosFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = PAGE_COUNT

    override fun createFragment(position: Int): Fragment =
        if (position == 0) PhotosFragment() else AlbumsFragment()

    companion object {
        const val PAGE_COUNT = 2
    }
}
