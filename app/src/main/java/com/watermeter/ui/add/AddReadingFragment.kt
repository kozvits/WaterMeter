package com.watermeter.ui.add

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
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
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.watermeter.databinding.FragmentAddReadingBinding
import com.watermeter.ml.OcrResult
import com.watermeter.util.DateUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

@AndroidEntryPoint
class AddReadingFragment : Fragment() {

    private var _binding: FragmentAddReadingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AddReadingViewModel by viewModels()

    // ── Permission launchers ──────────────────────────────────────────────────

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchMeterCamera() else showSnackbar("Нужен доступ к камере")
    }

    private val barcodeCameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchBarcodeScanner() else showSnackbar("Нужен доступ к камере")
    }

    // ── Activity result launchers ─────────────────────────────────────────────

    /** MeterCameraActivity → возвращает URI сделанного фото */
    private val meterCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uriStr = result.data?.getStringExtra(MeterCameraActivity.RESULT_PHOTO_URI)
            if (!uriStr.isNullOrBlank()) {
                val uri = Uri.parse(uriStr)
                viewModel.setImageUri(uri, requireContext())
            }
        }
    }

    /** BarcodeScannerActivity → возвращает строку серийного номера */
    private val barcodeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val value = result.data?.getStringExtra(BarcodeScannerActivity.RESULT_BARCODE_VALUE)
            if (!value.isNullOrBlank()) {
                binding.etSerialNumber.setText(value)
                binding.tvScanStatus.text = "✅ Код считан: $value"
                binding.tvScanStatus.isVisible = true
                viewModel.onSerialNumberScanned(value)
            } else {
                showSnackbar("Код не распознан — введите номер вручную")
            }
        }
    }

    /** Выбор фото из галереи → URI → OCR + штрихкод */
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.processGalleryImage(it, requireContext())
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

    private fun setupIconListeners() {
        // Иконка QR → сканер штрихкода/QR (остаётся на иконке поля серийного номера)
        binding.tilSerialNumber.setStartIconOnClickListener {
            barcodeCameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
        // Иконка камеры на поле показаний — просто визуальный индикатор,
        // активные кнопки вынесены ниже (btnTakePhoto / btnPickGallery)
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveReading() }
        binding.btnReset.setOnClickListener { resetForm() }
        binding.btnTakePhoto.setOnClickListener {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
        binding.btnPickGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
        }
        binding.cardDatePicker.setOnClickListener { showDatePickerDialog() }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.imageUri.collectLatest { uri ->
                if (uri != null) {
                    Glide.with(this@AddReadingFragment)
                        .load(uri).centerCrop().into(binding.imgPreview)
                    binding.cardPreview.isVisible = true
                } else {
                    binding.cardPreview.isVisible = false
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedDate.collectLatest { timestamp ->
                binding.tvDateLabel.text = DateUtils.formatDate(timestamp)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scannedBarcode.collectLatest { barcode ->
                if (!barcode.isNullOrBlank()) {
                    binding.etSerialNumber.setText(barcode)
                    binding.tvScanStatus.text = "✅ Штрихкод: $barcode"
                    binding.tvScanStatus.isVisible = true
                    viewModel.onSerialNumberScanned(barcode)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is AddUiState.Idle       -> setLoading(false)
                    is AddUiState.Processing -> setLoading(true)

                    is AddUiState.OcrDone -> {
                        setLoading(false)
                        applyOcrResult(state.result)
                    }

                    is AddUiState.OcrError -> {
                        setLoading(false)
                        showSnackbar(state.message)
                    }

                    is AddUiState.Saved -> {
                        setLoading(false)
                        showSnackbar("✅ Показания сохранены!")
                        resetForm()
                    }

                    is AddUiState.Error -> {
                        setLoading(false)
                        showSnackbar(state.message)
                    }
                }
            }
        }
    }

    private fun applyOcrResult(result: OcrResult) {
        if (!result.meterValue.isNullOrBlank()) {
            binding.etReading.setText(result.meterValue)
            showSnackbar("✅ Показания: ${result.meterValue} м³ — проверьте и сохраните")
        } else {
            showSnackbar("⚠️ Показания не распознаны — введите вручную")
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressOcr.isVisible = loading
        binding.tvOcrStatus.isVisible = loading
        binding.btnSave.isEnabled = !loading
    }

    private fun saveReading() {
        val serial = binding.etSerialNumber.text?.toString() ?: ""
        val name   = binding.etMeterName.text?.toString() ?: ""
        val value  = binding.etReading.text?.toString() ?: ""
        val date   = viewModel.selectedDate.value
        viewModel.saveReading(serial, name, value, date)
    }

    private fun resetForm() {
        viewModel.resetState()
        binding.etSerialNumber.text?.clear()
        binding.etMeterName.text?.clear()
        binding.etReading.text?.clear()
        binding.cardPreview.isVisible = false
        binding.tvScanStatus.isVisible = false
    }

    // ── Launch activities ─────────────────────────────────────────────────────

    private fun showDatePickerDialog() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = viewModel.selectedDate.value
        DatePickerDialog(
            requireContext(),
            android.app.AlertDialog.THEME_DEVICE_DEFAULT_LIGHT,
            { _, year, month, dayOfMonth ->
                viewModel.onDateSelected(year, month, dayOfMonth)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun launchMeterCamera() {
        val intent = Intent(requireContext(), MeterCameraActivity::class.java)
        meterCameraLauncher.launch(intent)
    }

    private fun launchBarcodeScanner() {
        val intent = Intent(requireContext(), BarcodeScannerActivity::class.java)
        barcodeLauncher.launch(intent)
    }

    private fun showSnackbar(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
