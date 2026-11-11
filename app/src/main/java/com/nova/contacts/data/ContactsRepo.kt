package com.nova.contacts.data

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactsRepo {

    fun hasContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

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
        // One Data query for every detail row, grouped by contact in memory.
        try {
            val projection = arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4
            )
            val selection = "${ContactsContract.Data.MIMETYPE} IN (?,?,?,?)"
            val args = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
            )
            val phones = HashMap<Long, MutableList<PhoneEntry>>()
            val emails = HashMap<Long, MutableList<EmailEntry>>()
            val orgs = HashMap<Long, String>()
            val notes = HashMap<Long, String>()
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
                                PhoneEntry(value, phoneTypeLabel(type, label))
                        }
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                            val type = c.getInt(d2I)
                            val label = c.getString(d3I)
                            emails.getOrPut(id) { mutableListOf() } +=
                                EmailEntry(value, emailTypeLabel(type, label))
                        }
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                            val title = c.getString(d4I)
                            orgs[id] = if (!title.isNullOrBlank()) "$value — $title" else value
                        }
                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                            notes[id] = value
                        }
                    }
                }
            }
            return headers.values.map { h ->
                h.copy(
                    phones = phones[h.contactId] ?: emptyList(),
                    emails = emails[h.contactId] ?: emptyList(),
                    organization = orgs[h.contactId],
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

    fun findDuplicates(context: Context): List<List<FullContact>> =
        ContactUtils.groupDuplicates(loadAll(context))

    // ---------- Writing ----------

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
            for (p in draft.phones.map { it.trim() }.filter { it.isNotEmpty() }) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, p)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            }
            for (e in draft.emails.map { it.trim() }.filter { it.isNotEmpty() }) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, e)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                    .build()
            }
            if (draft.organization.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, draft.organization.trim())
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
            // Update the name row (insert one if the contact somehow has none).
            val nameUpdated = updateNameRow(context, contactId, name)
            if (!nameUpdated) ops += nameOpForRaw(rawId, name)
            for (p in draft.phones.map { it.trim() }.filter { it.isNotEmpty() }) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, p)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            }
            for (e in draft.emails.map { it.trim() }.filter { it.isNotEmpty() }) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, e)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                    .build()
            }
            if (draft.organization.isNotBlank()) {
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, draft.organization.trim())
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

    /** Merges the group into its first contact (most fields win), deletes the rest. */
    fun mergeGroup(context: Context, group: List<FullContact>): Boolean {
        if (group.size < 2 || !hasWritePermission(context)) return false
        val sorted = group.sortedByDescending { it.phones.size + it.emails.size }
        val keep = sorted.first()
        val phones = (keep.phones.map { it.number } + sorted.drop(1).flatMap { it.phones.map { p -> p.number } })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val emails = (keep.emails.map { it.address } + sorted.drop(1).flatMap { it.emails.map { e -> e.address } })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val draft = ContactDraft(
            displayName = keep.displayName,
            phones = phones,
            emails = emails,
            organization = keep.organization ?: sorted.drop(1).firstNotNullOfOrNull { it.organization } ?: "",
            note = keep.note ?: sorted.drop(1).firstNotNullOfOrNull { it.note } ?: ""
        )
        if (!updateContact(context, keep.contactId, draft)) return false
        for (other in sorted.drop(1)) {
            if (!deleteContact(context, other.contactId)) {
                ErrorLog.log(context, "Contacts", "Merged into ${keep.contactId} but could not delete ${other.contactId}")
            }
        }
        return true
    }

    // ---------- vCard backup ----------

    fun exportVCard(context: Context): String {
        // v1.3: skip nameless rows (they produce broken vCard entries).
        val contacts = loadAll(context).filter { it.displayName.isNotBlank() }.map { c ->
            VCard.VCardContact(
                name = c.displayName,
                phones = c.phones.map { it.number },
                emails = c.emails.map { it.address },
                org = c.organization,
                note = c.note
            )
        }
        return VCard.generate(contacts)
    }

    /** Imports a .vcf file's text; returns how many contacts were created. */
    fun importVCard(context: Context, text: String): Int {
        var created = 0
        for (vc in VCard.parse(text)) {
            if (createContact(context, ContactDraft(vc.name, vc.phones, vc.emails, vc.org ?: "", vc.note ?: ""))) {
                created++
            }
        }
        return created
    }

    // ---------- internals ----------

    private fun nameOp(backRef: Int, name: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build()

    private fun nameOpForRaw(rawId: Long, name: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build()

    private fun rawContactId(context: Context, contactId: Long): Long? {
        return try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()), null
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        } catch (_: Exception) { null }
    }

    private fun updateNameRow(context: Context, contactId: Long, name: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            }
            context.contentResolver.update(
                ContactsContract.Data.CONTENT_URI, values,
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(
                    contactId.toString(),
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
            ) > 0
        } catch (_: Exception) { false }
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
