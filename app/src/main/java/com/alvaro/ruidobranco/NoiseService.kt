package com.alvaro.ruidobranco

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class NoiseService : Service() {

    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentVolume = DEFAULT_VOLUME

    @Volatile
    private var currentTone = DEFAULT_TONE

    @Volatile
    private var audioTrack: AudioTrack? = null

    private var audioThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val preferences by lazy {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val storedVolume = preferences.getFloat(KEY_VOLUME, DEFAULT_VOLUME)
        val storedTone = preferences.getFloat(KEY_TONE, DEFAULT_TONE)

        currentVolume = intent?.getFloatExtra(EXTRA_VOLUME, storedVolume)
            ?.coerceIn(MIN_VOLUME, MAX_VOLUME)
            ?: storedVolume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        currentTone = intent?.getFloatExtra(EXTRA_TONE, storedTone)
            ?.coerceIn(MIN_TONE, MAX_TONE)
            ?: storedTone.coerceIn(MIN_TONE, MAX_TONE)

        return when (action) {
            ACTION_STOP -> {
                stopPlaybackAndService()
                START_NOT_STICKY
            }

            ACTION_SET_VOLUME -> {
                preferences.edit().putFloat(KEY_VOLUME, currentVolume).apply()
                audioTrack?.setVolume(currentVolume)
                updateNotification()
                if (running.get()) START_STICKY else {
                    stopSelf()
                    START_NOT_STICKY
                }
            }

            ACTION_SET_TONE -> {
                preferences.edit().putFloat(KEY_TONE, currentTone).apply()
                updateNotification()
                if (running.get()) START_STICKY else {
                    stopSelf()
                    START_NOT_STICKY
                }
            }

            ACTION_PLAY, null -> {
                val shouldResume = action == ACTION_PLAY || preferences.getBoolean(KEY_PLAYING, false)
                if (!shouldResume) {
                    stopSelf()
                    START_NOT_STICKY
                } else {
                    preferences.edit()
                        .putBoolean(KEY_PLAYING, true)
                        .putFloat(KEY_VOLUME, currentVolume)
                        .putFloat(KEY_TONE, currentTone)
                        .apply()
                    startForeground(NOTIFICATION_ID, buildNotification())
                    startNoiseIfNeeded()
                    START_STICKY
                }
            }

            else -> START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopNoise()
        releaseWakeLock()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun startNoiseIfNeeded() {
        audioTrack?.setVolume(currentVolume)
        if (!running.compareAndSet(false, true)) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RuidoBranco::ContinuousPlayback"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        audioThread = Thread({ runAudioLoop() }, "RuidoBranco-Audio").apply {
            start()
        }
    }

    private fun runAudioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        val sampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            .coerceAtLeast(44_100)
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimumBufferBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val bufferBytes = max(minimumBufferBytes * 4, sampleRate * 2)
        val buffer = ShortArray(bufferBytes / 2)
        val generator = NoiseGenerator()

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()

            check(track.state == AudioTrack.STATE_INITIALIZED) {
                "Não foi possível inicializar a saída de áudio"
            }

            audioTrack = track
            track.setVolume(currentVolume)

            generator.fill(buffer, currentTone)
            val primed = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            check(primed >= 0) { "Falha ao preparar o buffer de áudio: $primed" }
            track.play()

            while (running.get()) {
                generator.fill(buffer, currentTone)
                val written = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    error("Falha ao escrever áudio: $written")
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Reprodução contínua interrompida", error)
            mainHandler.post {
                if (running.getAndSet(false)) {
                    preferences.edit().putBoolean(KEY_PLAYING, false).apply()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } finally {
            try {
                if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            } catch (_: IllegalStateException) {
                // O AudioTrack pode já ter sido interrompido pelo comando de parada.
            }
            track?.release()
            if (audioTrack === track) audioTrack = null
        }
    }

    private fun stopPlaybackAndService() {
        preferences.edit().putBoolean(KEY_PLAYING, false).apply()
        stopNoise()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopNoise() {
        running.set(false)
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (_: IllegalStateException) {
            // A thread de áudio fará a liberação final.
        }
        audioThread?.interrupt()
        audioThread = null
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NoiseService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val percentage = (currentVolume * 100).roundToInt()
        val toneLabel = getString(toneDescriptionResource(currentTone))
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_noise)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, percentage, toneLabel))
            .setContentIntent(openAppPendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.stop),
                stopPendingIntent
            )
            .build()
    }

    private fun toneDescriptionResource(value: Float): Int = when {
        value <= -0.65f -> R.string.tone_very_deep
        value < -0.18f -> R.string.tone_deep
        value >= 0.65f -> R.string.tone_very_bright
        value > 0.18f -> R.string.tone_bright
        else -> R.string.tone_neutral
    }

    private fun updateNotification() {
        if (!running.get()) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val ACTION_PLAY = "com.alvaro.ruidobranco.PLAY"
        const val ACTION_STOP = "com.alvaro.ruidobranco.STOP"
        const val ACTION_SET_VOLUME = "com.alvaro.ruidobranco.SET_VOLUME"
        const val ACTION_SET_TONE = "com.alvaro.ruidobranco.SET_TONE"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_TONE = "tone"

        const val PREFERENCES_NAME = "noise_preferences"
        const val KEY_PLAYING = "playing"
        const val KEY_VOLUME = "volume"
        const val KEY_TONE = "tone"

        private const val TAG = "NoiseService"
        private const val CHANNEL_ID = "continuous_noise"
        private const val NOTIFICATION_ID = 7
        private const val DEFAULT_VOLUME = 0.35f
        private const val MIN_VOLUME = 0.05f
        private const val MAX_VOLUME = 1.00f
        private const val DEFAULT_TONE = -0.35f
        private const val MIN_TONE = -1.00f
        private const val MAX_TONE = 1.00f
    }
}
