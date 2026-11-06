package com.cavearchive.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream

enum class RepositoryType {
    ONEDRIVE,
    FTP
}

data class RemoteFileItem(
    val id: String,
    val name: String,
    val size: Long,
    val lastModifiedTime: String,
    val downloadUrl: String? = null,
    val isFolder: Boolean = false
)

interface RepositoryProvider {
    val type: RepositoryType

    suspend fun isAuthenticated(context: Context): Boolean
    suspend fun authenticate(activity: android.app.Activity, onLog: ((String, Color) -> Unit)? = null): Boolean
    suspend fun logout(context: Context, onLog: ((String, Color) -> Unit)? = null)

    suspend fun resolveFolderId(context: Context, folderIdOrUrl: String): String
    suspend fun listChildren(context: Context, folderIdOrUrl: String): List<RemoteFileItem>
    suspend fun downloadText(context: Context, item: RemoteFileItem): String
    suspend fun downloadFileWithProgress(
        context: Context,
        item: RemoteFileItem,
        outputStream: OutputStream,
        onProgress: suspend (Int) -> Unit
    )
    suspend fun getOrCreateFolder(context: Context, parentFolderIdOrUrl: String, folderName: String): String
    suspend fun uploadFile(
        context: Context,
        parentFolderIdOrUrl: String,
        fileName: String,
        fileBytes: ByteArray,
        contentType: String = "application/octet-stream"
    ): RemoteFileItem
    suspend fun deleteFile(context: Context, fileId: String)
}

class OneDriveRepositoryProvider(
    private var msalApp: ISingleAccountPublicClientApplication? = null
) : RepositoryProvider {
    override val type: RepositoryType = RepositoryType.ONEDRIVE

    override suspend fun isAuthenticated(context: Context): Boolean {
        val token = getOneDriveToken(context, msalApp)
        return !token.isNullOrEmpty()
    }

    override suspend fun authenticate(activity: android.app.Activity, onLog: ((String, Color) -> Unit)?): Boolean {
        return isAuthenticated(activity)
    }

    override suspend fun logout(context: Context, onLog: ((String, Color) -> Unit)?) {}

    override suspend fun resolveFolderId(context: Context, folderIdOrUrl: String): String {
        val token = getOneDriveToken(context, msalApp) ?: throw Exception("OneDrive token not available")
        return resolveOneDriveFolderId(token, folderIdOrUrl)
    }

    override suspend fun listChildren(context: Context, folderIdOrUrl: String): List<RemoteFileItem> {
        val token = getOneDriveToken(context, msalApp) ?: throw Exception("OneDrive token not available")
        val items = listOneDriveFolderChildren(token, folderIdOrUrl)
        return items.map {
            RemoteFileItem(
                id = it.id,
                name = it.name,
                size = it.size,
                lastModifiedTime = it.lastModifiedTime,
                downloadUrl = it.downloadUrl,
                isFolder = it.isFolder
            )
        }
    }

    override suspend fun downloadText(context: Context, item: RemoteFileItem): String {
        val token = getOneDriveToken(context, msalApp) ?: throw Exception("OneDrive token not available")
        val oneDriveItem = OneDriveItem(item.id, item.name, item.size, item.lastModifiedTime, item.downloadUrl, item.isFolder)
        return downloadOneDriveText(token, oneDriveItem)
    }

    override suspend fun downloadFileWithProgress(
        context: Context,
        item: RemoteFileItem,
        outputStream: OutputStream,
        onProgress: suspend (Int) -> Unit
    ) {
        val token = getOneDriveToken(context, msalApp) ?: throw Exception("OneDrive token not available")
        val oneDriveItem = OneDriveItem(item.id, item.name, item.size, item.lastModifiedTime, item.downloadUrl, item.isFolder)
        downloadOneDriveFileWithProgress(token, oneDriveItem, outputStream, onProgress)
    }

    override suspend fun getOrCreateFolder(context: Context, parentFolderIdOrUrl: String, folderName: String): String {
        val token = getOneDriveToken(context, msalApp) ?: throw Exception("OneDrive token not available")
        return getOrCreateOneDriveFolder(token, parentFolderIdOrUrl, folderName)
    }

    override suspend fun uploadFile(
        context: Context,
        parentFolderIdOrUrl: String,
        fileName: String,
        fileBytes: ByteArray,
        contentType: String
    ): RemoteFileItem {
        val token = getOneDriveToken(context, msalApp) ?: throw Exception("OneDrive token not available")
        val uploaded = uploadOneDriveFile(token, parentFolderIdOrUrl, fileName, fileBytes, contentType)
        return RemoteFileItem(
            id = uploaded.id,
            name = uploaded.name,
            size = uploaded.size,
            lastModifiedTime = uploaded.lastModifiedTime,
            downloadUrl = uploaded.downloadUrl,
            isFolder = uploaded.isFolder
        )
    }

    override suspend fun deleteFile(context: Context, fileId: String) {
        val token = getOneDriveToken(context, msalApp) ?: throw Exception("OneDrive token not available")
        deleteOneDriveFile(token, fileId)
    }
}

