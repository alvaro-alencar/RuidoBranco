package com.alvaro.ruidobranco

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var volumeText: TextView
    private lateinit var playButton: Button

    private val preferences by lazy {
        getSharedPreferences(NoiseService.PREFERENCES_NAME, MODE_PRIVATE)
    }

    private var isPlaying = false
    private var currentVolume = DEFAULT_VOLUME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        volumeText = findViewById(R.id.volumeText)
        playButton = findViewById(R.id.playButton)
        val decreaseButton: Button = findViewById(R.id.decreaseButton)
        val increaseButton: Button = findViewById(R.id.increaseButton)

        playButton.setOnClickListener {
            if (isPlaying) stopNoise() else startNoise()
        }
        decreaseButton.setOnClickListener { changeVolume(-VOLUME_STEP) }
        increaseButton.setOnClickListener { changeVolume(VOLUME_STEP) }

        requestNotificationPermissionIfNeeded()
        loadState()
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
    }

    private fun startNoise() {
        isPlaying = true
        saveState()

        val intent = Intent(this, NoiseService::class.java)
            .setAction(NoiseService.ACTION_PLAY)
            .putExtra(NoiseService.EXTRA_VOLUME, currentVolume)
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

    private fun saveState() {
        preferences.edit()
            .putBoolean(NoiseService.KEY_PLAYING, isPlaying)
            .putFloat(NoiseService.KEY_VOLUME, currentVolume)
            .apply()
    }

    private fun updateUi() {
        val percentage = (currentVolume * 100).roundToInt()
        volumeText.text = getString(R.string.volume_value, percentage)

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
    }
}
