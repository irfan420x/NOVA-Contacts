package com.nova.contacts.data

/**
 * Pure contact helpers (no Android imports) — number normalisation and
 * duplicate grouping. Kept dependency-free so the logic can be unit-tested
 * on the JVM without a device.
 */
object ContactUtils {

    /** Digits only, keeping a leading '+'. */
    fun digitsOnly(s: String): String {
        val d = s.filter { it.isDigit() }
        return if (s.trimStart().startsWith("+")) "+$d" else d
    }

    /**
     * The identity of a phone number for duplicate detection: the last 10
     * digits. This treats +8801712345678, 01712345678 and 1712345678 as the
     * same number (country-code / trunk-prefix differences).
     */
    fun normalizeNumber(number: String): String {
        val d = digitsOnly(number).removePrefix("+")
        return if (d.length > 10) d.takeLast(10) else d
    }

    /**
     * Groups contacts that share a phone number. A contact can match
     * through any of its numbers; groups are keyed by the normalised
     * number, so the same person with two different numbers appears in
     * two groups. Only groups with 2+ contacts are returned.
     */
    fun groupDuplicates(contacts: List<FullContact>): List<List<FullContact>> {
        val byNumber = LinkedHashMap<String, MutableSet<FullContact>>()
        for (c in contacts) {
            for (p in c.phones) {
                val n = normalizeNumber(p.number)
                if (n.length < 7) continue
                byNumber.getOrPut(n) { linkedSetOf() } += c
            }
        }
        return byNumber.values.filter { it.size >= 2 }.map { it.toList() }
    }

    /** True when the query matches the contact (name, or a number/T9 prefix). */
    /**
     * Normalize a dialed/stored number for search comparison: +8801712…,
     * 01712… and 8801712… all become "1712…". (Bangladesh country code 880.)
     * Stripping is unconditional — a length guard broke progressive
     * matching (same regression the Dialer had in v1.6, fixed in v1.7).
     * Different from normalizeNumber (last-10, used for duplicates).
     */
    fun normalizeForMatch(digits: String): String {
        val d = digits.removePrefix("+")
        return when {
            d.startsWith("880") -> d.removePrefix("880")
            d.startsWith("0") -> d.removePrefix("0")
            else -> d
        }
    }

    fun matchesQuery(contact: FullContact, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (contact.displayName.contains(q, ignoreCase = true)) return true
        val digits = digitsOnly(q)
        if (digits.isNotEmpty()) {
            // v1.6: normalize both sides before comparing — a stored
            // 017… number must match a typed +880… query and vice versa
            // (the old raw contains() missed most form combinations).
            val qNorm = normalizeForMatch(digits)
            if (qNorm.length >= 2 && contact.phones.any {
                    normalizeForMatch(digitsOnly(it.number)).contains(qNorm)
                }) return true
            // T9: the typed digits are a prefix of some word of the name.
            if (digits.length >= 2 && digits.all { it.isDigit() }) {
                val t9 = mapOf(
                    '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
                    '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz"
                )
                val letterToDigit = buildMap<Char, Char> {
                    for ((d, letters) in t9) for (ch in letters) put(ch, d)
                }
                val words = contact.displayName.lowercase().split(Regex("[^a-z0-9]+"))
                    .filter { it.isNotEmpty() }
                val codes = words.map { w ->
                    w.mapNotNull { ch -> if (ch.isDigit()) ch else letterToDigit[ch] }.joinToString("")
                }
                if (codes.any { it.startsWith(digits) }) return true
            }
        }
        return false
    }
}
