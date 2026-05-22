package com.coordscanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.coordscanner.databinding.BottomSheetConvertFormatBinding
import com.coordscanner.utils.conversion.FileFormat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ConvertBottomSheet : BottomSheetDialogFragment() {

    var onFormatSelected: ((FileFormat) -> Unit)? = null

    private var _b: BottomSheetConvertFormatBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = BottomSheetConvertFormatBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnFormatGpx.setOnClickListener { pick(FileFormat.GPX) }
        b.btnFormatKml.setOnClickListener { pick(FileFormat.KML) }
        b.btnFormatKmz.setOnClickListener { pick(FileFormat.KMZ) }
        b.btnFormatDxf.setOnClickListener { pick(FileFormat.DXF) }
        b.btnCancel.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    private fun pick(fmt: FileFormat) {
        onFormatSelected?.invoke(fmt)
        dismiss()
    }
}
