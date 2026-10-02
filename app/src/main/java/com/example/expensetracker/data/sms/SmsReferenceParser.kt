package com.example.expensetracker.data.sms

/**
 * Finds the bank's own transaction references in a message.
 *
 * Nothing stores them any more — a rule's `ref` group is validated and discarded
 * (`PARSING_ARCHITECTURE.md` §2). What survives is the one use that doesn't need the value: a
 * labelled reference is structure only a bank alert has, so its presence is one of the markers
 * [hasBankAlertMarker] admits a message to the review queue on.
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

    /** @return every plausible reference in [body], upper-cased. */
    fun referencesIn(body: String): Set<String> {
        val found = LinkedHashSet<String>()
        LABELLED.findAll(body).mapTo(found) { it.groupValues[1].uppercase() }
        RAIL_PREFIXED.findAll(body).mapTo(found) { it.groupValues[1].uppercase() }
        BARE_TOKEN.findAll(body).mapTo(found) { it.groupValues[1].uppercase() }
        found.removeAll(NOT_A_REFERENCE)
        // A pure-digit run shorter than 8 is a card suffix or an amount, not a reference.
        return found.filterTo(LinkedHashSet()) { it.length >= 8 || it.any(Char::isLetter) }
    }
}
