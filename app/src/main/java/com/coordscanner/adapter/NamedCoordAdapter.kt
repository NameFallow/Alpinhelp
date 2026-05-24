package com.coordscanner.adapter

import android.text.Editable
import android.text.TextWatcher
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

    private data class Item(val row: MatchedRow, var selected: Boolean = true, var name: String = row.name)

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

    fun getSelected(): List<MatchedRow> = items.filter { it.selected }
        .map { it.row.copy(name = it.name.trim().ifEmpty { "Точка" }) }
    fun getSelectedCount(): Int = items.count { it.selected }
    fun getTotalCount(): Int = items.size

    inner class VH(val binding: ItemNamedCoordBinding) : RecyclerView.ViewHolder(binding.root) {
        var nameWatcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemNamedCoordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            // Снять старый watcher перед setText чтобы не вызывать его при rebind
            holder.nameWatcher?.let { etName.removeTextChangedListener(it) }
            cbSelect.setOnCheckedChangeListener(null)

            cbSelect.isChecked = item.selected
            etName.setText(item.name)

            tvCoords.text = when {
                item.row.isWgs84 ->
                    "%.5f°N   %.5f°E".format(item.row.lat, item.row.lon)
                showWgs84 -> {
                    val (lat, lon) = CoordConverter.sk42ToWgs84(item.row.x, item.row.y, item.row.zone)
                    "%.5f°N   %.5f°E".format(lat, lon)
                }
                else ->
                    "X: %,d   Y: %,d   зона %d".format(
                        item.row.x.toLong(), item.row.y.toLong(), item.row.zone
                    )
            }

            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { item.name = s.toString() }
            }
            etName.addTextChangedListener(watcher)
            holder.nameWatcher = watcher

            cbSelect.setOnCheckedChangeListener { _, checked ->
                item.selected = checked
                onSelectionChanged()
            }
            // Клик по строке переключает чекбокс только если фокус не в EditText
            root.setOnClickListener {
                if (!etName.hasFocus()) cbSelect.toggle()
            }
        }
    }

    override fun getItemCount() = items.size
}
