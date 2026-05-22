package com.coordscanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.coordscanner.databinding.BottomSheetSaveBinding
import com.coordscanner.utils.FileSaveHelper
import com.coordscanner.utils.SaveFormat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SaveBottomSheet : BottomSheetDialogFragment() {

    // Коллбэк: список форматов для сохранения + имя файла (уже санитизированное)
    var onSave: ((formats: List<SaveFormat>, fileName: String) -> Unit)? = null

    private var _b: BottomSheetSaveBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _b = BottomSheetSaveBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        // Дефолтное имя: "Метки_22.05.26"
        b.etFileName.setText(FileSaveHelper.defaultFileName())

        // KMZ выбран по умолчанию через XML (android:checked="true" на rbKmz)

        // Чекбокс "все форматы" — блокирует RadioGroup
        b.cbSaveAll.setOnCheckedChangeListener { _, checked ->
            b.rgFormat.isEnabled = !checked
            for (i in 0 until b.rgFormat.childCount) {
                b.rgFormat.getChildAt(i).isEnabled = !checked
            }
        }

        b.btnSave.setOnClickListener {
            val rawName = b.etFileName.text?.toString().orEmpty()
            val fileName = FileSaveHelper.sanitizeFileName(rawName)

            val formats: List<SaveFormat> = if (b.cbSaveAll.isChecked) {
                SaveFormat.values().toList()
            } else {
                listOf(
                    when (b.rgFormat.checkedRadioButtonId) {
                        R.id.rbGpx -> SaveFormat.GPX
                        R.id.rbKml -> SaveFormat.KML
                        R.id.rbDxf -> SaveFormat.DXF
                        else       -> SaveFormat.KMZ   // rbKmz или ничего → KMZ
                    }
                )
            }

            onSave?.invoke(formats, fileName)
            dismiss()
        }

        b.btnCancel.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
