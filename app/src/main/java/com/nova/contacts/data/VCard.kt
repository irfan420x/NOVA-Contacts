package com.nova.contacts.data

/**
 * vCard 3.0 import/export (pure Kotlin, no Android imports) — the backup
 * format every contacts app understands. Kept dependency-free so the
 * parser can be unit-tested on the JVM without a device.
 *
 * Supported properties: FN (name), TEL, EMAIL, ORG, NOTE. Values are
 * escaped per the spec (backslash, comma, semicolon, newline).
 */
object VCard {

    data class VCardContact(
        val name: String,
        val phones: List<String> = emptyList(),
        val emails: List<String> = emptyList(),
        val org: String? = null,
        val jobTitle: String? = null,
        val note: String? = null
    )

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    ',' -> out.append(',')
                    ';' -> out.append(';')
                    '\\' -> out.append('\\')
                    else -> { out.append(c); out.append(value[i + 1]) }
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    fun generate(contacts: List<VCardContact>): String = buildString {
        for (c in contacts) {
            if (c.name.isBlank() && c.phones.isEmpty()) continue
            append("BEGIN:VCARD\r\n")
            append("VERSION:3.0\r\n")
            append("FN:${escape(c.name)}\r\n")
            for (p in c.phones) append("TEL;TYPE=CELL:${escape(p)}\r\n")
            for (e in c.emails) append("EMAIL:${escape(e)}\r\n")
            c.org?.takeIf { it.isNotBlank() }?.let { append("ORG:${escape(it)}\r\n") }
            // v1.6: job title survives backup/restore (was silently dropped).
            c.jobTitle?.takeIf { it.isNotBlank() }?.let { append("TITLE:${escape(it)}\r\n") }
            c.note?.takeIf { it.isNotBlank() }?.let { append("NOTE:${escape(it)}\r\n") }
            append("END:VCARD\r\n")
        }
    }

    /**
     * Parses vCard text. Lines starting with a space are continuations of
     * the previous line (line folding). Unknown properties are ignored,
     * so cards from other apps import cleanly.
     */
    fun parse(text: String): List<VCardContact> {
        // v1.6: strip a leading UTF-8 BOM — files from Outlook/Windows/
        // Samsung commonly carry one, and it made the first line
        // "\uFEFFBEGIN:VCARD" so the whole file parsed to 0 contacts.
        val clean = text.removePrefix("\uFEFF")
        // Unfold: a folded line starts with a space or tab.
        val logical = mutableListOf<String>()
        for (raw in clean.split(Regex("\r\n|\n|\r"))) {
            if (raw.isEmpty()) continue
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && logical.isNotEmpty()) {
                logical[logical.lastIndex] = logical.last() + raw.drop(1)
            } else {
                logical += raw
            }
        }
        val out = mutableListOf<VCardContact>()
        var name = ""
        var phones = mutableListOf<String>()
        var emails = mutableListOf<String>()
        var org: String? = null
        var jobTitle: String? = null
        var note: String? = null
        var inCard = false
        fun flush() {
            if (inCard && (name.isNotBlank() || phones.isNotEmpty())) {
                out += VCardContact(name, phones.toList(), emails.toList(), org, jobTitle, note)
            }
            name = ""; phones = mutableListOf(); emails = mutableListOf(); org = null; jobTitle = null; note = null
        }
        for (line in logical) {
            val upper = line.uppercase()
            when {
                upper == "BEGIN:VCARD" -> { flush(); inCard = true }
                upper == "END:VCARD" -> { flush(); inCard = false }
                !inCard -> Unit
                else -> {
                    val colon = line.indexOf(':')
                    if (colon < 0) continue
                    val key = line.substring(0, colon).uppercase().substringBefore(';')
                    val value = unescape(line.substring(colon + 1))
                    when (key) {
                        "FN" -> name = value
                        "TEL" -> if (value.isNotBlank()) phones += value
                        "EMAIL" -> if (value.isNotBlank()) emails += value
                        "ORG" -> org = value
                        "TITLE" -> jobTitle = value
                        "NOTE" -> note = value
                    }
                }
            }
        }
        flush()
        return out
    }
}
