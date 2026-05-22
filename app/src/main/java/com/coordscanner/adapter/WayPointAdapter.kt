package com.coordscanner.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.coordscanner.databinding.ItemWaypointBinding
import com.coordscanner.model.WayPoint

class WayPointAdapter(
    private val onRemove: (WayPoint) -> Unit,
    private val onPhotoClick: ((String) -> Unit)? = null
) : ListAdapter<WayPoint, WayPointAdapter.VH>(DIFF) {

    inner class VH(val b: ItemWaypointBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(wp: WayPoint) {
            b.tvName.text   = wp.name.ifBlank { "Точка ${adapterPosition + 1}" }
            b.tvCoords.text = "%.5f, %.5f".format(wp.lat, wp.lon)
            b.colorDot.setBackgroundColor(
                try { Color.parseColor(wp.color) } catch (e: Exception) { Color.RED }
            )
            b.btnRemove.setOnClickListener { onRemove(wp) }
            if (wp.photoPath != null && onPhotoClick != null) {
                b.tvPhoto.visibility = View.VISIBLE
                b.tvPhoto.setOnClickListener { onPhotoClick.invoke(wp.photoPath) }
            } else {
                b.tvPhoto.visibility = View.GONE
                b.tvPhoto.setOnClickListener(null)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemWaypointBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<WayPoint>() {
            override fun areItemsTheSame(a: WayPoint, b: WayPoint) = a === b
            override fun areContentsTheSame(a: WayPoint, b: WayPoint) = a == b
        }
    }
}
