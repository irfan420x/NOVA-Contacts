package com.nova.contacts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nova.contacts.data.ContactUtils
import com.nova.contacts.data.ContactsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(onBack: () -> Unit, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<ContactUtils.DuplicateGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var merging by remember { mutableStateOf<ContactUtils.DuplicateGroup?>(null) }
    var mergingAll by remember { mutableStateOf(false) }
    var confirmMergeAll by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            groups = withContext(Dispatchers.IO) { ContactsRepo.findDuplicates(context) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicate contacts", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text(
                "Contacts that share a phone number — the same person saved twice, " +
                    "or the same number with a different country code. Merging keeps the " +
                    "most complete entry and deletes the rest.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            // v1.4: merge every duplicate group in one tap.
            if (groups.isNotEmpty()) {
                NebulaButton(
                    onClick = { confirmMergeAll = true },
                    enabled = merging == null && !mergingAll,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Merge, null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (mergingAll) "Merging all…" else "Merge all duplicates (${groups.size} groups)")
                }
                Spacer(Modifier.height(12.dp))
            }
            when {
                !ContactsRepo.hasContactsPermission(context) -> {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        NebulaEmptyIcon(Icons.Filled.CopyAll, null)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Contacts permission is needed to find duplicates.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        NebulaEmptyIcon(Icons.Filled.CheckCircle, null)
                        Spacer(Modifier.height(16.dp))
                        Text("No duplicates found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // v1.7: keyed by the shared number — the old key
                    // (first().contactId) collided when one contact was
                    // first() in two groups.
                    items(groups, key = { it.number }) { group ->
                        val members = group.contacts
                        NebulaCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                members.forEach { c ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        ContactPhoto(c, 40)
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(c.displayName, fontWeight = FontWeight.Bold)
                                            Text(
                                                (c.phones.firstOrNull()?.number ?: "") +
                                                    (c.emails.firstOrNull()?.let { " • ${it.address}" } ?: ""),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                NebulaButton(
                                    onClick = {
                                        if (!ContactsRepo.hasWritePermission(context)) {
                                            onStatus("Write permission is off — allow it to merge contacts")
                                            return@NebulaButton
                                        }
                                        merging = group
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                ContactsRepo.mergeGroup(context, members)
                                            }
                                            merging = null
                                            if (ok) {
                                                onStatus("Merged ${members.size} contacts into one")
                                                load()
                                            } else onStatus("Couldn't merge these contacts")
                                        }
                                    },
                                    // v1.7: per-group buttons used to stay
                                    // enabled during "Merge all", so two
                                    // merges could run on the same contacts.
                                    enabled = merging == null && !mergingAll,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Merge, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (merging == group) "Merging…" else "Merge into one contact")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmMergeAll) {
        AlertDialog(
            onDismissRequest = { confirmMergeAll = false },
            title = { Text("Merge all duplicates?") },
            text = {
                Text(
                    "${groups.size} duplicate groups will each be merged into one " +
                        "contact, keeping the most complete entry. The extra copies are deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmMergeAll = false
                    if (!ContactsRepo.hasWritePermission(context)) {
                        onStatus("Write permission is off — allow it to merge contacts")
                        return@TextButton
                    }
                    mergingAll = true
                    scope.launch {
                        var merged = 0
                        var failed = 0
                        // v1.7: re-resolve the groups after every merge.
                        // The old code iterated one stale snapshot, so a
                        // contact merged in group 1 was merged again from
                        // its pre-merge data in group 2 (losing the
                        // numbers the first merge had just added). Groups
                        // that fail are skipped, not retried forever.
                        val skipped = HashSet<String>()
                        var guard = 0
                        var remaining = withContext(Dispatchers.IO) {
                            ContactsRepo.findDuplicates(context)
                        }.filter { it.number !in skipped }
                        while (remaining.isNotEmpty() && guard < 200) {
                            guard++
                            val group = remaining.first()
                            val ok = withContext(Dispatchers.IO) {
                                ContactsRepo.mergeGroup(context, group.contacts)
                            }
                            if (ok) merged++ else { failed++; skipped += group.number }
                            remaining = withContext(Dispatchers.IO) {
                                ContactsRepo.findDuplicates(context)
                            }.filter { it.number !in skipped }
                        }
                        mergingAll = false
                        onStatus(
                            "Merged $merged group${if (merged != 1) "s" else ""}" +
                                if (failed > 0) " ($failed failed)" else ""
                        )
                        load()
                    }
                }) { Text("Merge all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMergeAll = false }) { Text("Cancel") }
            }
        )
    }
}
