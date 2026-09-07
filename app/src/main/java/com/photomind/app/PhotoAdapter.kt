package com.photomind.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.photomind.app.databinding.ItemPhotoBinding

class PhotoAdapter(
    private val onClick: (PhotoItem) -> Unit,
    private val onLongClick: (PhotoItem) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

    private val items = mutableListOf<PhotoItem>()

    fun submit(newItems: List<PhotoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun itemChanged(photo: PhotoItem) {
        val index = items.indexOfFirst { it.mediaId == photo.mediaId }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class PhotoViewHolder(private val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PhotoItem) {
            Glide.with(binding.photo)
                .load(Uri.parse(item.uri))
                .centerCrop()
                .into(binding.photo)

            if (item.userTags.isBlank()) {
                binding.tagBadge.visibility = android.view.View.GONE
            } else {
                binding.tagBadge.text = item.userTags
                binding.tagBadge.visibility = android.view.View.VISIBLE
            }

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }
}
