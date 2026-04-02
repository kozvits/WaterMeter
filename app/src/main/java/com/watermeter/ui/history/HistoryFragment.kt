package com.watermeter.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.android.material.snackbar.Snackbar
import com.watermeter.R
import com.watermeter.data.model.Reading
import com.watermeter.databinding.FragmentHistoryBinding
import com.watermeter.util.DateUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HistoryViewModel by viewModels()
    private lateinit var adapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        setupChart()
        setupAddButton()
        observeState()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onDeleteClick = { reading -> confirmDeleteReading(reading) },
            onEditClick   = { reading -> showEditReadingDialog(reading) }
        )
        binding.recyclerHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerHistory.adapter = adapter
    }

    private fun setupChart() {
        binding.barChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)
            setDrawBorders(false)
            animateY(500)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textSize = 10f
            }
            axisLeft.apply {
                setDrawGridLines(true)
                textSize = 10f
            }
            axisRight.isEnabled = false
        }
    }

    private fun setupAddButton() {
        binding.btnAddReading.setOnClickListener {
            showAddReadingDialog()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.meterWithReadings.collectLatest { data ->
                if (data == null) return@collectLatest

                // Заголовок
                binding.tvMeterTitle.text = data.meter.name
                binding.tvSerialNumber.text = "№ ${data.meter.serialNumber}"

                val readings = data.readings.sortedByDescending { it.date }
                adapter.submitList(readings)

                binding.tvEmpty.isVisible = readings.isEmpty()
                binding.recyclerHistory.isVisible = readings.isNotEmpty()

                // Обновляем график
                updateChart(readings.takeLast(6).reversed())

                // Последнее показание
                val last = data.lastReading
                if (last != null) {
                    binding.tvLastReading.text = "%.0f м³".format(last.value)
                    binding.tvLastDate.text = DateUtils.formatDate(last.date)
                }

                // Потребление
                val consumption = data.lastConsumption
                binding.tvConsumption.isVisible = consumption != null
                if (consumption != null) {
                    binding.tvConsumption.text = "+%.0f м³".format(consumption)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.snackbarMessage.collectLatest { msg ->
                if (msg != null) {
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                    viewModel.clearSnackbar()
                }
            }
        }
    }

    private fun updateChart(readings: List<Reading>) {
        if (readings.size < 2) {
            binding.barChart.isVisible = false
            return
        }
        binding.barChart.isVisible = true

        val entries = mutableListOf<BarEntry>()
        val labels  = mutableListOf<String>()

        readings.forEachIndexed { index, reading ->
            val prev = readings.getOrNull(index - 1)
            val consumption = if (prev != null) (reading.value - prev.value).coerceAtLeast(0.0) else 0.0
            entries.add(BarEntry(index.toFloat(), consumption.toFloat()))
            labels.add(DateUtils.formatDateShort(reading.date))
        }

        val dataSet = BarDataSet(entries, "Потребление").apply {
            color = requireContext().getColor(R.color.colorPrimary)
            valueTextSize = 9f
        }

        binding.barChart.apply {
            data = BarData(dataSet)
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.labelCount = labels.size
            invalidate()
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showAddReadingDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_reading, null)
        val etValue = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etReadingValue
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Добавить показание")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val newValue = etValue.text?.toString()
                    ?.replace(",", ".")?.toDoubleOrNull()
                if (newValue != null && newValue > 0) {
                    viewModel.addReading(newValue)
                } else {
                    Snackbar.make(binding.root, "Введите корректное значение", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDeleteReading(reading: Reading) {
        AlertDialog.Builder(requireContext())
            .setTitle("Удалить показание?")
            .setMessage("Показание ${reading.value} м³ от ${DateUtils.formatDate(reading.date)} будет удалено.")
            .setPositiveButton("Удалить") { _, _ -> viewModel.deleteReading(reading) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showEditReadingDialog(reading: Reading) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_reading, null)
        val etValue = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etReadingValue
        )
        etValue.setText(reading.value.toLong().toString())

        AlertDialog.Builder(requireContext())
            .setTitle("Редактировать показание")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val newValue = etValue.text?.toString()?.replace(",", ".")?.toDoubleOrNull()
                if (newValue != null) viewModel.updateReading(reading.copy(value = newValue))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
