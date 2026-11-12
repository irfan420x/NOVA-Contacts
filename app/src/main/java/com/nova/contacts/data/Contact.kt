package com.nova.contacts.data

/** A phone number with its provider type (Mobile/Home/Work/…) — the type is
 *  part of the data and must survive an edit (v1.5: the old editor rewrote
 *  every number to Mobile and every email to Other on every save). */
data class PhoneEntry(
    val number: String,
    val type: Int = android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
    val typeLabel: String = "Mobile"
)

data class EmailEntry(
    val address: String,
    val type: Int = android.provider.ContactsContract.CommonDataKinds.Email.TYPE_OTHER,
    val typeLabel: String = "Other"
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
    /** Job title — kept SEPARATE from the company name. v1.5: the old code
     *  merged them into "Acme Corp — Manager" on load and wrote that back
     *  as the company, permanently corrupting the title on every edit. */
    val jobTitle: String? = null,
    val note: String? = null
)

/** What the editor collects; the repo writes it to the provider. */
data class ContactDraft(
    val displayName: String,
    val phones: List<PhoneEntry>,
    val emails: List<EmailEntry>,
    val organization: String,
    val jobTitle: String,
    val note: String
)
