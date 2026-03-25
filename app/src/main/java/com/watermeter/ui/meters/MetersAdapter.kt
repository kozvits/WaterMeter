package com.watermeter.ui.meters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.watermeter.data.model.MeterWithReadings
import com.watermeter.databinding.ItemMeterBinding
import com.watermeter.util.DateUtils

class MetersAdapter(
    private val onItemClick: (MeterWithReadings) -> Unit,
    private val onLongClick: (MeterWithReadings) -> Unit
) : ListAdapter<MeterWithReadings, MetersAdapter.MeterViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeterViewHolder {
        val binding = ItemMeterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MeterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MeterViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MeterViewHolder(
        private val binding: ItemMeterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MeterWithReadings) {
            binding.apply {
                tvMeterName.text = item.meter.name
                tvSerialNumber.text = "№ ${item.meter.serialNumber}"

                val lastReading = item.lastReading
                if (lastReading != null) {
                    tvReading.text = "%.3f м³".format(lastReading.value)
                    tvDate.text = DateUtils.formatDate(lastReading.date)
                    tvReadingLabel.visibility = android.view.View.VISIBLE
                } else {
                    tvReading.text = "—"
                    tvDate.text = "Нет показаний"
                    tvReadingLabel.visibility = android.view.View.GONE
                }

                // Показываем потребление за последний период
                val consumption = item.lastConsumption
                if (consumption != null) {
                    tvConsumption.text = "+%.3f м³".format(consumption)
                    tvConsumption.visibility = android.view.View.VISIBLE
                } else {
                    tvConsumption.visibility = android.view.View.GONE
                }

                root.setOnClickListener { onItemClick(item) }
                root.setOnLongClickListener {
                    onLongClick(item)
                    true
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<MeterWithReadings>() {
        override fun areItemsTheSame(old: MeterWithReadings, new: MeterWithReadings) =
            old.meter.id == new.meter.id

        override fun areContentsTheSame(old: MeterWithReadings, new: MeterWithReadings) =
            old == new
    }
}
