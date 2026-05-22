package com.coordscanner

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.coordscanner.adapter.PointsAdapter
import com.coordscanner.databinding.ActivityMainBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.CoordsPrefs
import com.coordscanner.utils.GpxExporter
import com.coordscanner.viewmodel.PointViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PointViewModel by viewModels()
    private lateinit var adapter: PointsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "CoordScanner"

        adapter = PointsAdapter(
            onEdit = { point -> openEdit(point) },
            onDelete = { point -> confirmDelete(point) },
            onLongClick = { point -> showQuickEditDialog(point) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        viewModel.allPoints.observe(this) { points ->
            adapter.submitList(points)
            binding.emptyState.visibility = if (points.isEmpty()) View.VISIBLE else View.GONE

            val scanned = points.count { it.source == "scan" }
            val manual = points.count { it.source == "manual" }
            binding.tvPointCount.text = when {
                points.isEmpty() -> "Нет точек"
                else -> "${points.size} точек  •  📷 $scanned скан.  •  ✏️ $manual вручную"
            }
        }

        binding.btnScan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }

        binding.btnPhotoBatch.setOnClickListener {
            startActivity(Intent(this, PhotoBatchActivity::class.java))
        }

        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, PhotoBatchActivity::class.java).apply {
                putExtra(PhotoBatchActivity.EXTRA_GALLERY_ONLY, true)
            })
        }

        binding.fab.setOnClickListener {
            startActivity(Intent(this, AddPointActivity::class.java))
        }

        binding.btnColumnScan.setOnClickListener {
            startActivity(Intent(this, ColumnSelectorActivity::class.java))
        }

        binding.btnGpxManager.setOnClickListener {
            startActivity(Intent(this, GpxManagerActivity::class.java))
        }

        binding.btnExportBar.setOnClickListener { exportGpx() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_export   -> { exportGpx(); true }
        R.id.action_settings -> { showSettingsDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showSettingsDialog() {
        val current = CoordsPrefs.getMode(this)
        val options = arrayOf("СК-42  (Гаусс-Крюгер)", "WGS-84  (широта / долгота °)")
        val checkedIdx = if (current == CoordsPrefs.SK42) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("Система координат ввода")
            .setSingleChoiceItems(options, checkedIdx) { dialog, idx ->
                CoordsPrefs.setMode(this, if (idx == 0) CoordsPrefs.SK42 else CoordsPrefs.WGS84)
                dialog.dismiss()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun exportGpx() {
        val points = viewModel.allPoints.value ?: emptyList()
        if (points.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Экспорт GPX")
                .setMessage("Нет точек для экспорта.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        GpxExporter.exportAndShare(this, points)
    }

    private fun openEdit(point: Point) {
        startActivity(Intent(this, EditPointActivity::class.java).apply {
            putExtra(EditPointActivity.EXTRA_POINT_ID, point.id)
        })
    }

    private fun confirmDelete(point: Point) {
        AlertDialog.Builder(this)
            .setTitle("Удалить точку")
            .setMessage("Удалить «${point.name}»?")
            .setPositiveButton("Удалить") { _, _ -> viewModel.delete(point) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Диалог быстрого редактирования по долгому тапу (название + цвет)
    private fun showQuickEditDialog(point: Point) {
        val density = resources.displayMetrics.density

        val editText = EditText(this).apply {
            setText(point.name)
            selectAll()
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.edit_bg)
            setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt()
            )
        }

        // Храним выбранный цвет локально пока диалог открыт
        var pendingColor = point.color

        val btnColor = com.google.android.material.button.MaterialButton(
            this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Изменить цвет"
            setTextColor(Color.parseColor("#8BB4D8"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (10 * density).toInt() }
            setOnClickListener {
                ColorPickerDialog.show(this@MainActivity, pendingColor) { hex ->
                    pendingColor = hex
                }
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(), (16 * density).toInt(),
                (20 * density).toInt(), (8  * density).toInt()
            )
            addView(editText)
            addView(btnColor)
        }

        AlertDialog.Builder(this)
            .setTitle("Изменить точку")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = editText.text.toString().trim().ifEmpty { point.name }
                viewModel.update(point.copy(name = newName, color = pendingColor))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
