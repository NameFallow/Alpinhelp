package com.coordscanner

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.coordscanner.adapter.PointsAdapter
import com.coordscanner.databinding.ActivityMainBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.AiPrefs
import com.coordscanner.utils.CoordsPrefs
import com.coordscanner.utils.GpxExporter
import com.coordscanner.viewmodel.PointViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PointViewModel by viewModels()
    private lateinit var adapter: PointsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiPrefs.init(applicationContext)
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

            binding.tvPointCount.text = if (points.isEmpty()) {
                "Нет точек"
            } else {
                val scanned = points.count { it.source == "scan" }
                val manual  = points.count { it.source == "manual" }
                "${points.size} точек  •  $scanned распознано  •  $manual вручную"
            }
        }

        binding.btnManualBulk.setOnClickListener {
            startActivity(Intent(this, ManualBulkActivity::class.java))
        }

        binding.btnPhotoBatch.setOnClickListener {
            startActivity(Intent(this, PhotoBatchActivity::class.java))
        }

        binding.btnPhotoMap.setOnClickListener {
            startActivity(Intent(this, PhotoZoneActivity::class.java))
        }

        binding.btnColumnScan.setOnClickListener {
            startActivity(Intent(this, ColumnSelectorActivity::class.java))
        }

        binding.btnGpxManager.setOnClickListener {
            startActivity(Intent(this, GpxManagerActivity::class.java))
        }

        binding.btnExportBar.setOnClickListener { exportGpx() }

        binding.tvAiStatus.setOnClickListener { showSettingsDialog() }
    }

    override fun onResume() {
        super.onResume()
        AiPrefs.clearStaleError()
        refreshAiStatusBadge()
    }

    private fun refreshAiStatusBadge() {
        val active = AiPrefs.isActive()
        binding.tvAiStatus.setText(
            if (active) R.string.autoscanner_active else R.string.autoscanner_inactive
        )
        binding.tvAiStatus.setTextColor(
            Color.parseColor(if (active) "#2E7D32" else "#C62828")
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_export    -> { exportGpx(); true }
        R.id.action_clear_all -> { confirmClearAll(); true }
        R.id.action_settings  -> { showSettingsDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmClearAll() {
        val count = viewModel.allPoints.value?.size ?: 0
        if (count == 0) {
            AlertDialog.Builder(this)
                .setTitle("Очистить точки")
                .setMessage("Список и так пуст.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Удалить все точки?")
            .setMessage("Будут удалены все $count точек. Действие необратимо.")
            .setPositiveButton("Удалить все") { _, _ -> viewModel.deleteAll() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)

        val tvStatus = view.findViewById<TextView>(R.id.tv_ai_status)
        val tvReason = view.findViewById<TextView>(R.id.tv_ai_reason)
        if (AiPrefs.isActive()) {
            tvStatus.setText(R.string.autoscanner_active)
            tvStatus.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            tvStatus.setText(R.string.autoscanner_inactive)
            tvStatus.setTextColor(Color.parseColor("#C62828"))
        }
        tvReason.text = AiPrefs.statusReason()

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_reset_ai_status)
            .setOnClickListener {
                AiPrefs.forceClearStatus()
                tvStatus.setText(
                    if (AiPrefs.isActive()) R.string.autoscanner_active else R.string.autoscanner_inactive
                )
                tvStatus.setTextColor(
                    Color.parseColor(if (AiPrefs.isActive()) "#2E7D32" else "#C62828")
                )
                tvReason.text = AiPrefs.statusReason()
                refreshAiStatusBadge()
            }

        val etGemini = view.findViewById<EditText>(R.id.et_gemini_key)
        val etAnthropic = view.findViewById<EditText>(R.id.et_anthropic_key)
        val etOpenRouter = view.findViewById<EditText>(R.id.et_openrouter_key)
        val etGroq = view.findViewById<EditText>(R.id.et_groq_key)

        val rg = view.findViewById<RadioGroup>(R.id.rg_cs)
        val rbSk42 = view.findViewById<RadioButton>(R.id.rb_sk42)
        val rbWgs  = view.findViewById<RadioButton>(R.id.rb_wgs)
        if (CoordsPrefs.getMode(this) == CoordsPrefs.SK42) rbSk42.isChecked = true
        else rbWgs.isChecked = true
        rg.setOnCheckedChangeListener { _, id ->
            CoordsPrefs.setMode(
                this,
                if (id == R.id.rb_sk42) CoordsPrefs.SK42 else CoordsPrefs.WGS84
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                val g = etGemini.text.toString().trim()
                val a = etAnthropic.text.toString().trim()
                val o = etOpenRouter.text.toString().trim()
                val q = etGroq.text.toString().trim()
                if (g.isNotEmpty()) AiPrefs.setUserKey(g)
                if (a.isNotEmpty()) AiPrefs.setAnthropicUserKey(a)
                if (o.isNotEmpty()) AiPrefs.setOpenRouterUserKey(o)
                if (q.isNotEmpty()) AiPrefs.setGroqUserKey(q)
                refreshAiStatusBadge()
            }
            .setNegativeButton("Отмена", null)
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
