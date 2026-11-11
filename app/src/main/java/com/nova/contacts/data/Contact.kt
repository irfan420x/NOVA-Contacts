package com.nova.contacts.data

data class PhoneEntry(
    val number: String,
    val typeLabel: String
)

data class EmailEntry(
    val address: String,
    val typeLabel: String
)

/** One person, with every field the app manages. */
data class FullContact(
    val contactId: Long,
    val lookupKey: String,
    val displayName: String,
    val photoUri: String?,
    val starred: Boolean,
    val timesContacted: Int,
    val phones: List<PhoneEntry> = emptyList(),
    val emails: List<EmailEntry> = emptyList(),
    val organization: String? = null,
    val note: String? = null
)

/** What the editor collects; the repo writes it to the provider. */
data class ContactDraft(
    val displayName: String,
    val phones: List<String>,
    val emails: List<String>,
    val organization: String,
    val note: String
)
