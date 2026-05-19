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

            // Left stripe: use point's saved color
            val stripeColor = try {
                Color.parseColor(point.color)
            } catch (_: Exception) {
                Color.parseColor("#0055FF")
            }
            binding.sourceStripe.setBackgroundColor(stripeColor)

            if (isScanned) {
                binding.tvSourceBadge.text = "📷 Скан"
                binding.tvSourceBadge.setTextColor(Color.parseColor("#2196F3"))
            } else {
                binding.tvSourceBadge.text = "✏️ Вручную"
                binding.tvSourceBadge.setTextColor(Color.parseColor("#8BB4D8"))
            }

            // Reset card stroke to match dark theme
            binding.root.strokeColor = Color.parseColor("#1C3050")
            binding.root.strokeWidth = 1

            if (point.xSk42 != 0.0) {
                binding.tvSk42.text = "X: %,d  Y: %,d  зона %d"
                    .format(point.xSk42.toLong(), point.ySk42.toLong(), point.zone)
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
