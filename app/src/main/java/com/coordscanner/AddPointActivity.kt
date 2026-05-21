package com.coordscanner

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.coordscanner.databinding.ActivityAddPointBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.CoordConverter
import com.coordscanner.utils.CoordsPrefs
import com.coordscanner.utils.CoordsPrefs.SK42
import com.coordscanner.utils.CoordsPrefs.WGS84
import com.coordscanner.viewmodel.PointViewModel
import com.google.android.material.tabs.TabLayout

class AddPointActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddPointBinding
    private val viewModel: PointViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddPointBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Добавить точку"

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.viewFlipper.displayedChild = tab.position
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Camera tab: open ScanActivity (saves points directly)
        binding.btnScan.setOnClickListener {
            startActivity(android.content.Intent(this, ScanActivity::class.java))
        }

        // Pre-select toggle based on saved preference
        applyMode(CoordsPrefs.getMode(this))

        binding.toggleCoordMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = if (checkedId == R.id.btnInputSk42) SK42 else WGS84
                CoordsPrefs.setMode(this, mode)
                applyMode(mode)
            }
        }

        binding.btnSaveManual.setOnClickListener { saveManualInput() }
    }

    private fun applyMode(mode: String) {
        if (mode == WGS84) {
            binding.toggleCoordMode.check(R.id.btnInputWgs84)
            binding.layoutSk42.visibility = View.GONE
            binding.layoutWgs84.visibility = View.VISIBLE
        } else {
            binding.toggleCoordMode.check(R.id.btnInputSk42)
            binding.layoutSk42.visibility = View.VISIBLE
            binding.layoutWgs84.visibility = View.GONE
        }
    }

    private fun saveManualInput() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Введите название точки", Toast.LENGTH_SHORT).show()
            return
        }

        val mode = CoordsPrefs.getMode(this)

        if (mode == WGS84) {
            val latStr = binding.etLat.text.toString().trim()
            val lonStr = binding.etLon.text.toString().trim()
            if (latStr.isEmpty() || lonStr.isEmpty()) {
                Toast.makeText(this, "Заполните широту и долготу", Toast.LENGTH_SHORT).show()
                return
            }
            val lat = latStr.replace(",", ".").toDoubleOrNull()
            val lon = lonStr.replace(",", ".").toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                Toast.makeText(this, "Неверные координаты WGS-84. Широта: -90..90, Долгота: -180..180", Toast.LENGTH_LONG).show()
                return
            }
            viewModel.insert(
                Point(name = name, xSk42 = 0.0, ySk42 = 0.0, zone = 0,
                      latWgs84 = lat, lonWgs84 = lon, source = "manual")
            )
        } else {
            val xStr = binding.etX.text.toString().trim()
            val yStr = binding.etY.text.toString().trim()
            if (xStr.isEmpty() || yStr.isEmpty()) {
                Toast.makeText(this, "Заполните X и Y", Toast.LENGTH_SHORT).show()
                return
            }
            val x = xStr.replace(",", ".").toDoubleOrNull()
            val y = yStr.replace(",", ".").toDoubleOrNull()
            if (x == null || y == null ||
                x !in 1_000_000.0..9_999_999.0 || y !in 1_000_000.0..32_999_999.0) {
                Toast.makeText(this, "Неверные координаты СК-42. X: ~5000000–6000000, Y: 7000000–32000000", Toast.LENGTH_LONG).show()
                return
            }
            val zone = (y / 1_000_000).toInt()
            val (lat, lon) = CoordConverter.sk42ToWgs84(x, y, zone)
            viewModel.insert(
                Point(name = name, xSk42 = x, ySk42 = y, zone = zone,
                      latWgs84 = lat, lonWgs84 = lon, source = "manual")
            )
        }

        Toast.makeText(this, "Точка сохранена", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
