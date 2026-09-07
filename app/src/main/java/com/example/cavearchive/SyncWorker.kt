package com.cavearchive.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import androidx.compose.ui.graphics.Color

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
            val account = GoogleSignIn.getLastSignedInAccount(applicationContext) ?: return@withContext Result.retry()
            val credential = GoogleAccountCredential.usingOAuth2(
                applicationContext,
                Collections.singleton(DriveScopes.DRIVE_READONLY)
            ).apply { selectedAccount = account.account }
            
            val drive = Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("CaveArchive").build()

            for (archive in autoSyncArchives) {
                try {
                    val folderId = (if (archive.url.contains("/folders/")) {
                        archive.url.split("/folders/").getOrNull(1)?.split("?")?.getOrNull(0)
                    } else if (archive.url.contains("id=")) {
                        archive.url.split("id=").getOrNull(1)?.split("&")?.getOrNull(0)
                    } else {
                        null
                    }) ?: continue

                    // Check quick timestamp
                    val rootFiles = drive.files().list()
                        .setQ("'$folderId' in parents and trashed = false")
                        .setFields("files(id, name, modifiedTime)")
                        .execute().files ?: emptyList()

                    val cavesJsonPattern = Regex("caves([0-9a-fA-F]{5})\\.json")
                    val latestCavesFile = rootFiles.mapNotNull { f ->
                        val match = cavesJsonPattern.matchEntire(f.name)
                        if (match != null) f to match.groupValues[1].toInt(16) else null
                    }.maxByOrNull { it.second }?.first ?: continue

                    val remoteModifiedTime = latestCavesFile.modifiedTime?.toString() ?: ""
                    val lastSyncTime = sharedPrefs.getString("last_remote_sync_time_${archive.id}", "")

                    if (remoteModifiedTime.isNotEmpty() && remoteModifiedTime == lastSyncTime) continue

                    val (success, _, totalUpdates) = syncDriveFolderOAuth(
                        applicationContext,
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
        val notification = NotificationCompat.Builder(applicationContext, "sync_channel")
            .setSmallIcon(R.mipmap.cave_icon)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(1001, notification)
    }
}
