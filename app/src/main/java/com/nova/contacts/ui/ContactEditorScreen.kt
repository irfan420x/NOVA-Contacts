package com.nova.contacts.ui

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
import com.nova.contacts.data.FullContact
import kotlinx.coroutines.launch

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
    var phones by remember(contact) {
        mutableStateOf(contact?.phones?.map { it.number }?.toMutableList() ?: mutableListOf(""))
    }
    var emails by remember(contact) {
        mutableStateOf(contact?.emails?.map { it.address }?.toMutableList() ?: mutableListOf(""))
    }
    var organization by remember(contact) { mutableStateOf(contact?.organization ?: "") }
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
                        value = p, onValueChange = { value -> phones = phones.toMutableList().also { it[i] = value } },
                        label = { Text("Phone ${i + 1}") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (phones.size > 1) {
                        IconButton(onClick = { phones = phones.toMutableList().also { it.removeAt(i) } }) {
                            Icon(Icons.Filled.RemoveCircle, "Remove phone")
                        }
                    }
                }
            }
            OutlinedButton(onClick = { phones = phones.toMutableList().also { it.add("") } }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("Add phone")
            }
            Text("Email", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            emails.forEachIndexed { i, e ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = e, onValueChange = { value -> emails = emails.toMutableList().also { it[i] = value } },
                        label = { Text("Email ${i + 1}") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (emails.size > 1) {
                        IconButton(onClick = { emails = emails.toMutableList().also { it.removeAt(i) } }) {
                            Icon(Icons.Filled.RemoveCircle, "Remove email")
                        }
                    }
                }
            }
            OutlinedButton(onClick = { emails = emails.toMutableList().also { it.add("") } }) {
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
                value = note, onValueChange = { note = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth().height(96.dp)
            )
        }
    }
}
