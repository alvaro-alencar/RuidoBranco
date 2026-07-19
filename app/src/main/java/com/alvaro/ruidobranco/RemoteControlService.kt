package com.alvaro.ruidobranco

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Mantém uma conexão direta e criptografada entre dois celulares com o mesmo app.
 * O áudio nunca atravessa essa conexão: apenas comandos e estado são enviados.
 */
class RemoteControlService : Service() {

    enum class Role { PLAYER, CONTROLLER }

    data class RemoteState(
        val playing: Boolean,
        val volume: Float,
        val tone: Float,
        val battery: Int
    )

    interface Listener {
        fun onConnectionStatus(
            status: String,
            connected: Boolean,
            authenticated: Boolean,
            pairedName: String?
        )

        fun onPairingRequest(endpointId: String, peerName: String, authenticationDigits: String)

        fun onRemoteState(state: RemoteState)
    }

    inner class LocalBinder : Binder() {
        fun getService(): RemoteControlService = this@RemoteControlService
    }

    private data class PeerInfo(val role: Role, val id: String, val name: String)

    private enum class TransportState {
        IDLE,
        STARTING,
        ADVERTISING,
        DISCOVERING,
        CONNECTING,
        CONNECTED
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val pendingPeers = mutableMapOf<String, PeerInfo>()
    private val manuallyApprovedEndpoints = mutableSetOf<String>()

    private lateinit var connectionsClient: ConnectionsClient

