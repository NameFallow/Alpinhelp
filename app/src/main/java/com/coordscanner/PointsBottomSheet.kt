package com.coordscanner

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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

        // Перекрасить выделенные точки цветом из палитры.
        b.btnColor.setOnClickListener {
            ColorPickerDialog.showPalette(requireContext(), commonSelectedColor(), "Цвет для $count точек") { hex ->
                val n = vm.recolorSelected(hex)
                Toast.makeText(requireContext(), "Цвет изменён у $n точек", Toast.LENGTH_SHORT).show()
                // Снимаем выделение и закрываем шторку: новый цвет сразу виден на карте,
                // можно выделять следующую группу.
                vm.clearSelection()
                dismiss()
            }
        }
        commonSelectedColor()?.let { tintColorButton(it) }

        b.btnDelete.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Удалить точки")
                .setMessage("Удалить $count точек без сохранения в буфер?")
                .setPositiveButton("Удалить") { _, _ -> vm.deleteSelected(); dismiss() }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    /** Общий цвет выделенных точек, либо null если цвета разные. */
    private fun commonSelectedColor(): String? =
        vm.selected.value.orEmpty().map { it.color }.distinct().singleOrNull()

    /** Кружок на кнопке «Изменить цвет» показывает текущий цвет выделенных точек. */
    private fun tintColorButton(hex: String) {
        val c = try { Color.parseColor(hex) } catch (_: Exception) { return }
        b.btnColor.iconTint = ColorStateList.valueOf(c)
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
