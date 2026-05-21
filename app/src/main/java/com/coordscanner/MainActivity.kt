package com.coordscanner

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.coordscanner.adapter.PointsAdapter
import com.coordscanner.databinding.ActivityMainBinding
import com.coordscanner.model.Point
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
            onDelete = { point -> confirmDelete(point) }
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

        binding.btnExportBar.setOnClickListener { exportGpx() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_export -> { exportGpx(); true }
        else -> super.onOptionsItemSelected(item)
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
}
