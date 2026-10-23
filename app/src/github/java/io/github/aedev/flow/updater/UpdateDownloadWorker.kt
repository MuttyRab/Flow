package io.github.aedev.flow.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import io.github.aedev.flow.MainActivity
import io.github.aedev.flow.R
import io.github.aedev.flow.data.update.UpdateEntryPoint
import io.github.aedev.flow.data.update.UpdateFailure
import io.github.aedev.flow.data.update.UpdateTransferPolicy
import io.github.aedev.flow.notification.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val PROGRESS_NOTIFICATION_ID = 9998
private const val READY_NOTIFICATION_ID = 9999
private const val BUFFER_SIZE = 64 * 1024
private const val PROGRESS_STEP = 0.01f
private const val PROGRESS_INTERVAL_MS = 250L
private const val PERCENT = 100
private const val MAX_NETWORK_ATTEMPTS = 4

/**
 * Downloads one release APK in the foreground, hashing it as it arrives. The file is kept only if
 * its SHA-256 matches the digest GitHub published and it is a Flow package; anything else is
 * deleted and reported as a failure.
 */
class UpdateDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val version = inputData.getString(KEY_VERSION).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val url = inputData.getString(KEY_URL) ?: return@withContext fail(UpdateFailure.NETWORK)
            val expectedSha = inputData.getString(KEY_SHA256)
            val expectedBytes = inputData.getLong(KEY_SIZE, 0L)
            setForeground(foregroundInfo(null))
            val part = UpdateFiles.partFile(applicationContext, version)
            try {
                val actualSha = download(url, part, expectedBytes)
                setProgress(workDataOf(KEY_PHASE to PHASE_VERIFY))
                if (expectedSha != null &&
                    !expectedSha.equals(actualSha, ignoreCase = true)
                ) {
                    return@withContext fail(UpdateFailure.CHECKSUM, part)
                }
                if (!isFlowPackage(part)) return@withContext fail(UpdateFailure.PACKAGE, part)
                if (!part.renameTo(UpdateFiles.apkFile(applicationContext, version))) return@withContext fail(UpdateFailure.STORAGE, part)
                notifyReady()
                Result.success()
            } catch (e: IOException) {
                val storageFailure =
                    generateSequence<Throwable>(e) { it.cause }.any { cause ->
                        cause.message.orEmpty().contains("ENOSPC", ignoreCase = true) ||
                            cause.message.orEmpty().contains("No space left", ignoreCase = true)
                    }
                when {
                    storageFailure -> fail(UpdateFailure.STORAGE, part)
                    runAttemptCount < MAX_NETWORK_ATTEMPTS - 1 -> Result.retry()
                    else -> fail(UpdateFailure.NETWORK) // Retain the partial file for a manual retry.
                }
            }
        }

    private suspend fun download(
        url: String,
        target: File,
        expectedBytes: Long,
    ): String {
        val client =
            EntryPointAccessors
                .fromApplication(applicationContext, UpdateEntryPoint::class.java)
                .okHttpClient()
                .newBuilder()
                .cache(null)
                .readTimeout(2, TimeUnit.MINUTES)
                .build()
        val existing = target.takeIf { it.exists() }?.length() ?: 0L
        val offset = UpdateTransferPolicy.resumeOffset(existing, expectedBytes)
        if (existing > 0L && offset == 0L) target.delete()
        // A previous attempt might have received every byte before its connection failed.
        if (expectedBytes > 0L && offset == expectedBytes) return sha256(target)

        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
                .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 416 && offset > 0L) {
                // The server cannot resume this file. Start it over rather than retrying a bad range.
                target.delete()
                return download(url, target, expectedBytes)
            }
            if (!response.isSuccessful) throw IOException("HTTP " + response.code)
            if (response.code == 206 &&
                !UpdateTransferPolicy.acceptsPartialResponse(response.header("Content-Range"), offset, expectedBytes)
            ) {
                target.delete()
                throw IOException("Invalid partial response")
            }
            // A server may ignore Range and return HTTP 200. Truncate, never append a full APK.
            val append = offset > 0L && response.code == 206
            val initialBytes = if (append) offset else 0L
            val responseBytes = response.body.contentLength().takeIf { it >= 0L }?.let { it + initialBytes }
            val total = expectedBytes.takeIf { it > 0L } ?: responseBytes
            var received = initialBytes
            var reported = -1f
            var reportedAt = 0L
            if (initialBytes > 0L && total != null && total > 0L) {
                val progress = initialBytes.toFloat() / total
                setProgress(workDataOf(KEY_PHASE to PHASE_DOWNLOAD, KEY_PROGRESS to progress))
                setForeground(foregroundInfo(progress))
            }
            response.body.byteStream().use { input ->
                FileOutputStream(target, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        received += count
                        val progress = total?.takeIf { it > 0L }?.let { received.toFloat() / it }
                        val now = System.currentTimeMillis()
                        if (progress != null && progress - reported >= PROGRESS_STEP && now - reportedAt >= PROGRESS_INTERVAL_MS) {
                            reported = progress
                            reportedAt = now
                            setProgress(workDataOf(KEY_PHASE to PHASE_DOWNLOAD, KEY_PROGRESS to progress.coerceIn(0f, 1f)))
                            setForeground(foregroundInfo(progress.coerceIn(0f, 1f)))
                        }
                    }
                }
            }
            if (expectedBytes > 0L && received != expectedBytes) {
                throw EOFException("Incomplete APK: received $received of $expectedBytes bytes")
            }
            if (responseBytes != null && received != responseBytes) {
                throw EOFException("Incomplete response: received $received of $responseBytes bytes")
            }
        }
        return sha256(target)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isFlowPackage(apk: File): Boolean =
        applicationContext.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName == applicationContext.packageName

    private fun fail(
        reason: UpdateFailure,
        part: File? = null,
    ): Result {
        part?.delete()
        return Result.failure(workDataOf(KEY_FAILURE to reason.name))
    }

    private fun foregroundInfo(progress: Float?): ForegroundInfo {
        val notification =
            NotificationCompat
                .Builder(applicationContext, NotificationHelper.CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(applicationContext.getString(R.string.update_downloading_notification, version))
                .setProgress(PERCENT, ((progress ?: 0f) * PERCENT).toInt(), progress == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    0,
                    applicationContext.getString(R.string.cancel),
                    WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
                ).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun notifyReady() {
        val open =
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(NotificationHelper.EXTRA_OPEN_UPDATE, true)
        val notification =
            NotificationCompat
                .Builder(applicationContext, NotificationHelper.CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(applicationContext.getString(R.string.update_ready_notification, version))
                .setContentText(applicationContext.getString(R.string.update_ready_notification_text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        applicationContext,
                        0,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setAutoCancel(true)
                .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(READY_NOTIFICATION_ID, notification) }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_SIZE = "size"
        const val KEY_VERSION = "version"
        const val KEY_PHASE = "phase"
        const val KEY_PROGRESS = "progress"
        const val KEY_FAILURE = "failure"
        const val PHASE_DOWNLOAD = "download"
        const val PHASE_VERIFY = "verify"
    }
}

/** Where downloaded updates live: one directory in the cache, one file per version. */
internal object UpdateFiles {
    private const val DIRECTORY = "updates"

    private fun directory(context: Context) = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    fun apkFile(
        context: Context,
        version: String,
    ) = File(directory(context), "flow-$version.apk")

    fun partFile(
        context: Context,
        version: String,
    ) = File(directory(context), "flow-$version.apk.part")

    /** Deletes every downloaded update except [keepVersion]'s. */
    fun clean(
        context: Context,
        keepVersion: String?,
    ) {
        val keep =
            keepVersion?.let { setOf(apkFile(context, it).name, partFile(context, it).name) }.orEmpty()
        directory(context).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }
}
