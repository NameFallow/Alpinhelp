package com.coordscanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.coordscanner.databinding.BottomSheetLayerSwitcherBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

class LayerSwitcherBottomSheet : BottomSheetDialogFragment() {

    interface OnLayerSelectedListener {
        fun onLayerSelected(source: ITileSource)
    }

    var listener: OnLayerSelectedListener? = null

    private var _b: BottomSheetLayerSwitcherBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = BottomSheetLayerSwitcherBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnLayerOsm.setOnClickListener {
            listener?.onLayerSelected(TileSourceFactory.MAPNIK)
            dismiss()
        }
        b.btnLayerSatellite.setOnClickListener {
            listener?.onLayerSelected(GOOGLE_SATELLITE)
            dismiss()
        }
        b.btnLayerHybrid.setOnClickListener {
            listener?.onLayerSelected(GOOGLE_HYBRID)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        val GOOGLE_SATELLITE: OnlineTileSourceBase = object : OnlineTileSourceBase(
            "Google Satellite", 0, 20, 256, ".png",
            arrayOf("https://mt0.google.com", "https://mt1.google.com",
                    "https://mt2.google.com", "https://mt3.google.com")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                "${baseUrl}/vt/lyrs=s" +
                "&x=${MapTileIndex.getX(pMapTileIndex)}" +
                "&y=${MapTileIndex.getY(pMapTileIndex)}" +
                "&z=${MapTileIndex.getZoom(pMapTileIndex)}"
        }

        val GOOGLE_HYBRID: OnlineTileSourceBase = object : OnlineTileSourceBase(
            "Google Hybrid", 0, 20, 256, ".png",
            arrayOf("https://mt0.google.com", "https://mt1.google.com",
                    "https://mt2.google.com", "https://mt3.google.com")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                "${baseUrl}/vt/lyrs=y" +
                "&x=${MapTileIndex.getX(pMapTileIndex)}" +
                "&y=${MapTileIndex.getY(pMapTileIndex)}" +
                "&z=${MapTileIndex.getZoom(pMapTileIndex)}"
        }
    }
}
