package io.github.aedev.flow.data.video.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.aedev.flow.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class LiveRecordingState(
    val videoId: String? = null,
    val status: Status = Status.IDLE,
    val seconds: Long = 0,
    val bytes: Long = 0,
    val details: String = "",
) {
    enum class Status { IDLE, CONNECTING, RECORDING, SAVING, SAVED, FAILED }
    val busy get() = status == Status.CONNECTING || status == Status.RECORDING || status == Status.SAVING
}

/** User-initiated foreground transfer, separate from ordinary VOD downloads. */
class LiveRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var stopRequested = false
    private var lastNotificationAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.live_recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                if (state.value.busy) publishState(state.value.copy(status = LiveRecordingState.Status.SAVING))
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                val title = intent.getStringExtra(EXTRA_TITLE)?.take(160).orEmpty().ifBlank { id.orEmpty() }
                if (id == null || job?.isActive == true) return START_NOT_STICKY
                stopRequested = false
                publishState(LiveRecordingState(videoId = id, status = LiveRecordingState.Status.CONNECTING))
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification(title), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIFICATION_ID, notification(title))
                    }
                } catch (e: RuntimeException) {
                    publishState(state.value.copy(status = LiveRecordingState.Status.FAILED, details = e.message.orEmpty()))
                    stopSelf()
                    return START_NOT_STICKY
                }
                job = scope.launch {
                    val folder = File(filesDir, "live-recordings/$id-${System.currentTimeMillis()}")
                    try {
                        val recorder = LiveHlsRecorder(id, folder, { stopRequested }) { seconds, bytes ->
                            publishState(LiveRecordingState(id, LiveRecordingState.Status.RECORDING, seconds, bytes))
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastNotificationAt >= 5_000L) {
                                lastNotificationAt = now
                                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title))
                            }
                        }
                        val videoFile = recorder.record()
                        publishState(state.value.copy(status = LiveRecordingState.Status.SAVING))
                        val location = saveRecording(videoFile, title)
                        publishState(state.value.copy(status = LiveRecordingState.Status.SAVED, details = location))
                        folder.deleteRecursively()
                    } catch (e: CancellationException) {
                        publishState(state.value.copy(status = LiveRecordingState.Status.FAILED, details = "Interrupted; captured segments retained"))
                        throw e
                    } catch (e: Exception) {
                        val partial = File(folder, "capture.ts")
                        val salvaged = if (partial.length() >= 188) {
                            runCatching { saveRecording(partial, "$title (partial)") }.getOrNull()
                        } else null
                        if (salvaged != null) folder.deleteRecursively()
                        publishState(
                            state.value.copy(
                                status = LiveRecordingState.Status.FAILED,
                                details = listOfNotNull(e.message, salvaged?.let { "Partial recording saved: $it" }).joinToString(". "),
                            ),
                        )
                    } finally {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    /** Copies to Movies/My Flow on Android 10+; keeps an app-owned copy on older devices. */
    private suspend fun saveRecording(source: File, title: String): String = withContext(Dispatchers.IO) {
        val clean = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().take(100).ifBlank { "Livestream" }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val name = "$clean-$timestamp.ts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/My Flow")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create recording in Movies")
            try {
                val output = contentResolver.openOutputStream(uri) ?: throw IOException("Cannot write recording")
                output.use { out -> source.inputStream().use { it.copyTo(out) } }
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                uri.toString()
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            val destination = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir, name)
            source.copyTo(destination, overwrite = false)
            MediaScannerConnection.scanFile(this@LiveRecordingService, arrayOf(destination.absolutePath), arrayOf("video/mp2t"), null)
            destination.absolutePath
        }
    }

    private fun notification(title: String): Notification {
        val stopPending = PendingIntent.getService(
            this, 0, Intent(this, LiveRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val current = state.value
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(getString(R.string.live_recording_notification, current.seconds, current.bytes / (1024 * 1024)))
            .setOngoing(current.busy)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.live_recording_stop_save), stopPending)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "io.github.aedev.flow.live.START"
        private const val ACTION_STOP = "io.github.aedev.flow.live.STOP"
        private const val EXTRA_ID = "video_id"
        private const val EXTRA_TITLE = "video_title"
        private const val CHANNEL_ID = "flow-live-recording"
        private const val NOTIFICATION_ID = 9714
        private val mutableState = MutableStateFlow(LiveRecordingState())
        internal val state = mutableState.asStateFlow()
        private fun publishState(next: LiveRecordingState) { mutableState.value = next }

        fun start(context: Context, videoId: String, title: String) {
            val intent = Intent(context, LiveRecordingService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_ID, videoId).putExtra(EXTRA_TITLE, title)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LiveRecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
