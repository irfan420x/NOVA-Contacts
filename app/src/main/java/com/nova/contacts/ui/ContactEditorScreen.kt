package com.nova.contacts.ui

import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nova.contacts.data.ContactDraft
import com.nova.contacts.data.ContactsRepo
import com.nova.contacts.data.EmailEntry
import com.nova.contacts.data.FullContact
import com.nova.contacts.data.PhoneEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val PHONE_TYPES = listOf(
    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE to "Mobile",
    ContactsContract.CommonDataKinds.Phone.TYPE_HOME to "Home",
    ContactsContract.CommonDataKinds.Phone.TYPE_WORK to "Work",
    ContactsContract.CommonDataKinds.Phone.TYPE_MAIN to "Main",
    ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK to "Fax (work)",
    ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME to "Fax (home)",
    ContactsContract.CommonDataKinds.Phone.TYPE_OTHER to "Other",
    // v1.6: Custom — the label is typed in the field that appears below.
    ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM to "Custom"
)

private val EMAIL_TYPES = listOf(
    ContactsContract.CommonDataKinds.Email.TYPE_OTHER to "Other",
    ContactsContract.CommonDataKinds.Email.TYPE_HOME to "Home",
    ContactsContract.CommonDataKinds.Email.TYPE_WORK to "Work",
    ContactsContract.CommonDataKinds.Email.TYPE_MOBILE to "Mobile",
    ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM to "Custom"
)

/** One editor row with a stable id. v1.7: the rows used to be keyed by
 *  position, so removing a middle row could keep the wrong field's
 *  state (two identical empty rows even shared a key). */
