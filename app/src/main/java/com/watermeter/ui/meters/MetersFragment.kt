package com.watermeter.ui.meters

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.watermeter.R
import com.watermeter.databinding.FragmentMetersBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MetersFragment : Fragment() {

    private var _binding: FragmentMetersBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MetersViewModel by activityViewModels()
    private lateinit var adapter: MetersAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMetersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFab()
        setupTelegramButton()
        observeState()
    }

    private fun setupRecyclerView() {
        adapter = MetersAdapter(
            onItemClick = { meterWithReadings ->
                val action = MetersFragmentDirections
                    .actionMetersFragmentToHistoryFragment(meterWithReadings.meter.id)
                findNavController().navigate(action)
            },
            onLongClick = { meterWithReadings ->
                showMeterOptionsDialog(meterWithReadings)
            }
        )
        binding.recyclerViewMeters.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewMeters.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAddReading.setOnClickListener {
            // Переключаемся на вкладку "Добавить показания"
            requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                R.id.bottomNav
            ).selectedItemId = R.id.nav_add
        }
    }

    private fun setupTelegramButton() {
        binding.btnSendTelegram.setOnClickListener {
            viewModel.sendToTelegram()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.meters.collectLatest { list ->
                adapter.submitList(list)
                binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewMeters.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                binding.btnSendTelegram.isEnabled = !loading
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.event.collectLatest { event ->
                when (event) {
                    is UiEvent.TelegramSuccess -> {
                        Snackbar.make(binding.root, "✅ Отправлено в Telegram!", Snackbar.LENGTH_LONG).show()
                        viewModel.clearEvent()
                    }
                    is UiEvent.ShowSnackbar -> {
                        Snackbar.make(binding.root, event.message, Snackbar.LENGTH_LONG).show()
                        viewModel.clearEvent()
                    }
                    null -> Unit
                }
            }
        }
    }

    private fun showMeterOptionsDialog(meterWithReadings: com.watermeter.data.model.MeterWithReadings) {
        val options = arrayOf("✏️ Редактировать", "🗑️ Удалить")
        AlertDialog.Builder(requireContext())
            .setTitle(meterWithReadings.meter.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditMeterDialog(meterWithReadings)
                    1 -> confirmDeleteMeter(meterWithReadings)
                }
            }
            .show()
    }

    private fun showEditMeterDialog(meterWithReadings: com.watermeter.data.model.MeterWithReadings) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_meter, null)
        val etName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etMeterName)
        val etSerial = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSerialNumber)
        val etAddress = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAddress)

        etName.setText(meterWithReadings.meter.name)
        etSerial.setText(meterWithReadings.meter.serialNumber)
        etAddress.setText(meterWithReadings.meter.address)

        AlertDialog.Builder(requireContext())
            .setTitle("Редактировать счётчик")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val updated = meterWithReadings.meter.copy(
                    name = etName.text?.toString()?.trim() ?: meterWithReadings.meter.name,
                    serialNumber = etSerial.text?.toString()?.trim() ?: meterWithReadings.meter.serialNumber,
                    address = etAddress.text?.toString()?.trim() ?: ""
                )
                viewModel.updateMeter(updated)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDeleteMeter(meterWithReadings: com.watermeter.data.model.MeterWithReadings) {
        AlertDialog.Builder(requireContext())
            .setTitle("Удалить счётчик?")
            .setMessage("Счётчик «${meterWithReadings.meter.name}» и все его показания будут удалены безвозвратно.")
            .setPositiveButton("Удалить") { _, _ ->
                viewModel.deleteMeter(meterWithReadings.meter)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
