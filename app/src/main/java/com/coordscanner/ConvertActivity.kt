package com.coordscanner

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.coordscanner.adapter.ConvertFileAdapter
import com.coordscanner.databinding.ActivityConvertBinding
import com.coordscanner.utils.FilePickerHelper
import com.coordscanner.utils.conversion.ConversionManager
import com.coordscanner.utils.conversion.FileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConvertActivity : AppCompatActivity() {

    private lateinit var b: ActivityConvertBinding
    private lateinit var adapter: ConvertFileAdapter
    private lateinit var filePickerHelper: FilePickerHelper
    private lateinit var conversionManager: ConversionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityConvertBinding.inflate(layoutInflater)
        setContentView(b.root)

        conversionManager = ConversionManager(this)
        filePickerHelper  = FilePickerHelper(this) { loadFiles() }

        adapter = ConvertFileAdapter { updateSelectionUi() }

        b.rvFiles.layoutManager = LinearLayoutManager(this)
        b.rvFiles.adapter       = adapter

        b.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        b.btnConvert.setOnClickListener {
            val selected = adapter.getSelected()
            if (selected.isEmpty()) return@setOnClickListener
            ConvertBottomSheet().also { sheet ->
                sheet.onFormatSelected = { fmt -> startConversion(selected, fmt) }
                sheet.show(supportFragmentManager, "convert_fmt")
            }
        }

        loadFiles()
    }

    private fun loadFiles() {
        val folder = filePickerHelper.getSavedFolder()
        if (folder == null) {
            showEmpty("Папка не выбрана.\nОткройте GPX Менеджер и выберите папку с файлами.")
            return
        }

        val files = folder.listFiles()
            ?.filter { doc ->
                doc.name?.let { n ->
                    n.endsWith(".gpx", ignoreCase = true) ||
                    n.endsWith(".kml", ignoreCase = true) ||
                    n.endsWith(".kmz", ignoreCase = true) ||
                    n.endsWith(".dxf", ignoreCase = true)
                } == true
            }
            .orEmpty()

        if (files.isEmpty()) {
            showEmpty("В папке нет файлов GPX / KML / KMZ / DXF.\nДобавьте файлы в папку и обновите.")
        } else {
            b.emptyState.visibility = View.GONE
            b.rvFiles.visibility    = View.VISIBLE
            adapter.submitList(files)
        }
        updateSelectionUi()
    }

    private fun showEmpty(hint: String) {
        b.emptyState.visibility = View.VISIBLE
        b.tvEmptyHint.text      = hint
        b.rvFiles.visibility    = View.GONE
    }

    // --- Конвертация ---

    private fun startConversion(files: List<DocumentFile>, fmt: FileFormat) {
        val folder = filePickerHelper.getSavedFolder() ?: return

        // Проверяем, какие выходные файлы уже существуют
        val conflicts = files.mapNotNull { doc ->
            val outName = conversionManager.outputName(doc.name ?: return@mapNotNull null, fmt)
            if (folder.findFile(outName) != null) outName else null
        }

        if (conflicts.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Файлы уже существуют")
                .setMessage("Следующие файлы будут перезаписаны:\n\n${conflicts.joinToString("\n")}")
                .setPositiveButton("Перезаписать") { _, _ -> doConvert(files, fmt, folder, overwrite = true) }
                .setNegativeButton("Отмена", null)
                .show()
        } else {
            doConvert(files, fmt, folder, overwrite = false)
        }
    }

    private fun doConvert(
        files: List<DocumentFile>,
        fmt: FileFormat,
        folder: DocumentFile,
        overwrite: Boolean
    ) {
        val progressView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val progressText = progressView.findViewById<TextView>(android.R.id.text1).apply {
            setPadding(60, 40, 60, 40)
        }
        val progressDialog = AlertDialog.Builder(this)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val converted  = mutableListOf<DocumentFile>()
            var errorCount = 0

            files.forEachIndexed { idx, doc ->
                withContext(Dispatchers.Main) {
                    progressText.text = "Конвертация... ${idx + 1}/${files.size}\n${doc.name}"
                }

                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val sourceName = doc.name ?: return@runCatching null
                        val outName  = conversionManager.outputName(sourceName, fmt)
                        val data     = conversionManager.convert(doc, fmt)
                        val existing = folder.findFile(outName)
                        val target   = if (overwrite && existing != null) {
                            existing
                        } else {
                            folder.createFile(fmt.mimeType, outName)
                                ?: return@runCatching null
                        }
                        contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(data) }
                        target
                    }.getOrNull()
                }

                if (result != null) converted.add(result) else errorCount++
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()

                if (errorCount > 0) toast("Ошибок конвертации: $errorCount")

                if (converted.isNotEmpty()) {
                    ConversionResultDialog.show(
                        this@ConvertActivity, converted, fmt.label
                    ) {
                        adapter.clearSelection()
                    }
                }
            }
        }
    }

    // --- UI ---

    private fun updateSelectionUi() {
        val count = adapter.getSelected().size
        if (count > 0) {
            b.tvSelectedCount.visibility = View.VISIBLE
            b.tvSelectedCount.text       = "Выбрано: $count ${plural(count)}"
            b.btnConvert.isEnabled       = true
        } else {
            b.tvSelectedCount.visibility = View.GONE
            b.btnConvert.isEnabled       = false
        }
    }

    private fun plural(n: Int) = when {
        n % 10 == 1 && n % 100 != 11          -> "файл"
        n % 10 in 2..4 && n % 100 !in 12..14  -> "файла"
        else                                   -> "файлов"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
