package com.alvaro.ruidobranco

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
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
    private var restartWhenBound = false
    private var remoteConnected = false
    private var remoteAuthenticated = false
    private var pairedName: String? = null
    private var connectionStatus = "Preparando controle local…"
    private var remoteBattery = -1
    private var pairingDialog: AlertDialog? = null
    private var wifiDialog: AlertDialog? = null
    private var locationDialog: AlertDialog? = null
    private var waitingForSettings = false

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

            if (restartWhenBound) {
                restartWhenBound = false
                remoteService?.restartPairing()
            } else if (currentRole == RemoteControlService.Role.CONTROLLER) {
                remoteService?.requestRemoteState()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            remoteBound = false
            bindingRequested = false
            restartWhenBound = false
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
            prepareRemoteLayer(interactive = true, forceRestart = true)
        }
        forgetButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.forget_pairing_title)
                .setMessage(R.string.forget_pairing_message)
                .setPositiveButton(R.string.forget) { _, _ -> remoteService?.forgetPairing() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        prepareRemoteLayer(interactive = true, forceRestart = false)
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        if (currentRole == RemoteControlService.Role.PLAYER) {
            loadLocalState()
        } else {
            remoteService?.requestRemoteState()
        }

        if (waitingForSettings) {
            waitingForSettings = false
            prepareRemoteLayer(interactive = false, forceRestart = false)
        } else if (!remoteBound && !bindingRequested && allRemotePermissionsGranted()) {
            prepareRemoteLayer(interactive = false, forceRestart = false)
        }
        updateUi()
    }

    override fun onDestroy() {
        pairingDialog?.dismiss()
        wifiDialog?.dismiss()
        locationDialog?.dismiss()
        remoteService?.removeListener(remoteListener)
        if (remoteBound) unbindService(serviceConnection)
        remoteBound = false
        bindingRequested = false
        restartWhenBound = false
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android, retained for Bluetooth enable result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BLUETOOTH_ENABLE_REQUEST_CODE) {
            prepareRemoteLayer(interactive = false, forceRestart = false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NEARBY_PERMISSIONS_REQUEST_CODE -> {
                if (allRemotePermissionsGranted()) {
                    prepareRemoteLayer(interactive = true, forceRestart = false)
                } else {
                    connectionStatus = getString(R.string.remote_permissions_denied)
                    updateUi()
                }
            }

            NOTIFICATION_PERMISSION_REQUEST_CODE -> Unit
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

        if (remoteBound) {
            remoteService?.setRole(newRole)
        } else {
            prepareRemoteLayer(interactive = false, forceRestart = false)
        }
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

    private fun prepareRemoteLayer(interactive: Boolean, forceRestart: Boolean) {
        if (!allRemotePermissionsGranted()) {
            connectionStatus = getString(R.string.remote_permissions_requesting)
            updateUi()
            if (interactive) requestNearbyPermissions()
            return
        }

        val playServices = GoogleApiAvailability.getInstance()
        val playServicesStatus = playServices.isGooglePlayServicesAvailable(this)
        if (playServicesStatus != ConnectionResult.SUCCESS) {
            connectionStatus = getString(R.string.play_services_required, playServicesStatus)
            updateUi()
            if (interactive && playServices.isUserResolvableError(playServicesStatus)) {
                playServices.getErrorDialog(
                    this,
                    playServicesStatus,
                    PLAY_SERVICES_REQUEST_CODE
                )?.show()
            }
            return
        }

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (bluetoothAdapter == null) {
            connectionStatus = getString(R.string.bluetooth_unavailable)
            updateUi()
            return
        }

        val bluetoothEnabled = runCatching { bluetoothAdapter.isEnabled }.getOrDefault(false)
        if (!bluetoothEnabled) {
            connectionStatus = getString(R.string.bluetooth_required)
            updateUi()
            if (interactive) requestBluetoothEnable()
            return
        }

        val wifiEnabled = runCatching {
            getSystemService(WifiManager::class.java).isWifiEnabled
        }.getOrDefault(false)
        if (!wifiEnabled) {
            connectionStatus = getString(R.string.wifi_required)
            updateUi()
            if (interactive) showWifiRequiredDialog()
            return
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 && !isLocationEnabled()) {
            connectionStatus = getString(R.string.location_required)
            updateUi()
            if (interactive) showLocationRequiredDialog()
            return
        }

        startRemoteLayer(forceRestart)
    }

    private fun startRemoteLayer(forceRestart: Boolean) {
        val intent = Intent(this, RemoteControlService::class.java)
            .putExtra(RemoteControlService.EXTRA_ROLE, currentRole.name)

        if (!remoteBound && !bindingRequested) {
            restartWhenBound = forceRestart
            bindingRequested = true
            startForegroundService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else if (remoteBound) {
            remoteService?.setRole(currentRole)
            if (forceRestart) remoteService?.restartPairing()
        } else if (forceRestart) {
            restartWhenBound = true
        }

        requestNotificationPermissionIfNeeded()
    }

    private fun requestBluetoothEnable() {
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                BLUETOOTH_ENABLE_REQUEST_CODE
            )
        }.onFailure {
            waitingForSettings = true
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun showWifiRequiredDialog() {
        if (wifiDialog?.isShowing == true || isFinishing || isDestroyed) return
        wifiDialog = AlertDialog.Builder(this)
            .setTitle(R.string.wifi_required_title)
            .setMessage(R.string.wifi_required_message)
            .setPositiveButton(R.string.open_wifi_settings) { _, _ ->
                waitingForSettings = true
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_WIFI
                } else {
                    Settings.ACTION_WIFI_SETTINGS
                }
                startActivity(Intent(action))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLocationRequiredDialog() {
        if (locationDialog?.isShowing == true || isFinishing || isDestroyed) return
        locationDialog = AlertDialog.Builder(this)
            .setTitle(R.string.location_required_title)
            .setMessage(R.string.location_required_message)
            .setPositiveButton(R.string.open_location_settings) { _, _ ->
                waitingForSettings = true
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isLocationEnabled(): Boolean {
        val manager = getSystemService(LocationManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            ) != Settings.Secure.LOCATION_MODE_OFF
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

    private fun requiredNearbyPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 37) {
            permissions += ACCESS_LOCAL_NETWORK_PERMISSION
        }
        return permissions.distinct().toTypedArray()
    }

    private fun missingNearbyPermissions(): List<String> =
        requiredNearbyPermissions().filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }

    private fun allRemotePermissionsGranted(): Boolean = missingNearbyPermissions().isEmpty()

    private fun requestNearbyPermissions() {
        val missing = missingNearbyPermissions()
        if (missing.isEmpty()) {
            prepareRemoteLayer(interactive = true, forceRestart = false)
            return
        }

        val requestedBefore = preferences.getBoolean(KEY_NEARBY_PERMISSION_REQUESTED, false)
        val permanentlyDenied = requestedBefore && missing.any { permission ->
            !shouldShowRequestPermissionRationale(permission)
        }
        if (permanentlyDenied) {
            showPermissionSettingsDialog()
            return
        }

        preferences.edit().putBoolean(KEY_NEARBY_PERMISSION_REQUESTED, true).apply()
        requestPermissions(missing.toTypedArray(), NEARBY_PERMISSIONS_REQUEST_CODE)
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_permissions_settings_title)
            .setMessage(R.string.remote_permissions_settings_message)
            .setPositiveButton(R.string.open_app_settings) { _, _ ->
                waitingForSettings = true
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return

        preferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE
        )
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
                remoteConnected -> R.string.restart_remote
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
        private const val NEARBY_PERMISSIONS_REQUEST_CODE = 101
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 102
        private const val BLUETOOTH_ENABLE_REQUEST_CODE = 103
        private const val PLAY_SERVICES_REQUEST_CODE = 104
        private const val ACCESS_LOCAL_NETWORK_PERMISSION =
            "android.permission.ACCESS_LOCAL_NETWORK"
        private const val KEY_NEARBY_PERMISSION_REQUESTED = "nearby_permission_requested"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED =
            "notification_permission_requested"

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
