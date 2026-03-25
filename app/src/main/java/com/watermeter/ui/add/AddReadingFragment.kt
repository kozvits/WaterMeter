package com.watermeter.ui.add

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.watermeter.databinding.FragmentAddReadingBinding
import com.watermeter.ml.OcrResult
import com.watermeter.util.ImageUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AddReadingFragment : Fragment() {

    private var _binding: FragmentAddReadingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AddReadingViewModel by viewModels()

    private var tempPhotoUri: Uri? = null

    // ── Permission launchers ──────────────────────────────────────────────────

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera() else showSnackbar("Нужен доступ к камере")
    }

    private val barcodeCameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchBarcodeScanner() else showSnackbar("Нужен доступ к камере")
    }

    // ── Activity result launchers ─────────────────────────────────────────────

    /** Камера → фото → OCR показаний */
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) tempPhotoUri?.let { viewModel.setImageUri(it, requireContext()) }
    }

    /** Сканер штрихкода/QR → серийный номер */
    private val barcodeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val value = result.data?.getStringExtra(BarcodeScannerActivity.RESULT_BARCODE_VALUE)
            if (!value.isNullOrBlank()) {
                binding.etSerialNumber.setText(value)
                binding.tvScanStatus.text = "✅ Код считан: $value"
                binding.tvScanStatus.isVisible = true
            } else {
                showSnackbar("Код не распознан — введите вручную")
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupIconListeners()
        setupButtons()
        observeState()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupIconListeners() {
        // Иконка QR слева в поле "Номер счётчика" → запускает сканер
        binding.tilSerialNumber.setStartIconOnClickListener {
            requestBarcodeCameraPermission()
        }

        // Иконка камеры слева в поле "Показания" → делает фото → OCR
        binding.tilReading.setStartIconOnClickListener {
            requestCameraPermission()
        }
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveReading() }
        binding.btnReset.setOnClickListener { resetForm() }
    }

    // ── State observation ─────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.imageUri.collectLatest { uri ->
                if (uri != null) {
                    Glide.with(this@AddReadingFragment)
                        .load(uri).centerCrop().into(binding.imgPreview)
                    binding.cardPreview.isVisible = true
                    binding.tvHint.isVisible = true
                } else {
                    binding.cardPreview.isVisible = false
                    binding.tvHint.isVisible = false
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is AddUiState.Idle       -> setOcrLoading(false)
                    is AddUiState.Processing -> setOcrLoading(true)

                    is AddUiState.OcrDone -> {
                        setOcrLoading(false)
                        fillReadingFromOcr(state.result)
                        if (state.result.meterValue == null) {
                            showSnackbar("⚠️ Не удалось распознать показания — введите вручную")
                        } else {
                            showSnackbar("✅ Показания распознаны: ${state.result.meterValue} м³")
                        }
                    }

                    is AddUiState.OcrError -> {
                        setOcrLoading(false)
                        showSnackbar(state.message)
                    }

                    is AddUiState.Saved -> {
                        setOcrLoading(false)
                        showSnackbar("✅ Показания сохранены!")
                        resetForm()
                    }

                    is AddUiState.Error -> {
                        setOcrLoading(false)
                        showSnackbar(state.message)
                    }
                }
            }
        }
    }

    private fun fillReadingFromOcr(result: OcrResult) {
        // Заполняем только поле показаний — серийный номер уже получен из сканера
        if (!result.meterValue.isNullOrBlank()) {
            binding.etReading.setText(result.meterValue)
        }
    }

    private fun setOcrLoading(loading: Boolean) {
        binding.progressOcr.isVisible = loading
        binding.tvOcrStatus.isVisible = loading
        binding.btnSave.isEnabled = !loading
        binding.tilReading.isStartIconCheckable = !loading
        binding.tilSerialNumber.isStartIconCheckable = !loading
    }

    // ── Save / Reset ──────────────────────────────────────────────────────────

    private fun saveReading() {
        val serial = binding.etSerialNumber.text?.toString() ?: ""
        val name   = binding.etMeterName.text?.toString() ?: ""
        val value  = binding.etReading.text?.toString() ?: ""
        viewModel.saveReading(serial, name, value)
    }

    private fun resetForm() {
        viewModel.resetState()
        binding.etSerialNumber.text?.clear()
        binding.etMeterName.text?.clear()
        binding.etReading.text?.clear()
        binding.cardPreview.isVisible = false
        binding.tvHint.isVisible = false
        binding.tvScanStatus.isVisible = false
    }

    // ── Camera / Barcode permissions & launch ─────────────────────────────────

    private fun requestCameraPermission() {
        cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun requestBarcodeCameraPermission() {
        barcodeCameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val photoFile = ImageUtils.createTempImageFile(requireContext())
        tempPhotoUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            photoFile
        )
        cameraLauncher.launch(tempPhotoUri)
    }

    private fun launchBarcodeScanner() {
        val intent = Intent(requireContext(), BarcodeScannerActivity::class.java)
        barcodeLauncher.launch(intent)
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
