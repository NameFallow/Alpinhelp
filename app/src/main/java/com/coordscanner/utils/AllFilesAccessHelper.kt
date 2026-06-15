package com.coordscanner.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * Доступ ко всем файлам на устройстве (MANAGE_EXTERNAL_STORAGE). Нужен чтобы
 * встроенный файловый браузер видел Android/data/<чужое-приложение>/ (AlpineQuest),
 * которое Google заблокировал в SAF picker'е на Android 11+.
 *
 * На API < 30 полный доступ есть автоматически через READ_EXTERNAL_STORAGE из манифеста,
 * проверка возвращает true сразу.
 */
object AllFilesAccessHelper {

    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Если доступ есть — сразу вызвать onGranted. Иначе показать диалог с пояснением
     * и кнопкой «Открыть настройки», ведущей на экран «Доступ ко всем файлам» для
     * нашего пакета. После возврата из настроек onGranted нужно вызвать вручную
     * (через onResume или повторный тап на «Выбрать файл»).
     */
    fun ensureAccess(activity: Activity, onGranted: () -> Unit) {
        if (hasAccess()) { onGranted(); return }
        AlertDialog.Builder(activity)
            .setTitle("Нужен доступ ко всем файлам")
            .setMessage(
                "Чтобы видеть твои GPX в папке AlpineQuest (как через USB), включи " +
                "тумблер «Разрешить доступ ко всем файлам» в системных настройках. " +
                "Это безопасно — разрешение даётся только этому приложению."
            )
            .setPositiveButton("Открыть настройки") { _, _ -> openSettings(activity) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${activity.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }.onFailure {
            // На редких прошивках без нужного intent — открыть общий экран
            activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
