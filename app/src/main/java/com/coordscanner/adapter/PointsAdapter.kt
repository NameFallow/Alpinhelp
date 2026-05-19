package com.coordscanner.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.coordscanner.databinding.ItemPointBinding
import com.coordscanner.model.Point
import com.coordscanner.utils.CoordConverter

class PointsAdapter(
    private val onEdit: (Point) -> Unit,
    private val onDelete: (Point) -> Unit
) : ListAdapter<Point, PointsAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemPointBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(point: Point) {
            binding.tvName.text = point.name

            val isScanned = point.source == "scan"

            if (isScanned) {
                // Blue accent for camera-scanned points
                binding.root.strokeColor = Color.parseColor("#2196F3")
                binding.root.strokeWidth = 3
                binding.tvName.setTextColor(Color.parseColor("#1565C0"))
                binding.tvSourceBadge.text = "📷 Сканер"
                binding.tvSourceBadge.setTextColor(Color.parseColor("#2196F3"))
            } else {
                binding.root.strokeColor = Color.parseColor("#E0E0E0")
                binding.root.strokeWidth = 1
                binding.tvName.setTextColor(Color.parseColor("#212121"))
                binding.tvSourceBadge.text = "✏️ Вручную"
                binding.tvSourceBadge.setTextColor(Color.parseColor("#757575"))
            }

            if (point.xSk42 != 0.0) {
                binding.tvSk42.text = "СК-42  X: ${point.xSk42}  Y: ${point.ySk42}  Зона ${point.zone}"
            } else {
                binding.tvSk42.text = "WGS-84 (из градусов)"
            }

            binding.tvWgs84.text = CoordConverter.wgs84ToDisplayString(point.latWgs84, point.lonWgs84)
            binding.btnEdit.setOnClickListener { onEdit(point) }
            binding.btnDelete.setOnClickListener { onDelete(point) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPointBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Point>() {
            override fun areItemsTheSame(a: Point, b: Point) = a.id == b.id
            override fun areContentsTheSame(a: Point, b: Point) = a == b
        }
    }
}
