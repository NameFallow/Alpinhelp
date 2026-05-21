package com.coordscanner.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.coordscanner.databinding.ItemCoordResultBinding
import com.coordscanner.utils.ParsedCoord

private data class CoordResultItem(
    val parsed: ParsedCoord,
    var selected: Boolean = true,
    var currentName: String = parsed.name.ifEmpty { "Точка" }
)

// How the batch name is derived from textCandidates
enum class NameMode {
    LAST_TOKEN,      // last word before coords → city/area name
    ALL_EXCEPT_LAST, // everything except last word, joined → description/type
    SINGLE           // only one token available
}

data class ColumnOption(
    val label: String,       // shown on the button
    val mode: NameMode
)

class CoordResultAdapter(
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<CoordResultAdapter.VH>() {

    private val items = mutableListOf<CoordResultItem>()
    var hasColumnSelection = false
        private set

    // ── Data operations ───────────────────────────────────────

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
        hasColumnSelection = false
        notifyDataSetChanged()
        onSelectionChanged()
    }

    // ── Column / name selection ───────────────────────────────

    // Returns smart options based on how many text tokens exist per row:
    //   - 1 token only → no options (nothing to choose)
    //   - 2+ tokens    → "last token" (city) AND "all-except-last joined" (description)
    fun getColumnOptions(): List<ColumnOption> {
        val options = mutableListOf<ColumnOption>()
        val hasMulti = items.any { it.parsed.textCandidates.size > 1 }
        if (!hasMulti) return emptyList()

        // Option A — last token (city / area, different for each row)
        val lastExamples = items.mapNotNull { it.parsed.textCandidates.lastOrNull() }
            .filter { it.isNotBlank() }.distinct().take(2)
        if (lastExamples.isNotEmpty()) {
            options.add(ColumnOption(lastExamples.joinToString(" / "), NameMode.LAST_TOKEN))
        }

        // Option B — everything except last, joined (description/type, often same for all)
        val descExamples = items.mapNotNull {
            val t = it.parsed.textCandidates.dropLast(1)
            if (t.isEmpty()) null else t.joinToString(" ")
        }.filter { it.isNotBlank() }.distinct().take(2)
        if (descExamples.isNotEmpty()) {
            options.add(ColumnOption(descExamples.joinToString(" / "), NameMode.ALL_EXCEPT_LAST))
        }

        return options
    }

    fun setNamesFromMode(mode: NameMode) {
        items.forEach { item ->
            item.currentName = when (mode) {
                NameMode.LAST_TOKEN ->
                    item.parsed.textCandidates.lastOrNull()?.ifBlank { null } ?: item.parsed.name

                NameMode.ALL_EXCEPT_LAST -> {
                    val t = item.parsed.textCandidates.dropLast(1)
                    if (t.isEmpty()) item.parsed.name else t.joinToString(" ")
                }

                NameMode.SINGLE ->
                    item.parsed.textCandidates.firstOrNull()?.ifBlank { null } ?: item.parsed.name
            }.ifEmpty { "Точка" }
        }
        hasColumnSelection = true
        notifyDataSetChanged()
        onSelectionChanged()
    }

    // ── Read-out ──────────────────────────────────────────────

    fun getSelected(): List<ParsedCoord> = items.filter { it.selected }
        .map { it.parsed.copy(name = it.currentName) }

    fun getSelectedCount(): Int = items.count { it.selected }

    // ── RecyclerView ──────────────────────────────────────────

    inner class VH(val binding: ItemCoordResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCoordResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.binding.checkboxItem.setOnCheckedChangeListener(null)
        holder.binding.checkboxItem.isChecked = item.selected

        holder.binding.tvItemName.text = item.currentName
        holder.binding.tvItemCoords.text = if (!item.parsed.isWgs84)
            "X: %,d   Y: %,d   зона %d".format(
                item.parsed.x.toLong(), item.parsed.y.toLong(), item.parsed.zone)
        else
            "%.5f° N   %.5f° E".format(item.parsed.lat, item.parsed.lon)

        holder.binding.checkboxItem.setOnCheckedChangeListener { _, isChecked ->
            item.selected = isChecked
            onSelectionChanged()
        }
        holder.itemView.setOnClickListener { holder.binding.checkboxItem.toggle() }
    }

    override fun getItemCount() = items.size
}