    private val preferences by lazy {
        getSharedPreferences(NoiseService.PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private var role: Role = Role.PLAYER
    private var transportState = TransportState.IDLE
    private var transportGeneration = 0
    private var connectedEndpoint: String? = null
    private var connectedPeer: PeerInfo? = null
    private var requestedEndpoint: String? = null
    private var authenticated = false
    private var pendingPairToken: String? = null
    private var currentStatus = "Preparando conexão local…"

    private val reconnectRunnable = Runnable { restartTransport() }
    private val discoveryWatchdog = Runnable {
        if (
            role == Role.CONTROLLER &&
            transportState == TransportState.DISCOVERING &&
            connectedEndpoint == null
        ) {
            currentStatus = "Busca ativa. Nenhum reprodutor encontrado ainda."
            notifyStatus()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            runCatching {
                handleMessage(endpointId, JSONObject(bytes.toString(Charsets.UTF_8)))
            }.onFailure { error ->
                Log.e(TAG, "Mensagem remota inválida", error)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peer = parseEndpointName(info.endpointName)
                ?: PeerInfo(oppositeRole(), info.endpointName, info.endpointName)
            pendingPeers[endpointId] = peer
            transportState = TransportState.CONNECTING

            val trustedId = preferences.getString(KEY_TRUSTED_PEER_ID, null)
            val token = preferences.getString(KEY_PAIR_TOKEN, null)
            if (!trustedId.isNullOrBlank() && !token.isNullOrBlank() && peer.id == trustedId) {
                currentStatus = "Aparelho conhecido encontrado. Reconectando…"
                notifyStatus()
                acceptEndpoint(endpointId)
                return
            }

            currentStatus = "Confirme o código ${info.authenticationDigits} nos dois celulares"
            notifyStatus()
            listeners.forEach { listener ->
                mainHandler.post {
                    listener.onPairingRequest(endpointId, peer.name, info.authenticationDigits)
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            requestedEndpoint = null
            if (!result.status.isSuccess) {
                pendingPeers.remove(endpointId)
                manuallyApprovedEndpoints.remove(endpointId)
                transportState = TransportState.IDLE
                val code = ConnectionsStatusCodes.getStatusCodeString(result.status.statusCode)
                currentStatus = "Conexão recusada: $code (${result.status.statusCode}). Tentando novamente…"
                notifyStatus()
                scheduleReconnect()
                return
            }

            connectedEndpoint = endpointId
            connectedPeer = pendingPeers[endpointId]
            authenticated = false
            transportState = TransportState.CONNECTED
            mainHandler.removeCallbacks(discoveryWatchdog)
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()

            currentStatus = "Conectado. Validando o pareamento…"
            notifyStatus()

            val storedToken = preferences.getString(KEY_PAIR_TOKEN, null)
            val trustedId = preferences.getString(KEY_TRUSTED_PEER_ID, null)
            val peerId = connectedPeer?.id
            if (!storedToken.isNullOrBlank() && trustedId == peerId) {
                sendMessage(JSONObject().put("type", "hello").put("token", storedToken))
            } else if (role == Role.CONTROLLER) {
                pendingPairToken = UUID.randomUUID().toString()
                sendMessage(JSONObject().put("type", "pair").put("token", pendingPairToken))
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoint != endpointId) return
            connectedEndpoint = null
            connectedPeer = null
            authenticated = false
            pendingPairToken = null
            manuallyApprovedEndpoints.remove(endpointId)
            transportState = TransportState.IDLE
            currentStatus = "Conexão perdida. Procurando o outro celular…"
            notifyStatus()
            scheduleReconnect()
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (role != Role.CONTROLLER || connectedEndpoint != null || requestedEndpoint != null) return

            val peer = parseEndpointName(info.endpointName) ?: return
            if (peer.role != Role.PLAYER) return

            // Não descarte silenciosamente um aparelho quando houver um pareamento antigo.
            // A confirmação visual do código é a autorização para substituir a identidade salva.
            pendingPeers[endpointId] = peer
            requestedEndpoint = endpointId
            transportState = TransportState.CONNECTING
            mainHandler.removeCallbacks(discoveryWatchdog)
            currentStatus = "Encontrado: ${peer.name}. Solicitando conexão…"
            notifyStatus()

            connectionsClient
                .requestConnection(localEndpointName(), endpointId, connectionLifecycleCallback)
                .addOnFailureListener { error ->
                    Log.e(TAG, "Falha ao solicitar conexão", error)
                    requestedEndpoint = null
                    transportState = TransportState.IDLE
                    currentStatus = formatFailure("Falha ao solicitar conexão", error)
                    notifyStatus()
                    scheduleReconnect()
                }
        }

        override fun onEndpointLost(endpointId: String) {
            if (requestedEndpoint == endpointId && connectedEndpoint == null) {
                requestedEndpoint = null
                transportState = TransportState.DISCOVERING
                currentStatus = "Reprodutor saiu do alcance. Continuando a busca…"
                notifyStatus()
            }
            pendingPeers.remove(endpointId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectionsClient = Nearby.getConnectionsClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedRole = intent?.getStringExtra(EXTRA_ROLE)
            ?.let { value -> runCatching { Role.valueOf(value) }.getOrNull() }
            ?: storedRole()

        startForeground(NOTIFICATION_ID, buildNotification())
        setRole(requestedRole)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        transportGeneration++
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(discoveryWatchdog)
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        super.onDestroy()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        mainHandler.post {
            listener.onConnectionStatus(
                currentStatus,
                connectedEndpoint != null,
                authenticated,
                preferences.getString(KEY_TRUSTED_PEER_NAME, null)
            )
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun setRole(newRole: Role) {
        val changed = role != newRole
        role = newRole
        preferences.edit().putString(KEY_REMOTE_ROLE, role.name).apply()

        // Antes, toda chamada de setRole reiniciava o rádio quando ainda não havia conexão.
        // A Activity e o Service chamavam este método quase simultaneamente, gerando uma corrida.
        if (changed || transportState == TransportState.IDLE) {
            restartTransport()
        }
    }

    fun getRole(): Role = role

    fun isAuthenticated(): Boolean = authenticated

    fun acceptPairing(endpointId: String) {
        val peer = pendingPeers[endpointId]
        val trustedId = preferences.getString(KEY_TRUSTED_PEER_ID, null)
        if (peer != null && !trustedId.isNullOrBlank() && peer.id != trustedId) {
            clearTrustedPeer()
        }
        manuallyApprovedEndpoints.add(endpointId)
        acceptEndpoint(endpointId)
    }

    fun rejectPairing(endpointId: String) {
        connectionsClient.rejectConnection(endpointId)
        pendingPeers.remove(endpointId)
        manuallyApprovedEndpoints.remove(endpointId)
        transportState = TransportState.IDLE
        currentStatus = "Pareamento cancelado"
        notifyStatus()
        scheduleReconnect()
    }

    fun restartPairing() {
        restartTransport()
    }

    fun forgetPairing() {
        clearTrustedPeer()
        authenticated = false
        currentStatus = "Pareamento apagado. Procurando um novo aparelho…"
        notifyStatus()
        restartTransport()
    }

    fun sendPlay(play: Boolean) {
        sendCommand(if (play) "play" else "stop")
    }

    fun sendVolume(volume: Float) {
        sendCommand("volume", volume.coerceIn(MIN_VOLUME, MAX_VOLUME))
    }

    fun sendTone(tone: Float) {
        sendCommand("tone", tone.coerceIn(MIN_TONE, MAX_TONE))
    }

    fun requestRemoteState() {
        if (role == Role.CONTROLLER && authenticated) {
            sendMessage(JSONObject().put("type", "request_state"))
        }
    }

    fun publishLocalState() {
        if (role == Role.PLAYER && authenticated) publishState()
    }

    private fun restartTransport() {
        val generation = ++transportGeneration
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(discoveryWatchdog)

        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()

        connectedEndpoint = null
        connectedPeer = null
        requestedEndpoint = null
        authenticated = false
        pendingPairToken = null
        pendingPeers.clear()
        manuallyApprovedEndpoints.clear()
        transportState = TransportState.STARTING

        currentStatus = if (role == Role.PLAYER) {
            "Preparando o anúncio do reprodutor…"
        } else {
            "Preparando a busca pelo reprodutor…"
        }
        notifyStatus()

        // Dá tempo para o Google Play Services encerrar a operação anterior antes de iniciar outra.
        mainHandler.postDelayed({
            if (generation != transportGeneration || connectedEndpoint != null) return@postDelayed
            if (role == Role.PLAYER) {
                startAdvertising(generation)
            } else {
                startDiscovery(generation)
            }
        }, TRANSPORT_SETTLE_DELAY_MS)
    }

    private fun startAdvertising(generation: Int) {
        if (generation != transportGeneration || role != Role.PLAYER) return
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient
            .startAdvertising(localEndpointName(), SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                if (generation != transportGeneration || role != Role.PLAYER) return@addOnSuccessListener
                transportState = TransportState.ADVERTISING
                currentStatus = "Disponibilidade confirmada. Aguardando o celular Controle…"
                notifyStatus()
            }
            .addOnFailureListener { error ->
                if (generation != transportGeneration) return@addOnFailureListener
                val code = statusCodeName(error)
                if (code.contains("ALREADY_ADVERTISING")) {
                    transportState = TransportState.ADVERTISING
                    currentStatus = "Disponibilidade confirmada. Aguardando o celular Controle…"
                } else {
                    transportState = TransportState.IDLE
                    currentStatus = formatFailure("Falha ao anunciar", error)
                }
                notifyStatus()
            }
    }

    private fun startDiscovery(generation: Int) {
        if (generation != transportGeneration || role != Role.CONTROLLER) return
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient
            .startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener {
                if (generation != transportGeneration || role != Role.CONTROLLER) {
                    return@addOnSuccessListener
                }
                transportState = TransportState.DISCOVERING
                currentStatus = "Busca confirmada. Procurando o reprodutor próximo…"
                notifyStatus()
                mainHandler.removeCallbacks(discoveryWatchdog)
                mainHandler.postDelayed(discoveryWatchdog, DISCOVERY_WATCHDOG_MS)
            }
            .addOnFailureListener { error ->
                if (generation != transportGeneration) return@addOnFailureListener
                val code = statusCodeName(error)
                if (code.contains("ALREADY_DISCOVERING")) {
                    transportState = TransportState.DISCOVERING
                    currentStatus = "Busca confirmada. Procurando o reprodutor próximo…"
                    mainHandler.removeCallbacks(discoveryWatchdog)
                    mainHandler.postDelayed(discoveryWatchdog, DISCOVERY_WATCHDOG_MS)
                } else {
                    transportState = TransportState.IDLE
                    currentStatus = formatFailure("Falha ao procurar", error)
                }
                notifyStatus()
            }
    }

    private fun acceptEndpoint(endpointId: String) {
        connectionsClient
            .acceptConnection(endpointId, payloadCallback)
            .addOnFailureListener { error ->
                Log.e(TAG, "Falha ao aceitar conexão", error)
                manuallyApprovedEndpoints.remove(endpointId)
                transportState = TransportState.IDLE
                currentStatus = formatFailure("Falha ao aceitar o pareamento", error)
                notifyStatus()
                scheduleReconnect()
            }
    }

    private fun scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun handleMessage(endpointId: String, message: JSONObject) {
        if (endpointId != connectedEndpoint) return
        when (message.optString("type")) {
            "pair" -> handlePairRequest(message.optString("token"), endpointId)
            "pair_ack" -> handlePairAcknowledgement(message.optString("token"))
            "hello" -> handleHello(message.optString("token"))
            "hello_ack" -> handleHelloAcknowledgement(message.optString("token"))
            "command" -> handleCommand(message)
            "request_state" -> if (role == Role.PLAYER && authenticated) publishState()
            "state" -> if (role == Role.CONTROLLER && authenticated) receiveState(message)
        }
    }

    private fun handlePairRequest(token: String, endpointId: String) {
        if (role != Role.PLAYER || token.isBlank()) return

        val trustedId = preferences.getString(KEY_TRUSTED_PEER_ID, null)
        val peerId = connectedPeer?.id
        if (!trustedId.isNullOrBlank() && peerId != trustedId) {
            if (endpointId in manuallyApprovedEndpoints) {
                clearTrustedPeer()
            } else {
                disconnectForAuthenticationFailure()
                return
            }
        }

        saveTrustedPeer(token)
        manuallyApprovedEndpoints.remove(endpointId)
        authenticated = true
        sendMessage(JSONObject().put("type", "pair_ack").put("token", token))
        currentStatus = "Pareado com ${connectedPeer?.name ?: "controle remoto"}"
        notifyStatus()
        publishState()
    }

    private fun handlePairAcknowledgement(token: String) {
        if (role != Role.CONTROLLER || token.isBlank() || token != pendingPairToken) return
        saveTrustedPeer(token)
        pendingPairToken = null
        connectedEndpoint?.let { manuallyApprovedEndpoints.remove(it) }
        authenticated = true
        currentStatus = "Controlando ${connectedPeer?.name ?: "reprodutor"}"
        notifyStatus()
        requestRemoteState()
    }

    private fun handleHello(token: String) {
        val storedToken = preferences.getString(KEY_PAIR_TOKEN, null)
        val trustedId = preferences.getString(KEY_TRUSTED_PEER_ID, null)
        if (token.isBlank() || token != storedToken || connectedPeer?.id != trustedId) {
            disconnectForAuthenticationFailure()
            return
        }

        authenticated = true
        sendMessage(JSONObject().put("type", "hello_ack").put("token", token))
        currentStatus = if (role == Role.PLAYER) {
            "Controle remoto reconectado"
        } else {
            "Reprodutor reconectado"
        }
        notifyStatus()
        if (role == Role.PLAYER) publishState() else requestRemoteState()
    }

    private fun handleHelloAcknowledgement(token: String) {
        val storedToken = preferences.getString(KEY_PAIR_TOKEN, null)
        if (token.isBlank() || token != storedToken) {
            disconnectForAuthenticationFailure()
            return
        }

        authenticated = true
        currentStatus = if (role == Role.PLAYER) {
            "Controle remoto conectado"
        } else {
            "Controlando ${connectedPeer?.name ?: "reprodutor"}"
        }
        notifyStatus()
        if (role == Role.PLAYER) publishState() else requestRemoteState()
    }

    private fun disconnectForAuthenticationFailure() {
        currentStatus = "A identidade do outro aparelho não pôde ser confirmada"
        notifyStatus()
        connectedEndpoint?.let { connectionsClient.disconnectFromEndpoint(it) }
    }

    private fun saveTrustedPeer(token: String) {
        val peer = connectedPeer ?: return
        preferences.edit()
            .putString(KEY_TRUSTED_PEER_ID, peer.id)
            .putString(KEY_TRUSTED_PEER_NAME, peer.name)
            .putString(KEY_PAIR_TOKEN, token)
            .apply()
    }

    private fun clearTrustedPeer() {
        preferences.edit()
            .remove(KEY_TRUSTED_PEER_ID)
            .remove(KEY_TRUSTED_PEER_NAME)
            .remove(KEY_PAIR_TOKEN)
            .apply()
    }

    private fun sendCommand(command: String, value: Float? = null) {
        if (role != Role.CONTROLLER || !authenticated) return
        val message = JSONObject().put("type", "command").put("command", command)
        value?.let { message.put("value", it.toDouble()) }
        sendMessage(message)
    }

    private fun handleCommand(message: JSONObject) {
        if (role != Role.PLAYER || !authenticated) return

        when (message.optString("command")) {
            "play" -> {
                val volume = preferences.getFloat(NoiseService.KEY_VOLUME, DEFAULT_VOLUME)
                val tone = preferences.getFloat(NoiseService.KEY_TONE, DEFAULT_TONE)
                preferences.edit().putBoolean(NoiseService.KEY_PLAYING, true).apply()
                startForegroundService(
                    Intent(this, NoiseService::class.java)
                        .setAction(NoiseService.ACTION_PLAY)
                        .putExtra(NoiseService.EXTRA_VOLUME, volume)
                        .putExtra(NoiseService.EXTRA_TONE, tone)
                )
            }

            "stop" -> {
                preferences.edit().putBoolean(NoiseService.KEY_PLAYING, false).apply()
                startService(Intent(this, NoiseService::class.java).setAction(NoiseService.ACTION_STOP))
            }

            "volume" -> {
                val volume = message.optDouble("value", DEFAULT_VOLUME.toDouble()).toFloat()
                    .coerceIn(MIN_VOLUME, MAX_VOLUME)
                preferences.edit().putFloat(NoiseService.KEY_VOLUME, volume).apply()
                if (preferences.getBoolean(NoiseService.KEY_PLAYING, false)) {
                    startService(
                        Intent(this, NoiseService::class.java)
                            .setAction(NoiseService.ACTION_SET_VOLUME)
                            .putExtra(NoiseService.EXTRA_VOLUME, volume)
                    )
                }
            }

            "tone" -> {
                val tone = message.optDouble("value", DEFAULT_TONE.toDouble()).toFloat()
                    .coerceIn(MIN_TONE, MAX_TONE)
                preferences.edit().putFloat(NoiseService.KEY_TONE, tone).apply()
                if (preferences.getBoolean(NoiseService.KEY_PLAYING, false)) {
                    startService(
                        Intent(this, NoiseService::class.java)
                            .setAction(NoiseService.ACTION_SET_TONE)
                            .putExtra(NoiseService.EXTRA_TONE, tone)
                    )
                }
            }
        }

        mainHandler.postDelayed({ publishState() }, STATE_SETTLE_DELAY_MS)
    }

    private fun publishState() {
        if (role != Role.PLAYER || !authenticated) return
        val state = readLocalState()
        sendMessage(
            JSONObject()
                .put("type", "state")
                .put("playing", state.playing)
                .put("volume", state.volume.toDouble())
                .put("tone", state.tone.toDouble())
                .put("battery", state.battery)
        )
    }

    private fun receiveState(message: JSONObject) {
        val state = RemoteState(
            playing = message.optBoolean("playing", false),
            volume = message.optDouble("volume", DEFAULT_VOLUME.toDouble()).toFloat()
                .coerceIn(MIN_VOLUME, MAX_VOLUME),
            tone = message.optDouble("tone", DEFAULT_TONE.toDouble()).toFloat()
                .coerceIn(MIN_TONE, MAX_TONE),
            battery = message.optInt("battery", -1)
        )
        listeners.forEach { listener -> mainHandler.post { listener.onRemoteState(state) } }
    }

    private fun readLocalState(): RemoteState {
        val batteryManager = getSystemService(BatteryManager::class.java)
        return RemoteState(
            playing = preferences.getBoolean(NoiseService.KEY_PLAYING, false),
            volume = preferences.getFloat(NoiseService.KEY_VOLUME, DEFAULT_VOLUME)
                .coerceIn(MIN_VOLUME, MAX_VOLUME),
            tone = preferences.getFloat(NoiseService.KEY_TONE, DEFAULT_TONE)
                .coerceIn(MIN_TONE, MAX_TONE),
            battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        )
    }

    private fun sendMessage(message: JSONObject) {
        val endpoint = connectedEndpoint ?: return
        val payload = Payload.fromBytes(message.toString().toByteArray(Charsets.UTF_8))
        connectionsClient.sendPayload(endpoint, payload).addOnFailureListener { error ->
            Log.e(TAG, "Falha ao enviar comando remoto", error)
            currentStatus = formatFailure("Falha ao enviar dados", error)
            notifyStatus()
        }
    }

    private fun statusCodeName(error: Exception): String =
        if (error is ApiException) {
            ConnectionsStatusCodes.getStatusCodeString(error.statusCode)
        } else {
            error.javaClass.simpleName.ifBlank { "ERRO_DESCONHECIDO" }
        }

    private fun formatFailure(prefix: String, error: Exception): String {
        val code = statusCodeName(error)
        val number = (error as? ApiException)?.statusCode
        return if (number == null) "$prefix: $code" else "$prefix: $code ($number)"
    }

    private fun storedRole(): Role = preferences.getString(KEY_REMOTE_ROLE, Role.PLAYER.name)
        ?.let { value -> runCatching { Role.valueOf(value) }.getOrNull() }
        ?: Role.PLAYER

    private fun localEndpointName(): String {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { generated ->
                preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
            }
        val roleCode = if (role == Role.PLAYER) "P" else "C"
        val displayName = if (role == Role.PLAYER) "Quarto do bebê" else "Controle remoto"
        return "RB|$roleCode|${deviceId.take(12)}|$displayName"
    }

    private fun parseEndpointName(value: String): PeerInfo? {
        val parts = value.split('|', limit = 4)
        if (parts.size != 4 || parts[0] != "RB") return null
        val parsedRole = when (parts[1]) {
            "P" -> Role.PLAYER
            "C" -> Role.CONTROLLER
            else -> return null
        }
        return PeerInfo(parsedRole, parts[2], parts[3])
    }

    private fun oppositeRole(): Role = if (role == Role.PLAYER) Role.CONTROLLER else Role.PLAYER

    private fun notifyStatus() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
        val pairedName = preferences.getString(KEY_TRUSTED_PEER_NAME, null)
        val isConnected = connectedEndpoint != null
        listeners.forEach { listener ->
            mainHandler.post {
                listener.onConnectionStatus(currentStatus, isConnected, authenticated, pairedName)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.remote_notification_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.remote_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (role == Role.PLAYER) {
            getString(R.string.remote_notification_player_title)
        } else {
            getString(R.string.remote_notification_controller_title)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_noise)
            .setContentTitle(title)
            .setContentText(currentStatus)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_ROLE = "remote_role"
        const val KEY_REMOTE_ROLE = "remote_role"
        const val KEY_DEVICE_ID = "remote_device_id"
        const val KEY_TRUSTED_PEER_ID = "trusted_peer_id"
        const val KEY_TRUSTED_PEER_NAME = "trusted_peer_name"
        const val KEY_PAIR_TOKEN = "pair_token"

        private const val TAG = "RemoteControlService"
        private const val SERVICE_ID = "com.alvaro.ruidobranco.remote"
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT
        private const val CHANNEL_ID = "remote_connection"
        private const val NOTIFICATION_ID = 8
        private const val TRANSPORT_SETTLE_DELAY_MS = 350L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val DISCOVERY_WATCHDOG_MS = 10_000L
        private const val STATE_SETTLE_DELAY_MS = 180L

        private const val DEFAULT_VOLUME = 0.35f
        private const val MIN_VOLUME = 0.05f
        private const val MAX_VOLUME = 1.00f
        private const val DEFAULT_TONE = -0.35f
        private const val MIN_TONE = -1.00f
        private const val MAX_TONE = 1.00f
    }
}
