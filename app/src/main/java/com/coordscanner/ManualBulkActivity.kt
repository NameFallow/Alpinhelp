package com.coordscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.coordscanner.adapter.ManualBulkAdapter
import com.coordscanner.databinding.ActivityManualBulkBinding
import com.coordscanner.model.Point
import com.coordscanner.model.WayPoint
import com.coordscanner.utils.CoordConverter
import com.coordscanner.utils.CoordsPrefs
import com.coordscanner.utils.CoordsPrefs.SK42
import com.coordscanner.utils.CoordsPrefs.WGS84
import com.coordscanner.utils.IconManager
import com.coordscanner.utils.conversion.KmzConversionExporter
import com.coordscanner.viewmodel.PointViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ManualBulkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManualBulkBinding
    private val viewModel: PointViewModel by viewModels()
    private lateinit var iconManager: IconManager
    private lateinit var adapter: ManualBulkAdapter

    data class Row(
        var name: String,
        var xRaw: String,
        var yRaw: String,
        var lat: Double = Double.NaN,
        var lon: Double = Double.NaN,
        var icon: String? = null,
        var error: String? = null
    )

    private val rows = mutableListOf<Row>()
    private var commonIcon: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualBulkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Ручной ввод таблицей"

        iconManager = IconManager(this)

        adapter = ManualBulkAdapter(iconManager) { idx -> pickIconForRow(idx) }
        binding.rvPreview.layoutManager = LinearLayoutManager(this)
        binding.rvPreview.adapter = adapter
        binding.rvPreview.isNestedScrollingEnabled = false

        applyMode(CoordsPrefs.getMode(this))

        binding.toggleCoordMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = if (checkedId == R.id.btnInputSk42) SK42 else WGS84
                CoordsPrefs.setMode(this, mode)
                applyMode(mode)
                if (rows.isNotEmpty()) buildTable(silent = true)
            }
        }

        binding.btnBuild.setOnClickListener { buildTable(silent = false) }
        binding.btnPasteTable.setOnClickListener { showPasteTableDialog() }
        binding.btnClearAll.setOnClickListener { clearAll() }
        binding.btnIconForAll.setOnClickListener { pickIconForAll() }
        binding.btnSaveAll.setOnClickListener { saveAllToDb() }
        binding.btnExportKmz.setOnClickListener { exportKmz() }

        updateCount()
    }

    private fun applyMode(mode: String) {
        if (mode == WGS84) {
            binding.toggleCoordMode.check(R.id.btnInputWgs84)
            binding.tilX.hint = "Широта (по одной на строку)"
            binding.tilY.hint = "Долгота (по одной на строку)"
        } else {
            binding.toggleCoordMode.check(R.id.btnInputSk42)
            binding.tilX.hint = "X СК-42 (по одной на строку)"
            binding.tilY.hint = "Y СК-42 (по одной на строку)"
        }
    }

    // Разбиваем по переводам строк И табам, чтобы вставка одной колонки
    // или прямоугольника из Excel/Calc одинаково давала "одна ячейка = одна строка".
    private fun splitLines(text: String): List<String> =
        text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseNumber(s: String): Double? =
        s.replace(',', '.').replace("\\s".toRegex(), "").toDoubleOrNull()

    private fun buildTable(silent: Boolean) {
        val names = splitLines(binding.etName.text.toString())
        val xs    = splitLines(binding.etX.text.toString())
        val ys    = splitLines(binding.etY.text.toString())

        if (xs.isEmpty() && ys.isEmpty() && names.isEmpty()) {
            if (!silent) Toast.makeText(this, "Вставь данные в поля", Toast.LENGTH_SHORT).show()
            rows.clear()
            adapter.submit(emptyList())
            updateCount()
            return
        }

        // Сохраняем уже выбранные на конкретные строки иконки, чтобы при
        // пересборке (например при смене СК/WGS) они не сбрасывались.
        val perRowIcon = rows.mapIndexed { i, r -> i to r.icon }.toMap()

        val n = maxOf(names.size, xs.size, ys.size)
        val mode = CoordsPrefs.getMode(this)

        rows.clear()
        for (i in 0 until n) {
            val name = (names.getOrNull(i)?.takeIf { it.isNotBlank() }) ?: "Точка ${i + 1}"
            val xRaw = xs.getOrNull(i) ?: ""
            val yRaw = ys.getOrNull(i) ?: ""
            val xv = parseNumber(xRaw)
            val yv = parseNumber(yRaw)

            var lat = Double.NaN
            var lon = Double.NaN
            var err: String? = null

            if (xv == null || yv == null) {
                err = "не число"
            } else if (mode == SK42) {
                if (xv !in 1_000_000.0..9_999_999.0 || yv !in 1_000_000.0..32_999_999.0) {
                    err = "вне диапазона СК-42"
                } else {
                    val zone = (yv / 1_000_000).toInt()
                    val (la, lo) = CoordConverter.sk42ToWgs84(xv, yv, zone)
                    lat = la
                    lon = lo
                }
            } else {
                if (xv !in -90.0..90.0 || yv !in -180.0..180.0) {
                    err = "вне диапазона WGS-84 (широта -90..90, долгота -180..180)"
                } else {
                    lat = xv
                    lon = yv
                }
            }

            rows.add(
                Row(
                    name = name,
                    xRaw = xRaw,
                    yRaw = yRaw,
                    lat = lat,
                    lon = lon,
                    icon = perRowIcon[i] ?: commonIcon,
                    error = err
                )
            )
        }

        adapter.submit(rows.toList())
        updateCount()

        // Подсказка про рассогласованную длину
        if (!silent && (names.size != xs.size || xs.size != ys.size)) {
            Toast.makeText(
                this,
                "Длины различаются: имён ${names.size}, X ${xs.size}, Y ${ys.size}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateCount() {
        val total = rows.size
        val errs = rows.count { it.error != null }
        binding.tvCount.text = when {
            total == 0       -> "Таблица пуста"
            errs == 0        -> "Точек: $total  •  все валидны"
            else             -> "Точек: $total  •  с ошибками: $errs"
        }
    }

    // Диалог "Вставить таблицу": юзер кидает прямоугольник из Excel/Calc,
    // мы автоматически раскидываем по полям etName / etX / etY.
    // Правило раскладки строки (TAB-разделитель):
    //   >=3 колонок — последние 2 это X и Y, остальные склеиваются через пробел в имя
    //   2 колонки   — X и Y, имя пустое
    //   1 колонка   — имя, X/Y пустые
    private fun showPasteTableDialog() {
        val pad = (resources.displayMetrics.density * 16).toInt()
        val input = android.widget.EditText(this).apply {
            hint = "Вставь сюда таблицу из Excel/Calc — колонки разделены TAB, строки переводом строки"
            setHintTextColor(0xFF8B96A8.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1B2433.toInt())
            setPadding(pad, pad, pad, pad)
            minLines = 6
            maxLines = 14
            setHorizontallyScrolling(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        AlertDialog.Builder(this)
            .setTitle("Вставить таблицу")
            .setMessage("Поддерживается формат: №\tназвание\tX\tY (4 колонки), название\tX\tY (3), X\tY (2). Десятичная запятая допустима.")
            .setView(input)
            .setPositiveButton("Разложить") { _, _ ->
                val ok = applyPastedTable(input.text?.toString().orEmpty())
                if (ok) buildTable(silent = false)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyPastedTable(raw: String): Boolean {
        val lines = raw.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            Toast.makeText(this, "Пусто", Toast.LENGTH_SHORT).show()
            return false
        }
        val rowsParsed = lines.map { ln -> ln.split('\t').map { it.trim() } }
        val names = mutableListOf<String>()
        val xs = mutableListOf<String>()
        val ys = mutableListOf<String>()
        for (row in rowsParsed) {
            when {
                row.size >= 3 -> {
                    val name = row.dropLast(2).joinToString(" ").trim()
                    names.add(name)
                    xs.add(row[row.size - 2])
                    ys.add(row[row.size - 1])
                }
                row.size == 2 -> {
                    names.add("")
                    xs.add(row[0])
                    ys.add(row[1])
                }
                else -> {
                    names.add(row.getOrNull(0).orEmpty())
                    xs.add("")
                    ys.add("")
                }
            }
        }
        binding.etName.setText(names.joinToString("\n"))
        binding.etX.setText(xs.joinToString("\n"))
        binding.etY.setText(ys.joinToString("\n"))
        val cols = rowsParsed.maxOf { it.size }
        Toast.makeText(
            this,
            "Разложено: ${lines.size} строк × $cols колонок",
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    private fun clearAll() {
        AlertDialog.Builder(this)
            .setTitle("Очистить всё?")
            .setMessage("Поля и собранная таблица будут очищены.")
            .setPositiveButton("Очистить") { _, _ ->
                binding.etName.setText("")
                binding.etX.setText("")
                binding.etY.setText("")
                rows.clear()
                commonIcon = null
                adapter.submit(emptyList())
                updateCount()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun pickIconForAll() {
        IconPickerDialog(this, iconManager) { selected ->
            commonIcon = selected
            for (r in rows) r.icon = selected
            adapter.submit(rows.toList())
            Toast.makeText(this, "Иконка применена ко всем (${rows.size})", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun pickIconForRow(idx: Int) {
        if (idx !in rows.indices) return
        IconPickerDialog(this, iconManager) { selected ->
            rows[idx].icon = selected
            adapter.notifyItemChanged(idx)
        }.show()
    }

    private fun saveAllToDb() {
        val valid = rows.filter { it.error == null }
        if (valid.isEmpty()) {
            Toast.makeText(this, "Нет валидных точек", Toast.LENGTH_SHORT).show()
            return
        }
        val mode = CoordsPrefs.getMode(this)
        val points = valid.map { r ->
            val xv = parseNumber(r.xRaw) ?: 0.0
            val yv = parseNumber(r.yRaw) ?: 0.0
            val (x42, y42, zone) = if (mode == SK42) {
                Triple(xv, yv, (yv / 1_000_000).toInt())
            } else Triple(0.0, 0.0, 0)
            Point(
                name = r.name,
                xSk42 = x42,
                ySk42 = y42,
                zone = zone,
                latWgs84 = r.lat,
                lonWgs84 = r.lon,
                source = "manual",
                color = "#0055FF",
                icon = r.icon
            )
        }
        viewModel.insertAll(points)
        Toast.makeText(this, "Сохранено: ${points.size}", Toast.LENGTH_SHORT).show()
    }

    private fun exportKmz() {
        val valid = rows.filter { it.error == null }
        if (valid.isEmpty()) {
            Toast.makeText(this, "Нет валидных точек для экспорта", Toast.LENGTH_SHORT).show()
            return
        }

        val waypoints = valid.map { r ->
            WayPoint(
                name = r.name,
                lat = r.lat,
                lon = r.lon,
                color = "#FF0000",
                builtInIconName = r.icon
            )
        }

        lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "kmz_export").apply { mkdirs() }
                    // Чистим, чтобы AlpineQuest не открыл прошлый кеш по тому же URI
                    dir.listFiles()?.forEach { it.delete() }
                    val f = File(dir, "manual_${System.currentTimeMillis()}.kmz")
                    f.outputStream().use { os ->
                        KmzConversionExporter(this@ManualBulkActivity).export(waypoints, os)
                    }
                    f
                }
            } catch (t: Throwable) {
                Toast.makeText(
                    this@ManualBulkActivity,
                    "Ошибка экспорта: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            shareKmz(file)
        }
    }

    private fun shareKmz(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.google-earth.kmz")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            val chooser = Intent.createChooser(intent, "Открыть в...")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
