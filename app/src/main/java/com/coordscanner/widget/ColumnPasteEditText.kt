package com.coordscanner.widget

import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textfield.TextInputEditText
import com.coordscanner.R

class ColumnPasteEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.editTextStyle
) : TextInputEditText(context, attrs, defStyleAttr) {

    private var treatWhitespaceAsNewline: Boolean = false

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.ColumnPasteEditText)
            treatWhitespaceAsNewline =
                a.getBoolean(R.styleable.ColumnPasteEditText_treatWhitespaceAsNewline, false)
            a.recycle()
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val raw = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
                val normalized = normalize(raw)
                val s = selectionStart.coerceAtLeast(0)
                val e = selectionEnd.coerceAtLeast(0)
                val from = minOf(s, e)
                val to = maxOf(s, e)
                editableText?.replace(from, to, normalized)
                return true
            }
        }
        return super.onTextContextMenuItem(id)
    }

    private fun normalize(raw: String): String {
        var s = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\t', '\n')
        if (treatWhitespaceAsNewline && !s.contains('\n')) {
            // Числовой столбик, склеенный в одну строку — режем по любым пробелам.
            s = s.trim().split(Regex("\\s+")).joinToString("\n")
        }
        // Подчищаем лишние пустые строки.
        return s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
