package com.coordscanner.utils

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

class StoragePermissionHelper(
    private val activity: ComponentActivity,
    private val onGranted: () -> Unit,
    private val onDenied: () -> Unit = {}
) {
    private val legacyLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) onGranted() else onDenied()
        }

    // На Android 11+ перенаправляем в системные настройки для MANAGE_EXTERNAL_STORAGE
    private val manageStorageLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                onGranted()
            } else {
                onDenied()
            }
        }

    fun requestIfNeeded() {
        if (hasPermission()) { onGranted(); return }
        showExplanation()
    }

    fun hasPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Environment.isExternalStorageManager()
        else ->
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showExplanation() {
        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "CoordScanner нужен доступ к хранилищу чтобы читать GPX/KMZ файлы.\n\nВы будете перенаправлены в настройки, где нужно включить «Доступ ко всем файлам»."
        } else {
            "CoordScanner нужен доступ к хранилищу для чтения GPX/KMZ файлов."
        }
        AlertDialog.Builder(activity)
            .setTitle("Доступ к файлам")
            .setMessage(message)
            .setPositiveButton("Разрешить") { _, _ -> doRequest() }
            .setNegativeButton("Отмена") { _, _ -> onDenied() }
            .show()
    }

    private fun doRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manageStorageLauncher.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
            )
        } else {
            legacyLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }
}
