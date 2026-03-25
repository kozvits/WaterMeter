package com.watermeter.ui.add

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import com.watermeter.R
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

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera() else showSnackbar("Нужен доступ к камере")
    }

    private val galleryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchGallery() else showSnackbar("Нужен доступ к галерее")
    }

    // ── Activity result launchers ─────────────────────────────────────────────

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            tempPhotoUri?.let { uri ->
                viewModel.setImageUri(uri, requireContext())
            }
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setImageUri(it, requireContext()) }
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
        setupClickListeners()
        observeState()
    }

    private fun setupClickListeners() {
        binding.btnCamera.setOnClickListener { requestCameraPermission() }
        binding.btnGallery.setOnClickListener { requestGalleryPermission() }
        binding.btnSave.setOnClickListener { saveReading() }
        binding.btnReset.setOnClickListener { resetForm() }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.imageUri.collectLatest { uri ->
                if (uri != null) {
                    Glide.with(this@AddReadingFragment)
                        .load(uri)
                        .centerCrop()
                        .into(binding.imgPreview)
                    binding.cardPreview.isVisible = true
                } else {
                    binding.cardPreview.isVisible = false
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is AddUiState.Idle -> setLoadingState(false)

                    is AddUiState.Processing -> setLoadingState(true)

                    is AddUiState.OcrDone -> {
                        setLoadingState(false)
                        fillFieldsFromOcr(state.result)
                        if (state.result.meterValue == null && state.result.serialNumber == null) {
                            showSnackbar("⚠️ Распознавание не удалось — введите данные вручную")
                        } else {
                            showSnackbar("✅ Данные распознаны — проверьте и нажмите Сохранить")
                        }
                    }

                    is AddUiState.OcrError -> {
                        setLoadingState(false)
                        showSnackbar(state.message)
                    }

                    is AddUiState.Saved -> {
                        setLoadingState(false)
                        showSnackbar("✅ Показания сохранены!")
                        resetForm()
                    }

                    is AddUiState.Error -> {
                        setLoadingState(false)
                        showSnackbar(state.message)
                    }
                }
            }
        }
    }

    private fun fillFieldsFromOcr(result: OcrResult) {
        if (!result.serialNumber.isNullOrBlank()) {
            binding.etSerialNumber.setText(result.serialNumber)
        }
        if (!result.meterValue.isNullOrBlank()) {
            binding.etReading.setText(result.meterValue)
        }
    }

    private fun setLoadingState(loading: Boolean) {
        binding.progressOcr.isVisible = loading
        binding.btnSave.isEnabled = !loading
        binding.btnCamera.isEnabled = !loading
        binding.btnGallery.isEnabled = !loading
        binding.tvOcrStatus.isVisible = loading
    }

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
    }

    // ── Camera / Gallery ──────────────────────────────────────────────────────

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun requestGalleryPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        galleryPermissionLauncher.launch(permission)
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

    private fun launchGallery() {
        galleryLauncher.launch("image/*")
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
