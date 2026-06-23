package com.cavearchive.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material.icons.filled.Public
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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cavearchive.app.ui.theme.CaveArchiveTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.io.File as JFile

data class LogMessage(val text: String, val color: Color, val time: String)
data class ArchiveConfig(val id: Int = 0, val name: String, val url: String)
data class SurveyInfo(val id: Int, val name: String)

sealed class ActiveDialog {
    object Info : ActiveDialog()
    object Wipe : ActiveDialog()
    object ManageArchives : ActiveDialog()
    object Upload : ActiveDialog()
}

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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPrefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var activeDialog by mutableStateOf<ActiveDialog?>(null)
    var wipeAllData by mutableStateOf(true)
    var archiveToWipe by mutableStateOf<ArchiveConfig?>(null)
    var showMenu by mutableStateOf(false)

    var archives by mutableStateOf(emptyList<ArchiveConfig>())
    var selectedArchive by mutableStateOf<ArchiveConfig?>(null)

    var isSyncing by mutableStateOf(false)
    var syncJob by mutableStateOf<Job?>(null)
    var isSignedIn by mutableStateOf(GoogleSignIn.getLastSignedInAccount(application) != null)
    var isDbFound by mutableStateOf(false)

    val logs = mutableStateListOf<LogMessage>()

    var selectedFolderUri by mutableStateOf<Uri?>(sharedPrefs.getString(KEY_FOLDER_URI, null)?.toUri())

    val appVersion: String = try {
        val pInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        pInfo.versionName ?: "Unknown"
    } catch (e: Exception) {
        "Unknown"
    }

    private fun getString(resId: Int, vararg args: Any): String {
        return getApplication<Application>().getString(resId, *args)
    }

    fun addLog(text: String, color: Color = Color.White) {
        val currentTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        if (logs.size >= 500) {
            logs.removeAt(0)
        }
        logs.add(LogMessage(text, color, currentTime))
    }

    fun updateLog(text: String, color: Color = Color.White) {
        if (logs.isNotEmpty()) {
            val last = logs.last()
            logs[logs.size - 1] = last.copy(text = text, color = color)
        } else {
            addLog(text, color)
        }
    }

    fun setFolderUri(uri: Uri) {
        sharedPrefs.edit { putString(KEY_FOLDER_URI, uri.toString()) }
        selectedFolderUri = uri
        addLog(getString(R.string.log_folder_set), Color.Green)
    }

    fun refreshFolderContent(context: Context) {
        val uri = selectedFolderUri ?: return
        viewModelScope.launch {
            try {
                // Perform DB check on IO dispatcher
                val found = withContext(Dispatchers.IO) {
                    val parent = DocumentFile.fromTreeUri(context, uri)
                    parent?.findFile("distox14.sqlite") != null
                }
                
                isDbFound = found
                
                if (isDbFound) {
                    // Heavily IO-bound tasks on IO dispatcher
                    withContext(Dispatchers.IO) {
                        migrateJsonToDb(context, uri)
                    }
                    val loaded = withContext(Dispatchers.IO) {
                        loadSettings(context, uri)
                    }
                    archives = loaded
                    addLog(getString(R.string.log_loaded_archives, loaded.size), Color.Gray)

                    val lastUrl = sharedPrefs.getString(KEY_ARCHIVE_URL, "")
                    selectedArchive = loaded.find { it.url == lastUrl } ?: loaded.firstOrNull()
                    
                    selectedArchive?.let { 
                        sharedPrefs.edit { putString(KEY_ARCHIVE_URL, it.url) }
                    }
                    
                    if (selectedArchive == null || selectedArchive?.url?.isBlank() == true) {
                        addLog(getString(R.string.log_sync_disabled_no_url), Color.Yellow)
                    }
                } else {
                    archives = emptyList()
                    selectedArchive = null
                    addLog(getString(R.string.log_sync_disabled_no_db), Color.Red)
                }
            } catch (e: Exception) {
                addLog(getString(R.string.log_error_loading_folder, e.localizedMessage ?: ""), Color.Red)
                isDbFound = false
            }
        }
    }

    fun saveAndRefreshArchives(context: Context, newList: List<ArchiveConfig>) {
        val uri = selectedFolderUri ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                saveSettingsSuspend(context, uri, newList)
            }
            val loaded = withContext(Dispatchers.IO) {
                loadSettings(context, uri)
            }
            archives = loaded
            
            if (selectedArchive == null || loaded.none { it.url == selectedArchive?.url }) {
                selectedArchive = loaded.firstOrNull()
                selectedArchive?.let { 
                    sharedPrefs.edit { putString(KEY_ARCHIVE_URL, it.url) }
                }
            }
        }
    }

    fun startSync(context: Context) {
        val archive = selectedArchive ?: return
        val uri = selectedFolderUri ?: return
        if (archive.id <= 0) {
            addLog(getString(R.string.log_error_archive_id), Color.Red)
            return
        }

        syncJob = viewModelScope.launch {
            try {
                isSyncing = true
                addLog(getString(R.string.log_sync_started), Color.Cyan)
                val (res, msg) = syncDriveFolderOAuth(context, archive, uri, ::addLog, ::updateLog)
                isSyncing = false
                addLog(msg, if (res) Color.Green else Color.Red)
            } catch (t: Throwable) {
                isSyncing = false
                if (t is kotlinx.coroutines.CancellationException) {
                    addLog(getString(R.string.log_sync_stopped), Color.Yellow)
                } else {
                    addLog(getString(R.string.log_error_general, t.localizedMessage ?: ""), Color.Red)
                }
            } finally {
                syncJob = null
                isSyncing = false
            }
        }
    }

    fun wipeData(context: Context) {
        val uri = selectedFolderUri ?: return
        val targetId = if (wipeAllData) null else archiveToWipe?.id
        if (wipeAllData || (targetId != null && targetId > 0)) {
            activeDialog = null
            viewModelScope.launch {
                val (success, msg) = wipeManagedData(context, uri, targetId, ::addLog)
                addLog(msg, if (success) Color.Green else Color.Red)
            }
        } else if (targetId == 0) {
            addLog(getString(R.string.log_wipe_id_error), Color.Red)
        }
    }

    fun logout(context: Context) {
        showMenu = false
        val client = GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN)
        client.signOut().addOnCompleteListener {
            isSignedIn = false
            addLog(getString(R.string.log_logged_out), Color.Yellow)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val listState = rememberLazyListState()

    LaunchedEffect(viewModel.logs.size) {
        if (viewModel.logs.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.logs.size - 1)
        }
    }

    LaunchedEffect(viewModel.selectedFolderUri) {
        viewModel.refreshFolderContent(context)
    }

    LaunchedEffect(viewModel.selectedArchive) {
        if (viewModel.isDbFound && (viewModel.selectedArchive == null || viewModel.selectedArchive?.url?.isBlank() == true)) {
            viewModel.addLog(context.getString(R.string.log_sync_disabled_no_selection), Color.Yellow)
        }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            viewModel.isSignedIn = true
            viewModel.addLog(context.getString(R.string.log_login_success), Color.Green)
        } else {
            viewModel.addLog(context.getString(R.string.log_login_error, task.exception?.message ?: ""), Color.Red)
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
            viewModel.setFolderUri(uri)
        }
    }


    if (viewModel.activeDialog == ActiveDialog.Info) {
        AlertDialog(
            onDismissRequest = { viewModel.activeDialog = null },
            title = { Text(stringResource(R.string.info_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.info_version, viewModel.appVersion))
                    Text(stringResource(R.string.info_release_date))
                    Text(stringResource(R.string.info_developer))
                    Row {
                        Text(stringResource(R.string.info_email))
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
                        text = stringResource(R.string.info_bugs_suggestion),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.activeDialog = null }) { Text(stringResource(R.string.btn_ok)) }
            }
        )
    }

    if (viewModel.activeDialog == ActiveDialog.Wipe) {
        AlertDialog(
            onDismissRequest = { viewModel.activeDialog = null },
            title = { Text(stringResource(R.string.wipe_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.wipe_confirmation))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = viewModel.wipeAllData, onCheckedChange = { viewModel.wipeAllData = it })
                        Text(stringResource(R.string.wipe_all_data), modifier = Modifier.clickable { viewModel.wipeAllData = !viewModel.wipeAllData })
                    }

                    if (!viewModel.wipeAllData) {
                        var expanded by remember { mutableStateOf(false) }
                        Text(stringResource(R.string.wipe_select_archive), style = MaterialTheme.typography.labelLarge)
                        Box {
                            Button(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(viewModel.archiveToWipe?.name ?: stringResource(R.string.select_archive), maxLines = 1, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                viewModel.archives.forEach { arch ->
                                    DropdownMenuItem(text = { Text(arch.name) }, onClick = {
                                        viewModel.archiveToWipe = arch
                                        expanded = false
                                    })
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.wipe_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.wipeData(context) }
                ) { Text(stringResource(R.string.btn_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.activeDialog = null }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    if (viewModel.activeDialog == ActiveDialog.ManageArchives) {
        ManageArchivesDialog(
            initialArchives = viewModel.archives,
            currentFolderUri = viewModel.selectedFolderUri,
            isDbFound = viewModel.isDbFound,
            onFolderRequest = { folderLauncher.launch(null) },
            onDismiss = { viewModel.activeDialog = null },
            onSave = { newList ->
                viewModel.saveAndRefreshArchives(context, newList)
                viewModel.activeDialog = null
            }
        )
    }

    if (viewModel.activeDialog == ActiveDialog.Upload) {
        UploadDialog(
            context = context,
            localFolderUri = viewModel.selectedFolderUri,
            selectedArchive = viewModel.selectedArchive,
            onDismiss = { viewModel.activeDialog = null },
            onLog = { t, c -> viewModel.addLog(t, c) },
            externalScope = scope
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontSize = 32.sp
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = viewModel.showMenu, onDismissRequest = { viewModel.showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_upload)) },
                            onClick = { viewModel.showMenu = false; viewModel.activeDialog = ActiveDialog.Upload }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_site)) },
                            onClick = {
                                viewModel.showMenu = false
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.cavearchive.it/"))
                                context.startActivity(intent)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_logout)) },
                            onClick = { viewModel.logout(context) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_wipe_data)) },
                            onClick = { viewModel.showMenu = false; viewModel.activeDialog = ActiveDialog.Wipe }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_info)) },
                            onClick = { viewModel.showMenu = false; viewModel.activeDialog = ActiveDialog.Info }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.main_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))

                if (viewModel.isSyncing) {
                    CircularProgressIndicator()
                    val isStopping = viewModel.syncJob?.isCancelled == true
                    val status = if (isStopping) stringResource(R.string.status_stopping) else stringResource(R.string.status_syncing)
                    Text(status, modifier = Modifier.padding(top = 8.dp))
                    if (!isStopping) {
                        Button(
                            onClick = { 
                                viewModel.syncJob?.cancel()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text(stringResource(R.string.btn_stop_sync)) }
                    }
                } else {
                    if (!viewModel.isSignedIn) {
                        Button(onClick = {
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()
                                .requestScopes(
                                    Scope(DriveScopes.DRIVE_READONLY),
                                    Scope(DriveScopes.DRIVE_FILE)
                                ).build()
                            signInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
                        }) { Text(stringResource(R.string.btn_google_login)) }
                    } else {
                        Button(
                            onClick = { viewModel.startSync(context) },
                            enabled = viewModel.isDbFound && viewModel.selectedArchive != null && viewModel.selectedArchive?.url?.isNotBlank() == true,
                            modifier = Modifier
                                .height(56.dp)
                                .fillMaxWidth(0.6f)
                        ) { Text(stringResource(R.string.btn_sync), style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }

            Box(modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(8.dp))
                .padding(8.dp)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(viewModel.logs) { log ->
                        Text("[${log.time}] ${log.text}", color = log.color, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = {
                            val content = viewModel.logs.joinToString("\n") { "[${it.time}] ${it.text}" }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Console Logs", content)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, context.getString(R.string.toast_logs_copied), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.desc_copy_console),
                            tint = Color.Gray.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.logs.clear() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatPaint,
                            contentDescription = stringResource(R.string.desc_clear_console),
                            tint = Color.Gray.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.label_configuration), style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        var expanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            Button(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(viewModel.selectedArchive?.name ?: stringResource(R.string.select_archive), maxLines = 1, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                viewModel.archives.forEach { arch ->
                                    DropdownMenuItem(text = { Text(arch.name) }, onClick = {
                                        viewModel.selectedArchive = arch
                                        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                        sharedPrefs.edit { putString(KEY_ARCHIVE_URL, arch.url) }
                                        expanded = false
                                    })
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.activeDialog = ActiveDialog.ManageArchives }) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.label_folder, viewModel.selectedFolderUri?.let { getDisplayName(it) } ?: stringResource(R.string.folder_not_selected)),
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
    isDbFound: Boolean,
    onFolderRequest: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<ArchiveConfig>) -> Unit
) {
    var list by remember(initialArchives) { mutableStateOf(initialArchives) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingArchive by remember { mutableStateOf<ArchiveConfig?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)) {
                Text(stringResource(R.string.label_storage_folder), style = MaterialTheme.typography.labelLarge)
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
                    Button(onClick = onFolderRequest) { Text(stringResource(R.string.btn_change)) }
                }
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.label_drive_archives), style = MaterialTheme.typography.labelLarge)
                
                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 12.dp)
                    ) {
                        items(list) { arch ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
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
                                IconButton(onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(arch.url))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, context.getString(R.string.toast_cannot_open_url), Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Default.Public, contentDescription = stringResource(R.string.desc_open_drive), tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { editingArchive = arch; showAddDialog = true }) { Icon(Icons.Default.Edit, null) }
                                IconButton(onClick = { list = list.filter { it.id != arch.id || (it.id == 0 && it != arch) } }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }

                Button(
                    onClick = { editingArchive = null; showAddDialog = true }, 
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    enabled = isDbFound
                ) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.btn_add_archive))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(list) }) { Text(stringResource(R.string.btn_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } }
    )

    if (showAddDialog) {
        var name by remember { mutableStateOf(editingArchive?.name ?: "") }
        var url by remember { mutableStateOf(editingArchive?.url ?: "") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(if (editingArchive == null) stringResource(R.string.dialog_new_archive) else stringResource(R.string.dialog_edit_archive)) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.label_name)) })
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.label_drive_url)) })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        val newArch = ArchiveConfig(id = editingArchive?.id ?: 0, name = name, url = url)
                        list = if (editingArchive == null) list + newArch else list.map { if (it.id == editingArchive?.id) newArch else it }
                        showAddDialog = false
                    }
                }) { Text(stringResource(R.string.btn_ok)) }
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
    var isGeopointMode by remember { mutableStateOf(true) }
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
        title = { Text(stringResource(R.string.upload_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.label_select_survey), style = MaterialTheme.typography.labelLarge)
                    ExposedDropdownMenuBox(
                        expanded = expandedSurvey,
                        onExpandedChange = { expandedSurvey = it }
                    ) {
                        OutlinedTextField(
                            value = selectedSurvey?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSurvey) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
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
                        label = { Text(stringResource(R.string.label_new_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isGeopointMode,
                                onCheckedChange = { isGeopointMode = it }
                            )
                            Text(
                                stringResource(R.string.option_upload_geopoint),
                                modifier = Modifier.clickable { isGeopointMode = !isGeopointMode }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isBranchMode,
                                onCheckedChange = { isBranchMode = it }
                            )
                            Text(
                                stringResource(R.string.option_join_branch),
                                modifier = Modifier.clickable { isBranchMode = !isBranchMode }
                            )
                        }
                    }
                }

                if (isBranchMode) {
                    item {
                        Text(stringResource(R.string.label_first_junction), style = MaterialTheme.typography.labelLarge)
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
                                label = { Text(stringResource(R.string.hint_search_station)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFirstStation) },
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                                    .fillMaxWidth()
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
                        Text(stringResource(R.string.label_survey_to_connect), style = MaterialTheme.typography.labelLarge)
                        ExposedDropdownMenuBox(
                            expanded = expandedSurveyToConnect,
                            onExpandedChange = { expandedSurveyToConnect = it }
                        ) {
                            OutlinedTextField(
                                value = selectedSurveyToConnect?.name ?: "",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSurveyToConnect) },
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth()
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
                            Text(stringResource(R.string.label_second_junction), style = MaterialTheme.typography.labelLarge)
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
                                    label = { Text(stringResource(R.string.hint_search_station)) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSecondStation) },
                                    modifier = Modifier
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                                        .fillMaxWidth()
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
                        
                        onDismiss()
                        
                        externalScope.launch {
                            uploadSurveyToDrive(context, selectedArchive, localFolderUri, survey, newName, isGeopointMode, junctionData, onLog)
                        }
                    }
                },
                enabled = selectedSurvey != null && newName.isNotBlank()
            ) {
                Text(stringResource(R.string.btn_upload))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isUploading) { Text(stringResource(R.string.btn_cancel)) }
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
            db.query("surveys", arrayOf("id", "name"), "archiveId = ?", arrayOf(archiveId.toString()), null, null, "name ASC").use { cursor ->
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

fun getOrCreateFolder(drive: Drive, parentId: String, name: String): String {
    val list = drive.files().list()
        .setQ("'$parentId' in parents and name = '$name' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
        .execute().files ?: emptyList()
    if (list.isNotEmpty()) return list[0].id
    val meta = File().apply {
        setName(name); setMimeType("application/vnd.google-apps.folder"); setParents(
        listOf(
            parentId
        )
    )
    }
    return drive.files().create(meta).setFields("id").execute().id
}

fun createUploadShotsDatabase(
    uploadSqliteFile: JFile,
    srcDb: SQLiteDatabase,
    realSurveyId: Int
) {
    val outDb = SQLiteDatabase.openOrCreateDatabase(uploadSqliteFile.absolutePath, null)
    val tableStructureSql: String = srcDb.rawQuery(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='shots'",
        null
    ).use { c ->
        if (c.moveToFirst()) c.getString(0) ?: "" else ""
    }

    try {
        if (tableStructureSql.isNotEmpty()) {
            outDb.execSQL(tableStructureSql)
        } else {
            outDb.execSQL("CREATE TABLE shots (id INTEGER PRIMARY KEY, surveyId INTEGER, fStation TEXT, tStation TEXT, distance REAL, bearing REAL, clino REAL, roll REAL, acceleration REAL, magnetic REAL, dip REAL, type INTEGER, status INTEGER, comment TEXT, extend INTEGER, leg INTEGER, rawData TEXT, archiveId INTEGER)")
        }

        srcDb.query(
            "shots",
            null,
            "surveyId = ?",
            arrayOf(realSurveyId.toString()),
            null,
            null,
            null
        ).use { shotsCursor ->
            outDb.beginTransaction()
            try {
                while (shotsCursor.moveToNext()) {
                    val vals = ContentValues()
                    for (col in shotsCursor.columnNames) {
                        if (col == "caveArchive") continue // Skip old column name during upload creation
                        val idx = shotsCursor.getColumnIndex(col)
                        when (shotsCursor.getType(idx)) {
                            Cursor.FIELD_TYPE_INTEGER -> vals.put(col, shotsCursor.getLong(idx))
                            Cursor.FIELD_TYPE_FLOAT -> vals.put(col, shotsCursor.getDouble(idx))
                            Cursor.FIELD_TYPE_STRING -> vals.put(
                                col,
                                shotsCursor.getString(idx)
                            )

                            Cursor.FIELD_TYPE_BLOB -> vals.put(col, shotsCursor.getBlob(idx))
                            else -> vals.putNull(col)
                        }
                    }
                    outDb.insert("shots", null, vals)
                }
                outDb.setTransactionSuccessful()
            } finally {
                outDb.endTransaction()
            }
        }
    } finally {
        outDb.close()
    }
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
    fun getLog(resId: Int, vararg args: Any): String = context.getString(resId, *args)
    
    try {
        withContext(Dispatchers.Main) { onLog(getLog(R.string.upload_starting, newName), Color.Cyan) }
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            Collections.singleton(DriveScopes.DRIVE_FILE)
        ).apply { selectedAccount = account.account }
        val drive = Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("CaveArchive").build()

        val folderId = (if (archive.url.contains("/folders/")) {
            archive.url.split("/folders/").getOrNull(1)?.split("?")?.getOrNull(0)
        } else if (archive.url.contains("id=")) {
            archive.url.split("id=").getOrNull(1)?.split("&")?.getOrNull(0)
        } else {
            null
        }) ?: return@withContext

        val parent = DocumentFile.fromTreeUri(context, localFolderUri) ?: return@withContext
        val dbDocFile = parent.findFile("distox14.sqlite") ?: return@withContext

        val tempSrcDb = JFile(context.cacheDir, "temp_src_upload.sqlite")
        context.contentResolver.openInputStream(dbDocFile.uri)?.use { input ->
            FileOutputStream(tempSrcDb).use { output -> input.copyTo(output) }
        }

        val uploadSqliteFile = JFile(context.cacheDir, "$newName.sqlite")
        if (uploadSqliteFile.exists()) uploadSqliteFile.delete()
        val surveyDataJson = JSONObject()

        val srcDb = SQLiteDatabase.openDatabase(tempSrcDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            var realSurveyId = survey.id
            srcDb.query(
                "surveys",
                arrayOf("id"),
                "name = ?",
                arrayOf(survey.name),
                null,
                null,
                null
            ).use { c ->
                if (c.moveToFirst()) realSurveyId = c.getInt(0)
            }
            withContext(Dispatchers.Main) {
                onLog(getLog(R.string.upload_processing, realSurveyId, survey.name), Color.Gray)
            }

            srcDb.query(
                "surveys",
                null,
                "id = ?",
                arrayOf(realSurveyId.toString()),
                null,
                null,
                null
            ).use { sc ->
                if (sc.moveToFirst()) {
                    surveyDataJson.put("name", newName)
                    surveyDataJson.put("day", sc.getString(sc.getColumnIndexOrThrow("day")))
                    surveyDataJson.put("team", sc.getString(sc.getColumnIndexOrThrow("team")))
                    surveyDataJson.put(
                        "comment",
                        sc.getString(sc.getColumnIndexOrThrow("comment"))
                    )
                    surveyDataJson.put(
                        "declination",
                        sc.getString(sc.getColumnIndexOrThrow("declination"))
                    )
                    surveyDataJson.put(
                        "init_station",
                        sc.getString(sc.getColumnIndexOrThrow("init_station"))
                    )
                    surveyDataJson.put("shotsFile", "caves/$newName.sqlite")
                }
            }

            if (uploadGeopoint) {
                val fixedsArray = JSONArray()
                srcDb.query(
                    "fixeds",
                    null,
                    "surveyId = ?",
                    arrayOf(realSurveyId.toString()),
                    null,
                    null,
                    null
                ).use { fc ->
                    while (fc.moveToNext()) {
                        val fixedsObj = JSONObject()
                        fixedsObj.put(
                            "station",
                            fc.getString(fc.getColumnIndexOrThrow("station"))
                        )
                        fixedsObj.put(
                            "longitude",
                            fc.getDouble(fc.getColumnIndexOrThrow("longitude"))
                        )
                        fixedsObj.put(
                            "latitude",
                            fc.getDouble(fc.getColumnIndexOrThrow("latitude"))
                        )
                        fixedsObj.put(
                            "altimetric",
                            fc.getDouble(fc.getColumnIndexOrThrow("altimetric"))
                        )
                        fixedsObj.put(
                            "altitude",
                            fc.getDouble(fc.getColumnIndexOrThrow("altitude"))
                        )
                        fixedsArray.put(fixedsObj)
                    }
                }
                
                if (fixedsArray.length() > 0) {
                    surveyDataJson.put("fixeds", fixedsArray)
                } else {
                    throw Exception(context.getString(R.string.upload_error_no_geopoint))
                }
            }

            createUploadShotsDatabase(uploadSqliteFile, srcDb, realSurveyId)
        } finally {
            srcDb.close()
        }

        val cavesFolderId = getOrCreateFolder(drive, folderId, "caves")
        val projectFolderId = getOrCreateFolder(drive, folderId, "project")

        withContext(Dispatchers.Main) {
            onLog(getLog(R.string.upload_cleaning), Color.Cyan)
        }
        val caveDirFiles =
            drive.files().list().setQ("'$cavesFolderId' in parents and trashed = false")
                .setFields("files(id, name)").execute().files ?: emptyList()
        caveDirFiles.filter { it.name == "$newName.sqlite" }.forEach {
            try {
                drive.files().delete(it.id).execute()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onLog(getLog(R.string.upload_duplicate_warning, it.name), Color.Yellow)
                }
            }
        }

        withContext(Dispatchers.Main) { onLog(getLog(R.string.upload_sqlite), Color.Cyan) }
        val driveFileMetadata =
            File().apply { setName("$newName.sqlite"); setParents(listOf(cavesFolderId)) }
        val mediaContent =
            com.google.api.client.http.FileContent("application/x-sqlite3", uploadSqliteFile)
        drive.files().create(driveFileMetadata, mediaContent).execute()

        val rootFiles = drive.files().list().setQ("'$folderId' in parents and trashed = false")
            .setFields("files(id, name)").execute().files ?: emptyList()
        val cavesJsonPattern = Regex("caves([0-9a-fA-F]{5})\\.json")
        val cavesJsonFiles = rootFiles.mapNotNull { f ->
            val match = cavesJsonPattern.matchEntire(f.name)
            if (match != null) f to match.groupValues[1].toInt(16) else null
        }.sortedByDescending { it.second }

        val latestVersion = cavesJsonFiles.firstOrNull()?.second ?: 0
        val nextVersion = latestVersion + 1
        val nextName = "caves${nextVersion.toString(16).padStart(5, '0').uppercase()}.json"

        val caveJsonObject = if (cavesJsonFiles.isNotEmpty()) {
            val content =
                drive.files().get(cavesJsonFiles.first().first.id).executeMediaAsInputStream()
                    .bufferedReader(Charset.forName("UTF-8")).use { it.readText() }
            JSONObject(content)
        } else {
            JSONObject().apply { put("caves", JSONArray()) }
        }

        val cavesArray = caveJsonObject.optJSONArray("caves") ?: JSONArray().also {
            caveJsonObject.put(
                "caves",
                it
            )
        }
        var foundInCaves = false
        for (i in 0 until cavesArray.length()) {
            if (cavesArray.getJSONObject(i).getString("name") == newName) {
                cavesArray.put(i, surveyDataJson)
                foundInCaves = true
                break
            }
        }
        if (!foundInCaves) {
            cavesArray.put(surveyDataJson)
        }
        
        val projectDirFiles =
            drive.files().list().setQ("'$projectFolderId' in parents and trashed = false")
                .setFields("files(id, name)").execute().files ?: emptyList()
        val tdPattern = Regex("(.+?)([0-9a-fA-F]{5})\\.tdconfig")

        var currentProjectPath = caveJsonObject.optString("projectFile")
        if (currentProjectPath.isEmpty()) {
            currentProjectPath = "project/caveArchiveProject.tdconfig"
        }

        val baseName = currentProjectPath.split("/").last().let { name ->
            tdPattern.matchEntire(name)?.groupValues?.get(1) ?: name.removeSuffix(".tdconfig")
        }

        val versions = projectDirFiles.filter { it.name.startsWith(baseName) && it.name.endsWith(".tdconfig") }

        try {
            val updatedTdContent: String
            val nextTdName: String
            val nextTdPath: String

            if (versions.isEmpty()) {
                val nextVer = 1
                nextTdName = "$baseName${nextVer.toString(16).padStart(5, '0').uppercase()}.tdconfig"
                nextTdPath = "project/$nextTdName"
                
                val color = (Math.random() * 16777215).toInt()
                val sb = StringBuilder()
                sb.append("source\n")
                sb.append("  survey \"$baseName\"\n")
                sb.append("    load \"$newName\" -color $color\n")
                if (junctionData != null) {
                    sb.append("    equate \"${junctionData.firstStation}@$newName\" \"${junctionData.secondStation}@${junctionData.otherSurveyName}\"\n")
                }
                sb.append("  endsurvey\n")
                sb.append("endsource")
                updatedTdContent = sb.toString()
            } else {
                val latest = versions.mapNotNull { f ->
                    val m = tdPattern.matchEntire(f.name)
                    if (m != null) f to m.groupValues[2].toInt(16) else f to 0
                }.maxByOrNull { it.second }!!

                val currentFile = latest.first
                val nextVer = latest.second + 1
                nextTdName = "$baseName${nextVer.toString(16).padStart(5, '0').uppercase()}.tdconfig"
                nextTdPath = "project/$nextTdName"

                val content = drive.files().get(currentFile.id).executeMediaAsInputStream()
                    .bufferedReader(Charset.forName("UTF-8")).use { it.readText() }
                val lines = content.lines().toMutableList()
                val searchPattern = "load \"$newName\""

                if (!lines.any { it.trim().startsWith(searchPattern) }) {
                    var insertIndex = -1
                    for (i in lines.indices.reversed()) {
                        if (lines[i].trim().startsWith("load ") || lines[i].trim().startsWith("survey ")) {
                            insertIndex = i + 1; break
                        }
                    }
                    if (insertIndex != -1) {
                        val color = (Math.random() * 16777215).toInt()
                        lines.add(insertIndex, "    load \"$newName\" -color $color")
                        if (junctionData != null) {
                            var equateIndex = lines.size - 1
                            for (i in lines.indices.reversed()) if (lines[i].trim() == "endsurvey") {
                                equateIndex = i; break
                            }
                            lines.add(
                                equateIndex,
                                "    equate \"${junctionData.firstStation}@$newName\" \"${junctionData.secondStation}@${junctionData.otherSurveyName}\""
                            )
                        }
                    }
                    updatedTdContent = lines.joinToString("\n")
                } else {
                    updatedTdContent = "" 
                }
            }

            if (updatedTdContent.isNotEmpty()) {
                val tempTdFile = JFile(context.cacheDir, nextTdName).apply { writeText(updatedTdContent) }
                val meta = File().apply { setName(nextTdName); setParents(listOf(projectFolderId)) }
                drive.files().create(
                    meta,
                    com.google.api.client.http.FileContent("application/octet-stream", tempTdFile)
                ).execute()
                tempTdFile.delete()

                caveJsonObject.put("projectFile", nextTdPath)

                withContext(Dispatchers.Main) {
                    onLog(getLog(R.string.upload_version_created, nextTdName), Color.Green)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onLog(getLog(R.string.upload_project_error, e.localizedMessage ?: ""), Color.Red)
            }
        }

        val jsonFile =
            JFile(context.cacheDir, nextName).apply { writeText(caveJsonObject.toString(3)) }
        val meta = File().apply { setName(nextName); setParents(listOf(folderId)) }
        drive.files()
            .create(meta, com.google.api.client.http.FileContent("application/json", jsonFile))
            .execute()

        tempSrcDb.delete(); uploadSqliteFile.delete(); jsonFile.delete()
        withContext(Dispatchers.Main) {
            onLog(getLog(R.string.upload_completed_version, nextName), Color.Green)
            onLog(getLog(R.string.upload_completed_success), Color.Green)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        val msg = if (e is GoogleJsonResponseException) {
            "Drive Error: ${e.details?.message ?: e.message}"
        } else {
            e.message ?: e.toString()
        }
        withContext(Dispatchers.Main) { onLog(getLog(R.string.upload_error, msg), Color.Red) }
    }
}

suspend fun loadSettings(context: Context, folderUri: Uri): List<ArchiveConfig> {
    return withContext(Dispatchers.IO) {
        try {
            val parent =
                DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
            val dbFile = parent.findFile("distox14.sqlite") ?: return@withContext emptyList()

            val tempDb = JFile(context.cacheDir, "load_settings_temp.sqlite")
            context.contentResolver.openInputStream(dbFile.uri)?.use { input ->
                FileOutputStream(tempDb).use { output -> input.copyTo(output) }
            }

            val result = mutableListOf<ArchiveConfig>()
            SQLiteDatabase.openDatabase(tempDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .use { db ->
                    ensureCaveArchiveColumn(db)
                    db.query("CA_Archives", null, null, null, null, null, null).use { cursor ->
                        while (cursor.moveToNext()) {
                            result.add(
                                ArchiveConfig(
                                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                                    url = cursor.getString(cursor.getColumnIndexOrThrow("url"))
                                )
                            )
                        }
                    }
                }
            tempDb.delete()
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}

fun saveSettings(context: Context, folderUri: Uri, archives: List<ArchiveConfig>) =
        runBlocking<Unit> { saveSettingsSuspend(context, folderUri, archives) }

suspend fun saveSettingsSuspend(context: Context, folderUri: Uri, archives: List<ArchiveConfig>) =
        withContext(Dispatchers.IO) {
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
        fun getLog(resId: Int, vararg args: Any): String = context.getString(resId, *args)
        try {
            val parentFolder = DocumentFile.fromTreeUri(context, localFolderUri)
                ?: return@withContext false to "Local folder error"
            val dbDocFile = parentFolder.findFile("distox14.sqlite")
                ?: return@withContext false to "distox14.sqlite not found"

            val tempDb = JFile(context.cacheDir, "temp_wipe_${System.currentTimeMillis()}.sqlite")
            try {
                context.contentResolver.openInputStream(dbDocFile.uri)?.use { input ->
                    FileOutputStream(tempDb).use { output -> input.copyTo(output) }
                }

                SQLiteDatabase.openOrCreateDatabase(tempDb, null).use { db ->
                    db.beginTransaction()
                    try {
                        ensureCaveArchiveColumn(db)
                        val whereClause =
                            if (archiveId == null) "archiveId > 0" else "archiveId = ?"
                        val whereArgs =
                            if (archiveId == null) null else arrayOf(archiveId.toString())

                        withContext(Dispatchers.Main) {
                            onLog(getLog(R.string.wipe_starting, archiveId?.toString() ?: getLog(R.string.wipe_all)), Color.Cyan)
                        }

                        val fixedDeleted = db.delete("fixeds", whereClause, whereArgs)
                        val shotsDeleted = db.delete("shots", whereClause, whereArgs)
                        val surveysDeleted = db.delete("surveys", whereClause, whereArgs)

                        db.query(
                            "CA_Surveys",
                            arrayOf("file_name"),
                            if (archiveId == null) null else "archiveId = ?",
                            whereArgs,
                            null,
                            null,
                            null
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                val fileName = cursor.getString(0).removeSuffix(".sqlite")
                                db.delete(
                                    "surveys",
                                    "name = ? AND archiveId = 0",
                                    arrayOf(fileName)
                                )
                                db.execSQL("DELETE FROM shots WHERE surveyId NOT IN (SELECT id FROM surveys)")
                                db.execSQL("DELETE FROM fixeds WHERE surveyId NOT IN (SELECT id FROM surveys)")
                            }
                        }

                        db.delete(
                            "CA_Surveys",
                            if (archiveId == null) null else "archiveId = ?",
                            whereArgs
                        )

                        val parent = DocumentFile.fromTreeUri(context, localFolderUri)
                        var deletedFilesCount = 0
                        db.query(
                            "CA_Files",
                            arrayOf("path", "local_path"),
                            if (archiveId == null) null else "archiveId = ?",
                            whereArgs,
                            null,
                            null,
                            null
                        ).use { cursor ->
                            val versionPattern = Regex("(.+?)([0-9a-fA-F]{5})\\.(tdconfig|asc|asci|ascii)")
                            while (cursor.moveToNext()) {
                                val cloudPath = cursor.getString(0) 
                                val localPath = cursor.getString(1) 
                                
                                var fileToDelete: DocumentFile? = null
                                
                                // 1. Prova con local_path salvato nel DB (Source of Truth)
                                if (!localPath.isNullOrEmpty()) {
                                    if (localPath.contains("/")) {
                                        val lpParts = localPath.split("/")
                                        fileToDelete = parent?.findFile(lpParts[0])?.findFile(lpParts[1])
                                    } else {
                                        // Vecchio formato: usiamo la mappatura cloud->local per trovare la cartella
                                        val cloudParts = cloudPath.split("/")
                                        if (cloudParts.size == 2) {
                                            val dir = when (cloudParts[0]) {
                                                "project" -> "thconfig"
                                                "DEMs" -> "DTM"
                                                else -> cloudParts[0]
                                            }
                                            fileToDelete = parent?.findFile(dir)?.findFile(localPath)
                                        }
                                    }
                                }

                                // 2. Fallback aggressivo se local_path non ha funzionato o è assente
                                if (fileToDelete == null) {
                                    val cloudParts = cloudPath.split("/")
                                    if (cloudParts.size == 2) {
                                        val localDirName = when (cloudParts[0]) {
                                            "project" -> "thconfig"
                                            "DEMs" -> "DTM"
                                            else -> cloudParts[0]
                                        }
                                        
                                        val folder = parent?.findFile(localDirName)
                                        if (folder != null) {
                                            val filesInFolder = folder.listFiles()
                                            
                                            // Prova con nome base dal pattern Drive (senza versione)
                                            val match = versionPattern.matchEntire(cloudParts[1])
                                            if (match != null) {
                                                val baseName = match.groupValues[1]
                                                val ext = match.groupValues[3]
                                                fileToDelete = filesInFolder.find { it.name == "$baseName.$ext" }
                                            }
                                            
                                            // Prova con il nome Drive esatto
                                            if (fileToDelete == null) fileToDelete = folder.findFile(cloudParts[1])

                                            // Scansione "aggressiva": cerca file che iniziano con il nome base e hanno la stessa estensione
                                            if (fileToDelete == null) {
                                                val baseSearch = match?.groupValues?.get(1) ?: cloudParts[1].split(".").first()
                                                val extSearch = match?.groupValues?.get(3) ?: cloudParts[1].split(".").last()
                                                fileToDelete = filesInFolder.find { f ->
                                                    val fName = f.name ?: ""
                                                    fName.startsWith(baseSearch, ignoreCase = true) && fName.endsWith(".$extSearch", ignoreCase = true)
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                if (fileToDelete?.delete() == true) {
                                    deletedFilesCount++
                                }
                            }
                        }
                        db.delete(
                            "CA_Files",
                            if (archiveId == null) null else "archiveId = ?",
                            whereArgs
                        )

                        db.setTransactionSuccessful()

                        withContext(Dispatchers.Main) {
                            onLog(getLog(R.string.wipe_files_deleted, deletedFilesCount), Color.Yellow)
                            onLog(getLog(R.string.wipe_data_deleted, surveysDeleted, shotsDeleted, fixedDeleted), Color.Yellow)
                        }

                        if (archiveId == null) {
                            val archiveFolder = parent?.findFile("CaveArchive")
                            archiveFolder?.findFile("CaveArchive_files.json")?.delete()
                            archiveFolder?.findFile("CaveArchive_synched_caves.json")?.delete()
                        }
                    } finally {
                        if (db.inTransaction()) db.endTransaction()
                    }
                }

                context.contentResolver.openOutputStream(dbDocFile.uri, "wt")?.use { output ->
                    tempDb.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                }
                return@withContext true to getLog(R.string.wipe_success)
            } finally {
                if (tempDb.exists()) tempDb.delete()
            }
        } catch (e: Exception) {
            return@withContext false to getLog(R.string.wipe_error, e.localizedMessage ?: "")
        }
    }

fun getDisplayName(uri: Uri): String = uri.path?.split(":")?.lastOrNull() ?: uri.toString()

fun ensureCaveArchiveColumn(db: SQLiteDatabase) {
    val tables = listOf("surveys", "shots", "fixeds")
    for (table in tables) {
        try {
            val cursor = db.rawQuery("SELECT * FROM $table LIMIT 0", null)
            val hasOld = cursor.getColumnIndex("caveArchive") != -1
            val hasNew = cursor.getColumnIndex("archiveId") != -1
            cursor.close()

            if (hasOld && !hasNew) {
                db.execSQL("ALTER TABLE $table ADD COLUMN archiveId INTEGER DEFAULT 0")
                db.execSQL("UPDATE $table SET archiveId = caveArchive")
            } else if (!hasNew) {
                db.execSQL("ALTER TABLE $table ADD COLUMN archiveId INTEGER DEFAULT 0")
            }
        } catch (e: Exception) {
            // Table might not exist yet
        }
    }
    db.execSQL("CREATE TABLE IF NOT EXISTS CA_Archives (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, url TEXT)")
    db.execSQL("CREATE TABLE IF NOT EXISTS CA_Surveys (id INTEGER PRIMARY KEY AUTOINCREMENT, archiveId INTEGER, file_name TEXT, drive_id TEXT, modified_time TEXT)")
    db.execSQL("CREATE TABLE IF NOT EXISTS CA_Files (id INTEGER PRIMARY KEY AUTOINCREMENT, archiveId INTEGER, path TEXT, drive_id TEXT, modified_time TEXT)")
    
    try {
        val cursor = db.rawQuery("SELECT * FROM CA_Files LIMIT 0", null)
        if (cursor.getColumnIndex("local_path") == -1) {
            db.execSQL("ALTER TABLE CA_Files ADD COLUMN local_path TEXT")
        }
        cursor.close()
    } catch (e: Exception) {}
}

suspend fun migrateJsonToDb(context: Context, folderUri: Uri) = withContext(Dispatchers.IO) {
    try {
        val parent = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
        val archiveFolder = parent.findFile("CaveArchive") ?: return@withContext
        val dbFile = parent.findFile("distox14.sqlite") ?: return@withContext

        val settingsFile = archiveFolder.findFile("CaveArchive_Settings.json")
        if (settingsFile != null) {
            val current = loadSettings(context, folderUri)
            if (current.isEmpty()) {
                val jsonStr = context.contentResolver.openInputStream(settingsFile.uri)
                    ?.use { it.bufferedReader().readText() } ?: ""
                val array = JSONObject(jsonStr).getJSONArray("archives")
                val toMigrate = List(array.length()) { i ->
                    val obj = array.getJSONObject(i)
                    ArchiveConfig(name = obj.getString("name"), url = obj.getString("url"))
                }
                if (toMigrate.isNotEmpty()) {
                    saveSettingsSuspend(context, folderUri, toMigrate)
                }
            }
        }

        val inventoryFile = archiveFolder.findFile("CaveArchive_files.json")
        if (inventoryFile != null) {
            val tempDb = JFile(context.cacheDir, "migrate_files_temp.sqlite")
            context.contentResolver.openInputStream(dbFile.uri)?.use { input ->
                FileOutputStream(tempDb).use { output -> input.copyTo(output) }
            }

            SQLiteDatabase.openOrCreateDatabase(tempDb, null).use { db ->
                ensureCaveArchiveColumn(db)
                val count = db.rawQuery("SELECT COUNT(*) FROM CA_Files", null)
                    .use { if (it.moveToFirst()) it.getInt(0) else 0 }
                if (count == 0) {
                    val jsonStr = context.contentResolver.openInputStream(inventoryFile.uri)
                        ?.use { it.bufferedReader().readText() } ?: ""
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
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

suspend fun syncDriveFolderOAuth(
        context: Context,
        archive: ArchiveConfig,
        localFolderUri: Uri,
        onLog: (String, Color) -> Unit,
        onProgressLog: (String, Color) -> Unit
    ): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
        suspend fun safeLog(text: String, color: Color = Color.White) =
            withContext(Dispatchers.Main) { onLog(text, color) }

        suspend fun safeUpdateLog(text: String, color: Color = Color.White) =
            withContext(Dispatchers.Main) { onProgressLog(text, color) }

        fun checkCancelled(): Boolean = !isActive
        
        fun getLog(resId: Int, vararg args: Any): String = context.getString(resId, *args)

        suspend fun <T> runWithRetry(name: String, block: () -> T): T {
            var lastEx: Throwable? = null
            var currentDelay = 2000L
            for (i in 1..3) {
                yield()
                try {
                    return withTimeout(180000) { 
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
                        safeLog("Retry $i/3: $name (${t.localizedMessage ?: "Error"})", Color.Yellow)
                        delay(currentDelay)
                        currentDelay *= 2 
                    }
                }
            }
            throw lastEx ?: Exception("Task $name failed")
        }

        suspend fun downloadFileWithProgress(
            drive: Drive,
            driveFileId: String,
            fileName: String,
            outputStream: java.io.OutputStream
        ) {
            val fileMeta = drive.files().get(driveFileId).setFields("size").execute()
            val totalSize = fileMeta.getSize() ?: -1L
            var totalRead = 0L

            safeLog(getLog(R.string.sync_downloading, fileName), Color.Gray)

            val inputStream = drive.files().get(driveFileId).executeMediaAsInputStream()
            inputStream.use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var lastPct = -1
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        yield()
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalSize > 0) {
                            val pct = (totalRead * 100 / totalSize).toInt()
                            if (pct != lastPct) {
                                safeUpdateLog(getLog(R.string.sync_downloading_pct, fileName, pct), Color.Gray)
                                lastPct = pct
                            }
                        }
                    }
                    output.flush()
                }
            }
        }

        try {
            val account = GoogleSignIn.getLastSignedInAccount(context)
                ?: return@withContext false to context.getString(R.string.btn_google_login)
            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                Collections.singleton(DriveScopes.DRIVE_READONLY)
            ).apply { selectedAccount = account.account }
            val drive = Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("CaveArchive").build()

            val folderId =
                if (archive.url.contains("/folders/")) archive.url.split("/folders/").getOrNull(1)
                    ?.split("?")?.getOrNull(0)
                else if (archive.url.contains("id=")) archive.url.split("id=").getOrNull(1)
                    ?.split("&")?.getOrNull(0)
                else null
            if (folderId == null) return@withContext false to getLog(R.string.sync_error_invalid_url)

            safeLog(getLog(R.string.sync_connecting), Color.Cyan)
            val rootFiles = runWithRetry("file list") {
                drive.files().list().setQ("'$folderId' in parents and trashed = false")
                    .setFields("files(id, name)").execute()
            }.files ?: emptyList()

            val cavesJsonPattern = Regex("caves([0-9a-fA-F]{5})\\.json")
            val latestCavesFile = rootFiles.mapNotNull { f ->
                val match = cavesJsonPattern.matchEntire(f.name)
                if (match != null) f to match.groupValues[1].toInt(16) else null
            }.maxByOrNull { it.second }?.first
                ?: return@withContext false to getLog(R.string.sync_config_not_found)

            safeLog(getLog(R.string.sync_using_config, latestCavesFile.name), Color.Green)
            val jsonStr = runWithRetry("download ${latestCavesFile.name}") {
                drive.files().get(latestCavesFile.id).executeMediaAsInputStream().bufferedReader()
                    .use { it.readText() }
            }
            val caveJsonObject = JSONObject(jsonStr)
            val caves = caveJsonObject.getJSONArray("caves")

            val cavesFolderId = rootFiles.find { it.name.equals("caves", true) }?.id

            val parentFolder = DocumentFile.fromTreeUri(context, localFolderUri)
                ?: return@withContext false to "Local folder error"
            val dbDocFile = parentFolder.findFile("distox14.sqlite")

            var updatedCavesCount = 0

            if (dbDocFile != null) {
                val suffix = System.currentTimeMillis()
                val tempDb = JFile(context.cacheDir, "temp_distox14_$suffix.sqlite")
                try {
                    safeLog(getLog(R.string.sync_updating_db), Color.Cyan)
                    context.contentResolver.openInputStream(dbDocFile.uri)
                        ?.use { input -> FileOutputStream(tempDb).use { input.copyTo(it) } }

                    SQLiteDatabase.openOrCreateDatabase(tempDb, null).use { db ->
                        ensureCaveArchiveColumn(db)

                        val remoteCaveFiles = if (cavesFolderId != null) drive.files().list()
                            .setQ("'$cavesFolderId' in parents and trashed = false")
                            .setFields("files(id, name, modifiedTime)").execute().files
                            ?: emptyList() else emptyList()

                        db.beginTransaction()
                        try {
                            for (i in 0 until caves.length()) {
                                if (checkCancelled()) break
                                val cave = caves.getJSONObject(i)
                                val name = cave.getString("name")
                                val sNameWithPath =
                                    cave.getString("shotsFile") 
                                val sName = sNameWithPath.split("/").last()

                                val vals = ContentValues().apply {
                                    put("name", name); put("day", cave.optString("day", ""))
                                    put("team", cave.optString("team", ""))
                                    put("comment", cave.optString("comment", "")); put("archiveId", archive.id)
                                    put("declination", cave.optDouble("declination", 0.0))
                                    put("init_station", cave.optString("init_station", ""))
                                    put("xsections", 0); put("datamode", 0)
                                    put("extend", 0); put("created", 0); put("immutable", 0)
                                }

                                val sId = db.query(
                                    "surveys",
                                    arrayOf("id"),
                                    "name = ?",
                                    arrayOf(name),
                                    null,
                                    null,
                                    null
                                ).use { c ->
                                    if (cursorToFirst(c)) {
                                        db.update("surveys", vals, "name = ?", arrayOf(name))
                                        c.getInt(0)
                                    } else {
                                        val next = db.rawQuery("SELECT MAX(id) FROM surveys", null)
                                            .use { if (cursorToFirst(it)) it.getInt(0) + 1 else 1 }
                                        vals.put("id", next); db.insert("surveys", null, vals); next
                                    }
                                }

                                if (cave.has("fixeds")) {
                                    val fixedsItem = cave.get("fixeds")
                                    val fixedsArray = if (fixedsItem is JSONArray) fixedsItem else JSONArray().put(fixedsItem)
                                    
                                    for (j in 0 until fixedsArray.length()) {
                                        val f = fixedsArray.getJSONObject(j)
                                        val station = f.optString("station", "")
                                        val fv = ContentValues().apply {
                                            put("surveyId", sId)
                                            put("station", station)
                                            put("longitude", f.optDouble("longitude", 0.0))
                                            put("latitude", f.optDouble("latitude", 0.0))
                                            put("altitude", f.optDouble("altitude", 0.0))
                                            put("altimetric", f.optDouble("altimetric", 0.0))
                                            put("comment", "")
                                            put("archiveId", archive.id)
                                            put("status", 0); put("cs_name", ""); put("cs_longitude", 0.0)
                                            put("cs_latitude", 0.0); put("cs_altitude", 0.0); put("source", 2)
                                            put("cs_decimals", 2); put("convergence", 0.0)
                                        }
                                        
                                        db.query("fixeds", arrayOf("id"), "surveyId = ? AND station = ?", arrayOf(sId.toString(), station), null, null, null).use { c ->
                                            if (cursorToFirst(c)) {
                                                db.update("fixeds", fv, "id = ?", arrayOf(c.getInt(0).toString()))
                                            } else {
                                                db.insert("fixeds", null, fv)
                                            }
                                        }
                                    }
                                }

                                val sFile = remoteCaveFiles.find { it.name == sName }
                                if (sFile != null) {
                                    val currentModifiedTime = sFile.modifiedTime?.toString() ?: ""
                                    var needsUpdate = true
                                    db.query("CA_Surveys", arrayOf("modified_time"), "archiveId = ? AND file_name = ?", arrayOf(archive.id.toString(), sName), null, null, null).use { mc ->
                                        if (mc.moveToFirst() && mc.getString(0) == currentModifiedTime) needsUpdate = false
                                    }

                                    if (!needsUpdate) {
                                        safeLog(getLog(R.string.sync_is_up_to_date, sName), Color.Gray)
                                    } else {
                                        val tShots = JFile(context.cacheDir, "temp_shots.sqlite")
                                        runWithRetry("download $sName") {
                                            context.contentResolver.openOutputStream(Uri.fromFile(tShots))?.use { out ->
                                                kotlinx.coroutines.runBlocking { downloadFileWithProgress(drive, sFile.id, sName, out) }
                                            }
                                        }

                                        val metaVals = ContentValues().apply {
                                            put("archiveId", archive.id); put("file_name", sName)
                                            put("drive_id", sFile.id); put("modified_time", currentModifiedTime)
                                        }
                                        if (db.update("CA_Surveys", metaVals, "archiveId = ? AND file_name = ?", arrayOf(archive.id.toString(), sName)) == 0) 
                                            db.insert("CA_Surveys", null, metaVals)

                                        SQLiteDatabase.openDatabase(tShots.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sDb ->
                                            db.delete("shots", "surveyId = ?", arrayOf(sId.toString()))
                                            sDb.query("shots", null, null, null, null, null, null).use { sc ->
                                                if (cursorToFirst(sc)) do {
                                                    val row = ContentValues()
                                                    for (col in sc.columnNames) {
                                                        if (col == "caveArchive") continue 
                                                        val idx = sc.getColumnIndex(col)
                                                        when (sc.getType(idx)) {
                                                            Cursor.FIELD_TYPE_INTEGER -> row.put(col, sc.getLong(idx))
                                                            Cursor.FIELD_TYPE_FLOAT -> row.put(col, sc.getDouble(idx))
                                                            Cursor.FIELD_TYPE_STRING -> row.put(col, sc.getString(idx))
                                                            Cursor.FIELD_TYPE_BLOB -> row.put(col, sc.getBlob(idx))
                                                            else -> row.putNull(col)
                                                        }
                                                    }
                                                    row.put("archiveId", archive.id); row.put("surveyId", sId)
                                                    db.insertWithOnConflict("shots", null, row, SQLiteDatabase.CONFLICT_REPLACE)
                                                } while (sc.moveToNext())
                                            }
                                        }
                                        tShots.delete(); updatedCavesCount++
                                    }
                                } else {
                                    safeLog(getLog(R.string.sync_error_shots_not_found, sName), Color.Red)
                                }
                            }
                            if (!checkCancelled()) db.setTransactionSuccessful()
                        } finally {
                            if (db.inTransaction()) db.endTransaction()
                        }
                    }
                    if (!checkCancelled()) {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            context.contentResolver.openOutputStream(dbDocFile.uri, "wt")?.use { out ->
                                tempDb.inputStream().use { it.copyTo(out) }; out.flush()
                            }
                            safeLog(getLog(R.string.sync_db_updated, updatedCavesCount), Color.Green)
                        }
                    }
                } catch (e: Exception) {
                    safeLog("DB Error: ${e.localizedMessage}", Color.Red)
                } finally {
                    tempDb.delete()
                }
            }

            suspend fun syncFilesList(
                jsonArray: JSONArray,
                localDirName: String
            ) {
                val versionPattern = Regex("(.+?)([0-9a-fA-F]{5})\\.(tdconfig|asc|asci|ascii)")

                for (j in 0 until jsonArray.length()) {
                    if (checkCancelled()) break
                    val fullPath = jsonArray.getString(j) 
                    val parts = fullPath.split("/")
                    if (parts.size != 2) continue

                    val remoteDirName = parts[0]
                    val fileNameWithExt = parts[1]

                    val remoteFolderId = rootFiles.find { it.name.equals(remoteDirName, true) }?.id
                    if (remoteFolderId == null) continue

                    val remoteFiles = drive.files().list()
                        .setQ("'$remoteFolderId' in parents and trashed = false")
                        .setFields("files(id, name, modifiedTime)").execute().files ?: emptyList()

                    val baseMatch = versionPattern.matchEntire(fileNameWithExt)
                    val baseName = baseMatch?.groupValues?.get(1) ?: fileNameWithExt.split(".").first()
                    val ext = fileNameWithExt.split(".").last()

                    val latestRemoteFile = remoteFiles.filter { f ->
                        val m = versionPattern.matchEntire(f.name)
                        if (m != null) m.groupValues[1] == baseName && m.groupValues[3] == ext
                        else f.name == fileNameWithExt || f.name == "$baseName.$ext"
                    }.mapNotNull { f ->
                        val m = versionPattern.matchEntire(f.name)
                        if (m != null) f to m.groupValues[2].toInt(16) else f to 0
                    }.maxByOrNull { it.second }?.first
                        ?: remoteFiles.find { it.name == fileNameWithExt }

                    if (latestRemoteFile != null) {
                        val currentModifiedTime = latestRemoteFile.modifiedTime?.toString() ?: ""
                        var needsUpdate = true

                        val tempDbFile = JFile(context.cacheDir, "meta_check.sqlite")
                        if (dbDocFile != null) {
                            context.contentResolver.openInputStream(dbDocFile.uri)
                                ?.use { input -> FileOutputStream(tempDbFile).use { input.copyTo(it) } }
                            SQLiteDatabase.openDatabase(tempDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                                ensureCaveArchiveColumn(db)
                                db.query("CA_Files", arrayOf("modified_time"), "archiveId = ? AND path = ?", arrayOf(archive.id.toString(), fullPath), null, null, null).use { cursor ->
                                    if (cursor.moveToFirst() && cursor.getString(0) == currentModifiedTime) needsUpdate = false
                                }
                            }
                            tempDbFile.delete()
                        }

                        if (needsUpdate) {
                            val destDir = parentFolder.findFile(localDirName) ?: parentFolder.createDirectory(localDirName)
                            destDir?.let { d ->
                                val tempDownloadFile = JFile(context.cacheDir, "temp_download_${System.currentTimeMillis()}")
                                runWithRetry("download ${latestRemoteFile.name}") {
                                    FileOutputStream(tempDownloadFile).use { out ->
                                        kotlinx.coroutines.runBlocking { downloadFileWithProgress(drive, latestRemoteFile.id, latestRemoteFile.name, out) }
                                    }
                                }

                                var localName = "$baseName.$ext"
                                if (ext.equals("tdconfig", ignoreCase = true)) {
                                    try {
                                        val content = tempDownloadFile.readText(Charset.forName("UTF-8"))
                                        val surveyMatch = Regex("""\bsurvey\s+"([^"]+)"""").find(content)
                                        surveyMatch?.groupValues?.get(1)?.let { internalName ->
                                            localName = "$internalName.tdconfig"
                                        }
                                    } catch (e: Exception) {}
                                }

                                val target = d.findFile(localName) ?: d.createFile("application/octet-stream", localName)
                                target?.let { t ->
                                    context.contentResolver.openOutputStream(t.uri)?.use { out ->
                                        tempDownloadFile.inputStream().use { it.copyTo(out) }
                                    }
                                    
                                    updateFilesInventorySuspend(
                                        context, localFolderUri, archive.id,
                                        listOf(JSONObject().apply {
                                            put("path", fullPath); put("drive_id", latestRemoteFile.id)
                                            put("modified_time", currentModifiedTime); put("local_path", "$localDirName/$localName")
                                        })
                                    )
                                }
                                tempDownloadFile.delete()
                            }
                        } else {
                            safeLog(getLog(R.string.sync_is_up_to_date, latestRemoteFile.name), Color.Gray)
                        }
                    }
                }
            }

            val demFile = caveJsonObject.optString("demFile")
            if (demFile.isNotEmpty()) syncFilesList(JSONArray().put(demFile), "DTM")
            val projectFile = caveJsonObject.optString("projectFile")
            if (projectFile.isNotEmpty()) syncFilesList(JSONArray().put(projectFile), "thconfig")

            return@withContext true to getLog(R.string.sync_completed)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) return@withContext false to getLog(R.string.sync_interrupted)
            return@withContext false to getLog(R.string.sync_error, t.localizedMessage ?: "")
        }
    }
}

fun cursorToFirst(c: Cursor?): Boolean = c?.moveToFirst() ?: false

fun updateFilesInventory(
        context: Context,
        folderUri: Uri,
        archiveId: Int,
        newItems: List<JSONObject>
    ) = runBlocking<Unit> { updateFilesInventorySuspend(context, folderUri, archiveId, newItems) }

suspend fun updateFilesInventorySuspend(
        context: Context,
        folderUri: Uri,
        archiveId: Int,
        newItems: List<JSONObject>
    ) {
        withContext(Dispatchers.IO) {
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
                            if (newItem.has("local_path")) put("local_path", newItem.getString("local_path"))
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    CaveArchiveTheme { MainScreen() }
}
