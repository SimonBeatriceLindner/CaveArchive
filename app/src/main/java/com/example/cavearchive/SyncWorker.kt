package com.cavearchive.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sharedPrefs = applicationContext.getSharedPreferences("CaveArchivePrefs", Context.MODE_PRIVATE)
        val folderUriStr = sharedPrefs.getString("selected_folder_uri", null) ?: return@withContext Result.failure()
        val folderUri = folderUriStr.toUri()

        // Carica tutti gli archivi e filtra quelli con auto-sync attivo
        val allArchives = loadSettings(applicationContext, folderUri)
        val autoSyncArchives = allArchives.filter { it.isAutoSync }

        if (autoSyncArchives.isEmpty()) return@withContext Result.success()

        var overallSuccess = true
        var updatedCount = 0

        try {
            val token = getOneDriveToken(applicationContext) ?: return@withContext Result.retry()

            for (archive in autoSyncArchives) {
                try {
                    val folderIdOrUrl = archive.url.trim()
                    if (folderIdOrUrl.isBlank()) continue

                    val rootFiles = listOneDriveFolderChildren(token, folderIdOrUrl)

                    val cavesJsonPattern = Regex("caves([0-9a-fA-F]{5})\\.json")
                    val latestCavesFile = rootFiles.mapNotNull { f ->
                        val match = cavesJsonPattern.matchEntire(f.name)
                        if (match != null) f to match.groupValues[1].toInt(16) else null
                    }.maxByOrNull { it.second }?.first ?: continue

                    val remoteModifiedTime = latestCavesFile.lastModifiedTime
                    val lastSyncTime = sharedPrefs.getString("last_remote_sync_time_${archive.id}", "")

                    if (remoteModifiedTime.isNotEmpty() && remoteModifiedTime == lastSyncTime) continue

                    val (success, _, totalUpdates) = syncOneDriveFolder(
                        applicationContext,
                        null,
                        archive,
                        folderUri,
                        { _, _ -> },
                        { _, _ -> }
                    )

                    if (success) {
                        sharedPrefs.edit().putString("last_remote_sync_time_${archive.id}", remoteModifiedTime).apply()
                        if (totalUpdates > 0) {
                            updatedCount++
                        }
                    } else {
                        overallSuccess = false
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    overallSuccess = false
                }
            }

            if (updatedCount > 0) {
                showNotification(
                    applicationContext.getString(R.string.app_name),
                    applicationContext.getString(R.string.sync_completed) + " ($updatedCount archives updated)"
                )
            }

            return@withContext if (overallSuccess) Result.success() else Result.retry()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.retry()
        }
    }

    private fun showNotification(title: String, message: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val largeIcon = try {
            BitmapFactory.decodeResource(applicationContext.resources, R.mipmap.cave_icon)
        } catch (_: Exception) {
            null
        }

        val notificationBuilder = NotificationCompat.Builder(applicationContext, "sync_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (largeIcon != null) {
            notificationBuilder.setLargeIcon(largeIcon)
        }

        notificationManager.notify(1001, notificationBuilder.build())
    }
}
