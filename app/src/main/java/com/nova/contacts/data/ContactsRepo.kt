package com.nova.contacts.data

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.DatabaseUtils
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactsRepo {

    fun hasContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasCallLogPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    // ---------- v1.4: Recent calls for one contact ----------

    data class CallHistoryItem(
        val type: Int,
        val date: Long,
        val durationSeconds: Long,
        val number: String
    )

    /**
     * Recent calls with any of the given numbers (newest first, max 10).
     * v1.7: the old version queried the WHOLE call log with no selection
     * and walked it until 10 matches were found — on a phone with years
     * of calls and a contact with no recent calls, that scanned the
     * entire table. The provider now pre-filters with NUMBER LIKE
     * '%suffix', and the match is then checked exactly (normalised
     * equality), so a number that merely ends in the same digits can no
     * longer false-match.
     */
    fun loadCallHistory(context: Context, numbers: List<String>): List<CallHistoryItem> {
        if (!hasCallLogPermission(context) || numbers.isEmpty()) return emptyList()
        val suffixes = numbers.map { ContactUtils.normalizeNumber(it) }
            .filter { it.length >= 3 }.toSet()
        if (suffixes.isEmpty()) return emptyList()
        val out = ArrayList<CallHistoryItem>()
        try {
            val selection = suffixes.joinToString(" OR ") {
                "${android.provider.CallLog.Calls.NUMBER} LIKE ?"
            }
            val args = suffixes.map { "%$it" }.toTypedArray()
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(
                    android.provider.CallLog.Calls.TYPE,
                    android.provider.CallLog.Calls.DATE,
                    android.provider.CallLog.Calls.DURATION,
                    android.provider.CallLog.Calls.NUMBER
                ),
                selection, args,
                "${android.provider.CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val typeI = c.getColumnIndexOrThrow(android.provider.CallLog.Calls.TYPE)
                val dateI = c.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE)
                val durI = c.getColumnIndexOrThrow(android.provider.CallLog.Calls.DURATION)
                val numI = c.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER)
                while (c.moveToNext() && out.size < 10) {
                    val raw = c.getString(numI) ?: continue
                    // Exact normalised equality — the LIKE filter is only
                    // a coarse pre-filter and can false-match suffixes.
                    if (ContactUtils.normalizeNumber(raw) !in suffixes) continue
                    out += CallHistoryItem(
                        type = c.getInt(typeI),
                        date = c.getLong(dateI),
                        durationSeconds = c.getLong(durI),
                        number = raw
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Contacts", "Failed to load call history", e)
            return emptyList()
        }
        return out
    }

    // ---------- Loading ----------

    /** All contacts, each with phones, emails, organisation and note. */
    fun loadAll(context: Context): List<FullContact> {
        if (!hasContactsPermission(context)) return emptyList()
        val headers = LinkedHashMap<Long, FullContact>()
        try {
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.PHOTO_URI,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.TIMES_CONTACTED
            )
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, projection, null, null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val lookupI = c.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                val nameI = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                val photoI = c.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI)
                val starI = c.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)
                val timesI = c.getColumnIndexOrThrow(ContactsContract.Contacts.TIMES_CONTACTED)
                while (c.moveToNext()) {
                    val id = c.getLong(idI)
                    headers[id] = FullContact(
                        contactId = id,
                        lookupKey = c.getString(lookupI) ?: "",
                        displayName = c.getString(nameI) ?: "",
                        photoUri = c.getString(photoI),
                        starred = c.getInt(starI) == 1,
                        timesContacted = c.getInt(timesI)
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Contacts", "Failed to load contact list", e)
            return emptyList()
        }
        if (headers.isEmpty()) return emptyList()
        // v1.7: the Data query is restricted to the contacts we actually
        // loaded (chunked IN lists — the old query fetched every Data row
        // for every contact on the phone and filtered in memory).
        val managedMimes = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
        )
        try {
            val projection = arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4
            )
            val phones = HashMap<Long, MutableList<PhoneEntry>>()
            val emails = HashMap<Long, MutableList<EmailEntry>>()
            val orgs = HashMap<Long, String>()
            val titles = HashMap<Long, String>()
            val notes = HashMap<Long, String>()
            for (chunk in headers.keys.chunked(400)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val selection =
                    "${ContactsContract.Data.MIMETYPE} IN (?,?,?,?) AND ${ContactsContract.Data.CONTACT_ID} IN ($placeholders)"
                val args = managedMimes + chunk.map { it.toString() }.toTypedArray()
                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI, projection, selection, args,
                    "${ContactsContract.Data.CONTACT_ID} ASC"
                )?.use { c ->
                    val idI = c.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                    val mimeI = c.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                    val d1I = c.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
                    val d2I = c.getColumnIndexOrThrow(ContactsContract.Data.DATA2)
                    val d3I = c.getColumnIndexOrThrow(ContactsContract.Data.DATA3)
                    val d4I = c.getColumnIndexOrThrow(ContactsContract.Data.DATA4)
                    while (c.moveToNext()) {
                        val id = c.getLong(idI)
                        if (!headers.containsKey(id)) continue
                        val value = c.getString(d1I) ?: continue
                        when (c.getString(mimeI)) {
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                                val type = c.getInt(d2I)
                                val label = c.getString(d3I)
                                phones.getOrPut(id) { mutableListOf() } +=
                                    PhoneEntry(value, type, phoneTypeLabel(type, label), label)
                            }
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                                val type = c.getInt(d2I)
                                val label = c.getString(d3I)
                                emails.getOrPut(id) { mutableListOf() } +=
                                    EmailEntry(value, type, emailTypeLabel(type, label), label)
                            }
                            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                                // v1.5: company and title are separate fields —
                                // merging them here corrupted the title on edit.
                                orgs[id] = value
                                c.getString(d4I)?.takeIf { it.isNotBlank() }?.let { titles[id] = it }
                            }
                            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                                notes[id] = value
                            }
                        }
                    }
                }
            }
            return headers.values.map { h ->
                h.copy(
                    phones = phones[h.contactId] ?: emptyList(),
                    emails = emails[h.contactId] ?: emptyList(),
                    organization = orgs[h.contactId],
                    jobTitle = titles[h.contactId],
                    note = notes[h.contactId]
                )
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Contacts", "Failed to load contact details", e)
            return headers.values.toList()
        }
    }

    fun loadFavorites(context: Context): List<FullContact> =
        loadAll(context).filter { it.starred }

    fun findDuplicates(context: Context): List<ContactUtils.DuplicateGroup> =
        ContactUtils.groupDuplicates(loadAll(context))

    // ---------- Writing ----------

    /** Phones deduped by normalised number, emails by lowercase address —
     *  v1.7: the same number in two forms (017… / +880…) or the same
     *  email twice used to be saved as two rows. */
    private fun dedupedPhones(phones: List<PhoneEntry>): List<PhoneEntry> =
        phones.filter { it.number.trim().isNotEmpty() }
            .distinctBy { ContactUtils.normalizeNumber(it.number) }

    private fun dedupedEmails(emails: List<EmailEntry>): List<EmailEntry> =
        emails.filter { it.address.trim().isNotEmpty() }
            .distinctBy { it.address.trim().lowercase() }

    fun createContact(context: Context, draft: ContactDraft): Boolean {
        if (!hasWritePermission(context)) return false
        val name = draft.displayName.trim()
        if (name.isEmpty()) return false
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
                .build()
            ops += nameOp(0, name)
            for (p in dedupedPhones(draft.phones)) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, p.number.trim())
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, p.type)
                    // v1.6: write the custom label (DATA3) — without it a
                    // TYPE_CUSTOM number reloads as "Other".
                    .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, p.customLabel)
                    .build()
            }
            for (e in dedupedEmails(draft.emails)) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, e.address.trim())
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, e.type)
                    .withValue(ContactsContract.CommonDataKinds.Email.LABEL, e.customLabel)
                    .build()
            }
            if (draft.organization.isNotBlank() || draft.jobTitle.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, draft.organization.trim())
                    .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, draft.jobTitle.trim())
                    .build()
            }
            if (draft.note.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, draft.note.trim())
                    .build()
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Contacts", "Failed to create contact '$name'", e)
            false
        }
    }

    /** Replaces the contact's fields with the draft (name + all detail rows). */
    fun updateContact(context: Context, contactId: Long, draft: ContactDraft): Boolean {
        if (!hasWritePermission(context)) return false
        val name = draft.displayName.trim()
        if (name.isEmpty()) return false
        return try {
            val rawId = rawContactId(context, contactId) ?: return false
            val ops = ArrayList<ContentProviderOperation>()
            // v1.3: only replace the detail rows this editor manages (phones,
            // emails, organisation, note). The old code deleted EVERY row
            // except the name — silently destroying the contact's photo,
            // group memberships, websites and other data on every edit.
            val managed = listOf(
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
            )
            val placeholders = managed.joinToString(",") { "?" }
            ops += ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN ($placeholders)",
                    arrayOf(contactId.toString()) + managed.toTypedArray()
                ).build()
            // v1.7: the name change is part of the same batch now. The old
            // code updated the name row in a separate call BEFORE the
            // batch, so a failed batch left the new name with the old
            // details (or a half-saved contact).
            ops += ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(
                        contactId.toString(),
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                ).build()
            ops += nameOpForRaw(rawId, name)
            for (p in dedupedPhones(draft.phones)) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, p.number.trim())
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, p.type)
                    .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, p.customLabel)
                    .build()
            }
            for (e in dedupedEmails(draft.emails)) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, e.address.trim())
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, e.type)
                    .withValue(ContactsContract.CommonDataKinds.Email.LABEL, e.customLabel)
                    .build()
            }
            if (draft.organization.isNotBlank() || draft.jobTitle.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, draft.organization.trim())
                    .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, draft.jobTitle.trim())
                    .build()
            }
            if (draft.note.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, draft.note.trim())
                    .build()
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Contacts", "Failed to update contact $contactId", e)
            false
        }
    }

    fun deleteContact(context: Context, contactId: Long): Boolean {
        if (!hasWritePermission(context)) return false
        return try {
            val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            ErrorLog.log(context, "Contacts", "Failed to delete contact $contactId", e)
            false
        }
    }

    fun setStarred(context: Context, contactId: Long, starred: Boolean): Boolean {
        if (!hasWritePermission(context)) return false
        return try {
            val values = ContentValues().apply {
                put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
            }
            val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            ErrorLog.log(context, "Contacts", "Failed to star contact $contactId", e)
            false
        }
    }

    /**
     * Merges the group into one contact, keeping the most complete entry.
     * v1.7: the members are re-read fresh (the caller's snapshot may be
     * stale after earlier merges), the keep is preferred to be an
     * account-backed (synced) contact, blank organisation/title/note no
     * longer shadows a loser's real value, numbers dedupe by normalised
     * form, and the loser's unmanaged rows (photo, birthday, groups,
     * websites, addresses) are copied to the keep instead of being
     * destroyed with the loser. Returns false unless the merge fully
     * succeeded — the old version returned true even when a delete
     * failed, so "Merge all" reported success for a half-done merge.
     */
    fun mergeGroup(context: Context, group: List<FullContact>): Boolean {
        if (group.size < 2 || !hasWritePermission(context)) return false
        return try {
            // Re-read fresh: a member may already have been merged or
            // deleted by an earlier merge in the same "Merge all" run.
            val freshById = loadAll(context).associateBy { it.contactId }
            val members = group.mapNotNull { freshById[it.contactId] }
            if (members.size < 2) return false
            val synced = accountBackedIds(context, members.map { it.contactId })
            val sorted = members.sortedWith(
                compareByDescending<FullContact> { it.contactId in synced }
                    .thenByDescending { it.phones.size + it.emails.size }
                    .thenBy { it.contactId }
            )
            val keep = sorted.first()
            // v1.5: keep the full entries (number + type) — the old merge
            // flattened every type to Mobile/Other, same as the editor bug.
            val phones = (keep.phones + sorted.drop(1).flatMap { it.phones })
                .distinctBy { ContactUtils.normalizeNumber(it.number) }
                .filter { it.number.trim().isNotEmpty() }
            val emails = (keep.emails + sorted.drop(1).flatMap { it.emails })
                .distinctBy { it.address.trim().lowercase() }
                .filter { it.address.trim().isNotEmpty() }
            fun firstNonBlank(pick: (FullContact) -> String?): String =
                sorted.firstNotNullOfOrNull { pick(it)?.takeIf { v -> v.isNotBlank() } } ?: ""
            val draft = ContactDraft(
                displayName = keep.displayName,
                phones = phones,
                emails = emails,
                organization = firstNonBlank { it.organization },
                jobTitle = firstNonBlank { it.jobTitle },
                note = firstNonBlank { it.note }
            )
            if (!updateContact(context, keep.contactId, draft)) return false
            // Copy the losers' unmanaged rows (photo, birthday, groups,
            // websites, addresses…) onto the keep before deleting them.
            val keepRaw = rawContactId(context, keep.contactId) ?: return false
            for (other in sorted.drop(1)) {
                if (!copyUnmanagedRows(context, other.contactId, keepRaw)) {
                    ErrorLog.log(context, "Contacts", "Merge aborted: could not copy data from ${other.contactId} to ${keep.contactId}")
                    return false
                }
            }
            var allDeleted = true
            for (other in sorted.drop(1)) {
                if (!deleteContact(context, other.contactId)) {
                    ErrorLog.log(context, "Contacts", "Merged into ${keep.contactId} but could not delete ${other.contactId}")
                    allDeleted = false
                }
            }
            allDeleted
        } catch (e: Exception) {
            ErrorLog.log(context, "Contacts", "Failed to merge group", e)
            false
        }
    }

    /** Contact IDs in the set that have at least one account-backed raw
     *  contact (a Google/synced account, as opposed to local-only). */
    private fun accountBackedIds(context: Context, contactIds: List<Long>): Set<Long> {
        val out = HashSet<Long>()
        if (contactIds.isEmpty()) return out
        try {
            for (chunk in contactIds.chunked(400)) {
                val placeholders = chunk.joinToString(",") { "?" }
                context.contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.RawContacts.CONTACT_ID,
                        ContactsContract.RawContacts.ACCOUNT_TYPE
                    ),
                    "${ContactsContract.RawContacts.CONTACT_ID} IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(), null
                )?.use { c ->
                    val idI = c.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
                    val typeI = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    while (c.moveToNext()) {
                        if (c.getString(typeI) != null) out += c.getLong(idI)
                    }
                }
            }
        } catch (_: Exception) { }
        return out
    }

    /**
     * Copies a contact's unmanaged Data rows (everything the editor does
     * NOT manage — photo, birthday, group memberships, websites,
     * addresses, nicknames…) onto the keep raw contact, so merging no
     * longer destroys them. StructuredName and the managed mimetypes are
     * excluded (the merge handles those). Returns false on any failure.
     */
    private fun copyUnmanagedRows(context: Context, fromContactId: Long, toRawId: Long): Boolean {
        return try {
            val managed = listOf(
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                // Identity rows belong to the source account, not the person.
                ContactsContract.CommonDataKinds.Identity.CONTENT_ITEM_TYPE
            )
            val placeholders = managed.joinToString(",") { "?" }
            val projection = arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1, ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3, ContactsContract.Data.DATA4,
                ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
                ContactsContract.Data.DATA7, ContactsContract.Data.DATA8,
                ContactsContract.Data.DATA9, ContactsContract.Data.DATA10,
                ContactsContract.Data.DATA11, ContactsContract.Data.DATA12,
                ContactsContract.Data.DATA13, ContactsContract.Data.DATA14,
                ContactsContract.Data.DATA15
            )
            val ops = ArrayList<ContentProviderOperation>()
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI, projection,
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} NOT IN ($placeholders)",
                arrayOf(fromContactId.toString()) + managed.toTypedArray(), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val values = ContentValues()
                    DatabaseUtils.cursorRowToContentValues(c, values)
                    values.put(ContactsContract.Data.RAW_CONTACT_ID, toRawId)
                    ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValues(values).build()
                }
            }
            if (ops.isNotEmpty()) {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            }
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Contacts", "Failed to copy data from $fromContactId", e)
            false
        }
    }

    // ---------- vCard backup ----------

    /** A contact as a vCard card, with its real phone/email types — used
     *  by both the backup export and the Detail share button. */
    fun toVCardContact(c: FullContact): VCard.VCardContact =
        VCard.VCardContact(
            name = c.displayName,
            phones = c.phones.map { VCard.VCardPhone(it.number, phoneTypeToken(it)) },
            emails = c.emails.map { VCard.VCardEmail(it.address, emailTypeToken(it)) },
            org = c.organization,
            jobTitle = c.jobTitle,
            note = c.note
        )

    /** Returns the .vcf text plus how many contacts were exported, so the
     *  status can say the real number instead of the on-screen list size. */
    fun exportVCard(context: Context): Pair<String, Int> {
        // v1.3: skip nameless rows (they produce broken vCard entries).
        val contacts = loadAll(context).filter { it.displayName.isNotBlank() }
            .map { toVCardContact(it) }
        return VCard.generate(contacts) to contacts.size
    }

    /** Imports a .vcf file's text; returns how many contacts were created. */
    fun importVCard(context: Context, text: String): Int {
        var created = 0
        for (vc in VCard.parse(text)) {
            val draft = ContactDraft(
                displayName = vc.name,
                phones = vc.phones.map { phoneFromToken(it.number, it.typeToken) },
                emails = vc.emails.map { emailFromToken(it.address, it.typeToken) },
                organization = vc.org ?: "",
                jobTitle = vc.jobTitle ?: "",
                note = vc.note ?: ""
            )
            if (createContact(context, draft)) created++
        }
        return created
    }

    // ---------- vCard TYPE mapping ----------

    private fun phoneTypeToken(p: PhoneEntry): String = when (p.type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "CELL"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "HOME"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "WORK"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "FAX"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "FAX"
        ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "PAGER"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "MAIN"
        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM ->
            p.customLabel?.takeIf { it.isNotBlank() } ?: "OTHER"
        else -> "OTHER"
    }

    private fun emailTypeToken(e: EmailEntry): String = when (e.type) {
        ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "HOME"
        ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "WORK"
        ContactsContract.CommonDataKinds.Email.TYPE_MOBILE -> "CELL"
        ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM ->
            e.customLabel?.takeIf { it.isNotBlank() } ?: "OTHER"
        else -> "OTHER"
    }

    /** Picks the recognised TYPE out of a possibly comma-joined token
     *  ("VOICE,CELL" → "CELL"); an unknown token is a custom label. */
    private fun pickToken(raw: String?, known: Set<String>): String? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        return parts.firstOrNull { it in known } ?: parts.firstOrNull()
    }

    private fun phoneFromToken(number: String, rawToken: String?): PhoneEntry {
        val token = pickToken(rawToken, setOf("CELL", "HOME", "WORK", "FAX", "PAGER", "MAIN", "OTHER"))
        return when (token) {
            "CELL" -> PhoneEntry(number, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, "Mobile")
            "HOME" -> PhoneEntry(number, ContactsContract.CommonDataKinds.Phone.TYPE_HOME, "Home")
            "WORK" -> PhoneEntry(number, ContactsContract.CommonDataKinds.Phone.TYPE_WORK, "Work")
            "FAX" -> PhoneEntry(number, ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK, "Fax (work)")
            "PAGER" -> PhoneEntry(number, ContactsContract.CommonDataKinds.Phone.TYPE_PAGER, "Pager")
            "MAIN" -> PhoneEntry(number, ContactsContract.CommonDataKinds.Phone.TYPE_MAIN, "Main")
            "OTHER" -> PhoneEntry(number, ContactsContract.CommonDataKinds.Phone.TYPE_OTHER, "Other")
            null -> PhoneEntry(number, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, "Mobile")
            else -> PhoneEntry(number, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM, token, token)
        }
    }

    private fun emailFromToken(address: String, rawToken: String?): EmailEntry {
        val token = pickToken(rawToken, setOf("CELL", "HOME", "WORK", "OTHER"))
        return when (token) {
            "CELL" -> EmailEntry(address, ContactsContract.CommonDataKinds.Email.TYPE_MOBILE, "Mobile")
            "HOME" -> EmailEntry(address, ContactsContract.CommonDataKinds.Email.TYPE_HOME, "Home")
            "WORK" -> EmailEntry(address, ContactsContract.CommonDataKinds.Email.TYPE_WORK, "Work")
            "OTHER" -> EmailEntry(address, ContactsContract.CommonDataKinds.Email.TYPE_OTHER, "Other")
            null -> EmailEntry(address, ContactsContract.CommonDataKinds.Email.TYPE_OTHER, "Other")
            else -> EmailEntry(address, ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM, token, token)
        }
    }

    // ---------- internals ----------

    private fun nameOp(backRef: Int, name: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, givenName(name))
            .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, familyName(name))
            .build()

    private fun nameOpForRaw(rawId: Long, name: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, givenName(name))
            .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, familyName(name))
            .build()

    /** First word is the given name; the rest is the family name. */
    private fun givenName(name: String): String = name.trim().split(Regex("\\s+")).firstOrNull() ?: name

    private fun familyName(name: String): String? {
        val parts = name.trim().split(Regex("\\s+"))
        return if (parts.size > 1) parts.drop(1).joinToString(" ") else null
    }

    /**
     * Which raw contact the editor writes into. v1.7: the old code took
     * whichever raw the provider returned first (no ORDER BY) — for an
     * aggregated contact that silently moved the rows into an arbitrary
     * raw. Prefer the raw that already holds the most managed rows,
     * then an account-backed (synced) raw, then the lowest id, so the
     * choice is deterministic and the data stays where it lived.
     */
    private fun rawContactId(context: Context, contactId: Long): Long? {
        return try {
            val raws = ArrayList<Pair<Long, String?>>()
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts._ID,
                    ContactsContract.RawContacts.ACCOUNT_TYPE
                ),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()), null
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
                val typeI = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
                while (c.moveToNext()) raws += c.getLong(idI) to c.getString(typeI)
            }
            if (raws.isEmpty()) return null
            if (raws.size == 1) return raws[0].first
            val counts = HashMap<Long, Int>()
            val managed = listOf(
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
            )
            val placeholders = managed.joinToString(",") { "?" }
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN ($placeholders)",
                arrayOf(contactId.toString()) + managed.toTypedArray(), null
            )?.use { c ->
                val rawI = c.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
                while (c.moveToNext()) {
                    val rawId = c.getLong(rawI)
                    counts[rawId] = (counts[rawId] ?: 0) + 1
                }
            }
            raws.sortedWith(
                compareByDescending<Pair<Long, String?>> { counts[it.first] ?: 0 }
                    .thenByDescending { it.second != null }
                    .thenBy { it.first }
            ).first().first
        } catch (_: Exception) { null }
    }

    private fun phoneTypeLabel(type: Int, customLabel: String?): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "Fax (work)"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "Fax (home)"
        ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "Pager"
        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> customLabel?.takeIf { it.isNotBlank() } ?: "Other"
        else -> "Other"
    }

    private fun emailTypeLabel(type: Int, customLabel: String?): String = when (type) {
        ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "Home"
        ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "Work"
        ContactsContract.CommonDataKinds.Email.TYPE_MOBILE -> "Mobile"
        ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM -> customLabel?.takeIf { it.isNotBlank() } ?: "Other"
        else -> "Other"
    }
}
