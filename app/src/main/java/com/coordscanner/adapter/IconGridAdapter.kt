package com.coordscanner.adapter

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.coordscanner.databinding.ItemIconGridBinding
import com.coordscanner.utils.IconManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class IconGridAdapter(
    private var icons: List<String>,
    private val iconManager: IconManager,
    private val onSelected: (String) -> Unit
) : RecyclerView.Adapter<IconGridAdapter.VH>() {

    private val cache   = ConcurrentHashMap<String, Bitmap>()
    private val pool    = Executors.newFixedThreadPool(4)
    private val handler = Handler(Looper.getMainLooper())

    private val ICON_PX = 120  // размер рендера в пикселях

    inner class VH(val b: ItemIconGridBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemIconGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = icons.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = icons[position]
        holder.b.tvIconName.text = name.substringBeforeLast('.')
        holder.b.ivIcon.setImageBitmap(null)
        holder.b.ivIcon.tag = name

        val cached = cache[name]
        if (cached != null) {
            holder.b.ivIcon.setImageBitmap(cached)
        } else {
            pool.submit {
                val bmp = iconManager.renderBitmap(name, ICON_PX)
                if (bmp != null) cache[name] = bmp
                handler.post {
                    if (holder.b.ivIcon.tag == name) {
                        holder.b.ivIcon.setImageBitmap(bmp)
                    }
                }
            }
        }

        holder.b.root.setOnClickListener { onSelected(name) }
    }

    fun updateList(filtered: List<String>) {
        icons = filtered
        notifyDataSetChanged()
    }

    fun shutdown() {
        pool.shutdownNow()
    }
}
