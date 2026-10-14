package com.sentencebuddy.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.sentencebuddy.app.ai.GeminiClient
import com.sentencebuddy.app.ai.SuggestionResult
import com.sentencebuddy.app.data.AppPreferences
import com.sentencebuddy.app.data.Tone
import com.sentencebuddy.app.databinding.ActivityMainBinding
import com.sentencebuddy.app.util.ServiceStatus
import kotlinx.coroutines.launch

/** Setup and settings screen. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        prefs = AppPreferences.get(this)

        setUpStatusSection()
        setUpGeminiSection()
        setUpStyleSection()
        setUpBubbleSection()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------------------------------------------------------------------------------------------
    // Sections
    // ---------------------------------------------------------------------------------------------

    private fun setUpStatusSection() {
        binding.switchEnabled.isChecked = prefs.assistantEnabled
        binding.switchEnabled.setOnCheckedChangeListener { _, checked -> prefs.assistantEnabled = checked }

        binding.btnEnableService.setOnClickListener { showEnableServiceDialog() }
        binding.btnAppInfo.setOnClickListener { openAppInfo() }
    }

    private fun setUpGeminiSection() {
        binding.inputApiKey.setText(prefs.apiKey)
        binding.inputModel.setText(prefs.model)

        binding.btnSaveKey.setOnClickListener {
            saveGeminiFields()
            refreshStatus()
            snack(getString(R.string.msg_saved))
        }
        binding.btnGetKey.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        binding.btnTest.setOnClickListener { runTest() }
    }

    private fun setUpStyleSection() {
        // Tone chips
        val group = binding.chipGroupTone
        for (tone in Tone.values()) {
            val chip = layoutInflater.inflate(R.layout.item_tone_chip, group, false) as Chip
            chip.id = android.view.View.generateViewId()
            chip.text = tone.label
            chip.tag = tone
            group.addView(chip)
            if (tone == prefs.tone) chip.isChecked = true
        }
        group.setOnCheckedStateChangeListener { chipGroup, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val tone = chipGroup.findViewById<Chip>(id)?.tag as? Tone ?: return@setOnCheckedStateChangeListener
            prefs.tone = tone
        }

        // Suggestion count
        bindSlider(binding.sliderCount, prefs.suggestionCount) { value ->
            prefs.suggestionCount = value
            binding.labelCount.text = getString(R.string.label_count, value)
        }
    }

    private fun setUpBubbleSection() {
        bindSlider(binding.sliderOpacity, prefs.bubbleOpacityPercent) { value ->
            prefs.bubbleOpacityPercent = value
            binding.labelOpacity.text = getString(R.string.label_opacity, value)
        }
        bindSlider(binding.sliderSize, prefs.bubbleSizeDp) { value ->
            prefs.bubbleSizeDp = value
            binding.labelSize.text = getString(R.string.label_size, value)
        }
        bindSlider(binding.sliderDelay, prefs.debounceMs) { value ->
            prefs.debounceMs = value
            binding.labelDelay.text = getString(R.string.label_delay, value / 1000f)
        }

        binding.switchAutoShow.isChecked = prefs.autoShowPanel
        binding.switchAutoShow.setOnCheckedChangeListener { _, checked -> prefs.autoShowPanel = checked }

        binding.switchAutoSend.isChecked = prefs.autoSend
        binding.switchAutoSend.setOnCheckedChangeListener { _, checked -> prefs.autoSend = checked }

        binding.switchWhatsapp.isChecked = prefs.whatsappOnly
        binding.switchWhatsapp.setOnCheckedChangeListener { _, checked -> prefs.whatsappOnly = checked }

        binding.btnResetPosition.setOnClickListener {
            prefs.resetBubblePosition()
            snack(getString(R.string.msg_position_reset))
        }
    }

    /**
     * Sets a slider to [initial] (snapped to its step), calls [onValue] once to fill the label,
     * and again whenever the user moves it.
     */
    private fun bindSlider(slider: Slider, initial: Int, onValue: (Int) -> Unit) {
        val step = slider.stepSize
        val from = slider.valueFrom
        val to = slider.valueTo
        val snapped = (from + Math.round((initial - from) / step) * step).coerceIn(from, to)
        slider.value = snapped
        onValue(snapped.toInt())
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) onValue(value.toInt())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private fun saveGeminiFields() {
        prefs.apiKey = binding.inputApiKey.text?.toString().orEmpty()
        prefs.model = binding.inputModel.text?.toString().orEmpty()
        binding.inputModel.setText(prefs.model)
    }

    private fun runTest() {
        saveGeminiFields()
        refreshStatus()
        if (prefs.apiKey.isBlank()) {
            snack(getString(R.string.test_need_key))
            return
        }

        binding.btnTest.isEnabled = false
        binding.btnTest.text = getString(R.string.test_running)

        lifecycleScope.launch {
            val result = GeminiClient.suggest(
                apiKey = prefs.apiKey,
                model = prefs.model,
                text = "Tell me where is the Mango",
                tone = prefs.tone,
                count = prefs.suggestionCount
            )
            binding.btnTest.isEnabled = true
            binding.btnTest.text = getString(R.string.btn_test)

            val message = when (result) {
                is SuggestionResult.Success -> result.items.joinToString("\n\n") { "•  $it" }
                is SuggestionResult.Failure -> result.message
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.test_title)
                .setMessage(message)
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
        }
    }

    private fun showEnableServiceDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_enable_title)
            .setMessage(R.string.dialog_enable_message)
            .setPositiveButton(R.string.dialog_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton(R.string.btn_app_info) { _, _ -> openAppInfo() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun refreshStatus() {
        val serviceOn = ServiceStatus.isAccessibilityEnabled(this)
        binding.statusServiceText.setText(
            if (serviceOn) R.string.status_service_on else R.string.status_service_off
        )
        binding.statusServiceText.setTextColor(
            ContextCompat.getColor(this, if (serviceOn) R.color.status_ok else R.color.status_warn)
        )
        binding.btnEnableService.visibility = if (serviceOn) android.view.View.GONE else android.view.View.VISIBLE

        val keyOk = prefs.apiKey.isNotBlank()
        binding.statusKeyText.setText(if (keyOk) R.string.status_key_set else R.string.status_key_missing)
        binding.statusKeyText.setTextColor(
            ContextCompat.getColor(this, if (keyOk) R.color.status_ok else R.color.status_warn)
        )
    }

    private fun snack(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}
