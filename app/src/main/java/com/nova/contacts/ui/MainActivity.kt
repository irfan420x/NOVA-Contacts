package com.nova.contacts.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.nova.contacts.data.ContactUtils
import com.nova.contacts.data.ContactsRepo
import com.nova.contacts.data.ErrorLog
import com.nova.contacts.data.FullContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ContactsScreen {
    data object List : ContactsScreen
    data class Detail(val contact: FullContact) : ContactsScreen
    data class Editor(val contact: FullContact?) : ContactsScreen
    data object Duplicates : ContactsScreen
    data object ErrorLog : ContactsScreen
}

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.WRITE_CONTACTS
)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                ErrorLog.log(applicationContext, "CRASH", "Uncaught exception on ${thread.name}", e)
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, e)
        }
        setContent { NovaContactsApp() }
    }
}

private fun loadDarkTheme(context: Context): Boolean =
    context.getSharedPreferences("nova_contacts", Context.MODE_PRIVATE).getBoolean("ui_dark_theme", true)

private fun saveDarkTheme(context: Context, dark: Boolean) {
    context.getSharedPreferences("nova_contacts", Context.MODE_PRIVATE)
        .edit().putBoolean("ui_dark_theme", dark).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaContactsApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<ContactsScreen>(ContactsScreen.List) }
    var contacts by remember { mutableStateOf<List<FullContact>>(emptyList()) }
    var duplicateCount by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var darkTheme by remember { mutableStateOf(loadDarkTheme(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var permissionsGranted by remember {
        mutableStateOf(REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    var skipPermissions by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true
            contacts = withContext(Dispatchers.IO) { ContactsRepo.loadAll(context) }
            duplicateCount = withContext(Dispatchers.IO) {
                ContactUtils.groupDuplicates(contacts).size
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(status) {
        if (status != null) { kotlinx.coroutines.delay(3000); status = null }
    }
    fun onStatus(msg: String) { status = msg }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = REQUIRED_PERMISSIONS.all { results[it] == true }
        if (permissionsGranted) reload()
    }

    // vCard import: pick a .vcf file, read it, create the contacts.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                } catch (e: Exception) {
                    ErrorLog.log(context, "Import", "Failed to read the .vcf file", e)
                    null
                }
            }
            if (text == null) { onStatus("Couldn't read that file"); return@launch }
            val created = withContext(Dispatchers.IO) { ContactsRepo.importVCard(context, text) }
            onStatus(if (created > 0) "Imported $created contact${if (created == 1) "" else "s"}" else "No contacts found in that file")
            reload()
        }
    }
    // vCard export: save all contacts as a .vcf file.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/vcard")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val vcard = ContactsRepo.exportVCard(context)
                    context.contentResolver.openOutputStream(uri)?.writer()?.use { it.write(vcard) }
                    true
                } catch (e: Exception) {
                    ErrorLog.log(context, "Export", "Failed to write the .vcf file", e)
                    false
                }
            }
            onStatus(if (ok) "Backup saved (${contacts.size} contacts)" else "Couldn't save the backup")
        }
    }

    NovaTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = screen) {
                is ContactsScreen.ErrorLog -> ErrorLogScreen(onBack = { screen = ContactsScreen.List })
                is ContactsScreen.Duplicates -> DuplicatesScreen(
                    onBack = { screen = ContactsScreen.List; reload() },
                    onStatus = ::onStatus
                )
                is ContactsScreen.Detail -> ContactDetailScreen(
                    contact = s.contact,
                    onBack = { screen = ContactsScreen.List; reload() },
                    onEdit = { screen = ContactsScreen.Editor(s.contact) },
                    onStatus = ::onStatus
                )
                is ContactsScreen.Editor -> ContactEditorScreen(
                    contact = s.contact,
                    onBack = { screen = if (s.contact == null) ContactsScreen.List else ContactsScreen.Detail(s.contact) },
                    onSaved = { screen = ContactsScreen.List; reload() },
                    onStatus = ::onStatus
                )
                is ContactsScreen.List -> {
                    if (!permissionsGranted && !skipPermissions) {
                        PermissionScreen(
                            onGrant = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                            onSkip = { skipPermissions = true }
                        )
                    } else {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = { Text("Contacts", fontWeight = FontWeight.Bold) },
                                    actions = {
                                        IconButton(onClick = { importLauncher.launch(arrayOf("text/vcard", "text/x-vcard", "*/*")) }) {
                                            Icon(Icons.Filled.FileDownload, "Import .vcf")
                                        }
                                        IconButton(onClick = {
                                            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                            exportLauncher.launch("nova-contacts-backup-$date.vcf")
                                        }) {
                                            Icon(Icons.Filled.FileUpload, "Export .vcf")
                                        }
                                        IconButton(onClick = { screen = ContactsScreen.Duplicates }) {
                                            BadgedBox(badge = {
                                                if (duplicateCount > 0) Badge { Text(duplicateCount.toString()) }
                                            }) { Icon(Icons.Filled.CopyAll, "Duplicates") }
                                        }
                                        IconButton(onClick = { screen = ContactsScreen.ErrorLog }) {
                                            Icon(Icons.Filled.BugReport, "Error log")
                                        }
                                        IconButton(onClick = {
                                            darkTheme = !darkTheme
                                            saveDarkTheme(context, darkTheme)
                                        }) {
                                            Icon(
                                                if (darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                                if (darkTheme) "Light theme" else "Dark theme"
                                            )
                                        }
                                    }
                                )
                            },
                            floatingActionButton = {
                                FloatingActionButton(onClick = { screen = ContactsScreen.Editor(null) }) {
                                    Icon(Icons.Filled.PersonAdd, "Add contact")
                                }
                            }
                        ) { padding ->
                            Column(Modifier.padding(padding).fillMaxSize()) {
                                status?.let {
                                    Text(
                                        it, color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                }
                                OutlinedTextField(
                                    value = query, onValueChange = { query = it },
                                    placeholder = { Text("Search name or number") },
                                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                                    trailingIcon = {
                                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                            Icon(Icons.Filled.Close, "Clear")
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                ContactList(
                                    contacts = contacts,
                                    query = query,
                                    loading = loading,
                                    onOpen = { screen = ContactsScreen.Detail(it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ContactList(
    contacts: List<FullContact>,
    query: String,
    loading: Boolean,
    onOpen: (FullContact) -> Unit
) {
    val context = LocalContext.current
    val filtered = remember(query, contacts) {
        if (query.isBlank()) contacts
        else contacts.filter { ContactUtils.matchesQuery(it, query) }
    }
    val favorites = remember(contacts) { contacts.filter { it.starred } }
    val searching = query.isNotBlank()
    when {
        !ContactsRepo.hasContactsPermission(context) -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Contacts need permission", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (searching) "No contacts match \"$query\"" else "No contacts yet — tap + to add one",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> {
            // Group by first letter for section headers.
            val grouped = remember(filtered) {
                filtered.groupBy { (it.displayName.firstOrNull()?.uppercase() ?: "#").let { l -> if (l[0].isLetter()) l else "#" } }
                    .toSortedMap()
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (!searching && favorites.isNotEmpty()) {
                    item {
                        Text("Favorites", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    item {
                        LazyRow(
                            Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(favorites, key = { it.contactId }) { c ->
                                Column(
                                    Modifier.width(72.dp).clickable { onOpen(c) },
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    ContactPhoto(c, 56)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        c.displayName.split(" ").firstOrNull() ?: c.displayName,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                grouped.forEach { (letter, group) ->
                    stickyHeader {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Text(
                                letter, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }
                    items(group, key = { it.contactId }) { c ->
                        ListItem(
                            headlineContent = { Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    (c.phones.firstOrNull()?.number ?: c.emails.firstOrNull()?.address ?: c.organization ?: ""),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = { ContactPhoto(c, 48) },
                            trailingContent = {
                                if (c.starred) Icon(
                                    Icons.Filled.Star, "Favorite",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            modifier = Modifier.clickable { onOpen(c) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContactPhoto(c: FullContact, sizeDp: Int) {
    if (c.photoUri != null) {
        AsyncImage(
            model = c.photoUri, contentDescription = null,
            modifier = Modifier.size(sizeDp.dp).clip(CircleShape)
        )
    } else {
        Box(
            Modifier.size(sizeDp.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                c.displayName.firstOrNull()?.uppercase() ?: "?",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Contacts, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("NOVA Contacts", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "To show, add and edit your contacts, NOVA Contacts needs access to them. Your data stays on your phone — nothing is uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, Modifier.fillMaxWidth()) { Text("Allow access") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSkip, Modifier.fillMaxWidth()) { Text("Continue without it") }
    }
}
