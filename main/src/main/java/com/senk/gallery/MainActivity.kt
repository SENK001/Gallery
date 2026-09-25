package com.senk.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.senk.gallery.ui.AlbumsFragment
import com.senk.gallery.ui.PhotosFragment
import com.senk.gallery.util.SystemBarUtils

class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var permissionView: View
    private lateinit var navPhotos: View
    private lateinit var navAlbums: View
    private var requestCount = 0
    private var pendingSettingsCheck = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        requestCount++
        updatePermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = true)
        setContentView(R.layout.activity_main)
        val main = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        pager = findViewById(R.id.pager)
        permissionView = findViewById(R.id.permission_view)
        navPhotos = findViewById(R.id.nav_photos)
        navAlbums = findViewById(R.id.nav_albums)
        pager.adapter = MainPagerAdapter(this)
        pager.isUserInputEnabled = false
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateNavSelection(position)
            }
        })
        navPhotos.setOnClickListener { selectPage(0) }
        navAlbums.setOnClickListener { selectPage(1) }
        updateNavSelection(0)
        findViewById<View>(R.id.permission_button).setOnClickListener {
            if (requestCount >= 2 && !hasMediaPermission()) {
                openAppSettings()
            } else {
                requestPermissions()
            }
        }
        updatePermissionState()
        if (!hasMediaPermission()) {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingSettingsCheck) {
            pendingSettingsCheck = false
            updatePermissionState()
        }
    }

    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            ),
        )
    }

    private fun selectPage(position: Int) {
        if (pager.currentItem != position) {
            pager.setCurrentItem(position, true)
        } else {
            updateNavSelection(position)
        }
    }

    private fun updateNavSelection(position: Int) {
        navPhotos.isSelected = position == 0
        navAlbums.isSelected = position == 1
    }

    private fun updatePermissionState() {
        val granted = hasMediaPermission()
        permissionView.isVisible = !granted
        pager.isVisible = granted
        findViewById<View>(R.id.bottom_nav).isVisible = granted
        if (granted) {
            refreshFragments()
        }
    }

    private fun refreshFragments() {
        supportFragmentManager.fragments.forEach { fragment ->
            when (fragment) {
                is PhotosFragment -> fragment.loadPage(reset = true)
                is AlbumsFragment -> fragment.refresh()
            }
        }
    }

    private fun openAppSettings() {
        pendingSettingsCheck = true
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}
