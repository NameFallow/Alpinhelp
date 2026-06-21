package com.coordscanner

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.coordscanner.databinding.SheetFileBrowserBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Простой in-app файловый браузер для выбора GPX/KMZ/KML на устройстве.
 * Видит ВСЁ что разрешает MANAGE_EXTERNAL_STORAGE — включая Android/data/<other-app>/files.
 *
 * Использование:
 *   FileBrowserBottomSheet.show(supportFragmentManager) { file -> dispatchFile(Uri.fromFile(file)) }
 */
class FileBrowserBottomSheet : BottomSheetDialogFragment() {

    private var _b: SheetFileBrowserBinding? = null
    private val b get() = _b!!

    private var onPicked: ((File) -> Unit)? = null
    private var currentDir: File = defaultRoot()
    private val adapter = FileAdapter()

    private fun defaultRoot(): File {
        // Environment.getExternalStorageDirectory() с API 30+ может вернуть путь,
        // куда нет доступа без MANAGE_EXTERNAL_STORAGE. Если папка нечитаема —
        // фолбэк на app-scoped storage, который всегда работает без разрешений.
        val ext = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (ext != null && ext.canRead()) return ext
        val ctx = context
        val appExt = ctx?.getExternalFilesDir(null)
        if (appExt != null && appExt.canRead()) return appExt
        return File("/")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?,
    ): View {
        _b = SheetFileBrowserBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        b.recyclerFiles.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerFiles.adapter = adapter

        b.btnUp.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead()) showDir(parent)
        }

        buildShortcuts()
        showDir(currentDir)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?) =
        BottomSheetDialog(requireContext(), theme).apply {
            setOnShowListener {
                val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                sheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
                sheet?.requestLayout()
            }
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    /** Кнопки-shortcuts: память + AlpineQuest (если папка найдена). */
    private fun buildShortcuts() {
        b.chipShortcuts.removeAllViews()
        val ext = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (ext != null && ext.canRead()) {
            b.chipShortcuts.addView(makeChip("🏠 Память", ext))
            val dl = File(ext, "Download")
            if (dl.canRead()) b.chipShortcuts.addView(makeChip("📥 Download", dl))
        }
        context?.getExternalFilesDir(null)?.let { app ->
            if (app.canRead()) b.chipShortcuts.addView(makeChip("📂 Папка приложения", app))
        }
        findAlpineQuestDir()?.let { aq ->
            b.chipShortcuts.addView(makeChip("📁 AlpineQuest", aq))
        }
    }

    private fun makeChip(text: String, target: File): Chip = Chip(requireContext()).apply {
        this.text = text
        isCheckable = false
        isClickable = true
        setOnClickListener { if (target.exists() && target.canRead()) showDir(target) }
    }

    private fun findAlpineQuestDir(): File? {
        val ext = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            ?: return null
        val candidates = listOf(
            File(ext, "Android/data/psyberia.alpinequest.full/files"),
            File(ext, "Android/data/psyberia.alpinequest.free/files"),
            File(ext, "Android/data/psyberia.alpinequest.lite/files"),
            File(ext, "AlpineQuest"),
            File(ext, "Documents/AlpineQuest"),
            File(ext, "alpinequest"),
        )
        return candidates.firstOrNull { it.exists() && it.canRead() }
    }

    private fun showDir(dir: File) {
        currentDir = dir
        b.tvCurrentPath.text = dir.absolutePath

        val children = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()
        val dirs = children.filter { it.isDirectory && !it.isHidden }.sortedBy { it.name.lowercase() }
        val files = children
            .filter { it.isFile && !it.isHidden && it.name.lowercase().let {
                n -> n.endsWith(".gpx") || n.endsWith(".kmz") || n.endsWith(".kml")
            }}
            .sortedBy { it.name.lowercase() }

        val rows = dirs + files
        adapter.submit(rows)
        b.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.recyclerFiles.scrollToPosition(0)
    }

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.VH>() {
        private val items = mutableListOf<File>()
        private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        fun submit(list: List<File>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file_row, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = items[position]
            h.name.text = f.name
            if (f.isDirectory) {
                h.icon.setImageResource(R.drawable.ic_folder)
                val cnt = runCatching { f.list()?.size ?: 0 }.getOrDefault(0)
                h.meta.text = if (cnt > 0) "$cnt элементов" else "—"
                h.itemView.setOnClickListener { showDir(f) }
            } else {
                h.icon.setImageResource(R.drawable.ic_file)
                h.meta.text = "${formatSize(f.length())} · ${dateFmt.format(Date(f.lastModified()))}"
                h.itemView.setOnClickListener {
                    onPicked?.invoke(f)
                    dismiss()
                }
            }
        }

        private fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
            else -> "${bytes / (1024 * 1024)} МБ"
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.ivIcon)
            val name: TextView  = v.findViewById(R.id.tvName)
            val meta: TextView  = v.findViewById(R.id.tvMeta)
        }
    }

    companion object {
        fun show(fm: androidx.fragment.app.FragmentManager, onPicked: (File) -> Unit) {
            val frag = FileBrowserBottomSheet()
            frag.onPicked = onPicked
            frag.show(fm, "file_browser")
        }
    }
}
