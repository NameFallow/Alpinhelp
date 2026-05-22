package com.coordscanner.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import com.coordscanner.R
import com.coordscanner.databinding.ItemConvertFileBinding

class ConvertFileAdapter(
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<ConvertFileAdapter.VH>() {

    private val items    = mutableListOf<DocumentFile>()
    private val selected = mutableSetOf<DocumentFile>()

    val isSelectMode get() = selected.isNotEmpty()

    fun submitList(list: List<DocumentFile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun toggleSelection(doc: DocumentFile) {
        if (!selected.remove(doc)) selected.add(doc)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun getSelected(): List<DocumentFile> = selected.toList()

    inner class VH(private val b: ItemConvertFileBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(doc: DocumentFile) {
            val name  = doc.name ?: "—"
            val isSel = doc in selected

            b.tvFileName.text = name
            b.tvFileExt.text  = name.substringAfterLast('.', "?").uppercase()
            b.tvFileSize.text = formatSize(doc.length())

            // Чекбокс виден только в режиме мультиселекта
            b.cbSelect.visibility = if (isSelectMode) View.VISIBLE else View.GONE
            b.cbSelect.isChecked  = isSel

            // Выделенная карточка подсвечивается синей рамкой
            val strokeColor = if (isSel)
                ContextCompat.getColor(b.root.context, R.color.primary)
            else
                ContextCompat.getColor(b.root.context, R.color.navy_stroke)
            b.cardRoot.strokeColor = strokeColor

            b.root.setOnLongClickListener {
                toggleSelection(doc)
                true
            }
            b.root.setOnClickListener {
                if (isSelectMode) toggleSelection(doc)
            }
            b.cbSelect.setOnClickListener {
                toggleSelection(doc)
            }
        }

        private fun formatSize(bytes: Long): String = when {
            bytes <= 0         -> "—"
            bytes < 1024       -> "$bytes Б"
            bytes < 1_048_576  -> "${"%.1f".format(bytes / 1024.0)} КБ"
            else               -> "${"%.1f".format(bytes / 1_048_576.0)} МБ"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemConvertFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
}
