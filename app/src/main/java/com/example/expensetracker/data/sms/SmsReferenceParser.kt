package com.example.expensetracker.data.sms

/**
 * Extracts the bank's own transaction reference from a message.
 *
 * Banks often send two SMS for one movement of money — a debit notification and a transfer
 * confirmation — and the only exact link between them is a shared reference. For an NPS
 * contribution, `HDFCH00842011992` appears buried in the debit's `Info:` blob and again as
 * `Txn No HDFCH00842011992` in the confirmation. Matching on it is what stops one payment being
 * recorded twice.
 */
object SmsReferenceParser {

    /** `Ref 119088866187`, `Ref No: ABC123456`, `UTR 123456789012`, `Txn No HDFCH00842011992`. */
    private val LABELLED = Regex(
        """(?i)\b(?:ref(?:erence)?|utr|txn|transaction)\s*(?:no\.?|id|#)?\s*[:\-]?\s*([A-Z0-9]{6,})\b""",
    )

    /**
     * The UTR in a rail-prefixed narration: `IMPS/512345678901/HDFC BANK`, `UPI-123456789012`.
     *
     * A separator is required, so "NEFT money transfer" and "SMS BLOCK UPI to 730..." don't match —
     * only the slash/dash form that actually introduces a reference does.
     */
    private val RAIL_PREFIXED = Regex("""(?i)\b(?:imps|neft|rtgs|upi)\s*[/:\-]\s*([A-Z0-9]{6,})\b""")

    /**
     * An unlabelled bank reference: a short alphabetic prefix followed by a long digit run, e.g.
     * `HDFCH00842011992`. The 10-digit minimum keeps IFSC codes (`UTIB0CCH274`) out — they carry
     * far fewer trailing digits — and rules out ordinary words and amounts.
     */
    private val BARE_TOKEN = Regex("""\b([A-Z]{2,6}\d{10,})\b""")

    /** Words that follow the labels above but are never the reference itself. */
    private val NOT_A_REFERENCE = setOf("NUMBER", "DETAILS", "BELOW", "ATTACHED")

    /**
     * @return every plausible reference in [body], upper-cased. A message can carry more than one
     * (its own and a counterparty's), so callers should treat an intersection as a match rather
     * than requiring equality.
     */
    fun referencesIn(body: String): Set<String> {
        val found = LinkedHashSet<String>()
        LABELLED.findAll(body).mapTo(found) { it.groupValues[1].uppercase() }
        RAIL_PREFIXED.findAll(body).mapTo(found) { it.groupValues[1].uppercase() }
        BARE_TOKEN.findAll(body).mapTo(found) { it.groupValues[1].uppercase() }
        found.removeAll(NOT_A_REFERENCE)
        // A pure-digit run shorter than 8 is a card suffix or an amount, not a reference.
        return found.filterTo(LinkedHashSet()) { it.length >= 8 || it.any(Char::isLetter) }
    }

    /** The single best reference to store on a transaction: prefer the bank's own long token. */
    fun primaryReference(body: String): String? {
        val all = referencesIn(body)
        return all.firstOrNull { it.any(Char::isLetter) && it.any(Char::isDigit) }
            ?: all.firstOrNull()
    }
}
