package com.coordscanner

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.coordscanner.utils.KmzPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.infowindow.InfoWindow
import java.io.File

class CustomInfoWindow(
    mapView: MapView,
    private val point: KmzPoint
) : InfoWindow(R.layout.info_window_kmz, mapView) {

    override fun onOpen(item: Any?) {
        mView.findViewById<TextView>(R.id.tvPointName).text = point.name

        val ivPhoto = mView.findViewById<ImageView>(R.id.ivPointPhoto)
        if (point.photoPath != null) {
            ivPhoto.visibility = View.VISIBLE
            Glide.with(mMapView.context)
                .load(File(point.photoPath))
                .centerCrop()
                .into(ivPhoto)
        } else {
            ivPhoto.visibility = View.GONE
        }

        mView.findViewById<View>(R.id.btnCloseInfo).setOnClickListener { close() }
    }

    override fun onClose() {
        // Glide освобождает ImageView автоматически при detach
    }
}
