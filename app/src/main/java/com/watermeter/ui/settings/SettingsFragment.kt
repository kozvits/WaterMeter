package com.watermeter.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.watermeter.BuildConfig
import com.watermeter.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Версия приложения
        binding.tvAppVersion.text = "Версия ${BuildConfig.VERSION_NAME}"

        // Кнопка сохранения
        binding.btnSaveSettings.setOnClickListener {
            val token = binding.etBotToken.text?.toString() ?: ""
            val chatId = binding.etChatId.text?.toString() ?: ""
            viewModel.saveBotToken(token)
            viewModel.saveChatId(chatId)
            Snackbar.make(binding.root, "✅ Настройки сохранены", Snackbar.LENGTH_SHORT).show()
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
