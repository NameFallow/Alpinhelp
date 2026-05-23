package com.coordscanner.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.coordscanner.databinding.ItemNamedCoordBinding
import com.coordscanner.utils.CoordConverter
import com.coordscanner.utils.MatchedRow

class NamedCoordAdapter(
    private val onSelectionChanged: () -> Unit,
    var showWgs84: Boolean = false
) : RecyclerView.Adapter<NamedCoordAdapter.VH>() {

    private data class Item(val row: MatchedRow, var selected: Boolean = true)

    private val items = mutableListOf<Item>()

    fun setData(rows: List<MatchedRow>) {
        items.clear()
        items.addAll(rows.map { Item(it) })
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectAll(select: Boolean) {
        items.forEach { it.selected = select }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun getSelected(): List<MatchedRow> = items.filter { it.selected }.map { it.row }
    fun getSelectedCount(): Int = items.count { it.selected }
    fun getTotalCount(): Int = items.size

    inner class VH(val binding: ItemNamedCoordBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemNamedCoordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            cbSelect.setOnCheckedChangeListener(null)
            cbSelect.isChecked = item.selected
            tvName.text = item.row.name
            tvCoords.text = if (showWgs84) {
                val (lat, lon) = CoordConverter.sk42ToWgs84(item.row.x, item.row.y, item.row.zone)
                "%.6f°N   %.6f°E".format(lat, lon)
            } else {
                "X: %,d   Y: %,d   зона %d".format(
                    item.row.x.toLong(), item.row.y.toLong(), item.row.zone
                )
            }
            cbSelect.setOnCheckedChangeListener { _, checked ->
                item.selected = checked
                onSelectionChanged()
            }
            root.setOnClickListener { cbSelect.toggle() }
        }
    }

    override fun getItemCount() = items.size
}