private data class EditorRow<T>(val id: String, val value: T)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditorScreen(
    contact: FullContact?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(contact) { mutableStateOf(contact?.displayName ?: "") }
    // v1.5: the editor keeps the full entries (number + type), not just the
    // strings — the old editor rewrote every number to Mobile and every
    // email to Other on every save.
    var phoneRows by remember(contact) {
        mutableStateOf(
            (contact?.phones ?: listOf(PhoneEntry("")))
                .map { EditorRow(UUID.randomUUID().toString(), it) }
        )
    }
    var emailRows by remember(contact) {
        mutableStateOf(
            (contact?.emails ?: listOf(EmailEntry("")))
                .map { EditorRow(UUID.randomUUID().toString(), it) }
        )
    }
    var organization by remember(contact) { mutableStateOf(contact?.organization ?: "") }
    // v1.5: job title is its own field — the old code merged it into the
    // company name ("Acme Corp — Manager") and corrupted it on every edit.
    var jobTitle by remember(contact) { mutableStateOf(contact?.jobTitle ?: "") }
    var note by remember(contact) { mutableStateOf(contact?.note ?: "") }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val isEdit = contact != null

    // v1.7: leaving the editor with unsaved changes used to discard them
    // silently. Compare against the values the editor opened with.
    val initName = remember(contact) { contact?.displayName ?: "" }
    val initPhones = remember(contact) { contact?.phones ?: listOf(PhoneEntry("")) }
    val initEmails = remember(contact) { contact?.emails ?: listOf(EmailEntry("")) }
    val initOrg = remember(contact) { contact?.organization ?: "" }
    val initTitle = remember(contact) { contact?.jobTitle ?: "" }
    val initNote = remember(contact) { contact?.note ?: "" }
    val isDirty = name != initName ||
        phoneRows.map { it.value } != initPhones ||
        emailRows.map { it.value } != initEmails ||
        organization != initOrg || jobTitle != initTitle || note != initNote

    fun requestBack() {
        if (isDirty) confirmDiscard = true else onBack()
    }
    // The editor owns its own back handling so the system back button
    // gets the same discard confirmation as the AppBar back button.
    BackHandler { requestBack() }

    fun save() {
        if (saving) return
        if (name.isBlank()) { onStatus("A name is required"); return }
        // v1.7: basic validation — a phone with no digits or an email
        // without '@' used to be saved as-is.
        val badPhone = phoneRows.map { it.value }
            .firstOrNull { it.number.isNotBlank() && it.number.count { ch -> ch.isDigit() } < 2 }
        if (badPhone != null) {
            onStatus("Phone number \"${badPhone.number}\" doesn't look like a number")
            return
        }
        val badEmail = emailRows.map { it.value }
            .firstOrNull { it.address.isNotBlank() && !isValidEmail(it.address) }
        if (badEmail != null) {
            onStatus("Email \"${badEmail.address}\" doesn't look like an email address")
            return
        }
        if (!ContactsRepo.hasWritePermission(context)) {
            onStatus("Couldn't save — contacts write permission is off")
            return
        }
        saving = true
        val draft = ContactDraft(
            displayName = name.trim(),
            phones = phoneRows.map { it.value },
            emails = emailRows.map { it.value },
            organization = organization,
            jobTitle = jobTitle,
            note = note
        )
        scope.launch {
            // v1.7: provider writes run off the main thread.
            val ok = withContext(Dispatchers.IO) {
                if (isEdit) ContactsRepo.updateContact(context, contact!!.contactId, draft)
                else ContactsRepo.createContact(context, draft)
            }
            saving = false
            if (ok) {
                onStatus(if (isEdit) "Contact updated" else "Contact added")
                onSaved()
            } else {
                onStatus("Couldn't save — check the contacts permission")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit contact" else "New contact", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { requestBack() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(
                        onClick = { save() },
                        enabled = !saving && name.isNotBlank()
                    ) { Text(if (saving) "Saving…" else "Save") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Phone", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            phoneRows.forEach { row ->
                key(row.id) {
                    val p = row.value
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = p.number, onValueChange = { value ->
                                phoneRows = phoneRows.map {
                                    if (it.id == row.id) it.copy(value = p.copy(number = value)) else it
                                }
                            },
                            label = { Text("Phone") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TypePicker(
                            label = if (p.type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) {
                                p.customLabel?.takeIf { it.isNotBlank() } ?: "Custom"
                            } else p.typeLabel,
                            options = PHONE_TYPES,
                            onPick = { type, label ->
                                phoneRows = phoneRows.map {
                                    if (it.id == row.id) {
                                        it.copy(
                                            value = if (type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) {
                                                p.copy(type = type, typeLabel = "Custom", customLabel = p.customLabel ?: "")
                                            } else {
                                                p.copy(type = type, typeLabel = label, customLabel = null)
                                            }
                                        )
                                    } else it
                                }
                            }
                        )
                        if (phoneRows.size > 1) {
                            IconButton(onClick = { phoneRows = phoneRows.filter { it.id != row.id } }) {
                                Icon(Icons.Filled.RemoveCircle, "Remove phone")
                            }
                        }
                    }
                    // v1.6: a custom type needs its label typed out, or the
                    // label is lost and the number shows as "Other" after save.
                    if (p.type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) {
                        OutlinedTextField(
                            value = p.customLabel ?: "", onValueChange = { value ->
                                phoneRows = phoneRows.map {
                                    if (it.id == row.id) it.copy(
                                        value = p.copy(customLabel = value, typeLabel = value.ifBlank { "Custom" })
                                    ) else it
                                }
                            },
                            label = { Text("Custom label (e.g. Assistant)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            OutlinedButton(onClick = {
                phoneRows = phoneRows + EditorRow(
                    UUID.randomUUID().toString(),
                    PhoneEntry("", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, "Mobile")
                )
            }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("Add phone")
            }
            Text("Email", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            emailRows.forEach { row ->
                key(row.id) {
                    val e = row.value
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = e.address, onValueChange = { value ->
                                emailRows = emailRows.map {
                                    if (it.id == row.id) it.copy(value = e.copy(address = value)) else it
                                }
                            },
                            label = { Text("Email") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TypePicker(
                            label = if (e.type == ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM) {
                                e.customLabel?.takeIf { it.isNotBlank() } ?: "Custom"
                            } else e.typeLabel,
                            options = EMAIL_TYPES,
                            onPick = { type, label ->
                                emailRows = emailRows.map {
                                    if (it.id == row.id) {
                                        it.copy(
                                            value = if (type == ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM) {
                                                e.copy(type = type, typeLabel = "Custom", customLabel = e.customLabel ?: "")
                                            } else {
                                                e.copy(type = type, typeLabel = label, customLabel = null)
                                            }
                                        )
                                    } else it
                                }
                            }
                        )
                        if (emailRows.size > 1) {
                            IconButton(onClick = { emailRows = emailRows.filter { it.id != row.id } }) {
                                Icon(Icons.Filled.RemoveCircle, "Remove email")
                            }
                        }
                    }
                    if (e.type == ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM) {
                        OutlinedTextField(
                            value = e.customLabel ?: "", onValueChange = { value ->
                                emailRows = emailRows.map {
                                    if (it.id == row.id) it.copy(
                                        value = e.copy(customLabel = value, typeLabel = value.ifBlank { "Custom" })
                                    ) else it
                                }
                            },
                            label = { Text("Custom label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            OutlinedButton(onClick = {
                emailRows = emailRows + EditorRow(
                    UUID.randomUUID().toString(),
                    EmailEntry("", ContactsContract.CommonDataKinds.Email.TYPE_OTHER, "Other")
                )
            }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("Add email")
            }
            OutlinedTextField(
                value = organization, onValueChange = { organization = it },
                label = { Text("Organisation") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = jobTitle, onValueChange = { jobTitle = it },
                label = { Text("Job title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth().height(96.dp)
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your unsaved changes to this contact will be lost.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            }
        )
    }
}

private fun isValidEmail(address: String): Boolean {
    val a = address.trim()
    val at = a.indexOf('@')
    return at > 0 && at < a.length - 1 && a.indexOf('@', at + 1) < 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypePicker(
    label: String,
    options: List<Pair<Int, String>>,
    onPick: (Int, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (type, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onPick(type, text); expanded = false }
                )
            }
        }
    }
}
