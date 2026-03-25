package com.watermeter.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.watermeter.data.model.Reading
import com.watermeter.databinding.ItemReadingBinding
import com.watermeter.util.DateUtils

class HistoryAdapter(
    private val onDeleteClick: (Reading) -> Unit,
    private val onEditClick: (Reading) -> Unit
) : ListAdapter<Reading, HistoryAdapter.ReadingViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReadingViewHolder {
        val binding = ItemReadingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ReadingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReadingViewHolder, position: Int) {
        val current = getItem(position)
        val previous = if (position < currentList.size - 1) getItem(position + 1) else null
        holder.bind(current, previous)
    }

    inner class ReadingViewHolder(
        private val binding: ItemReadingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(reading: Reading, previous: Reading?) {
            binding.apply {
                tvValue.text = "%.3f м³".format(reading.value)
                tvDate.text = DateUtils.formatDate(reading.date)

                val delta = previous?.let { reading.value - it.value }
                if (delta != null && delta >= 0) {
                    tvDelta.text = "+%.3f".format(delta)
                    tvDelta.visibility = android.view.View.VISIBLE
                } else {
                    tvDelta.visibility = android.view.View.GONE
                }

                // Фото-иконка если есть снимок
                ivPhoto.visibility = if (reading.photoPath != null)
                    android.view.View.VISIBLE else android.view.View.GONE

                btnEdit.setOnClickListener { onEditClick(reading) }
                btnDelete.setOnClickListener { onDeleteClick(reading) }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<Reading>() {
        override fun areItemsTheSame(old: Reading, new: Reading) = old.id == new.id
        override fun areContentsTheSame(old: Reading, new: Reading) = old == new
    }
}
