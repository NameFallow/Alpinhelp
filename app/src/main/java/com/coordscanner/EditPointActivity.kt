package com.coordscanner

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.coordscanner.data.PointDatabase
import com.coordscanner.databinding.ActivityEditPointBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.CoordConverter
import com.coordscanner.viewmodel.PointViewModel
import kotlinx.coroutines.launch

class EditPointActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_POINT_ID = "point_id"
    }

    private lateinit var binding: ActivityEditPointBinding
    private val viewModel: PointViewModel by viewModels()
    private var currentPoint: Point? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditPointBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Редактировать точку"

        val pointId = intent.getLongExtra(EXTRA_POINT_ID, -1L)
        if (pointId == -1L) { finish(); return }

        lifecycleScope.launch {
            val point = PointDatabase.getDatabase(application).pointDao().getById(pointId)
            if (point == null) { finish(); return@launch }
            currentPoint = point
            binding.etName.setText(point.name)
            binding.etX.setText(point.xSk42.toLong().toString())
            binding.etY.setText(point.ySk42.toLong().toString())
        }

        binding.btnSave.setOnClickListener { saveChanges() }
    }

    private fun saveChanges() {
        val name = binding.etName.text.toString().trim()
        val xStr = binding.etX.text.toString().trim()
        val yStr = binding.etY.text.toString().trim()

        if (name.isEmpty() || xStr.isEmpty() || yStr.isEmpty()) {
            Toast.makeText(this, "Заполните все поля", Toast.LENGTH_SHORT).show()
            return
        }

        val x = xStr.replace(",", ".").toDoubleOrNull()
        val y = yStr.replace(",", ".").toDoubleOrNull()

        if (x == null || y == null || x !in 1_000_000.0..9_999_999.0 || y !in 1_000_000.0..32_999_999.0) {
            Toast.makeText(this, "Неверные координаты СК-42. X: ~5000000–6000000, Y: 7000000–32000000", Toast.LENGTH_LONG).show()
            return
        }

        val zone = (y / 1_000_000).toInt()
        val (lat, lon) = CoordConverter.sk42ToWgs84(x, y, zone)
        val updated = currentPoint!!.copy(name = name, xSk42 = x, ySk42 = y, zone = zone, latWgs84 = lat, lonWgs84 = lon)
        viewModel.update(updated)
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
