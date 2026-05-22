package com.coordscanner.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile

class FilePickerHelper(
    private val activity: ComponentActivity,
    private val onFolderSelected: (DocumentFile) -> Unit
) {
    private val prefs: SharedPreferences =
        activity.getSharedPreferences("file_picker_prefs", Context.MODE_PRIVATE)

    private val folderLauncher: ActivityResultLauncher<Uri?> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            // Сохраняем постоянный доступ к папке между перезапусками
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString(KEY_URI, uri.toString()).apply()
            DocumentFile.fromTreeUri(activity, uri)?.let { onFolderSelected(it) }
        }

    fun requestFolderAccess() {
        AlertDialog.Builder(activity)
            .setTitle("Выбор папки")
            .setMessage("Выберите папку где хранятся ваши GPX/KMZ файлы.\n\nCoordScanner запомнит этот выбор и сможет читать и сохранять файлы в неё.")
            .setPositiveButton("Выбрать папку") { _, _ ->
                folderLauncher.launch(getSavedUri())  // открывается в ранее выбранной папке
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    fun getSavedFolder(): DocumentFile? {
        val uri = getSavedUri() ?: return null
        // Проверяем что разрешение ещё действует (могло быть отозвано)
        val valid = activity.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
        if (!valid) {
            prefs.edit().remove(KEY_URI).apply()
            return null
        }
        return DocumentFile.fromTreeUri(activity, uri)
    }

    fun hasSavedFolder(): Boolean = getSavedFolder() != null

    fun listGpxKmzFiles(): List<DocumentFile> =
        getSavedFolder()
            ?.listFiles()
            ?.filter { doc ->
                doc.name?.let { n ->
                    n.endsWith(".gpx", ignoreCase = true) || n.endsWith(".kmz", ignoreCase = true)
                } == true
            }
            .orEmpty()

    // Записать данные в файл выбранной папки (создаёт или перезаписывает)
    fun writeToFolder(fileName: String, mimeType: String, data: ByteArray): Boolean {
        val folder = getSavedFolder() ?: return false
        val existing = folder.findFile(fileName)
        val target = existing ?: folder.createFile(mimeType, fileName) ?: return false
        return runCatching {
            activity.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(data) }
            true
        }.getOrDefault(false)
    }

    private fun getSavedUri(): Uri? =
        prefs.getString(KEY_URI, null)?.let { Uri.parse(it) }

    companion object {
        private const val KEY_URI = "folder_uri"
    }
}
