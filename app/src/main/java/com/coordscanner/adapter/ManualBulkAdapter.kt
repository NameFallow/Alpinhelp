package com.coordscanner.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.coordscanner.ManualBulkActivity
import com.coordscanner.databinding.ItemManualBulkBinding
import com.coordscanner.utils.CoordConverter
import com.coordscanner.utils.IconManager

class ManualBulkAdapter(
    private val iconManager: IconManager,
    private val onIconClick: (Int) -> Unit
) : RecyclerView.Adapter<ManualBulkAdapter.VH>() {

    private val items = mutableListOf<ManualBulkActivity.Row>()

    fun submit(newItems: List<ManualBulkActivity.Row>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemManualBulkBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: ManualBulkActivity.Row, pos: Int) {
            b.tvIndex.text = "%02d".format(pos + 1)
            b.tvName.text = row.name

            if (row.error != null) {
                b.tvCoords.text = "X=${row.xRaw}  Y=${row.yRaw}  —  ${row.error}"
                b.tvCoords.setTextColor(Color.parseColor("#EF5350"))
                b.tvBadge.visibility = View.VISIBLE
                b.tvBadge.text = "!"
                b.tvBadge.setTextColor(Color.parseColor("#EF5350"))
                b.root.strokeColor = Color.parseColor("#7A1F1F")
            } else {
                b.tvCoords.text = CoordConverter.wgs84ToDisplayString(row.lat, row.lon)
                b.tvCoords.setTextColor(Color.parseColor("#8BB4D8"))
                b.tvBadge.visibility = View.GONE
                b.root.strokeColor = Color.parseColor("#1C3050")
            }

            val icon = row.icon
            if (icon != null) {
                b.ivIcon.visibility = View.VISIBLE
                b.ivIconPlaceholder.visibility = View.GONE
                iconManager.loadInto(icon, b.ivIcon)
            } else {
                b.ivIcon.visibility = View.GONE
                b.ivIconPlaceholder.visibility = View.VISIBLE
            }

            b.iconWrap.setOnClickListener { onIconClick(bindingAdapterPosition) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemManualBulkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    override fun getItemCount() = items.size
}
