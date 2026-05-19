package com.example.cavearchive

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.example.cavearchive.ui.theme.CaveArchiveTheme
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
data class ArchiveConfig(val name: String, val url: String)

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
    var showMenu by remember { mutableStateOf(false) }
    var showManageArchivesDialog by remember { mutableStateOf(false) }

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
    var isSignedIn by remember { mutableStateOf(GoogleSignIn.getLastSignedInAccount(context) != null) }

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
            archives = emptyList()
            val loaded = loadSettings(context, uri)
            archives = loaded
            val lastUrl = sharedPrefs.getString(KEY_ARCHIVE_URL, "")
            selectedArchive = loaded.find { it.url == lastUrl } ?: loaded.firstOrNull()
        }
    }

    fun saveAndRefreshArchives(newList: List<ArchiveConfig>) {
        archives = newList
        selectedFolderUri?.let { uri ->
            scope.launch {
                saveSettings(context, uri, newList)
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
                    Text("Release date: May 2026")
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
                Column {
                    Text("Are you sure you want to delete all data synchronized with CaveArchive?")
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "All objects with the comment 'CaveArchiveManaged' in 'surveys' and 'shots' tables will be permanently removed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWipeDialog = false
                        selectedFolderUri?.let { uri ->
                            scope.launch {
                                val (success, msg) = wipeManagedData(context, uri, ::addLog)
                                addLog(msg, if (success) Color.Green else Color.Red)
                            }
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
                if (selectedArchive !in newList) {
                    selectedArchive = newList.firstOrNull()
                    sharedPrefs.edit { putString(KEY_ARCHIVE_URL, selectedArchive?.url ?: "") }
                }
                showManageArchivesDialog = false
            }
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
                    text = "Synchronize TopoDroid surveys from Google Drive",
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
                            onClick = { isCancelled = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text("Stop Sync") }
                    }
                } else {
                    if (!isSignedIn) {
                        Button(onClick = {
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail().requestScopes(Scope(DriveScopes.DRIVE_READONLY)).build()
                            signInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
                        }) { Text("Google Login") }
                    } else {
                        Button(
                            onClick = {
                                if (selectedArchive == null || selectedFolderUri == null) {
                                    addLog("Configure URL and folder!", Color.Red)
                                } else {
                                    scope.launch {
                                        try {
                                            isSyncing = true
                                            isCancelled = false
                                            addLog("Sync started...", Color.Cyan)
                                            val (res, msg) = syncDriveFolderOAuth(context, selectedArchive!!.url, selectedFolderUri!!, ::addLog, { isCancelled })
                                            isSyncing = false
                                            addLog(msg, if (res) Color.Green else Color.Red)
                                        } catch (t: Throwable) {
                                            isSyncing = false
                                            addLog("ERROR: ${t.localizedMessage}", Color.Red)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.height(56.dp).fillMaxWidth(0.6f)
                        ) { Text("Sync", style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).padding(8.dp)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(logs) { log ->
                        Text("[${log.time}] > ${log.text}", color = log.color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
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
                                IconButton(onClick = { list = list.filter { it != arch } }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
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
                        val newArch = ArchiveConfig(name, url)
                        list = if (editingArchive == null) list + newArch else list.map { if (it == editingArchive) newArch else it }
                        showAddDialog = false
                    }
                }) { Text("OK") }
            }
        )
    }
}

suspend fun loadSettings(context: Context, folderUri: Uri): List<ArchiveConfig> = withContext(Dispatchers.IO) {
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        val settingsFile = parent.findFile("CaveArchive")?.findFile("CaveArchive_Settings.json") ?: return@withContext emptyList()
        val jsonStr = context.contentResolver.openInputStream(settingsFile.uri)?.use { it.bufferedReader().readText() } ?: ""
        val array = JSONObject(jsonStr).getJSONArray("archives")
        List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            ArchiveConfig(obj.getString("name"), obj.getString("url"))
        }
    } catch (e: Exception) { emptyList() }
}

suspend fun saveSettings(context: Context, folderUri: Uri, archives: List<ArchiveConfig>) = withContext(Dispatchers.IO) {
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
        val folder = parent.findFile("CaveArchive") ?: parent.createDirectory("CaveArchive") ?: return@withContext
        val file = folder.findFile("CaveArchive_Settings.json") ?: folder.createFile("application/json", "CaveArchive_Settings.json") ?: return@withContext
        val json = JSONObject().apply {
            put("archives", JSONArray().apply {
                archives.forEach { put(JSONObject().apply { put("name", it.name); put("url", it.url) }) }
            })
        }
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(json.toString(2).toByteArray()) }
    } catch (e: Exception) { e.printStackTrace() }
}

suspend fun wipeManagedData(
    context: Context,
    localFolderUri: Uri,
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
                    val fixedDeleted = db.delete("fixeds", "comment = ?", arrayOf("CaveArchiveManaged"))
                    val shotsDeleted = db.delete("shots", "comment = ?", arrayOf("CaveArchiveManaged"))
                    val surveysDeleted = db.delete("surveys", "comment = ?", arrayOf("CaveArchiveManaged"))
                    db.setTransactionSuccessful()
                    
                    // Delete supporting files and inventory
                    val parent = DocumentFile.fromTreeUri(context, localFolderUri)
                    val archiveFolder = parent?.findFile("CaveArchive")
                    val inventoryFile = archiveFolder?.findFile("CaveArchive_files.json")
                    
                    if (inventoryFile != null) {
                        try {
                            val jsonStr = context.contentResolver.openInputStream(inventoryFile.uri)?.use { it.bufferedReader().readText() }
                            if (!jsonStr.isNullOrBlank()) {
                                val json = JSONObject(jsonStr)
                                val array = json.getJSONArray("downloaded_files")
                                var deletedFilesCount = 0
                                for (i in 0 until array.length()) {
                                    val path = array.getString(i)
                                    val parts = path.split("/")
                                    if (parts.size == 2) {
                                        // Cerca la sottocartella (es. DTM) e poi il file
                                        parent?.findFile(parts[0])?.findFile(parts[1])?.delete()
                                        deletedFilesCount++
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    onLog("Deleted $deletedFilesCount supporting files.", Color.Yellow)
                                }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                        inventoryFile.delete()
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

suspend fun syncDriveFolderOAuth(
    context: Context, urlString: String, localFolderUri: Uri, onLog: (String, Color) -> Unit, checkCancelled: () -> Boolean
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    suspend fun safeLog(text: String, color: Color = Color.White) = withContext(Dispatchers.Main) { onLog(text, color) }
    suspend fun <T> runWithRetry(name: String, block: suspend () -> T): T {
        var lastEx: Throwable? = null
        for (i in 1..3) {
            if (checkCancelled()) throw Exception("Annullato")
            try { return withTimeout(120000) { block() } }
            catch (t: Throwable) { lastEx = t; if (i < 3) { safeLog("Retry $i: $name", Color.Yellow); delay(2000) } }
        }
        throw lastEx ?: Exception("Timeout $name")
    }

    try {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext false to "Please Login to Google"
        val credential = GoogleAccountCredential.usingOAuth2(context, Collections.singleton(DriveScopes.DRIVE_READONLY)).apply { selectedAccount = account.account }
        val drive = Drive.Builder(AndroidHttp.newCompatibleTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("CaveArchive").build()

        val folderId = if (urlString.contains("/folders/")) urlString.split("/folders/").getOrNull(1)?.split("?")?.getOrNull(0)
                       else if (urlString.contains("id=")) urlString.split("id=").getOrNull(1)?.split("&")?.getOrNull(0)
                       else null

        if (folderId == null) return@withContext false to "Invalid URL"

        safeLog("Connecting to Drive...", Color.Cyan)
        val driveFiles = runWithRetry("file list") { drive.files().list().setQ("'$folderId' in parents and trashed = false").setFields("files(id, name, mimeType)").execute() }.files ?: emptyList()
        val caveJsonFile = driveFiles.find { it.name == "Cave.json" } ?: return@withContext false to "Cave.json not found"

        val parentFolder = DocumentFile.fromTreeUri(context, localFolderUri) ?: return@withContext false to "Local folder error"
        val dbDocFile = parentFolder.findFile("distox14.sqlite")

        if (dbDocFile != null) {
            val suffix = System.currentTimeMillis()
            val tempDb = JFile(context.cacheDir, "temp_distox14_$suffix.sqlite")
            try {
                safeLog("Updating DB...", Color.Cyan)
                val jsonStr = runWithRetry("download Cave.json") { drive.files().get(caveJsonFile.id).executeMediaAsInputStream().bufferedReader().use { it.readText() } }
                val caves = JSONObject(jsonStr).getJSONArray("caves")
                context.contentResolver.openInputStream(dbDocFile.uri)?.use { input -> FileOutputStream(tempDb).use { input.copyTo(it) } }

                SQLiteDatabase.openOrCreateDatabase(tempDb, null).use { db ->
                    db.beginTransaction()
                    try {
                        for (i in 0 until caves.length()) {
                            if (checkCancelled()) break
                            val cave = caves.getJSONObject(i)
                            val name = cave.getString("name")
                            val vals = ContentValues().apply {
                                put("name", name); put("day", cave.optString("day", "")); put("team", cave.optString("team", ""))
                                put("comment", "CaveArchiveManaged"); put("declination", cave.optDouble("declination", 0.0))
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
                                    put("comment", "CaveArchiveManaged")
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
                                    val tShots = JFile(context.cacheDir, "temp_shots_$suffix.sqlite")
                                    runWithRetry("download $sName") { drive.files().get(sFile.id).executeMediaAsInputStream().use { inp -> FileOutputStream(tShots).use { inp.copyTo(it) } } }
                                    SQLiteDatabase.openDatabase(tShots.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sDb ->
                                        db.delete("shots", "surveyId = ?", arrayOf(sId.toString()))
                                        sDb.query("shots", null, null, null, null, null, null).use { sc ->
                                            if (cursorToFirst(sc)) do {
                                                val row = ContentValues()
                                                for (col in sc.columnNames) {
                                                    if (col == "comment") continue
                                                    val idx = sc.getColumnIndex(col)
                                                    when (sc.getType(idx)) {
                                                        Cursor.FIELD_TYPE_INTEGER -> row.put(col, sc.getLong(idx))
                                                        Cursor.FIELD_TYPE_FLOAT -> row.put(col, sc.getDouble(idx))
                                                        Cursor.FIELD_TYPE_STRING -> row.put(col, sc.getString(idx))
                                                        Cursor.FIELD_TYPE_BLOB -> row.put(col, sc.getBlob(idx))
                                                    }
                                                }
                                                row.put("comment", "CaveArchiveManaged")
                                                row.put("surveyId", sId)
                                                db.insertWithOnConflict("shots", null, row, SQLiteDatabase.CONFLICT_REPLACE)
                                            } while (sc.moveToNext())
                                            safeLog("  + ${sc.count} measurements ($name)", Color.Gray)
                                        }
                                    }
                                    tShots.delete()
                                }
                            }
                        }
                        if (!checkCancelled()) db.setTransactionSuccessful()
                    } finally { if (db.inTransaction()) db.endTransaction() }
                }
                if (!checkCancelled()) {
                    context.contentResolver.openOutputStream(dbDocFile.uri, "wt")?.use { out -> tempDb.inputStream().use { input -> input.copyTo(out) }; out.flush() }
                    safeLog("Database updated.", Color.Green)
                }
            } catch (e: Exception) { safeLog("DB Error: ${e.localizedMessage}", Color.Red) }
            finally { tempDb.delete() }
        }

        var dlCount = 0
        val downloadedPaths = mutableListOf<String>()
        for (f in driveFiles) {
            if (checkCancelled()) break
            if (f.name == "Cave.json" || f.name.lowercase().endsWith(".zip")) continue
            val folderName = when {
                f.name.lowercase().endsWith(".tdconfig") || f.name.lowercase().endsWith(".thconfig") -> "thconfig"
                f.name.lowercase().endsWith(".asc") || f.name.lowercase().endsWith(".asci") || f.name.lowercase().endsWith(".ascii") -> "DTM"
                else -> null
            } ?: continue
            val dest = parentFolder.findFile(folderName) ?: parentFolder.createDirectory(folderName)
            dest?.let { d ->
                runWithRetry("download ${f.name}") {
                    val target = d.findFile(f.name) ?: d.createFile(f.mimeType, f.name)
                    target?.let { t -> 
                        context.contentResolver.openOutputStream(t.uri)?.use { drive.files().get(f.id).executeMediaAndDownloadTo(it) }
                        downloadedPaths.add("$folderName/${f.name}")
                    }
                }
                safeLog("Saved: ${f.name}", Color.Gray)
                dlCount++
            }
        }
        
        if (downloadedPaths.isNotEmpty()) {
            updateFilesInventory(context, localFolderUri, downloadedPaths)
        }

        return@withContext true to (if (checkCancelled()) "Interrupted." else "Completed! $dlCount files downloaded.")
    } catch (t: Throwable) { return@withContext false to "Error: ${t.localizedMessage}" }
}

private fun cursorToFirst(c: Cursor?): Boolean = c?.moveToFirst() ?: false

suspend fun updateFilesInventory(context: Context, folderUri: Uri, newPaths: List<String>) = withContext(Dispatchers.IO) {
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
        val folder = parent.findFile("CaveArchive") ?: parent.createDirectory("CaveArchive") ?: return@withContext
        val file = folder.findFile("CaveArchive_files.json") ?: folder.createFile("application/json", "CaveArchive_files.json") ?: return@withContext
        
        val existingContent = context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() } ?: ""
        val json = if (existingContent.isNotBlank()) JSONObject(existingContent) else JSONObject().apply { put("downloaded_files", JSONArray()) }
        val array = json.getJSONArray("downloaded_files")
        
        val currentSet = mutableSetOf<String>()
        for (i in 0 until array.length()) { currentSet.add(array.getString(i)) }
        
        newPaths.forEach { if (currentSet.add(it)) array.put(it) }
        
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(json.toString(2).toByteArray()) }
    } catch (e: Exception) { e.printStackTrace() }
}

suspend fun updateSynchedCavesInventory(context: Context, folderUri: Uri, newItems: List<JSONObject>) = withContext(Dispatchers.IO) {
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
        val folder = parent.findFile("CaveArchive") ?: parent.createDirectory("CaveArchive") ?: return@withContext
        val file = folder.findFile("CaveArchive_synched_caves.json") ?: folder.createFile("application/json", "CaveArchive_synched_caves.json") ?: return@withContext

        val existingContent = context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() } ?: ""
        val json = if (existingContent.isNotBlank()) JSONObject(existingContent) else JSONObject().apply { put("synched_caves", JSONArray()) }
        val array = json.getJSONArray("synched_caves")
        
        // Aggiorna o aggiungi nuovi item
        newItems.forEach { newItem ->
            val fileName = newItem.getString("file_name")
            var found = false
            for (i in 0 until array.length()) {
                if (array.getJSONObject(i).getString("file_name") == fileName) {
                    array.put(i, newItem)
                    found = true
                    break
                }
            }
            if (!found) array.put(newItem)
        }

        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(json.toString(2).toByteArray()) }
    } catch (e: Exception) { e.printStackTrace() }
}

@Preview(showBackground = true) @Composable fun MainScreenPreview() { CaveArchiveTheme { MainScreen() } }
