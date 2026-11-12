package com.nova.contacts.data

/**
 * vCard 3.0 import/export (pure Kotlin, no Android imports) — the backup
 * format every contacts app understands. Kept dependency-free so the
 * parser can be unit-tested on the JVM without a device.
 *
 * Supported properties: FN (name), TEL, EMAIL, ORG, TITLE, NOTE. Values
 * are escaped per the spec (backslash, comma, semicolon, newline).
 * Phone/email TYPE parameters are preserved (CELL/HOME/WORK/… and custom
 * labels) so a backup/restore cycle no longer flattens every number to
 * Mobile and every email to Other.
 */
object VCard {

    /** A phone number with its vCard TYPE token ("CELL", "HOME", …) or a
     *  custom label for TYPE_CUSTOM ("Assistant"). */
    data class VCardPhone(val number: String, val typeToken: String? = null)
    data class VCardEmail(val address: String, val typeToken: String? = null)

    data class VCardContact(
        val name: String,
        val phones: List<VCardPhone> = emptyList(),
        val emails: List<VCardEmail> = emptyList(),
        val org: String? = null,
        val jobTitle: String? = null,
        val note: String? = null
    )

    private fun escape(value: String): String =
        // v1.7: a bare CR inside a value broke the card — the line
        // splitter treats \r as a line break. Normalise CR(LF) to \n
        // before escaping so the value round-trips.
        value.replace("\r\n", "\n").replace("\r", "\n")
            .replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

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

    /** A TYPE parameter value safe to write unquoted, else quoted. */
    private fun typeParam(token: String?): String? {
        if (token.isNullOrBlank()) return null
        return if (token.all { it.isLetterOrDigit() || it == '-' }) token
        else "\"" + token.replace("\"", "") + "\""
    }

    fun generate(contacts: List<VCardContact>): String = buildString {
        for (c in contacts) {
            if (c.name.isBlank() && c.phones.isEmpty()) continue
            append("BEGIN:VCARD\r\n")
            append("VERSION:3.0\r\n")
            append("FN:${escape(c.name)}\r\n")
            for (p in c.phones) {
                val t = typeParam(p.typeToken)
                if (t != null) append("TEL;TYPE=$t:${escape(p.number)}\r\n")
                else append("TEL:${escape(p.number)}\r\n")
            }
            for (e in c.emails) {
                val t = typeParam(e.typeToken)
                if (t != null) append("EMAIL;TYPE=$t:${escape(e.address)}\r\n")
                else append("EMAIL:${escape(e.address)}\r\n")
            }
            c.org?.takeIf { it.isNotBlank() }?.let { append("ORG:${escape(it)}\r\n") }
            // v1.6: job title survives backup/restore (was silently dropped).
            c.jobTitle?.takeIf { it.isNotBlank() }?.let { append("TITLE:${escape(it)}\r\n") }
            c.note?.takeIf { it.isNotBlank() }?.let { append("NOTE:${escape(it)}\r\n") }
            append("END:VCARD\r\n")
        }
    }

    /** Splits a property part ("TEL;TYPE=CELL" / "item1.TEL;TYPE=\"My Label\"")
     *  on ';' without splitting inside quotes. */
    private fun splitParams(part: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        for (ch in part) {
            when {
                ch == '"' -> { inQuotes = !inQuotes; cur.append(ch) }
                ch == ';' && !inQuotes -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        out += cur.toString()
        return out
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
        var phones = mutableListOf<VCardPhone>()
        var emails = mutableListOf<VCardEmail>()
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
            // v1.7: tolerate trailing whitespace on BEGIN/END lines.
            val upper = line.trim().uppercase()
            when {
                upper == "BEGIN:VCARD" -> { flush(); inCard = true }
                upper == "END:VCARD" -> { flush(); inCard = false }
                !inCard -> Unit
                else -> {
                    val colon = line.indexOf(':')
                    if (colon < 0) continue
                    val propPart = line.substring(0, colon)
                    // v1.7: strip a group prefix ("item1.FN") and any
                    // parameters — the property name is the part before
                    // the first ';', after the last '.'.
                    val key = propPart.substringBefore(';').substringAfterLast('.').uppercase()
                    val value = unescape(line.substring(colon + 1))
                    // TYPE parameters (first one wins; quoted values unquoted).
                    val typeToken = splitParams(propPart).drop(1)
                        .firstNotNullOfOrNull { param ->
                            val eq = param.indexOf('=')
                            if (eq > 0 && param.substring(0, eq).trim().uppercase() == "TYPE") {
                                param.substring(eq + 1).trim().removeSurrounding("\"").takeIf { it.isNotBlank() }
                            } else null
                        }
                    when (key) {
                        "FN" -> name = value
                        "TEL" -> if (value.isNotBlank()) phones += VCardPhone(value, typeToken)
                        "EMAIL" -> if (value.isNotBlank()) emails += VCardEmail(value, typeToken)
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
