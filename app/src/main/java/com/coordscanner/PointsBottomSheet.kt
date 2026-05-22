package com.coordscanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import com.coordscanner.databinding.BottomSheetPointsBinding
import com.coordscanner.viewmodel.GpxManagerViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PointsBottomSheet : BottomSheetDialogFragment() {

    private var _b: BottomSheetPointsBinding? = null
    private val b get() = _b!!
    private val vm: GpxManagerViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _b = BottomSheetPointsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        val count = vm.selected.value?.size ?: 0
        b.tvSelectionInfo.text = "Выбрано: $count точек"

        b.btnCut.setOnClickListener {
            vm.cut()
            dismiss()
            (requireActivity() as? GpxManagerActivity)?.promptSaveSourceFile()
        }

        b.btnCopy.setOnClickListener {
            vm.copy()
            dismiss()
        }

        b.btnDelete.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Удалить точки")
                .setMessage("Удалить $count точек без сохранения в буфер?")
                .setPositiveButton("Удалить") { _, _ -> vm.deleteSelected(); dismiss() }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
