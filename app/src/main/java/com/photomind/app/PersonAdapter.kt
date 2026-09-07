package com.photomind.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.photomind.app.databinding.ItemPersonBinding

class PersonAdapter(
    private val onName: (PersonCluster) -> Unit
) : RecyclerView.Adapter<PersonAdapter.PersonViewHolder>() {
    private val items = mutableListOf<PersonCluster>()

    fun submit(newItems: List<PersonCluster>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PersonViewHolder {
        return PersonViewHolder(ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: PersonViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class PersonViewHolder(private val binding: ItemPersonBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PersonCluster) {
            binding.personName.text = item.name.ifBlank { "Nieznana osoba" }
            binding.personCount.text = "${item.sampleCount} rozpoznanych twarzy"
            binding.nameButton.text = if (item.name.isBlank()) "Nazwij" else "Zmień"
            Glide.with(binding.personImage)
                .load(Uri.parse(item.representativeUri))
                .centerCrop()
                .into(binding.personImage)
            binding.nameButton.setOnClickListener { onName(item) }
            binding.root.setOnClickListener { onName(item) }
        }
    }
}
