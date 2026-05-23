package com.coordscanner

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Window
import androidx.recyclerview.widget.GridLayoutManager
import com.coordscanner.adapter.IconGridAdapter
import com.coordscanner.databinding.DialogIconPickerBinding
import com.coordscanner.utils.IconManager

class IconPickerDialog(
    context: Context,
    private val iconManager: IconManager,
    private val onSelected: (String) -> Unit
) : Dialog(context) {

    private lateinit var b: DialogIconPickerBinding
    private lateinit var adapter: IconGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = DialogIconPickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Растягиваем диалог на всю ширину экрана
        window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val allIcons = iconManager.builtInIcons

        adapter = IconGridAdapter(allIcons, iconManager) { selected ->
            onSelected(selected)
            dismiss()
        }

        b.rvIcons.layoutManager = GridLayoutManager(context, 4)
        b.rvIcons.adapter = adapter

        b.tvIconCount.text = "Иконок: ${allIcons.size}"

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.lowercase().orEmpty()
                val filtered = if (query.isBlank()) allIcons
                               else allIcons.filter { it.lowercase().contains(query) }
                adapter.updateList(filtered)
                b.tvIconCount.text = "Иконок: ${filtered.size}"
            }
        })

        b.btnCancel.setOnClickListener { dismiss() }
    }

    override fun dismiss() {
        if (::adapter.isInitialized) adapter.shutdown()
        super.dismiss()
    }
}
