package com.coordscanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile

object ConversionResultDialog {

    fun show(
        context: Context,
        converted: List<DocumentFile>,
        formatLabel: String,
        onDone: () -> Unit
    ) {
        val count = converted.size
        AlertDialog.Builder(context)
            .setTitle("Конвертация завершена")
            .setMessage("Конвертировано: $count ${plural(count)} → $formatLabel")
            .setPositiveButton("✓ Готово") { _, _ -> onDone() }
            .setNeutralButton("📤 Поделиться") { _, _ ->
                shareFiles(context, converted)
                onDone()
            }
            .setNegativeButton("📂 Открыть папку") { _, _ ->
                openFolder(context, converted)
                onDone()
            }
            .setCancelable(false)
            .show()
    }

    private fun shareFiles(context: Context, files: List<DocumentFile>) {
        val uris = ArrayList<Uri>(files.map { it.uri })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить файлы"))
    }

    private fun openFolder(context: Context, files: List<DocumentFile>) {
        val parent = files.firstOrNull()?.parentFile ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(parent.uri, "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun plural(n: Int) = when {
        n % 10 == 1 && n % 100 != 11          -> "файл"
        n % 10 in 2..4 && n % 100 !in 12..14  -> "файла"
        else                                   -> "файлов"
    }
}
