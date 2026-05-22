package com.coordscanner

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.coordscanner.databinding.DialogSaveResultBinding
import com.coordscanner.utils.FileSaveHelper
import com.coordscanner.utils.SaveFormat
import com.coordscanner.utils.SaveResult
import java.util.Locale

object SaveResultDialog {

    fun show(context: Context, results: List<SaveResult>, onDone: (() -> Unit)? = null) {
        val b = DialogSaveResultBinding.inflate(LayoutInflater.from(context))

        // Список файлов с размерами
        b.tvFileList.text = results.joinToString("\n") { r ->
            "${r.fileName}  —  ${formatSize(r.sizeBytes)}"
        }

        val dialog = AlertDialog.Builder(context)
            .setView(b.root)
            .setCancelable(false)
            .create()

        // Поделиться KMZ (только если KMZ есть в результатах)
        val kmzResult = results.firstOrNull { it.format == SaveFormat.KMZ }
        if (kmzResult != null) {
            b.btnShareKmz.visibility = View.VISIBLE
            b.btnShareKmz.setOnClickListener {
                FileSaveHelper.shareKmz(kmzResult.uri, context)
            }
        } else {
            b.btnShareKmz.visibility = View.GONE
        }

        // Поделиться всеми форматами
        if (results.size > 1) {
            b.btnShareAll.visibility = View.VISIBLE
            b.btnShareAll.setOnClickListener {
                FileSaveHelper.shareAll(results.map { it.uri }, context)
            }
        } else {
            b.btnShareAll.visibility = View.GONE
        }

        b.btnDone.setOnClickListener {
            dialog.dismiss()
            onDone?.invoke()
        }

        dialog.show()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
        bytes >= 1024        -> String.format(Locale.US, "%.1f КБ", bytes / 1024.0)
        else                 -> "$bytes Б"
    }
}
