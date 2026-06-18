package com.cavearchive.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.cavearchive.app.ui.theme.CaveArchiveTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.zip.ZipInputStream
import java.io.File as JFile

data class LogMessage(val text: String, val color: Color, val time: String)
data class ArchiveConfig(val id: Int = 0, val name: String, val url: String)
data class SurveyInfo(val id: Int, val name: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CaveArchiveTheme {
                MainScreen()
            }
        }
    }
}

private const val PREFS_NAME = "CaveArchivePrefs"
private const val KEY_FOLDER_URI = "selected_folder_uri"
private const val KEY_ARCHIVE_URL = "archive_url"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPrefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var showPermissionDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showWipeDialog by remember { mutableStateOf(false) }
    var wipeAllData by remember { mutableStateOf(true) }
    var archiveToWipe by remember { mutableStateOf<ArchiveConfig?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showManageArchivesDialog by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }

    var archives by remember { mutableStateOf(emptyList<ArchiveConfig>()) }
    var selectedArchive by remember { mutableStateOf<ArchiveConfig?>(null) }

    val appVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    var isSyncing by remember { mutableStateOf(false) }
    var isCancelled by remember { mutableStateOf(false) }
    var syncJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isSignedIn by remember { mutableStateOf(GoogleSignIn.getLastSignedInAccount(context) != null) }
    var isDbFound by remember { mutableStateOf(false) }

    val logs = remember { mutableStateListOf<LogMessage>() }
    val listState = rememberLazyListState()

    // Auto-scroll logs to bottom when new entries are added
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    fun addLog(text: String, color: Color = Color.White) {
        val currentTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logs.add(LogMessage(text, color, currentTime))
    }

    val selectedFolderUriString = sharedPrefs.getString(KEY_FOLDER_URI, null)
    var selectedFolderUri by remember {
        mutableStateOf(selectedFolderUriString?.toUri())
    }

    // Caricamento permessi e cartella di default
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showPermissionDialog = true
            } else if (sharedPrefs.getString(KEY_FOLDER_URI, null) == null) {
                val defaultPath = JFile(Environment.getExternalStorageDirectory(), "Documents/TDX/TopoDroid")
                if (defaultPath.exists() && defaultPath.isDirectory) {
                    val uri = Uri.fromFile(defaultPath)
                    sharedPrefs.edit { putString(KEY_FOLDER_URI, uri.toString()) }
                    selectedFolderUri = uri
                    addLog("Default folder set: TopoDroid", Color.Cyan)
                }
            }
        }
    }

    // Carica le impostazioni quando cambia la cartella
    LaunchedEffect(selectedFolderUri) {
        selectedFolderUri?.let { uri ->
            scope.launch {
                try {
                    val parent = DocumentFile.fromTreeUri(context, uri)
                    if (parent == null || !parent.canRead()) {
                        addLog("Permission lost for selected folder. Please re-select.", Color.Red)
                        isDbFound = false
                        return@launch
                    }
                    
                    isDbFound = parent.findFile("distox14.sqlite") != null
                    
                    if (isDbFound) {
                        migrateJsonToDb(context, uri)
                        archives = emptyList()
                        val loaded = loadSettings(context, uri)
                        archives = loaded
                        val lastUrl = sharedPrefs.getString(KEY_ARCHIVE_URL, "")
                        selectedArchive = loaded.find { it.url == lastUrl } ?: loaded.firstOrNull()
                        
                        if (selectedArchive == null || selectedArchive?.url?.isBlank() == true) {
                            addLog("Sync disabled: Please configure an Archive URL in settings.", Color.Yellow)
                        }
                    } else {
                        archives = emptyList()
                        selectedArchive = null
                        addLog("Sync disabled: distox14.sqlite not found in selected folder!", Color.Red)
                    }
                } catch (e: SecurityException) {
                    addLog("Storage permission error. Please re-select folder.", Color.Red)
                    isDbFound = false
                    // Reset folder if permission is permanently lost
                    // sharedPrefs.edit { remove(KEY_FOLDER_URI) }
                } catch (e: Exception) {
                    addLog("Error loading folder: ${e.localizedMessage}", Color.Red)
                    isDbFound = false
                }
            }
        } ?: run {
            isDbFound = false
            addLog("Sync disabled: Storage folder not selected.", Color.Yellow)
        }
    }

    // Monitora la selezione dell'archivio
    LaunchedEffect(selectedArchive) {
        if (isDbFound && (selectedArchive == null || selectedArchive?.url?.isBlank() == true)) {
            addLog("Sync disabled: No valid archive selected.", Color.Yellow)
        }
    }

    fun saveAndRefreshArchives(newList: List<ArchiveConfig>) {
        selectedFolderUri?.let { uri ->
            scope.launch {
                saveSettings(context, uri, newList)
                // Reload immediately to get correctly assigned IDs from DB
                archives = loadSettings(context, uri)
            }
        }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            isSignedIn = true
            addLog("Google Login successful", Color.Green)
        } else {
            addLog("Google Login error: ${task.exception?.message}", Color.Red)
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            sharedPrefs.edit { putString(KEY_FOLDER_URI, uri.toString()) }
            selectedFolderUri = uri
            addLog("Folder set successfully", Color.Green)
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Permission Required") },
            text = { Text("The app requires access to all files to manage TopoDroid folders.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }) { Text("Settings") }
            }
        )
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("CaveArchive Info") },
            text = {
                Column {
                    Text("Version: $appVersion")
                    Text("Release date: June 2026")
                    Text("Developer: Simon Beatrice Lidner")
                    Row {
                        Text("Email: ")
                        Text(
                            text = "simon.beatrice.lindner@gmail.com",
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:simon.beatrice.lindner@gmail.com")
                                }
                                context.startActivity(intent)
                            }
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Please report bugs and suggest new features.",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text("OK") }
            }
        )
    }

    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            title = { Text("Wipe Managed Data") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Are you sure you want to delete data synchronized with CaveArchive?")
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wipeAllData, onCheckedChange = { wipeAllData = it })
                        Text("Wipe all data", modifier = Modifier.clickable { wipeAllData = !wipeAllData })
                    }

                    if (!wipeAllData) {
                        var expanded by remember { mutableStateOf(false) }
                        Text("Select archive:", style = MaterialTheme.typography.labelLarge)
                        Box {
                            Button(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(archiveToWipe?.name ?: "Select Archive", maxLines = 1, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                archives.forEach { arch ->
                                    DropdownMenuItem(text = { Text(arch.name) }, onClick = {
                                        archiveToWipe = arch
                                        expanded = false
                                    })
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Only data managed by CaveArchive (marked in the 'caveArchive' column) will be removed. Your personal surveys will remain safe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetId = if (wipeAllData) null else archiveToWipe?.id
                        if (wipeAllData || (targetId != null && targetId > 0)) {
                            showWipeDialog = false
                            selectedFolderUri?.let { uri ->
                                scope.launch {
                                    val (success, msg) = wipeManagedData(context, uri, targetId, ::addLog)
                                    addLog(msg, if (success) Color.Green else Color.Red)
                                }
                            }
                        } else if (!wipeAllData && targetId == 0) {
                            addLog("Error: Archive ID not valid. Please restart the app.", Color.Red)
                        }
                    }
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showManageArchivesDialog) {
        ManageArchivesDialog(
            initialArchives = archives,
            currentFolderUri = selectedFolderUri,
            onFolderRequest = { folderLauncher.launch(null) },
            onDismiss = { showManageArchivesDialog = false },
            onSave = { newList ->
                saveAndRefreshArchives(newList)
                if (selectedArchive == null || newList.none { it.id == selectedArchive?.id }) {
                    selectedArchive = newList.firstOrNull()
                    sharedPrefs.edit { putString(KEY_ARCHIVE_URL, selectedArchive?.url ?: "") }
                }
                showManageArchivesDialog = false
            }
        )
    }

    if (showUploadDialog) {
        UploadDialog(
            context = context,
            localFolderUri = selectedFolderUri,
            selectedArchive = selectedArchive,
            onDismiss = { showUploadDialog = false },
            onLog = { t, c -> addLog(t, c) },
            externalScope = scope
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "CaveArchive",
                        style = MaterialTheme.typography.headlineLarge,
                        fontSize = 32.sp
                    )
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Upload") },
                            onClick = { showMenu = false; showUploadDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Logout") },
                            onClick = {
                                showMenu = false
                                val client = GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN)
                                client.signOut().addOnCompleteListener {
                                    isSignedIn = false
                                    addLog("Logged out", Color.Yellow)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Wipe data") },
                            onClick = { showMenu = false; showWipeDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Info") },
                            onClick = { showMenu = false; showInfoDialog = true }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Synchronize TopoDroid surveys/to Google Drive",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))

                if (isSyncing) {
                    CircularProgressIndicator()
                    val status = if (isCancelled) "Stopping..." else "Syncing..."
                    Text(status, modifier = Modifier.padding(top = 8.dp))
                    if (!isCancelled) {
                        Button(
                            onClick = { 
                                isCancelled = true
                                syncJob?.cancel()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text("Stop Sync") }
                    }
                } else {
                    if (!isSignedIn) {
                        Button(onClick = {
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()
                                .requestScopes(
                                    Scope(DriveScopes.DRIVE_READONLY),
                                    Scope(DriveScopes.DRIVE_FILE)
                                ).build()
                            signInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
                        }) { Text("Google Login") }
                    } else {
                        Button(
                            onClick = {
                                if (selectedArchive == null || selectedFolderUri == null) {
                                    addLog("Configure URL and folder!", Color.Red)
                                } else {
                                    syncJob = scope.launch {
                                        try {
                                            isSyncing = true
                                            isCancelled = false
                                            addLog("Sync started...", Color.Cyan)
                                            val (res, msg) = syncDriveFolderOAuth(context, selectedArchive!!, selectedFolderUri!!, ::addLog, { isCancelled })
                                            isSyncing = false
                                            addLog(msg, if (res) Color.Green else Color.Red)
                                        } catch (t: Throwable) {
                                            isSyncing = false
                                            if (t !is kotlinx.coroutines.CancellationException) {
                                                addLog("ERROR: ${t.localizedMessage}", Color.Red)
                                            }
                                        } finally {
                                            syncJob = null
                                        }
                                    }
                                }
                            },
                            enabled = isDbFound && selectedArchive != null && selectedArchive?.url?.isNotBlank() == true,
                            modifier = Modifier.height(56.dp).fillMaxWidth(0.6f)
                        ) { Text("Sync", style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(8.dp)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(logs) { log ->
                        Text("[${log.time}] ${log.text}", color = log.color, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = {
                            val content = logs.joinToString("\n") { "[${it.time}] ${it.text}" }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Console Logs", content)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Console",
                            tint = Color.Gray.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(
                        onClick = { logs.clear() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatPaint,
                            contentDescription = "Clear Console",
                            tint = Color.Gray.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Configuration:", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        var expanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            Button(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(selectedArchive?.name ?: "Select Archive", maxLines = 1, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                archives.forEach { arch ->
                                    DropdownMenuItem(text = { Text(arch.name) }, onClick = {
                                        selectedArchive = arch
                                        sharedPrefs.edit { putString(KEY_ARCHIVE_URL, arch.url) }
                                        expanded = false
                                    })
                                }
                            }
                        }
                        IconButton(onClick = { showManageArchivesDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Folder: ${selectedFolderUri?.let { getDisplayName(it) } ?: "Not selected"}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ManageArchivesDialog(
    initialArchives: List<ArchiveConfig>,
    currentFolderUri: Uri?,
    onFolderRequest: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<ArchiveConfig>) -> Unit
) {
    var list by remember(initialArchives) { mutableStateOf(initialArchives) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingArchive by remember { mutableStateOf<ArchiveConfig?>(null) }
    val listState = rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                Text("Storage Folder:", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = currentFolderUri?.let { getDisplayName(it) } ?: "None",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onFolderRequest) { Text("Change") }
                }
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
                Text("Drive Archives:", style = MaterialTheme.typography.labelLarge)
                
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 12.dp)
                            .drawWithContent {
                                drawContent()
                                val firstVisibleElementIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                                val needScrollbar = listState.layoutInfo.totalItemsCount > listState.layoutInfo.visibleItemsInfo.size
                                
                                if (needScrollbar && firstVisibleElementIndex != null) {
                                    val elementHeight = size.height / listState.layoutInfo.totalItemsCount
                                    val scrollbarHeight = size.height * (listState.layoutInfo.visibleItemsInfo.size.toFloat() / listState.layoutInfo.totalItemsCount)
                                    val scrollbarOffset = firstVisibleElementIndex * elementHeight
                                    
                                    drawRect(
                                        color = Color.Gray.copy(alpha = 0.5f),
                                        topLeft = Offset(size.width + 6, scrollbarOffset),
                                        size = Size(4f, scrollbarHeight)
                                    )
                                }
                            }
                    ) {
                        items(list) { arch ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(arch.name, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        arch.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        lineHeight = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { editingArchive = arch; showAddDialog = true }) { Icon(Icons.Default.Edit, null) }
                                IconButton(onClick = { list = list.filter { it.id != arch.id || (it.id == 0 && it != arch) } }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }

                Button(onClick = { editingArchive = null; showAddDialog = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Archive")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(list) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showAddDialog) {
        var name by remember { mutableStateOf(editingArchive?.name ?: "") }
        var url by remember { mutableStateOf(editingArchive?.url ?: "") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(if (editingArchive == null) "New" else "Edit") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Drive URL") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        val newArch = ArchiveConfig(id = editingArchive?.id ?: 0, name = name, url = url)
                        list = if (editingArchive == null) list + newArch else list.map { if (it.id == editingArchive?.id) newArch else it }
                        showAddDialog = false
                    }
                }) { Text("OK") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadDialog(
    context: Context,
    localFolderUri: Uri?,
    selectedArchive: ArchiveConfig?,
    onDismiss: () -> Unit,
    onLog: (String, Color) -> Unit,
    externalScope: kotlinx.coroutines.CoroutineScope
) {
    var surveys by remember { mutableStateOf(emptyList<SurveyInfo>()) }
    var selectedSurvey by remember { mutableStateOf<SurveyInfo?>(null) }
    var newName by remember { mutableStateOf("") }
    
    var isBranchMode by remember { mutableStateOf(false) }
    var selectedFirstStation by remember { mutableStateOf("") }
    var selectedSurveyToConnect by remember { mutableStateOf<SurveyInfo?>(null) }
    var selectedSecondStation by remember { mutableStateOf("") }
    
    var firstSurveyStations by remember { mutableStateOf(emptyList<String>()) }
    var secondSurveyStations by remember { mutableStateOf(emptyList<String>()) }
    var archiveSurveys by remember { mutableStateOf(emptyList<SurveyInfo>()) }
    
    var firstStationSearch by remember { mutableStateOf("") }
    var secondStationSearch by remember { mutableStateOf("") }
    
    val filteredFirstStations = remember(firstSurveyStations, firstStationSearch) {
        if (firstStationSearch.isEmpty()) firstSurveyStations.take(50)
        else firstSurveyStations.filter { it.contains(firstStationSearch, ignoreCase = true) }.take(50)
    }
    
    val filteredSecondStations = remember(secondSurveyStations, secondStationSearch) {
        if (secondStationSearch.isEmpty()) secondSurveyStations.take(50)
        else secondSurveyStations.filter { it.contains(secondStationSearch, ignoreCase = true) }.take(50)
    }

    var expandedSurvey by remember { mutableStateOf(false) }
    var expandedFirstStation by remember { mutableStateOf(false) }
    var expandedSurveyToConnect by remember { mutableStateOf(false) }
    var expandedSecondStation by remember { mutableStateOf(false) }
    
    var isUploading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        localFolderUri?.let { uri ->
            surveys = loadSurveysFromDb(context, uri)
            selectedSurvey = surveys.firstOrNull()
            newName = selectedSurvey?.name ?: ""
        }
    }

    LaunchedEffect(selectedArchive) {
        selectedArchive?.let { arch ->
            localFolderUri?.let { uri ->
                archiveSurveys = loadArchiveSurveysFromDb(context, uri, arch.id)
            }
        }
    }

    LaunchedEffect(selectedSurvey) {
        selectedSurvey?.let { s ->
            localFolderUri?.let { uri ->
                firstSurveyStations = loadStationsFromDb(context, uri, s.id)
                selectedFirstStation = firstSurveyStations.firstOrNull() ?: ""
                firstStationSearch = selectedFirstStation
            }
        }
    }

    LaunchedEffect(selectedSurveyToConnect) {
        selectedSurveyToConnect?.let { s ->
            localFolderUri?.let { uri ->
                secondSurveyStations = loadStationsFromDb(context, uri, s.id)
                selectedSecondStation = secondSurveyStations.firstOrNull() ?: ""
                secondStationSearch = selectedSecondStation
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload Survey") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Select Survey:", style = MaterialTheme.typography.labelLarge)
                    ExposedDropdownMenuBox(
                        expanded = expandedSurvey,
                        onExpandedChange = { expandedSurvey = it }
                    ) {
                        OutlinedTextField(
                            value = selectedSurvey?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSurvey) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expandedSurvey,
                            onDismissRequest = { expandedSurvey = false }
                        ) {
                            surveys.forEach { survey ->
                                DropdownMenuItem(
                                    text = { Text(survey.name) },
                                    onClick = {
                                        selectedSurvey = survey
                                        newName = survey.name
                                        expandedSurvey = false
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = !isBranchMode,
                                onClick = { isBranchMode = false }
                            )
                            Text(
                                "Upload geopoint",
                                modifier = Modifier.clickable { isBranchMode = false }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = isBranchMode,
                                onClick = { isBranchMode = true }
                            )
                            Text(
                                "Join branch",
                                modifier = Modifier.clickable { isBranchMode = true }
                            )
                        }
                    }
                }

                if (isBranchMode) {
                    item {
                        Text("First junction point:", style = MaterialTheme.typography.labelLarge)
                        ExposedDropdownMenuBox(
                            expanded = expandedFirstStation,
                            onExpandedChange = { expandedFirstStation = it }
                        ) {
                            OutlinedTextField(
                                value = firstStationSearch,
                                onValueChange = { 
                                    firstStationSearch = it
                                    selectedFirstStation = it
                                    expandedFirstStation = true
                                },
                                label = { Text("Search station") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFirstStation) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedFirstStation,
                                onDismissRequest = { expandedFirstStation = false }
                            ) {
                                filteredFirstStations.forEach { station ->
                                    DropdownMenuItem(
                                        text = { Text(station) },
                                        onClick = {
                                            selectedFirstStation = station
                                            firstStationSearch = station
                                            expandedFirstStation = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text("Survey to connect:", style = MaterialTheme.typography.labelLarge)
                        ExposedDropdownMenuBox(
                            expanded = expandedSurveyToConnect,
                            onExpandedChange = { expandedSurveyToConnect = it }
                        ) {
                            OutlinedTextField(
                                value = selectedSurveyToConnect?.name ?: "",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSurveyToConnect) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedSurveyToConnect,
                                onDismissRequest = { expandedSurveyToConnect = false }
                            ) {
                                archiveSurveys.forEach { survey ->
                                    DropdownMenuItem(
                                        text = { Text(survey.name) },
                                        onClick = {
                                            selectedSurveyToConnect = survey
                                            expandedSurveyToConnect = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (selectedSurveyToConnect != null) {
                        item {
                            Text("Second junction point:", style = MaterialTheme.typography.labelLarge)
                            ExposedDropdownMenuBox(
                                expanded = expandedSecondStation,
                                onExpandedChange = { expandedSecondStation = it }
                            ) {
                                OutlinedTextField(
                                    value = secondStationSearch,
                                    onValueChange = { 
                                        secondStationSearch = it
                                        selectedSecondStation = it
                                        expandedSecondStation = true
                                    },
                                    label = { Text("Search station") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSecondStation) },
                                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = expandedSecondStation,
                                    onDismissRequest = { expandedSecondStation = false }
                                ) {
                                    filteredSecondStations.forEach { station ->
                                        DropdownMenuItem(
                                            text = { Text(station) },
                                            onClick = {
                                                selectedSecondStation = station
                                                secondStationSearch = station
                                                expandedSecondStation = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val survey = selectedSurvey
                    if (survey != null && newName.isNotBlank() && selectedArchive != null && localFolderUri != null) {
                        val junctionData = if (isBranchMode && selectedFirstStation.isNotBlank() && selectedSurveyToConnect != null && selectedSecondStation.isNotBlank()) {
                            JunctionData(selectedFirstStation, selectedSurveyToConnect!!.name, selectedSecondStation)
                        } else null
                        
                        // Chiudi subito il dialogo
                        onDismiss()
                        
                        // Avvia l'upload usando lo scope del MainScreen così non si interrompe
                        externalScope.launch {
                            uploadSurveyToDrive(context, selectedArchive, localFolderUri, survey, newName, !isBranchMode, junctionData, onLog)
                        }
                    }
                },
                enabled = selectedSurvey != null && newName.isNotBlank()
            ) {
                Text("Upload")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isUploading) { Text("Cancel") }
        }
    )
}

data class JunctionData(val firstStation: String, val otherSurveyName: String, val secondStation: String)

suspend fun loadStationsFromDb(context: Context, folderUri: Uri, surveyId: Int): List<String> = withContext(Dispatchers.IO) {
    val stations = mutableSetOf<String>()
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        val dbFile = parent.findFile("distox14.sqlite") ?: return@withContext emptyList()
        val tempDb = JFile(context.cacheDir, "temp_load_stations.sqlite")
        context.contentResolver.openInputStream(dbFile.uri)?.use { input ->
            FileOutputStream(tempDb).use { output -> input.copyTo(output) }
        }
        SQLiteDatabase.openDatabase(tempDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.query("shots", arrayOf("fStation", "tStation"), "surveyId = ?", arrayOf(surveyId.toString()), null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val f = cursor.getString(0)
                    val t = cursor.getString(1)
                    if (!f.isNullOrBlank()) stations.add(f)
                    if (!t.isNullOrBlank()) stations.add(t)
                }
            }
        }
        tempDb.delete()
    } catch (e: Exception) { e.printStackTrace() }
    stations.toList().sorted()
}

suspend fun loadArchiveSurveysFromDb(context: Context, folderUri: Uri, archiveId: Int): List<SurveyInfo> = withContext(Dispatchers.IO) {
    val list = mutableListOf<SurveyInfo>()
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        val dbFile = parent.findFile("distox14.sqlite") ?: return@withContext emptyList()
        val tempDb = JFile(context.cacheDir, "temp_load_arch_surveys.sqlite")
        context.contentResolver.openInputStream(dbFile.uri)?.use { input ->
            FileOutputStream(tempDb).use { output -> input.copyTo(output) }
        }
        SQLiteDatabase.openDatabase(tempDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            // Query the main 'surveys' table directly using the 'caveArchive' column
            db.query("surveys", arrayOf("id", "name"), "caveArchive = ?", arrayOf(archiveId.toString()), null, null, "name ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(SurveyInfo(cursor.getInt(0), cursor.getString(1)))
                }
            }
        }
        tempDb.delete()
    } catch (e: Exception) { e.printStackTrace() }
    list
}

suspend fun loadSurveysFromDb(context: Context, folderUri: Uri): List<SurveyInfo> = withContext(Dispatchers.IO) {
    val list = mutableListOf<SurveyInfo>()
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        val dbFile = parent.findFile("distox14.sqlite") ?: return@withContext emptyList()
        val tempDb = JFile(context.cacheDir, "temp_load_surveys.sqlite")
        context.contentResolver.openInputStream(dbFile.uri)?.use { input ->
            FileOutputStream(tempDb).use { output -> input.copyTo(output) }
        }
        SQLiteDatabase.openDatabase(tempDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.query("surveys", arrayOf("id", "name"), null, null, null, null, "name ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(SurveyInfo(cursor.getInt(0), cursor.getString(1)))
                }
            }
        }
        tempDb.delete()
    } catch (e: Exception) { e.printStackTrace() }
    list
}

suspend fun uploadSurveyToDrive(
    context: Context,
    archive: ArchiveConfig,
    localFolderUri: Uri,
    survey: SurveyInfo,
    newName: String,
    uploadGeopoint: Boolean,
    junctionData: JunctionData?,
    onLog: (String, Color) -> Unit
) = withContext(Dispatchers.IO) {
    try {
        withContext(Dispatchers.Main) { onLog("Starting upload: $newName", Color.Cyan) }
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext
        val credential = GoogleAccountCredential.usingOAuth2(context, Collections.singleton(DriveScopes.DRIVE_FILE)).apply { selectedAccount = account.account }
        val drive = Drive.Builder(AndroidHttp.newCompatibleTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("CaveArchive").build()

        val folderId = if (archive.url.contains("/folders/")) archive.url.split("/folders/").getOrNull(1)?.split("?")?.getOrNull(0)
                       else if (archive.url.contains("id=")) archive.url.split("id=").getOrNull(1)?.split("&")?.getOrNull(0)
                       else null ?: return@withContext

        val parent = DocumentFile.fromTreeUri(context, localFolderUri) ?: return@withContext
        val dbDocFile = parent.findFile("distox14.sqlite") ?: return@withContext
        
        val tempSrcDb = JFile(context.cacheDir, "temp_src_upload.sqlite")
        context.contentResolver.openInputStream(dbDocFile.uri)?.use { input ->
            FileOutputStream(tempSrcDb).use { output -> input.copyTo(output) }
        }

        val uploadSqliteFile = JFile(context.cacheDir, "$newName.sqlite")
        if (uploadSqliteFile.exists()) uploadSqliteFile.delete()
        var surveyDataJson = JSONObject()
        
        SQLiteDatabase.openDatabase(tempSrcDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { srcDb ->
            // 0. Trova l'ID reale della survey nel DB (nel caso fosse cambiato o diverso)
            var realSurveyId = survey.id
            srcDb.query("surveys", arrayOf("id"), "name = ?", arrayOf(survey.name), null, null, null).use { c ->
                if (c.moveToFirst()) realSurveyId = c.getInt(0)
            }
            withContext(Dispatchers.Main) { onLog("Processing Survey ID: $realSurveyId (${survey.name})", Color.Gray) }

            // 1. Leggi dati survey
            srcDb.query("surveys", null, "id = ?", arrayOf(realSurveyId.toString()), null, null, null).use { sc ->
                if (sc.moveToFirst()) {
                    surveyDataJson.put("name", newName)
                    surveyDataJson.put("day", sc.getString(sc.getColumnIndexOrThrow("day")))
                    surveyDataJson.put("team", sc.getString(sc.getColumnIndexOrThrow("team")))
                    surveyDataJson.put("comment", sc.getString(sc.getColumnIndexOrThrow("comment")))
                    surveyDataJson.put("declination", sc.getString(sc.getColumnIndexOrThrow("declination")))
                    surveyDataJson.put("init_station", sc.getString(sc.getColumnIndexOrThrow("init_station")))
                    surveyDataJson.put("shotsFile", "$newName.sqlite")
                }
            }

            // 2. Leggi fixeds
            if (uploadGeopoint) {
                srcDb.query("fixeds", null, "surveyId = ?", arrayOf(realSurveyId.toString()), null, null, null).use { fc ->
                    if (fc.moveToFirst()) {
                        val fixedsObj = JSONObject()
                        fixedsObj.put("station", fc.getString(fc.getColumnIndexOrThrow("station")))
                        fixedsObj.put("longitude", fc.getDouble(fc.getColumnIndexOrThrow("longitude")))
                        fixedsObj.put("latitude", fc.getDouble(fc.getColumnIndexOrThrow("latitude")))
                        fixedsObj.put("altimetric", fc.getDouble(fc.getColumnIndexOrThrow("altimetric")))
                        fixedsObj.put("altitude", fc.getDouble(fc.getColumnIndexOrThrow("altitude")))
                        surveyDataJson.put("fixeds", fixedsObj)
                    } else {
                        throw Exception("No geopoint (fixed station) found for this survey. Please add one in TopoDroid or use 'Join branch' mode.")
                    }
                }
            }

            // 3. Crea il nuovo SQLite con i soli shots della survey
            SQLiteDatabase.openOrCreateDatabase(uploadSqliteFile, null).use { outDb ->
                // Ottieni lo schema esatto della tabella shots dal database sorgente
                var createTableSql = ""
                srcDb.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='shots'", null).use { c ->
                    if (c.moveToFirst()) createTableSql = c.getString(0)
                }
                
                if (createTableSql.isNotEmpty()) {
                    outDb.execSQL(createTableSql)
                } else {
                    // Fallback se non lo trova
                    outDb.execSQL("CREATE TABLE shots (id INTEGER PRIMARY KEY, surveyId INTEGER, fStation TEXT, tStation TEXT, distance REAL, bearing REAL, clino REAL, roll REAL, acceleration REAL, magnetic REAL, dip REAL, type INTEGER, status INTEGER, comment TEXT, extend INTEGER, leg INTEGER, rawData TEXT, caveArchive INTEGER)")
                }

                srcDb.query("shots", null, "surveyId = ?", arrayOf(realSurveyId.toString()), null, null, null).use { shotsCursor ->
                    val count = shotsCursor.count
                    withContext(Dispatchers.Main) { onLog("Found $count shots for ID $realSurveyId", if (count > 0) Color.Green else Color.Yellow) }
                    
                    outDb.beginTransaction()
                    var insertedCount = 0
                    try {
                        while (shotsCursor.moveToNext()) {
                            val vals = ContentValues()
                            for (col in shotsCursor.columnNames) {
                                val idx = shotsCursor.getColumnIndex(col)
                                when (shotsCursor.getType(idx)) {
                                    Cursor.FIELD_TYPE_INTEGER -> vals.put(col, shotsCursor.getLong(idx))
                                    Cursor.FIELD_TYPE_FLOAT -> vals.put(col, shotsCursor.getDouble(idx))
                                    Cursor.FIELD_TYPE_STRING -> vals.put(col, shotsCursor.getString(idx))
                                    Cursor.FIELD_TYPE_BLOB -> vals.put(col, shotsCursor.getBlob(idx))
                                }
                            }
                            val rid = outDb.insert("shots", null, vals)
                            if (rid != -1L) insertedCount++
                        }
                        outDb.setTransactionSuccessful()
                    } finally { outDb.endTransaction() }
                    withContext(Dispatchers.Main) { onLog("Successfully written $insertedCount shots to local file.", Color.Green) }
                }
            }
        }

        // 4. Carica lo SQLite su Drive (rimuovendo duplicati)
        withContext(Dispatchers.Main) { 
            onLog("Temp file size: ${uploadSqliteFile.length()} bytes", Color.Gray)
            onLog("Cleaning old files on Drive...", Color.Cyan) 
        }
        val driveFiles = drive.files().list().setQ("'$folderId' in parents and trashed = false").setFields("files(id, name)").execute().files ?: emptyList()
        driveFiles.filter { it.name == "$newName.sqlite" }.forEach { 
            try {
                drive.files().delete(it.id).execute()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onLog("WARNING: Duplicate ${it.name} remains! Please delete it manually on Drive.", Color.Yellow) }
            }
        }

        withContext(Dispatchers.Main) { onLog("Uploading new SQLite file...", Color.Cyan) }
        val driveFileMetadata = File().apply {
            name = "$newName.sqlite"
            parents = listOf(folderId)
        }
        val mediaContent = com.google.api.client.http.FileContent("application/x-sqlite3", uploadSqliteFile)
        val uploadedFile = drive.files().create(driveFileMetadata, mediaContent).setFields("id").execute()
        withContext(Dispatchers.Main) { onLog("SQLite uploaded (ID: ${uploadedFile.id})", Color.Green) }

        // 5. Aggiorna Cave.json
        withContext(Dispatchers.Main) { onLog("Updating Cave.json...", Color.Cyan) }
        val caveJsonFile = driveFiles.find { it.name == "Cave.json" }
        
        val caveJsonObject = if (caveJsonFile != null) {
            try {
                val content = drive.files().get(caveJsonFile.id).executeMediaAsInputStream().bufferedReader(Charset.forName("UTF-8")).use { it.readText() }
                if (content.isBlank()) JSONObject().apply { put("caves", JSONArray()) }
                else JSONObject(content)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onLog("Warning: Could not parse existing Cave.json, creating new.", Color.Yellow) }
                JSONObject().apply { put("caves", JSONArray()) }
            }
        } else {
            JSONObject().apply { put("caves", JSONArray()) }
        }

        if (!caveJsonObject.has("caves")) caveJsonObject.put("caves", JSONArray())
        val cavesArray = caveJsonObject.getJSONArray("caves")
        // Se esiste già una survey con lo stesso nome, la aggiorniamo, altrimenti la aggiungiamo
        var found = false
        for (i in 0 until cavesArray.length()) {
            if (cavesArray.getJSONObject(i).getString("name") == newName) {
                cavesArray.put(i, surveyDataJson)
                found = true
                break
            }
        }
        if (!found) cavesArray.put(surveyDataJson)

        val updatedJsonStr = caveJsonObject.toString(3)
        val jsonFile = JFile(context.cacheDir, "Cave.json")
        jsonFile.writeText(updatedJsonStr)
        
        val jsonMedia = com.google.api.client.http.FileContent("application/json", jsonFile)
        
        // Strategia Elimina e Ricrea anche per Cave.json per gestire DRIVE_FILE scope
        if (caveJsonFile != null) {
            try {
                drive.files().delete(caveJsonFile.id).execute()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onLog("WARNING: Duplicate Cave.json remains! Please delete the old one manually.", Color.Yellow) }
            }
        }
        
        val meta = File().apply { name = "Cave.json"; parents = listOf(folderId) }
        drive.files().create(meta, jsonMedia).execute()

        // 6. Aggiorna eventuale file .tdconfig
        withContext(Dispatchers.Main) { onLog("Checking for .tdconfig files...", Color.Cyan) }
        val tdConfigFiles = driveFiles.filter { it.name.lowercase().endsWith(".tdconfig") }
        for (tdFile in tdConfigFiles) {
            try {
                val content = drive.files().get(tdFile.id).executeMediaAsInputStream().bufferedReader(Charset.forName("UTF-8")).use { it.readText() }
                val lines = content.lines().toMutableList()
                
                // Controlla se la survey è già presente (per non duplicarla)
                val searchPattern = "load \"$newName\""
                val alreadyPresent = lines.any { it.trim().startsWith(searchPattern) }
                
                if (!alreadyPresent) {
                    // Cerca l'ultima riga "load" o la riga "survey" per inserire la nuova riga
                    var insertIndex = -1
                    for (i in lines.indices.reversed()) {
                        val trimmed = lines[i].trim()
                        if (trimmed.startsWith("load ") || trimmed.startsWith("survey ")) {
                            insertIndex = i + 1
                            break
                        }
                    }
                    
                    if (insertIndex != -1) {
                        // Genera un colore randomico
                        val color = (Math.random() * 16777215).toInt()
                        lines.add(insertIndex, "    load \"$newName\" -color $color")
                        
                        // Aggiungi equate se junctionData è presente
                        if (junctionData != null) {
                            // Cerca la posizione dell'equate (alla fine del blocco load o dopo l'ultima equate)
                            var equateIndex = lines.size - 1
                            for (i in lines.indices.reversed()) {
                                if (lines[i].trim() == "endsurvey") {
                                    equateIndex = i
                                    break
                                }
                            }
                            // Formato: equate "STATION@CURRENT_SURVEY" "STATION@OTHER_SURVEY"
                            // Nota: assumo che i nomi delle survey nel tdconfig abbiano il nome usato per l'upload (newName)
                            // e che per l'altra survey si usi il nome originale o quello trovato nel file? 
                            // Per ora uso junctionData.otherSurveyName come indicato dall'utente.
                            lines.add(equateIndex, "    equate \"${junctionData.firstStation}@$newName\" \"${junctionData.secondStation}@${junctionData.otherSurveyName}\"")
                        }

                        val updatedTdContent = lines.joinToString("\n")
                        val tempTdFile = JFile(context.cacheDir, tdFile.name)
                        tempTdFile.writeText(updatedTdContent)
                        
                        val tdMedia = com.google.api.client.http.FileContent("application/octet-stream", tempTdFile)
                        
                        // Invece di update, eliminiamo e ricreiamo per essere "proprietari" del file sotto DRIVE_FILE scope
                        try {
                            drive.files().delete(tdFile.id).execute()
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { onLog("WARNING: Duplicate ${tdFile.name} remains! Manual delete suggested.", Color.Yellow) }
                        }
                        
                        val meta = File().apply { name = tdFile.name; parents = listOf(folderId) }
                        drive.files().create(meta, tdMedia).execute()

                        tempTdFile.delete()
                        withContext(Dispatchers.Main) { onLog("Updated ${tdFile.name}", Color.Green) }
                    } else {
                        withContext(Dispatchers.Main) { onLog("ERROR: Could not find insertion point in ${tdFile.name}", Color.Red) }
                    }
                } else {
                    withContext(Dispatchers.Main) { onLog("${tdFile.name} already contains this survey.", Color.Gray) }
                }
            } catch (e: Exception) {
                val errMsg = e.localizedMessage ?: e.toString()
                withContext(Dispatchers.Main) { onLog("ERROR updating ${tdFile.name}: $errMsg", Color.Red) }
            }
        }

        tempSrcDb.delete()
        uploadSqliteFile.delete()
        jsonFile.delete()
        
        withContext(Dispatchers.Main) { onLog("Upload completed successfully!", Color.Green) }
    } catch (e: Exception) {
        val errorMessage = when {
            e is com.google.api.client.googleapis.json.GoogleJsonResponseException -> {
                "Drive Error: ${e.details?.message ?: e.message}"
            }
            e.message == null -> e.toString()
            else -> e.message
        }
        withContext(Dispatchers.Main) { onLog("Upload ERROR: $errorMessage", Color.Red) }
        e.printStackTrace()
    }
}

suspend fun loadSettings(context: Context, folderUri: Uri): List<ArchiveConfig> = withContext(Dispatchers.IO) {
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        val dbFile = parent.findFile("distox14.sqlite") ?: return@withContext emptyList()
        
        val tempDb = JFile(context.cacheDir, "load_settings_temp.sqlite")
        context.contentResolver.openInputStream(dbFile.uri)?.use { input ->
            FileOutputStream(tempDb).use { output -> input.copyTo(output) }
        }
        
        val result = mutableListOf<ArchiveConfig>()
        SQLiteDatabase.openDatabase(tempDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            ensureCaveArchiveColumn(db)
            db.query("CA_Archives", null, null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(ArchiveConfig(
                        id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        url = cursor.getString(cursor.getColumnIndexOrThrow("url"))
                    ))
                }
            }
        }
        tempDb.delete()
        result
    } catch (e: Exception) {
        emptyList()
    }
}

suspend fun saveSettings(context: Context, folderUri: Uri, archives: List<ArchiveConfig>) = withContext(Dispatchers.IO) {
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
        val dbFile = parent.findFile("distox14.sqlite") ?: return@withContext
        
        val tempDb = JFile(context.cacheDir, "save_settings_temp.sqlite")
        context.contentResolver.openInputStream(dbFile.uri)?.use { input ->
            FileOutputStream(tempDb).use { output -> input.copyTo(output) }
        }
        
        SQLiteDatabase.openOrCreateDatabase(tempDb, null).use { db ->
            ensureCaveArchiveColumn(db)
            db.beginTransaction()
            try {
                db.delete("CA_Archives", null, null)
                for (archive in archives) {
                    val values = ContentValues().apply {
                        put("name", archive.name)
                        put("url", archive.url)
                        if (archive.id > 0) put("id", archive.id)
                    }
                    db.insert("CA_Archives", null, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        
        context.contentResolver.openOutputStream(dbFile.uri, "wt")?.use { output ->
            tempDb.inputStream().use { input -> input.copyTo(output) }
            output.flush()
        }
        tempDb.delete()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

suspend fun wipeManagedData(
    context: Context,
    localFolderUri: Uri,
    archiveId: Int? = null,
    onLog: (String, Color) -> Unit
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val parentFolder = DocumentFile.fromTreeUri(context, localFolderUri) ?: return@withContext false to "Local folder error"
        val dbDocFile = parentFolder.findFile("distox14.sqlite") ?: return@withContext false to "distox14.sqlite not found"

        val tempDb = JFile(context.cacheDir, "temp_wipe_${System.currentTimeMillis()}.sqlite")
        try {
            context.contentResolver.openInputStream(dbDocFile.uri)?.use { input ->
                FileOutputStream(tempDb).use { output -> input.copyTo(output) }
            }

            SQLiteDatabase.openOrCreateDatabase(tempDb, null).use { db ->
                db.beginTransaction()
                try {
                    ensureCaveArchiveColumn(db)
                    val whereClause = if (archiveId == null) "caveArchive > 0" else "caveArchive = ?"
                    val whereArgs = if (archiveId == null) null else arrayOf(archiveId.toString())

                    val fixedDeleted = db.delete("fixeds", whereClause, whereArgs)
                    val shotsDeleted = db.delete("shots", whereClause, whereArgs)
                    val surveysDeleted = db.delete("surveys", whereClause, whereArgs)
                    
                    // Clear synched surveys metadata
                    db.delete("CA_Surveys", if (archiveId == null) null else "archiveId = ?", whereArgs)
                    
                    // Delete physical supporting files before clearing the table
                    val parent = DocumentFile.fromTreeUri(context, localFolderUri)
                    var deletedFilesCount = 0
                    db.query("CA_Files", arrayOf("path"), if (archiveId == null) null else "archiveId = ?", whereArgs, null, null, null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val path = cursor.getString(0)
                            val parts = path.split("/")
                            if (parts.size == 2) {
                                parent?.findFile(parts[0])?.findFile(parts[1])?.delete()
                                deletedFilesCount++
                            }
                        }
                    }
                    db.delete("CA_Files", if (archiveId == null) null else "archiveId = ?", whereArgs)
                    
                    db.setTransactionSuccessful()
                    
                    withContext(Dispatchers.Main) {
                        onLog("Deleted $deletedFilesCount supporting files.", Color.Yellow)
                    }

                    // Delete OLD JSONs if they exist (only for full wipe)
                    if (archiveId == null) {
                        val archiveFolder = parent?.findFile("CaveArchive")
                        archiveFolder?.findFile("CaveArchive_files.json")?.delete()
                        archiveFolder?.findFile("CaveArchive_synched_caves.json")?.delete()
                    }

                    withContext(Dispatchers.Main) {
                        onLog("Deleted $surveysDeleted surveys, $shotsDeleted shots and $fixedDeleted fixed points.", Color.Yellow)
                    }
                } finally {
                    if (db.inTransaction()) db.endTransaction()
                }
            }

            context.contentResolver.openOutputStream(dbDocFile.uri, "wt")?.use { output ->
                tempDb.inputStream().use { input -> input.copyTo(output) }
                output.flush()
            }
            return@withContext true to "Wipe completed successfully."
        } finally {
            if (tempDb.exists()) tempDb.delete()
        }
    } catch (e: Exception) {
        return@withContext false to "Wipe error: ${e.localizedMessage}"
    }
}

fun getDisplayName(uri: Uri): String = uri.path?.split(":")?.lastOrNull() ?: uri.toString()

fun ensureCaveArchiveColumn(db: SQLiteDatabase) {
    val tables = listOf("surveys", "shots", "fixeds")
    for (table in tables) {
        try {
            val cursor = db.rawQuery("SELECT * FROM $table LIMIT 0", null)
            val exists = cursor.getColumnIndex("caveArchive") != -1
            cursor.close()
            if (!exists) {
                db.execSQL("ALTER TABLE $table ADD COLUMN caveArchive INTEGER DEFAULT 0")
            }
        } catch (e: Exception) {
            // Table might not exist yet in TopoDroid DB if it's very old or corrupted
        }
    }
    db.execSQL("CREATE TABLE IF NOT EXISTS CA_Archives (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, url TEXT)")
    db.execSQL("CREATE TABLE IF NOT EXISTS CA_Surveys (id INTEGER PRIMARY KEY AUTOINCREMENT, archiveId INTEGER, file_name TEXT, drive_id TEXT, modified_time TEXT)")
    db.execSQL("CREATE TABLE IF NOT EXISTS CA_Files (id INTEGER PRIMARY KEY AUTOINCREMENT, archiveId INTEGER, path TEXT, drive_id TEXT, modified_time TEXT)")
}

suspend fun migrateJsonToDb(context: Context, folderUri: Uri) = withContext(Dispatchers.IO) {
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
        val archiveFolder = parent.findFile("CaveArchive") ?: return@withContext
        val dbFile = parent.findFile("distox14.sqlite") ?: return@withContext

        // Migrate Archives (Settings)
        val settingsFile = archiveFolder.findFile("CaveArchive_Settings.json")
        if (settingsFile != null) {
            val current = loadSettings(context, folderUri)
            if (current.isEmpty()) {
                val jsonStr = context.contentResolver.openInputStream(settingsFile.uri)?.use { it.bufferedReader().readText() } ?: ""
                val array = JSONObject(jsonStr).getJSONArray("archives")
                val toMigrate = List(array.length()) { i ->
                    val obj = array.getJSONObject(i)
                    ArchiveConfig(name = obj.getString("name"), url = obj.getString("url"))
                }
                if (toMigrate.isNotEmpty()) {
                    saveSettings(context, folderUri, toMigrate)
                }
            }
        }

        // Migrate Files Inventory
        val inventoryFile = archiveFolder.findFile("CaveArchive_files.json")
        if (inventoryFile != null) {
            val tempDb = JFile(context.cacheDir, "migrate_files_temp.sqlite")
            context.contentResolver.openInputStream(dbFile.uri)?.use { input ->
                FileOutputStream(tempDb).use { output -> input.copyTo(output) }
            }

            SQLiteDatabase.openOrCreateDatabase(tempDb, null).use { db ->
                ensureCaveArchiveColumn(db)
                val count = db.rawQuery("SELECT COUNT(*) FROM CA_Files", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                if (count == 0) {
                    val jsonStr = context.contentResolver.openInputStream(inventoryFile.uri)?.use { it.bufferedReader().readText() } ?: ""
                    val array = JSONObject(jsonStr).getJSONArray("downloaded_files")
                    val archives = loadSettings(context, folderUri)
                    if (archives.isNotEmpty()) {
                        val firstArchiveId = archives.first().id
                        db.beginTransaction()
                        try {
                            for (i in 0 until array.length()) {
                                val item = array.get(i)
                                val obj = if (item is JSONObject) item else null
                                if (obj != null) {
                                    val values = ContentValues().apply {
                                        put("archiveId", firstArchiveId)
                                        put("path", obj.getString("path"))
                                        put("drive_id", obj.getString("drive_id"))
                                        put("modified_time", obj.getString("modified_time"))
                                    }
                                    db.insert("CA_Files", null, values)
                                }
                            }
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }
                }
            }
            context.contentResolver.openOutputStream(dbFile.uri, "wt")?.use { output ->
                tempDb.inputStream().use { input -> input.copyTo(output) }
                output.flush()
            }
            tempDb.delete()
        }
    } catch (e: Exception) { e.printStackTrace() }
}

suspend fun syncDriveFolderOAuth(
    context: Context, archive: ArchiveConfig, localFolderUri: Uri, onLog: (String, Color) -> Unit, checkCancelled: () -> Boolean
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    suspend fun safeLog(text: String, color: Color = Color.White) = withContext(Dispatchers.Main) { onLog(text, color) }
    suspend fun <T> runWithRetry(name: String, block: () -> T): T {
        var lastEx: Throwable? = null
        var currentDelay = 2000L
        for (i in 1..3) {
            if (checkCancelled()) throw kotlinx.coroutines.CancellationException()
            
            try {
                return withTimeout(180000) { // Increased to 3 minutes
                    kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                        block()
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) {
                    throw t
                }
                
                lastEx = t
                if (i < 3) {
                    val errorDetail = t.localizedMessage ?: "Timeout or Network error"
                    safeLog("Retry $i/3: $name ($errorDetail)", Color.Yellow)
                    delay(currentDelay)
                    currentDelay *= 2 // Exponential backoff
                } else {
                    safeLog("Failed after 3 attempts: $name", Color.Red)
                }
            }
        }
        throw lastEx ?: Exception("Task $name failed after multiple attempts")
    }

    // Helper to download stream with cancellation check
    suspend fun downloadFileWithProgress(drive: Drive, driveFileId: String, outputStream: java.io.OutputStream) {
        val inputStream = drive.files().get(driveFileId).executeMediaAsInputStream()
        inputStream.use { input ->
            outputStream.use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (checkCancelled()) throw kotlinx.coroutines.CancellationException()
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
            }
        }
    }

    try {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext false to "Please Login to Google"
        val credential = GoogleAccountCredential.usingOAuth2(context, Collections.singleton(DriveScopes.DRIVE_READONLY)).apply { selectedAccount = account.account }
        val drive = Drive.Builder(AndroidHttp.newCompatibleTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("CaveArchive").build()

        val folderId = if (archive.url.contains("/folders/")) archive.url.split("/folders/").getOrNull(1)?.split("?")?.getOrNull(0)
                       else if (archive.url.contains("id=")) archive.url.split("id=").getOrNull(1)?.split("&")?.getOrNull(0)
                       else null

        if (folderId == null) return@withContext false to "Invalid URL"

        safeLog("Connecting to Drive...", Color.Cyan)
        val driveFiles = runWithRetry("file list") { drive.files().list().setQ("'$folderId' in parents and trashed = false").setFields("files(id, name, mimeType, modifiedTime)").execute() }.files ?: emptyList()
        val caveJsonFile = driveFiles.find { it.name == "Cave.json" } ?: return@withContext false to "Cave.json not found"

        val parentFolder = DocumentFile.fromTreeUri(context, localFolderUri) ?: return@withContext false to "Local folder error"
        val dbDocFile = parentFolder.findFile("distox14.sqlite")

        var updatedCavesCount = 0
        
        // Load existing files inventory metadata
        val existingFilesMetadata = mutableMapOf<String, JSONObject>()
        if (dbDocFile != null) {
            try {
                val tempDb = JFile(context.cacheDir, "metadata_temp.sqlite")
                context.contentResolver.openInputStream(dbDocFile.uri)?.use { input ->
                    FileOutputStream(tempDb).use { output -> input.copyTo(output) }
                }
                SQLiteDatabase.openDatabase(tempDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    ensureCaveArchiveColumn(db)
                    db.query("CA_Files", null, "archiveId = ?", arrayOf(archive.id.toString()), null, null, null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val path = cursor.getString(cursor.getColumnIndexOrThrow("path"))
                            existingFilesMetadata[path] = JSONObject().apply {
                                put("path", path)
                                put("drive_id", cursor.getString(cursor.getColumnIndexOrThrow("drive_id")))
                                put("modified_time", cursor.getString(cursor.getColumnIndexOrThrow("modified_time")))
                            }
                        }
                    }
                }
                tempDb.delete()
            } catch (e: Exception) { e.printStackTrace() }
        }

        if (dbDocFile != null) {
            val suffix = System.currentTimeMillis()
            val tempDb = JFile(context.cacheDir, "temp_distox14_$suffix.sqlite")
            try {
                safeLog("Updating DB...", Color.Cyan)
                val jsonStr = runWithRetry("download Cave.json") { drive.files().get(caveJsonFile.id).executeMediaAsInputStream().bufferedReader().use { it.readText() } }
                val caves = JSONObject(jsonStr).getJSONArray("caves")
                context.contentResolver.openInputStream(dbDocFile.uri)?.use { input -> FileOutputStream(tempDb).use { input.copyTo(it) } }

                SQLiteDatabase.openOrCreateDatabase(tempDb, null).use { db ->
                    ensureCaveArchiveColumn(db)
                    
                    // Load existing metadata from CA_Surveys
                    val existingSynchedCaves = mutableMapOf<String, JSONObject>()
                    db.query("CA_Surveys", null, "archiveId = ?", arrayOf(archive.id.toString()), null, null, null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val fileName = cursor.getString(cursor.getColumnIndexOrThrow("file_name"))
                            existingSynchedCaves[fileName] = JSONObject().apply {
                                put("drive_id", cursor.getString(cursor.getColumnIndexOrThrow("drive_id")))
                                put("modified_time", cursor.getString(cursor.getColumnIndexOrThrow("modified_time")))
                            }
                        }
                    }

                    db.beginTransaction()
                    try {
                        for (i in 0 until caves.length()) {
                            if (checkCancelled()) break
                            val cave = caves.getJSONObject(i)
                            val name = cave.getString("name")
                            val vals = ContentValues().apply {
                                put("name", name); put("day", cave.optString("day", "")); put("team", cave.optString("team", ""))
                                put("comment", cave.optString("comment", ""))
                                put("caveArchive", archive.id)
                                put("declination", cave.optDouble("declination", 0.0))
                                put("init_station", cave.optString("init_station", "")); put("xsections", 0); put("datamode", 0)
                                put("extend", 0); put("created", 0); put("immutable", 0)
                            }
                            val sId = db.query("surveys", arrayOf("id"), "name = ?", arrayOf(name), null, null, null).use { c ->
                                if (cursorToFirst(c)) { db.update("surveys", vals, "name = ?", arrayOf(name)); c.getInt(0) }
                                else {
                                    val next = db.rawQuery("SELECT MAX(id) FROM surveys", null).use { if (cursorToFirst(it)) it.getInt(0) + 1 else 1 }
                                    vals.put("id", next); db.insert("surveys", null, vals); next
                                }
                            }
                            if (cave.has("fixeds")) {
                                val f = cave.getJSONObject("fixeds")
                                val fv = ContentValues().apply {
                                    put("surveyId", sId)
                                    put("station", f.optString("station", ""))
                                    put("longitude", f.optDouble("longitude", 0.0))
                                    put("latitude", f.optDouble("latitude", 0.0))
                                    val alt = f.optDouble("altimetric", 0.0)
                                    put("altitude", f.optDouble("altitude", 0.0))
                                    put("altimetric", alt)
                                    put("comment", "")
                                    put("caveArchive", archive.id)
                                    put("status", 0)
                                    put("cs_name", "")
                                    put("cs_longitude", 0.0)
                                    put("cs_latitude", 0.0)
                                    put("cs_altitude", 0.0)
                                    put("source", 2)
                                    put("cs_decimals", 2)
                                    put("convergence", 0.0)
                                }
                                db.query("fixeds", arrayOf("id"), "surveyId = ?", arrayOf(sId.toString()), null, null, null).use { c ->
                                    if (cursorToFirst(c)) { 
                                        fv.put("id", c.getInt(0))
                                        db.update("fixeds", fv, "surveyId = ?", arrayOf(sId.toString())) 
                                    }
                                    else { 
                                        fv.put("id", 1)
                                        db.insert("fixeds", null, fv) 
                                    }
                                }
                            }
                            if (cave.has("shotsFile") && !checkCancelled()) {
                                val sName = cave.getString("shotsFile")
                                driveFiles.find { it.name == sName }?.let { sFile ->
                                    val currentModifiedTime = sFile.modifiedTime?.toString() ?: ""
                                    val existingInfo = existingSynchedCaves[sName]
                                    
                                    val needsUpdate = existingInfo == null || 
                                                     existingInfo.optString("drive_id") != sFile.id || 
                                                     existingInfo.optString("modified_time") != currentModifiedTime

                                    if (!needsUpdate && existingInfo != null) {
                                        safeLog("$sName is up to date, skipping measurements.", Color.Gray)
                                    } else {
                                        val tShots = JFile(context.cacheDir, "temp_shots_$suffix.sqlite")
                                        runWithRetry("download $sName") { drive.files().get(sFile.id).executeMediaAsInputStream().use { inp -> FileOutputStream(tShots).use { inp.copyTo(it) } } }
                                        
                                        // Update metadata in DB
                                        val metaVals = ContentValues().apply {
                                            put("archiveId", archive.id)
                                            put("file_name", sName)
                                            put("drive_id", sFile.id)
                                            put("modified_time", currentModifiedTime)
                                        }
                                        val existingIdCursor = db.query("CA_Surveys", arrayOf("id"), "archiveId = ? AND file_name = ?", arrayOf(archive.id.toString(), sName), null, null, null)
                                        if (existingIdCursor.moveToFirst()) {
                                            db.update("CA_Surveys", metaVals, "id = ?", arrayOf(existingIdCursor.getInt(0).toString()))
                                        } else {
                                            db.insert("CA_Surveys", null, metaVals)
                                        }
                                        existingIdCursor.close()

                                        SQLiteDatabase.openDatabase(tShots.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sDb ->
                                            db.delete("shots", "surveyId = ?", arrayOf(sId.toString()))
                                            sDb.query("shots", null, null, null, null, null, null).use { sc ->
                                                if (cursorToFirst(sc)) do {
                                                    val row = ContentValues()
                                                    for (col in sc.columnNames) {
                                                        val idx = sc.getColumnIndex(col)
                                                        when (sc.getType(idx)) {
                                                            Cursor.FIELD_TYPE_INTEGER -> row.put(col, sc.getLong(idx))
                                                            Cursor.FIELD_TYPE_FLOAT -> row.put(col, sc.getDouble(idx))
                                                            Cursor.FIELD_TYPE_STRING -> row.put(col, sc.getString(idx))
                                                            Cursor.FIELD_TYPE_BLOB -> row.put(col, sc.getBlob(idx))
                                                        }
                                                    }
                                                    row.put("caveArchive", archive.id)
                                                    row.put("surveyId", sId)
                                                    db.insertWithOnConflict("shots", null, row, SQLiteDatabase.CONFLICT_REPLACE)
                                                } while (sc.moveToNext())
                                                safeLog("${sc.count} measurements ($name)", Color.Gray)
                                            }
                                        }
                                        tShots.delete()
                                        updatedCavesCount++
                                    }
                                }
                            }
                        }
                        if (!checkCancelled()) db.setTransactionSuccessful()
                    } finally { if (db.inTransaction()) db.endTransaction() }
                }
                if (!checkCancelled()) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        context.contentResolver.openOutputStream(dbDocFile.uri, "wt")?.use { out -> 
                            tempDb.inputStream().use { input -> input.copyTo(out) }
                            out.flush() 
                        }
                        safeLog("Database updated: $updatedCavesCount sqlite files processed.", Color.Green)
                    }
                }
            } catch (e: Exception) { safeLog("DB Error: ${e.localizedMessage}", Color.Red) }
            finally { tempDb.delete() }
        }

        var dlCount = 0
        val downloadedPathsMetadata = mutableListOf<JSONObject>()
        for (f in driveFiles) {
            if (checkCancelled()) break
            if (f.name == "Cave.json" || f.name.lowercase().endsWith(".zip")) continue
            val folderName = when {
                f.name.lowercase().endsWith(".tdconfig") || f.name.lowercase().endsWith(".thconfig") -> "thconfig"
                f.name.lowercase().endsWith(".asc") || f.name.lowercase().endsWith(".asci") || f.name.lowercase().endsWith(".ascii") -> "DTM"
                else -> null
            } ?: continue
            
            val path = "$folderName/${f.name}"
            val currentModifiedTime = f.modifiedTime?.toString() ?: ""
            val existing = existingFilesMetadata[path]
            
            val needsUpdate = existing == null || 
                             existing.optString("drive_id") != f.id || 
                             existing.optString("modified_time") != currentModifiedTime

            if (!needsUpdate && existing != null) {
                safeLog("${f.name} is up to date.", Color.Gray)
                downloadedPathsMetadata.add(existing)
                continue
            }

            val dest = parentFolder.findFile(folderName) ?: parentFolder.createDirectory(folderName)
            dest?.let { d ->
                try {
                    val target = d.findFile(f.name) ?: d.createFile(f.mimeType, f.name)
                    target?.let { t -> 
                        runWithRetry("download ${f.name}") {
                            context.contentResolver.openOutputStream(t.uri)?.use { outStream ->
                                kotlinx.coroutines.runBlocking {
                                    downloadFileWithProgress(drive, f.id, outStream)
                                }
                            }
                        }
                        // Solo se il download ha avuto successo, aggiungiamo ai metadata
                        downloadedPathsMetadata.add(JSONObject().apply {
                            put("path", path)
                            put("drive_id", f.id)
                            put("modified_time", currentModifiedTime)
                        })
                    }
                    safeLog("Saved: ${f.name}", Color.Gray)
                    dlCount++
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    safeLog("Failed to save ${f.name}: ${e.localizedMessage}", Color.Red)
                }
            }
        }
        
        if (downloadedPathsMetadata.isNotEmpty()) {
            updateFilesInventory(context, localFolderUri, archive.id, downloadedPathsMetadata)
        }

        return@withContext true to (if (checkCancelled()) "Interrupted." else "Files updated: $dlCount files downloaded.")
    } catch (t: Throwable) { return@withContext false to "Error: ${t.localizedMessage}" }
}

private fun cursorToFirst(c: Cursor?): Boolean = c?.moveToFirst() ?: false

suspend fun updateFilesInventory(context: Context, folderUri: Uri, archiveId: Int, newItems: List<JSONObject>) = withContext(Dispatchers.IO) {
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
        val dbDocFile = parent.findFile("distox14.sqlite") ?: return@withContext

        val tempDb = JFile(context.cacheDir, "update_files_temp.sqlite")
        context.contentResolver.openInputStream(dbDocFile.uri)?.use { input ->
            FileOutputStream(tempDb).use { output -> input.copyTo(output) }
        }

        SQLiteDatabase.openOrCreateDatabase(tempDb, null).use { db ->
            ensureCaveArchiveColumn(db)
            db.beginTransaction()
            try {
                newItems.forEach { newItem ->
                    val path = newItem.getString("path")
                    val vals = ContentValues().apply {
                        put("archiveId", archiveId)
                        put("path", path)
                        put("drive_id", newItem.getString("drive_id"))
                        put("modified_time", newItem.getString("modified_time"))
                    }
                    
                    val existingIdCursor = db.query("CA_Files", arrayOf("id"), "archiveId = ? AND path = ?", arrayOf(archiveId.toString(), path), null, null, null)
                    if (existingIdCursor.moveToFirst()) {
                        db.update("CA_Files", vals, "id = ?", arrayOf(existingIdCursor.getInt(0).toString()))
                    } else {
                        db.insert("CA_Files", null, vals)
                    }
                    existingIdCursor.close()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        context.contentResolver.openOutputStream(dbDocFile.uri, "wt")?.use { output ->
            tempDb.inputStream().use { input -> input.copyTo(output) }
            output.flush()
        }
        tempDb.delete()
    } catch (e: Exception) { e.printStackTrace() }
}

@Preview(showBackground = true) @Composable fun MainScreenPreview() { CaveArchiveTheme { MainScreen() } }
