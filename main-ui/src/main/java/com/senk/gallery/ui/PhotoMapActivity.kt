package com.senk.gallery.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.TextureMapView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.senk.gallery.util.SystemBarUtils

class PhotoMapActivity : AppCompatActivity() {

    private lateinit var mapView: TextureMapView
    private lateinit var mapAddress: TextView
    private var addressResolver: LocationAddressResolver? = null
    private var navApps: List<NavigationHelper.NavApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_photo_map)
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = true)
        val toolbar = findViewById<MaterialToolbar>(R.id.map_toolbar)
        val bottomCard = findViewById<View>(R.id.map_bottom_card)
        val navButton = findViewById<MaterialButton>(R.id.map_nav_button)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.gallery_map_title)
        mapView = findViewById(R.id.photo_map)
        mapView.showZoomControls(false)
        val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
        val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
        val bd09 = LocationAddressResolver.toBd09(latitude, longitude)
        mapView.map.addOverlay(MarkerOptions().position(bd09).icon(MapMarkers.dot(this)))
        mapView.map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(bd09, MAP_ZOOM))
        mapAddress = findViewById(R.id.map_address)
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        if (address.isNullOrBlank()) {
            mapAddress.text = getString(R.string.gallery_info_address_loading)
            val resolver = LocationAddressResolver(this).also { addressResolver = it }
            resolver.resolve(latitude, longitude) { resolved ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        mapAddress.text =
                            resolved ?: getString(R.string.gallery_info_address_failed)
                    }
                }
            }
        } else {
            mapAddress.text = address
        }
        navApps = NavigationHelper.navigationApps(this, latitude, longitude)
        navButton.isVisible = navApps.isNotEmpty()
        navButton.setOnClickListener { showNavigationChooser() }
        applyInsets(toolbar, bottomCard)
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        addressResolver?.release()
        addressResolver = null
        mapView.onDestroy()
        super.onDestroy()
    }

    private fun applyInsets(toolbar: MaterialToolbar, bottomCard: View) {
        val baseBottom = bottomCard.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.photo_map_root),
        ) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(0, bars.top, 0, 0)
            bottomCard.setPadding(
                bottomCard.paddingLeft,
                bottomCard.paddingTop,
                bottomCard.paddingRight,
                baseBottom + bars.bottom,
            )
            insets
        }
    }

    private fun showNavigationChooser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.gallery_nav_choose)
            .setItems(navApps.map { it.label }.toTypedArray()) { _, which ->
                NavigationHelper.start(this, navApps[which])
            }
            .show()
    }

    companion object {

        private const val EXTRA_LATITUDE = "latitude"
        private const val EXTRA_LONGITUDE = "longitude"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_NAME = "name"
        private const val MAP_ZOOM = 16f

        fun intent(
            context: Context,
            latitude: Double,
            longitude: Double,
            address: String?,
            name: String?,
        ): Intent =
            Intent(context, PhotoMapActivity::class.java)
                .putExtra(EXTRA_LATITUDE, latitude)
                .putExtra(EXTRA_LONGITUDE, longitude)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_NAME, name)
    }
}
