package com.alvaro.ruidobranco

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var volumeText: TextView
    private lateinit var toneText: TextView
    private lateinit var toneSeekBar: SeekBar
    private lateinit var playButton: Button

    private val preferences by lazy {
        getSharedPreferences(NoiseService.PREFERENCES_NAME, MODE_PRIVATE)
    }

    private var isPlaying = false
    private var currentVolume = DEFAULT_VOLUME
    private var currentTone = DEFAULT_TONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        volumeText = findViewById(R.id.volumeText)
        toneText = findViewById(R.id.toneText)
        toneSeekBar = findViewById(R.id.toneSeekBar)
        playButton = findViewById(R.id.playButton)
        val decreaseButton: Button = findViewById(R.id.decreaseButton)
        val increaseButton: Button = findViewById(R.id.increaseButton)

        loadState()

        toneSeekBar.max = TONE_STEPS
        toneSeekBar.progress = toneToProgress(currentTone)
        toneSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) changeTone(progressToTone(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        playButton.setOnClickListener {
            if (isPlaying) stopNoise() else startNoise()
        }
        decreaseButton.setOnClickListener { changeVolume(-VOLUME_STEP) }
        increaseButton.setOnClickListener { changeVolume(VOLUME_STEP) }

        requestNotificationPermissionIfNeeded()
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        loadState()
        updateUi()
    }

    private fun loadState() {
        isPlaying = preferences.getBoolean(NoiseService.KEY_PLAYING, false)
        currentVolume = preferences.getFloat(NoiseService.KEY_VOLUME, DEFAULT_VOLUME)
            .coerceIn(MIN_VOLUME, MAX_VOLUME)
        currentTone = preferences.getFloat(NoiseService.KEY_TONE, DEFAULT_TONE)
            .coerceIn(MIN_TONE, MAX_TONE)
    }

    private fun startNoise() {
        isPlaying = true
        saveState()

        val intent = Intent(this, NoiseService::class.java)
            .setAction(NoiseService.ACTION_PLAY)
            .putExtra(NoiseService.EXTRA_VOLUME, currentVolume)
            .putExtra(NoiseService.EXTRA_TONE, currentTone)
        startForegroundService(intent)
        updateUi()
    }

    private fun stopNoise() {
        isPlaying = false
        saveState()

        val intent = Intent(this, NoiseService::class.java)
            .setAction(NoiseService.ACTION_STOP)
        startService(intent)
        updateUi()
    }

    private fun changeVolume(delta: Float) {
        currentVolume = (currentVolume + delta).coerceIn(MIN_VOLUME, MAX_VOLUME)
        saveState()

        if (isPlaying) {
            val intent = Intent(this, NoiseService::class.java)
                .setAction(NoiseService.ACTION_SET_VOLUME)
                .putExtra(NoiseService.EXTRA_VOLUME, currentVolume)
            startService(intent)
        }
        updateUi()
    }

    private fun changeTone(value: Float) {
        currentTone = value.coerceIn(MIN_TONE, MAX_TONE)
        saveState()

        if (isPlaying) {
            val intent = Intent(this, NoiseService::class.java)
                .setAction(NoiseService.ACTION_SET_TONE)
                .putExtra(NoiseService.EXTRA_TONE, currentTone)
            startService(intent)
        }
        updateUi()
    }

    private fun saveState() {
        preferences.edit()
            .putBoolean(NoiseService.KEY_PLAYING, isPlaying)
            .putFloat(NoiseService.KEY_VOLUME, currentVolume)
            .putFloat(NoiseService.KEY_TONE, currentTone)
            .apply()
    }

    private fun updateUi() {
        val percentage = (currentVolume * 100).roundToInt()
        volumeText.text = getString(R.string.volume_value, percentage)
        toneText.text = getString(
            R.string.tone_value,
            getString(toneDescriptionResource(currentTone))
        )

        val expectedProgress = toneToProgress(currentTone)
        if (toneSeekBar.progress != expectedProgress) {
            toneSeekBar.progress = expectedProgress
        }

        if (isPlaying) {
            statusText.setText(R.string.status_playing)
            statusText.setTextColor(getColor(R.color.success))
            playButton.setText(R.string.stop)
            playButton.backgroundTintList = getColorStateList(R.color.stop_button)
        } else {
            statusText.setText(R.string.status_stopped)
            statusText.setTextColor(getColor(R.color.text_muted))
            playButton.setText(R.string.play)
            playButton.backgroundTintList = getColorStateList(R.color.primary)
        }
    }

    private fun toneDescriptionResource(value: Float): Int = when {
        value <= -0.65f -> R.string.tone_very_deep
        value < -0.18f -> R.string.tone_deep
        value >= 0.65f -> R.string.tone_very_bright
        value > 0.18f -> R.string.tone_bright
        else -> R.string.tone_neutral
    }

    private fun toneToProgress(value: Float): Int =
        (((value.coerceIn(MIN_TONE, MAX_TONE) + 1f) / 2f) * TONE_STEPS).roundToInt()

    private fun progressToTone(progress: Int): Float =
        ((progress.toFloat() / TONE_STEPS) * 2f) - 1f

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST_CODE)
        }
    }

    companion object {
        private const val NOTIFICATION_REQUEST_CODE = 100
        private const val DEFAULT_VOLUME = 0.35f
        private const val MIN_VOLUME = 0.05f
        private const val MAX_VOLUME = 1.00f
        private const val VOLUME_STEP = 0.05f

        private const val DEFAULT_TONE = -0.35f
        private const val MIN_TONE = -1.00f
        private const val MAX_TONE = 1.00f
        private const val TONE_STEPS = 200
    }
}
