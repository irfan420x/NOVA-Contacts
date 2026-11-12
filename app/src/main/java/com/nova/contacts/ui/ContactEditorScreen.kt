package com.nova.contacts.ui

import android.provider.ContactsContract
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
import kotlinx.coroutines.launch

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
    var phones by remember(contact) {
        mutableStateOf(contact?.phones?.toMutableList() ?: mutableListOf(PhoneEntry("")))
    }
    var emails by remember(contact) {
        mutableStateOf(contact?.emails?.toMutableList() ?: mutableListOf(EmailEntry("")))
    }
    var organization by remember(contact) { mutableStateOf(contact?.organization ?: "") }
    // v1.5: job title is its own field — the old code merged it into the
    // company name ("Acme Corp — Manager") and corrupted it on every edit.
    var jobTitle by remember(contact) { mutableStateOf(contact?.jobTitle ?: "") }
    var note by remember(contact) { mutableStateOf(contact?.note ?: "") }
    var saving by remember { mutableStateOf(false) }
    val isEdit = contact != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit contact" else "New contact", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(
                        onClick = {
                            if (name.isBlank()) { onStatus("A name is required"); return@TextButton }
                            saving = true
                            val draft = ContactDraft(
                                displayName = name.trim(),
                                phones = phones,
                                emails = emails,
                                organization = organization,
                                jobTitle = jobTitle,
                                note = note
                            )
                            scope.launch {
                                val ok = if (isEdit) {
                                    ContactsRepo.updateContact(context, contact!!.contactId, draft)
                                } else {
                                    ContactsRepo.createContact(context, draft)
                                }
                                saving = false
                                if (ok) {
                                    onStatus(if (isEdit) "Contact updated" else "Contact added")
                                    onSaved()
                                } else {
                                    onStatus("Couldn't save — check the contacts permission")
                                }
                            }
                        },
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
            phones.forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = p.number, onValueChange = { value ->
                            phones = phones.toMutableList().also { it[i] = p.copy(number = value) }
                        },
                        label = { Text("Phone ${i + 1}") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TypePicker(
                        label = if (p.type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) {
                            p.customLabel?.takeIf { it.isNotBlank() } ?: "Custom"
                        } else p.typeLabel,
                        options = PHONE_TYPES,
                        onPick = { type, label ->
                            phones = phones.toMutableList().also {
                                it[i] = if (type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) {
                                    p.copy(type = type, typeLabel = "Custom", customLabel = p.customLabel ?: "")
                                } else {
                                    p.copy(type = type, typeLabel = label, customLabel = null)
                                }
                            }
                        }
                    )
                    if (phones.size > 1) {
                        IconButton(onClick = { phones = phones.toMutableList().also { it.removeAt(i) } }) {
                            Icon(Icons.Filled.RemoveCircle, "Remove phone")
                        }
                    }
                }
                // v1.6: a custom type needs its label typed out, or the
                // label is lost and the number shows as "Other" after save.
                if (p.type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) {
                    OutlinedTextField(
                        value = p.customLabel ?: "", onValueChange = { value ->
                            phones = phones.toMutableList().also { it[i] = p.copy(customLabel = value, typeLabel = value.ifBlank { "Custom" }) }
                        },
                        label = { Text("Custom label (e.g. Assistant)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            OutlinedButton(onClick = {
                phones = phones.toMutableList().also {
                    it.add(PhoneEntry("", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, "Mobile"))
                }
            }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("Add phone")
            }
            Text("Email", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            emails.forEachIndexed { i, e ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = e.address, onValueChange = { value ->
                            emails = emails.toMutableList().also { it[i] = e.copy(address = value) }
                        },
                        label = { Text("Email ${i + 1}") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TypePicker(
                        label = if (e.type == ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM) {
                            e.customLabel?.takeIf { it.isNotBlank() } ?: "Custom"
                        } else e.typeLabel,
                        options = EMAIL_TYPES,
                        onPick = { type, label ->
                            emails = emails.toMutableList().also {
                                it[i] = if (type == ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM) {
                                    e.copy(type = type, typeLabel = "Custom", customLabel = e.customLabel ?: "")
                                } else {
                                    e.copy(type = type, typeLabel = label, customLabel = null)
                                }
                            }
                        }
                    )
                    if (emails.size > 1) {
                        IconButton(onClick = { emails = emails.toMutableList().also { it.removeAt(i) } }) {
                            Icon(Icons.Filled.RemoveCircle, "Remove email")
                        }
                    }
                }
                if (e.type == ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM) {
                    OutlinedTextField(
                        value = e.customLabel ?: "", onValueChange = { value ->
                            emails = emails.toMutableList().also { it[i] = e.copy(customLabel = value, typeLabel = value.ifBlank { "Custom" }) }
                        },
                        label = { Text("Custom label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            OutlinedButton(onClick = {
                emails = emails.toMutableList().also {
                    it.add(EmailEntry("", ContactsContract.CommonDataKinds.Email.TYPE_OTHER, "Other"))
                }
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
