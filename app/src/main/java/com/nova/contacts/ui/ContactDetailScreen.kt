package com.nova.contacts.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nova.contacts.data.ContactsRepo
import com.nova.contacts.data.ErrorLog
import com.nova.contacts.data.FullContact
import com.nova.contacts.data.VCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    contact: FullContact,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var starred by remember(contact) { mutableStateOf(contact.starred) }
    var starring by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // v1.4: recent calls with this contact (needs the call-log permission,
    // requested here on demand — the app works without it).
    var callLogGranted by remember { mutableStateOf(ContactsRepo.hasCallLogPermission(context)) }
    var callHistory by remember { mutableStateOf<List<ContactsRepo.CallHistoryItem>>(emptyList()) }
    val callLogLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        callLogGranted = granted
        if (granted) {
            scope.launch {
                callHistory = withContext(Dispatchers.IO) {
                    ContactsRepo.loadCallHistory(context, contact.phones.map { it.number })
                }
            }
        } else {
            // v1.7: after a permanent denial the button used to do
            // nothing at all — say why, instead of failing silently.
            onStatus("Call history needs the call log permission — allow it in Settings")
        }
    }
    LaunchedEffect(contact.contactId) {
        if (callLogGranted) {
            callHistory = withContext(Dispatchers.IO) {
                ContactsRepo.loadCallHistory(context, contact.phones.map { it.number })
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(
                        onClick = {
                            if (starring) return@IconButton
                            if (!ContactsRepo.hasWritePermission(context)) {
                                onStatus("Write permission is off — allow it to change favorites")
                                return@IconButton
                            }
                            starring = true
                            val target = !starred
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    ContactsRepo.setStarred(context, contact.contactId, target)
                                }
                                starring = false
                                if (ok) starred = target
                                else onStatus("Couldn't update favorite")
                            }
                        },
                        enabled = !starring
                    ) {
                        Icon(
                            if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            if (starred) "Remove from favorites" else "Add to favorites",
                            tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        if (!ContactsRepo.hasWritePermission(context)) {
                            onStatus("Write permission is off — allow it to edit contacts")
                        } else onEdit()
                    }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = {
                        if (!ContactsRepo.hasWritePermission(context)) {
                            onStatus("Write permission is off — allow it to delete contacts")
                        } else confirmDelete = true
                    }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ContactPhoto(contact, 96)
            Spacer(Modifier.height(12.dp))
            Text(contact.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            contact.organization?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // v1.6: the job title is shown here — it was stored by the
            // editor but invisible everywhere except the editor itself.
            contact.jobTitle?.takeIf { t -> t.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (contact.timesContacted > 0) {
                Spacer(Modifier.height(4.dp))
                Text("${contact.timesContacted} calls", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            // Quick actions.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val firstPhone = contact.phones.firstOrNull()?.number
                if (firstPhone != null) {
                    FilledTonalButton(onClick = {
                        // v1.5: Uri.fromParts keeps #/* intact — Uri.parse
                        // treats # as a fragment delimiter and truncates.
                        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", firstPhone, null))
                        try { context.startActivity(intent) } catch (e: Exception) {
                            ErrorLog.log(context, "Detail", "Failed to open dialer", e)
                            onStatus("Couldn't open the dialer")
                        }
                    }) {
                        Icon(Icons.Filled.Call, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Call")
                    }
                    FilledTonalButton(onClick = {
                        // v1.6: Uri.fromParts keeps # intact — Uri.parse
                        // treats # as a fragment delimiter and truncates.
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", firstPhone, null))
                        try { context.startActivity(intent) } catch (e: Exception) {
                            ErrorLog.log(context, "Detail", "Failed to open messaging", e)
                            onStatus("Couldn't open messages")
                        }
                    }) {
                        Icon(Icons.Filled.Message, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Message")
                    }
                }
                FilledTonalButton(onClick = {
                    scope.launch {
                        val shared = withContext(Dispatchers.IO) {
                            shareContactAsVcf(context, contact)
                        }
                        if (!shared) onStatus("Couldn't share this contact")
                    }
                }) {
                    Icon(Icons.Filled.Share, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Share")
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (contact.phones.isNotEmpty()) {
                    Text("Phone", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    contact.phones.forEach { p ->
                        DetailRow(Icons.Filled.Phone, p.number, p.typeLabel) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Phone number", p.number))
                            onStatus("Number copied")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (contact.emails.isNotEmpty()) {
                    Text("Email", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    contact.emails.forEach { e ->
                        DetailRow(Icons.Filled.Email, e.address, e.typeLabel) {
                            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", e.address, null))
                            try { context.startActivity(intent) } catch (ex: Exception) {
                                ErrorLog.log(context, "Detail", "Failed to open email", ex)
                                onStatus("Couldn't open email")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                contact.note?.takeIf { it.isNotBlank() }?.let {
                    Text("Note", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(it, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                // v1.4: recent calls with this contact.
                if (contact.phones.isNotEmpty()) {
                    Text("Recent calls", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    if (!callLogGranted) {
                        OutlinedButton(
                            onClick = { callLogLauncher.launch(android.Manifest.permission.READ_CALL_LOG) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) { Text("Show call history") }
                    } else if (callHistory.isEmpty()) {
                        Text(
                            "No recent calls with this contact.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        callHistory.forEach { h ->
                            val typeLabel = when (h.type) {
                                android.provider.CallLog.Calls.INCOMING_TYPE -> "Incoming"
                                android.provider.CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                                android.provider.CallLog.Calls.MISSED_TYPE -> "Missed"
                                android.provider.CallLog.Calls.REJECTED_TYPE -> "Rejected"
                                android.provider.CallLog.Calls.BLOCKED_TYPE -> "Blocked"
                                else -> "Call"
                            }
                            val dateText = java.text.DateFormat.getDateTimeInstance(
                                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
                            ).format(java.util.Date(h.date))
                            val durText = if (h.durationSeconds > 0) " • ${formatCallDuration(h.durationSeconds)}" else ""
                            DetailRow(
                                Icons.Filled.Call,
                                "$typeLabel — $dateText$durText",
                                h.number
                            ) { }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete contact?") },
            text = { Text("${contact.displayName} will be deleted from your contacts. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            ContactsRepo.deleteContact(context, contact.contactId)
                        }
                        if (ok) {
                            onStatus("Contact deleted")
                            onBack()
                        } else onStatus("Couldn't delete this contact")
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/**
 * Shares one contact as a real .vcf file (not vCard text in EXTRA_TEXT,
 * which most apps cannot import as a contact). v1.7: the old share sent
 * type "text/x-vcard" with EXTRA_TEXT — receiving apps got plain text,
 * not a contact file. Now the card (with its real types and job title)
 * is written to a cache file and shared through FileProvider.
 */
private fun shareContactAsVcf(context: Context, contact: FullContact): Boolean {
    return try {
        val vcard = VCard.generate(listOf(ContactsRepo.toVCardContact(contact)))
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safeName = contact.displayName
            .replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
            .takeIf { it.isNotBlank() } ?: "contact"
        val file = File(dir, "$safeName.vcf")
        file.writeText(vcard)
        val uri = FileProvider.getUriForFile(context, "com.nova.contacts.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/x-vcard"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Contact", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share contact"))
        true
    } catch (e: Exception) {
        ErrorLog.log(context, "Detail", "Failed to share contact", e)
        false
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(label) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** v1.7: hours were missing — a 1-hour call rendered as "60m 0s". */
private fun formatCallDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m ${s}s" else if (m > 0) "${m}m ${s}s" else "${s}s"
}
