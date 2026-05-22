package com.coordscanner

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.coordscanner.adapter.WayPointAdapter
import com.coordscanner.databinding.FragmentNewFileBinding
import com.coordscanner.utils.FileSaveHelper
import com.coordscanner.utils.SaveFormat
import com.coordscanner.viewmodel.GpxManagerViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NewFileFragment : BottomSheetDialogFragment() {

    private var _b: FragmentNewFileBinding? = null
    private val b get() = _b!!
    private val vm: GpxManagerViewModel by activityViewModels()
    private val adapter = WayPointAdapter(
        onRemove = { wp -> vm.removeFromNewFile(wp) },
        onPhotoClick = { path -> showPhotoPreview(path) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _b = FragmentNewFileBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        b.rvPoints.layoutManager = LinearLayoutManager(requireContext())
        b.rvPoints.adapter = adapter

        vm.newFile.observe(viewLifecycleOwner) { pts ->
            adapter.submitList(pts.toList())
            b.tvCount.text = "Точек в файле: ${pts.size}"
            b.btnSave.isEnabled  = pts.isNotEmpty()
            b.btnShare.isEnabled = pts.isNotEmpty()
        }

        vm.buffer.observe(viewLifecycleOwner) { buf ->
            b.btnPaste.isEnabled = buf.isNotEmpty()
            b.tvBuffer.text = "Буфер: ${buf.size}"
        }

        b.btnPaste.setOnClickListener {
            vm.pasteToNewFile()
            Toast.makeText(requireContext(), "Вставлено ${vm.buffer.value?.size ?: 0} точек", Toast.LENGTH_SHORT).show()
        }

        b.btnClear.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Очистить файл")
                .setMessage("Удалить все точки из нового файла?")
                .setPositiveButton("Очистить") { _, _ -> vm.clearNewFile() }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // Кнопка "Сохранить" — открывает BottomSheet выбора формата
        b.btnSave.setOnClickListener {
            val pts = vm.newFile.value.orEmpty()
            if (pts.isEmpty()) return@setOnClickListener

            SaveBottomSheet().also { sheet ->
                sheet.onSave = { formats, fileName ->
                    val ctx = requireContext()
                    lifecycleScope.launch(Dispatchers.IO) {
                        val results = try {
                            if (formats.size > 1) {
                                FileSaveHelper.saveAll(ctx, pts, fileName)
                            } else {
                                val fmt = formats.first()
                                listOf(FileSaveHelper.saveToDownloads(
                                    ctx, pts, "$fileName${fmt.ext}", fmt
                                ))
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }
                        withContext(Dispatchers.Main) {
                            SaveResultDialog.show(ctx, results)
                        }
                    }
                }
                sheet.show(parentFragmentManager, "save_fmt")
            }
        }

        // Кнопка "Поделиться" — отправить KMZ через intent
        b.btnShare.setOnClickListener {
            shareAsKmz(vm.newFile.value.orEmpty())
        }
    }

    private fun shareAsKmz(pts: List<com.coordscanner.model.WayPoint>) {
        if (pts.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(requireContext().cacheDir, "gpx").also { it.mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val fileName = FileSaveHelper.defaultFileName() + ".kmz"
                val file = File(dir, fileName)

                file.outputStream().use { fos ->
                    java.io.BufferedOutputStream(fos).use { bos ->
                        FileSaveHelper.getExporter(SaveFormat.KMZ).export(pts, bos)
                        bos.flush()
                    }
                }

                val fileUri = FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider", file
                )
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = SaveFormat.KMZ.mimeType
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Поделиться KMZ"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPhotoPreview(photoPath: String) {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_photo_preview, null)
        Glide.with(this).load(File(photoPath)).into(view.findViewById<ImageView>(R.id.ivPhoto))
        AlertDialog.Builder(requireContext())
            .setView(view)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