class FtpRepositoryProvider(
    private val archiveConfig: ArchiveConfig? = null
) : RepositoryProvider {
    override val type: RepositoryType = RepositoryType.FTP

    override suspend fun isAuthenticated(context: Context): Boolean {
        val host = archiveConfig?.url ?: ""
        val user = archiveConfig?.ftpUsername ?: ""
        return host.isNotBlank() && user.isNotBlank()
    }

    override suspend fun authenticate(activity: android.app.Activity, onLog: ((String, Color) -> Unit)?): Boolean {
        return isAuthenticated(activity)
    }

    override suspend fun logout(context: Context, onLog: ((String, Color) -> Unit)?) {}

    private fun parseFtpUrl(url: String): Triple<String, Int, String> {
        var clean = url.trim()
        if (clean.startsWith("ftp://", ignoreCase = true)) {
            clean = clean.substring(6)
        }
        val parts = clean.split("/", limit = 2)
        val hostPort = parts[0]
        val path = if (parts.size > 1 && parts[1].isNotBlank()) "/" + parts[1] else "/"

        val hostPortParts = hostPort.split(":")
        val host = hostPortParts[0]
        val port = if (hostPortParts.size > 1) hostPortParts[1].toIntOrNull() ?: 21 else 21

        return Triple(host, port, path)
    }

    private fun <T> withFtp(block: (FTPClient) -> T): T {
        val hostUrl = archiveConfig?.url ?: throw Exception("FTP URL not set")
        val username = archiveConfig.ftpUsername
        val password = archiveConfig.ftpPassword

        val (host, port, _) = parseFtpUrl(hostUrl)
        val ftp = FTPClient()
        ftp.connectTimeout = 15000
        ftp.defaultTimeout = 15000
        try {
            ftp.connect(host, port)
            val reply = ftp.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftp.disconnect()
                throw Exception("FTP connection failed: reply $reply")
            }
            if (!ftp.login(username, password)) {
                ftp.disconnect()
                throw Exception("FTP login failed for user '$username'")
            }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)

            val result = block(ftp)

            try { ftp.logout() } catch (e: Exception) {}
            return result
        } finally {
            if (ftp.isConnected) {
                try { ftp.disconnect() } catch (e: Exception) {}
            }
        }
    }

    override suspend fun resolveFolderId(context: Context, folderIdOrUrl: String): String = withContext(Dispatchers.IO) {
        val (_, _, path) = parseFtpUrl(folderIdOrUrl)
        path
    }

    override suspend fun listChildren(context: Context, folderIdOrUrl: String): List<RemoteFileItem> = withContext(Dispatchers.IO) {
        val path = if (folderIdOrUrl.startsWith("/")) folderIdOrUrl else "/" + folderIdOrUrl
        withFtp { ftp ->
            val files = ftp.listFiles(path) ?: emptyArray()
            files.map { f ->
                val fullPath = if (path.endsWith("/")) path + f.name else "$path/${f.name}"
                RemoteFileItem(
                    id = fullPath,
                    name = f.name,
                    size = f.size,
                    lastModifiedTime = f.timestamp?.timeInMillis?.toString() ?: "0",
                    isFolder = f.isDirectory
                )
            }
        }
    }

    override suspend fun downloadText(context: Context, item: RemoteFileItem): String = withContext(Dispatchers.IO) {
        withFtp { ftp ->
            val baos = ByteArrayOutputStream()
            val ok = ftp.retrieveFile(item.id, baos)
            if (!ok) throw Exception("Failed to download text file: ${item.name}")
            baos.toString("UTF-8")
        }
    }

    override suspend fun downloadFileWithProgress(
        context: Context,
        item: RemoteFileItem,
        outputStream: OutputStream,
        onProgress: suspend (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        withFtp { ftp ->
            val totalBytes = item.size
            var bytesDownloaded = 0L
            var lastReportedPct = -1

            val countingOut = object : FilterOutputStream(outputStream) {
                override fun write(b: Int) {
                    out.write(b)
                    bytesDownloaded++
                    reportProgress()
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    bytesDownloaded += len
                    reportProgress()
                }

                private fun reportProgress() {
                    if (totalBytes > 0) {
                        val pct = ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct
                            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                try { onProgress(pct) } catch (e: Exception) {}
                            }
                        }
                    }
                }
            }

            val ok = ftp.retrieveFile(item.id, countingOut)
            if (!ok) throw Exception("Failed to download file: ${item.name}")
        }
    }

    override suspend fun getOrCreateFolder(context: Context, parentFolderIdOrUrl: String, folderName: String): String = withContext(Dispatchers.IO) {
        val parentPath = if (parentFolderIdOrUrl.startsWith("/")) parentFolderIdOrUrl else "/" + parentFolderIdOrUrl
        val targetPath = if (parentPath.endsWith("/")) parentPath + folderName else "$parentPath/$folderName"

        withFtp { ftp ->
            if (!ftp.changeWorkingDirectory(targetPath)) {
                ftp.makeDirectory(targetPath)
            }
            targetPath
        }
    }

    override suspend fun uploadFile(
        context: Context,
        parentFolderIdOrUrl: String,
        fileName: String,
        fileBytes: ByteArray,
        contentType: String
    ): RemoteFileItem = withContext(Dispatchers.IO) {
        val parentPath = if (parentFolderIdOrUrl.startsWith("/")) parentFolderIdOrUrl else "/" + parentFolderIdOrUrl
        val filePath = if (parentPath.endsWith("/")) parentPath + fileName else "$parentPath/$fileName"

        withFtp { ftp ->
            val inputStream = ByteArrayInputStream(fileBytes)
            val ok = ftp.storeFile(filePath, inputStream)
            if (!ok) throw Exception("Failed to upload file to FTP: $fileName")

            RemoteFileItem(
                id = filePath,
                name = fileName,
                size = fileBytes.size.toLong(),
                lastModifiedTime = System.currentTimeMillis().toString(),
                isFolder = false
            )
        }
    }

    override suspend fun deleteFile(context: Context, fileId: String) = withContext(Dispatchers.IO) {
        withFtp { ftp ->
            ftp.deleteFile(fileId)
        }
        Unit
    }
}

object RepositoryProviderFactory {
    fun getProvider(type: RepositoryType, msalApp: ISingleAccountPublicClientApplication? = null): RepositoryProvider {
        return when (type) {
            RepositoryType.ONEDRIVE -> OneDriveRepositoryProvider(msalApp)
            RepositoryType.FTP -> FtpRepositoryProvider()
        }
    }

    fun getProvider(archive: ArchiveConfig, msalApp: ISingleAccountPublicClientApplication? = null): RepositoryProvider {
        return when (archive.repositoryType) {
            RepositoryType.ONEDRIVE -> OneDriveRepositoryProvider(msalApp)
            RepositoryType.FTP -> FtpRepositoryProvider(archive)
        }
    }
}
