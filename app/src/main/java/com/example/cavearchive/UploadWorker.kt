package com.cavearchive.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_ARCHIVE_ID = "archive_id"
        const val KEY_ARCHIVE_NAME = "archive_name"
        const val KEY_ARCHIVE_URL = "archive_url"
        const val KEY_SURVEY_ID = "survey_id"
        const val KEY_SURVEY_NAME = "survey_name"
        const val KEY_NEW_NAME = "new_name"
        const val KEY_UPLOAD_GEOPOINT = "upload_geopoint"
        const val KEY_JUNCTIONS_JSON = "junctions_json"
        const val KEY_UPLOAD_TYPE = "upload_type" // "survey" or "dem"
        const val KEY_DEM_FILE_URI = "dem_file_uri"
        const val KEY_DEM_FILE_NAME = "dem_file_name"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sharedPrefs = applicationContext.getSharedPreferences("CaveArchivePrefs", Context.MODE_PRIVATE)
        val folderUriStr = sharedPrefs.getString("selected_folder_uri", null) ?: return@withContext Result.failure()
        val localFolderUri = folderUriStr.toUri()

        val archiveId = inputData.getInt(KEY_ARCHIVE_ID, 0)
        val archiveName = inputData.getString(KEY_ARCHIVE_NAME) ?: ""
        val archiveUrl = inputData.getString(KEY_ARCHIVE_URL) ?: ""
        val uploadType = inputData.getString(KEY_UPLOAD_TYPE) ?: "survey"
        val newName = inputData.getString(KEY_NEW_NAME) ?: ""

        val archive = ArchiveConfig(id = archiveId, name = archiveName, url = archiveUrl)

        val notificationId = System.currentTimeMillis().toInt()
        showForegroundNotification(notificationId, newName)

        var uploadSuccess = false
        var lastErrorMessage = ""

        try {
            val token = getOneDriveToken(applicationContext)
            if (token == null) {
                showFailureNotification(newName, "Autenticazione OneDrive non disponibile. Effettua il login nell'app.")
                return@withContext Result.failure()
            }

            if (uploadType == "dem") {
                val demFileUriStr = inputData.getString(KEY_DEM_FILE_URI) ?: return@withContext Result.failure()
                val demFileName = inputData.getString(KEY_DEM_FILE_NAME) ?: "dem.asc"

                uploadDemToDrive(
                    context = applicationContext,
                    archive = archive,
                    fileUri = demFileUriStr.toUri(),
                    fileName = demFileName,
                    onLog = { msg, _ -> lastErrorMessage = msg }
                )
                uploadSuccess = true
            } else {
                val surveyId = inputData.getInt(KEY_SURVEY_ID, 0)
                val surveyName = inputData.getString(KEY_SURVEY_NAME) ?: newName
                val uploadGeopoint = inputData.getBoolean(KEY_UPLOAD_GEOPOINT, true)
                val junctionsJsonStr = inputData.getString(KEY_JUNCTIONS_JSON) ?: "[]"

                val junctions = mutableListOf<JunctionData>()
                val jArray = JSONArray(junctionsJsonStr)
                for (i in 0 until jArray.length()) {
                    val obj = jArray.getJSONObject(i)
                    junctions.add(
                        JunctionData(
                            firstStation = obj.getString("firstStation"),
                            secondStation = obj.getString("secondStation"),
                            otherSurveyName = obj.getString("otherSurveyName")
                        )
                    )
                }

                uploadSurveyToDrive(
                    context = applicationContext,
                    archive = archive,
                    localFolderUri = localFolderUri,
                    survey = SurveyInfo(id = surveyId, name = surveyName),
                    newName = newName,
                    uploadGeopoint = uploadGeopoint,
                    junctions = junctions,
                    onLog = { msg, _ -> lastErrorMessage = msg }
                )
                uploadSuccess = true
            }

            showSuccessNotification(newName)
            return@withContext Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMsg = e.localizedMessage ?: lastErrorMessage.ifEmpty { "Errore sconosciuto" }
            showFailureNotification(newName, errorMsg)
            return@withContext Result.failure()
        }
    }

    private suspend fun showForegroundNotification(notificationId: Int, name: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = try {
            BitmapFactory.decodeResource(applicationContext.resources, R.mipmap.cave_icon)
        } catch (_: Exception) { null }

        val builder = NotificationCompat.Builder(applicationContext, "sync_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Upload in corso: $name")
            .setContentText("Invio dati a OneDrive in corso...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, builder.build())
        }

        try {
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, builder.build())
        }
    }

    private fun showSuccessNotification(name: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(applicationContext, "sync_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Upload completato")
            .setContentText("Il rilievo $name è stato caricato con successo su OneDrive.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun showFailureNotification(name: String, errorMsg: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(applicationContext, "sync_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Upload fallito: $name")
            .setContentText("Impossibile completare il caricamento: $errorMsg")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
