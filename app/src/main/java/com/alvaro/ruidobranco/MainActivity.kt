package com.alvaro.ruidobranco

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var volumeText: TextView
    private lateinit var toneText: TextView
    private lateinit var toneSeekBar: SeekBar
    private lateinit var playButton: Button
    private lateinit var decreaseButton: Button
    private lateinit var increaseButton: Button
    private lateinit var modeGroup: RadioGroup
    private lateinit var playerRadio: RadioButton
    private lateinit var controllerRadio: RadioButton
    private lateinit var connectionText: TextView
    private lateinit var pairButton: Button
    private lateinit var forgetButton: Button

    private val preferences by lazy {
        getSharedPreferences(NoiseService.PREFERENCES_NAME, MODE_PRIVATE)
    }

    private var isPlaying = false
    private var currentVolume = DEFAULT_VOLUME
    private var currentTone = DEFAULT_TONE
    private var currentRole = RemoteControlService.Role.PLAYER

    private var remoteService: RemoteControlService? = null
    private var remoteBound = false
    private var bindingRequested = false
    private var remoteConnected = false
    private var remoteAuthenticated = false
    private var pairedName: String? = null
    private var connectionStatus = "Preparando controle local…"
    private var remoteBattery = -1
    private var pairingDialog: AlertDialog? = null

    private val remoteListener = object : RemoteControlService.Listener {
        override fun onConnectionStatus(
            status: String,
            connected: Boolean,
            authenticated: Boolean,
            pairedName: String?
        ) {
            connectionStatus = status
            remoteConnected = connected
            remoteAuthenticated = authenticated
            this@MainActivity.pairedName = pairedName
            updateUi()
        }

        override fun onPairingRequest(
            endpointId: String,
            peerName: String,
            authenticationDigits: String
        ) {
            showPairingDialog(endpointId, peerName, authenticationDigits)
        }

        override fun onRemoteState(state: RemoteControlService.RemoteState) {
            if (currentRole != RemoteControlService.Role.CONTROLLER) return
            isPlaying = state.playing
            currentVolume = state.volume
            currentTone = state.tone
            remoteBattery = state.battery
            updateUi()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RemoteControlService.LocalBinder ?: return
            remoteService = binder.getService()
            remoteBound = true
            bindingRequested = false
            remoteService?.addListener(remoteListener)
            remoteService?.setRole(currentRole)
            if (currentRole == RemoteControlService.Role.CONTROLLER) {
                remoteService?.requestRemoteState()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            remoteBound = false
            bindingRequested = false
            remoteConnected = false
            remoteAuthenticated = false
            connectionStatus = "Serviço de controle desconectado"
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        volumeText = findViewById(R.id.volumeText)
        toneText = findViewById(R.id.toneText)
        toneSeekBar = findViewById(R.id.toneSeekBar)
        playButton = findViewById(R.id.playButton)
        decreaseButton = findViewById(R.id.decreaseButton)
        increaseButton = findViewById(R.id.increaseButton)
        modeGroup = findViewById(R.id.modeGroup)
        playerRadio = findViewById(R.id.playerRadio)
        controllerRadio = findViewById(R.id.controllerRadio)
        connectionText = findViewById(R.id.connectionText)
        pairButton = findViewById(R.id.pairButton)
        forgetButton = findViewById(R.id.forgetButton)

        loadRole()
        loadLocalState()

        if (currentRole == RemoteControlService.Role.PLAYER) {
            playerRadio.isChecked = true
        } else {
            controllerRadio.isChecked = true
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedRole = if (checkedId == R.id.controllerRadio) {
                RemoteControlService.Role.CONTROLLER
            } else {
                RemoteControlService.Role.PLAYER
            }
            changeRole(selectedRole)
        }

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
            if (currentRole == RemoteControlService.Role.CONTROLLER) {
                remoteService?.sendPlay(!isPlaying)
            } else if (isPlaying) {
                stopNoiseLocally()
            } else {
                startNoiseLocally()
            }
        }
        decreaseButton.setOnClickListener { changeVolume(-VOLUME_STEP) }
        increaseButton.setOnClickListener { changeVolume(VOLUME_STEP) }
        pairButton.setOnClickListener {
            if (allRemotePermissionsGranted()) {
                remoteService?.restartPairing()
            } else {
                requestRequiredPermissions()
            }
        }
        forgetButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.forget_pairing_title)
                .setMessage(R.string.forget_pairing_message)
                .setPositiveButton(R.string.forget) { _, _ -> remoteService?.forgetPairing() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        if (allRemotePermissionsGranted()) {
            startRemoteLayer()
        } else {
            requestRequiredPermissions()
        }
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        if (currentRole == RemoteControlService.Role.PLAYER) {
            loadLocalState()
        } else {
            remoteService?.requestRemoteState()
        }
        updateUi()
    }

    override fun onDestroy() {
        pairingDialog?.dismiss()
        remoteService?.removeListener(remoteListener)
        if (remoteBound) unbindService(serviceConnection)
        remoteBound = false
        bindingRequested = false
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST_CODE) return
        if (allRemotePermissionsGranted()) {
            startRemoteLayer()
        } else {
            connectionStatus = getString(R.string.remote_permissions_denied)
            updateUi()
        }
    }

    private fun changeRole(newRole: RemoteControlService.Role) {
        if (currentRole == newRole) return
        currentRole = newRole
        preferences.edit().putString(RemoteControlService.KEY_REMOTE_ROLE, newRole.name).apply()
        remoteBattery = -1
        remoteConnected = false
        remoteAuthenticated = false
        if (newRole == RemoteControlService.Role.PLAYER) loadLocalState()
        remoteService?.setRole(newRole)
        updateUi()
    }

    private fun loadRole() {
        currentRole = preferences.getString(
            RemoteControlService.KEY_REMOTE_ROLE,
            RemoteControlService.Role.PLAYER.name
        )?.let { value ->
            runCatching { RemoteControlService.Role.valueOf(value) }.getOrNull()
        } ?: RemoteControlService.Role.PLAYER
    }

    private fun loadLocalState() {
        isPlaying = preferences.getBoolean(NoiseService.KEY_PLAYING, false)
        currentVolume = preferences.getFloat(NoiseService.KEY_VOLUME, DEFAULT_VOLUME)
            .coerceIn(MIN_VOLUME, MAX_VOLUME)
        currentTone = preferences.getFloat(NoiseService.KEY_TONE, DEFAULT_TONE)
            .coerceIn(MIN_TONE, MAX_TONE)
    }

    private fun startNoiseLocally() {
        isPlaying = true
        saveLocalState()
        startForegroundService(
            Intent(this, NoiseService::class.java)
                .setAction(NoiseService.ACTION_PLAY)
                .putExtra(NoiseService.EXTRA_VOLUME, currentVolume)
                .putExtra(NoiseService.EXTRA_TONE, currentTone)
        )
        remoteService?.publishLocalState()
        updateUi()
    }

    private fun stopNoiseLocally() {
        isPlaying = false
        saveLocalState()
        startService(Intent(this, NoiseService::class.java).setAction(NoiseService.ACTION_STOP))
        remoteService?.publishLocalState()
        updateUi()
    }

    private fun changeVolume(delta: Float) {
        currentVolume = (currentVolume + delta).coerceIn(MIN_VOLUME, MAX_VOLUME)
        if (currentRole == RemoteControlService.Role.CONTROLLER) {
            remoteService?.sendVolume(currentVolume)
        } else {
            saveLocalState()
            if (isPlaying) {
                startService(
                    Intent(this, NoiseService::class.java)
                        .setAction(NoiseService.ACTION_SET_VOLUME)
                        .putExtra(NoiseService.EXTRA_VOLUME, currentVolume)
                )
            }
            remoteService?.publishLocalState()
        }
        updateUi()
    }

    private fun changeTone(value: Float) {
        currentTone = value.coerceIn(MIN_TONE, MAX_TONE)
        if (currentRole == RemoteControlService.Role.CONTROLLER) {
            remoteService?.sendTone(currentTone)
        } else {
            saveLocalState()
            if (isPlaying) {
                startService(
                    Intent(this, NoiseService::class.java)
                        .setAction(NoiseService.ACTION_SET_TONE)
                        .putExtra(NoiseService.EXTRA_TONE, currentTone)
                )
            }
            remoteService?.publishLocalState()
        }
        updateUi()
    }

    private fun saveLocalState() {
        preferences.edit()
            .putBoolean(NoiseService.KEY_PLAYING, isPlaying)
            .putFloat(NoiseService.KEY_VOLUME, currentVolume)
            .putFloat(NoiseService.KEY_TONE, currentTone)
            .apply()
    }

    private fun startRemoteLayer() {
        val intent = Intent(this, RemoteControlService::class.java)
            .putExtra(RemoteControlService.EXTRA_ROLE, currentRole.name)
        startForegroundService(intent)
        if (!remoteBound && !bindingRequested) {
            bindingRequested = true
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun showPairingDialog(endpointId: String, peerName: String, digits: String) {
        if (isFinishing || isDestroyed) return
        pairingDialog?.dismiss()
        pairingDialog = AlertDialog.Builder(this)
            .setTitle(R.string.confirm_pairing_title)
            .setMessage(getString(R.string.confirm_pairing_message, peerName, digits))
            .setPositiveButton(R.string.confirm) { _, _ ->
                remoteService?.acceptPairing(endpointId)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                remoteService?.rejectPairing(endpointId)
            }
            .setCancelable(false)
            .show()
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        return permissions.distinct().toTypedArray()
    }

    private fun allRemotePermissionsGranted(): Boolean =
        requiredPermissions().all { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestRequiredPermissions() {
        val missing = requiredPermissions().filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        } else {
            startRemoteLayer()
        }
    }

    private fun updateUi() {
        val percentage = (currentVolume * 100).roundToInt()
        volumeText.text = getString(R.string.volume_value, percentage)
        toneText.text = getString(
            R.string.tone_value,
            getString(toneDescriptionResource(currentTone))
        )

        val expectedProgress = toneToProgress(currentTone)
        if (toneSeekBar.progress != expectedProgress) toneSeekBar.progress = expectedProgress

        val remoteControlsAvailable =
            currentRole == RemoteControlService.Role.PLAYER || remoteAuthenticated
        playButton.isEnabled = remoteControlsAvailable
        decreaseButton.isEnabled = remoteControlsAvailable
        increaseButton.isEnabled = remoteControlsAvailable
        toneSeekBar.isEnabled = remoteControlsAvailable

        if (isPlaying) {
            statusText.setText(R.string.status_playing)
            statusText.setTextColor(getColor(R.color.success))
            playButton.setText(R.string.stop)
            playButton.backgroundTintList = getColorStateList(R.color.stop_button)
        } else {
            statusText.setText(
                if (currentRole == RemoteControlService.Role.CONTROLLER && !remoteAuthenticated) {
                    R.string.status_waiting_remote
                } else {
                    R.string.status_stopped
                }
            )
            statusText.setTextColor(getColor(R.color.text_muted))
            playButton.setText(R.string.play)
            playButton.backgroundTintList = getColorStateList(R.color.primary)
        }

        val batterySuffix = if (
            currentRole == RemoteControlService.Role.CONTROLLER &&
            remoteAuthenticated &&
            remoteBattery in 0..100
        ) {
            getString(R.string.remote_battery_suffix, remoteBattery)
        } else {
            ""
        }
        connectionText.text = connectionStatus + batterySuffix
        connectionText.setTextColor(
            getColor(if (remoteAuthenticated) R.color.success else R.color.text_muted)
        )

        pairButton.setText(
            when {
                remoteConnected -> R.string.disconnect_remote
                pairedName != null -> R.string.reconnect_remote
                currentRole == RemoteControlService.Role.PLAYER -> R.string.make_player_available
                else -> R.string.find_player
            }
        )
        forgetButton.visibility = if (pairedName.isNullOrBlank()) View.GONE else View.VISIBLE
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

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 101
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
