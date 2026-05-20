package com.coordscanner.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.coordscanner.databinding.ItemCoordResultBinding
import com.coordscanner.utils.ParsedCoord

private data class CoordResultItem(val parsed: ParsedCoord, var selected: Boolean = true)

class CoordResultAdapter(
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<CoordResultAdapter.VH>() {

    private val items = mutableListOf<CoordResultItem>()

    fun addAll(newItems: List<ParsedCoord>) {
        val existing = items.map { "${it.parsed.x.toLong()}_${it.parsed.y.toLong()}" }.toSet()
        val toAdd = newItems.filter { "${it.x.toLong()}_${it.y.toLong()}" !in existing }
        val startPos = items.size
        items.addAll(toAdd.map { CoordResultItem(it) })
        notifyItemRangeInserted(startPos, toAdd.size)
        onSelectionChanged()
    }

    fun selectAll(select: Boolean) {
        items.forEach { it.selected = select }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun clearAll() {
        items.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun getSelected(): List<ParsedCoord> = items.filter { it.selected }.map { it.parsed }
    fun getSelectedCount(): Int = items.count { it.selected }

    inner class VH(val binding: ItemCoordResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCoordResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.binding.checkboxItem.setOnCheckedChangeListener(null)
        holder.binding.checkboxItem.isChecked = item.selected

        holder.binding.tvItemName.text = item.parsed.name.ifEmpty { "Точка" }
        holder.binding.tvItemCoords.text = if (!item.parsed.isWgs84)
            "X: %,d   Y: %,d   зона %d".format(
                item.parsed.x.toLong(), item.parsed.y.toLong(), item.parsed.zone)
        else
            "%.5f° N   %.5f° E".format(item.parsed.lat, item.parsed.lon)

        holder.binding.checkboxItem.setOnCheckedChangeListener { _, isChecked ->
            item.selected = isChecked
            onSelectionChanged()
        }
        holder.itemView.setOnClickListener {
            holder.binding.checkboxItem.toggle()
        }
    }

    override fun getItemCount() = items.size
}
