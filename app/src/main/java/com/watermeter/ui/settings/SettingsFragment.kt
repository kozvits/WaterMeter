package com.watermeter.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.watermeter.BuildConfig
import com.watermeter.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    // ── SAF launchers ─────────────────────────────────────────────────────────

    /** Создать файл для экспорта */
    private val createFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportData(requireContext(), it) }
    }

    /** Выбрать файл для импорта */
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importData(requireContext(), it) }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvAppVersion.text = "Версия ${BuildConfig.VERSION_NAME}"

        binding.btnSaveSettings.setOnClickListener {
            val token  = binding.etBotToken.text?.toString() ?: ""
            val chatId = binding.etChatId.text?.toString() ?: ""
            viewModel.saveBotToken(token)
            viewModel.saveChatId(chatId)
            showSnackbar("✅ Настройки сохранены")
        }

        // Экспорт — открываем диалог создания файла
        binding.btnExport.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            createFileLauncher.launch("WaterMeter_backup_${timestamp}.json")
        }

        // Импорт — открываем диалог выбора файла
        binding.btnImport.setOnClickListener {
            openFileLauncher.launch(arrayOf("application/json", "*/*"))
        }

        // Загружаем сохранённые значения
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.botToken.collectLatest { token ->
                if (binding.etBotToken.text.isNullOrEmpty()) {
                    binding.etBotToken.setText(token)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.chatId.collectLatest { id ->
                if (binding.etChatId.text.isNullOrEmpty()) {
                    binding.etChatId.setText(id)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                binding.progressSettings.isVisible = loading
                binding.btnExport.isEnabled = !loading
                binding.btnImport.isEnabled = !loading
                binding.btnSaveSettings.isEnabled = !loading
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.event.collectLatest { event ->
                if (event is SettingsEvent.ShowMessage) {
                    showSnackbar(event.message)
                    viewModel.clearEvent()
                }
            }
        }
    }

    private fun showSnackbar(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
