package com.coordscanner

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.coordscanner.databinding.ActivityAddPointBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.CoordConverter
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

        // Camera tab: open ScanActivity (it saves points directly)
        binding.btnScan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }

        binding.btnSaveManual.setOnClickListener {
            saveManualInput()
        }
    }

    private fun saveManualInput() {
        val name = binding.etName.text.toString().trim()
        val xStr = binding.etX.text.toString().trim()
        val yStr = binding.etY.text.toString().trim()
        val zoneStr = binding.etZone.text.toString().trim()

        if (name.isEmpty() || xStr.isEmpty() || yStr.isEmpty() || zoneStr.isEmpty()) {
            Toast.makeText(this, "Заполните все поля", Toast.LENGTH_SHORT).show()
            return
        }

        val x = xStr.replace(",", ".").toDoubleOrNull()
        val y = yStr.replace(",", ".").toDoubleOrNull()
        val zone = zoneStr.toIntOrNull()

        if (x == null || y == null || zone == null || zone !in 1..32) {
            Toast.makeText(this, "Проверьте правильность данных", Toast.LENGTH_SHORT).show()
            return
        }

        val (lat, lon) = CoordConverter.sk42ToWgs84(x, y, zone)
        viewModel.insert(Point(name = name, xSk42 = x, ySk42 = y, zone = zone,
            latWgs84 = lat, lonWgs84 = lon, source = "manual"))
        Toast.makeText(this, "Точка сохранена", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
