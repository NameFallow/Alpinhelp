package com.coordscanner

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.coordscanner.adapter.WayPointAdapter
import com.coordscanner.databinding.FragmentNewFileBinding
import com.coordscanner.model.WayPoint
import com.coordscanner.utils.GpxParser
import com.coordscanner.viewmodel.GpxManagerViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

class NewFileFragment : BottomSheetDialogFragment() {

    private var _b: FragmentNewFileBinding? = null
    private val b get() = _b!!
    private val vm: GpxManagerViewModel by activityViewModels()
    private val adapter = WayPointAdapter { wp -> vm.removeFromNewFile(wp) }

    private lateinit var saveFileLauncher: ActivityResultLauncher<String>

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
            uri?.let { saveToUri(it.toString()) }
        }
    }

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
            b.btnSave.isEnabled    = pts.isNotEmpty()
            b.btnShare.isEnabled   = pts.isNotEmpty()
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

        b.btnSave.setOnClickListener {
            askNameAndSave()
        }

        b.btnShare.setOnClickListener {
            shareFile(vm.newFile.value.orEmpty())
        }
    }

    private fun askNameAndSave() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Имя файла"
            setText("markers_${System.currentTimeMillis()}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Сохранить GPX")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "markers" }
                saveFileLauncher.launch("$name.gpx")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun saveToUri(uriStr: String) {
        val pts = vm.newFile.value.orEmpty()
        val uri = android.net.Uri.parse(uriStr)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    GpxParser.write(pts, out.writer())
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Сохранено: ${pts.size} точек", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareFile(pts: List<WayPoint>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(requireContext().cacheDir, "gpx").also { it.mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "export_${System.currentTimeMillis()}.gpx")
                FileWriter(file).use { GpxParser.write(pts, it) }
                val fileUri = FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider", file
                )
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/gpx+xml"
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Поделиться GPX"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
