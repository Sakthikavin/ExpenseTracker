package com.example.expensetracker.data.remoterules

/**
 * Turns a raw bank SMS into the **template** that gets uploaded for rule authoring (REQUIREMENTS §6):
 * the shape of the message, with every value a regex doesn't need stripped out.
 *
 * Placeholders match the console's vocabulary (`<[A-Z]+\d*>`); anything outside that set is
 * invisible to its template grouping and starter-regex builder.
 */
object Redactor {

    /**
     * Applied in order — later rules only see what earlier ones left behind, which is what keeps a
     * bare number run from swallowing the tail of a date or a reference number.
     */
    /**
     * Bank short links carry a per-customer code (`…/HDFCBK/s/a/E0WMgeP0`), so they identify the
     * person as surely as an account number. Requiring a letters-only TLD before the path is what
     * keeps this off `Avl Bal 1234.56/…`; the spec's looser `[a-z0-9-]+` last label would eat it.
     */
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+|(?i)\b(?:[a-z0-9-]+\.)+[a-z]{2,}/\S*""")

    private val RULES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // First, so digits inside a link can't be half-masked into <DATE> or <REF> fragments.
        URL to { "<URL>" },

        // Balance: the whole phrase goes, not just the number — "Avl Bal Rs.15,342.50" is as
        // identifying as the account number it follows.
        Regex("""(?i)\b((?:avl|available|closing|updated)?\s*(?:bal|balance)\b[^\d]{0,12}?)(?:rs\.?|inr)?\s*[\d,]+(?:\.\d{1,2})?""") to
            { m -> m.groupValues[1].trimEnd() + " <BAL>" },

        // Account / card numbers: keep the masking the bank already applied, drop the visible digits.
        Regex("""(?i)\b(a/c|ac|acct|account|card)(\s*(?:no\.?|number)?\s*)([xX*]+)\s*\d{2,6}""") to
            { m -> "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}<D4>" },
        Regex("""\b[xX*]{2,}\s*\d{2,6}\b""") to { m -> m.value.takeWhile { it == 'x' || it == 'X' || it == '*' } + "<D4>" },

        // Amounts: the currency token and its spacing stay so a rule can anchor on them.
        Regex("""(?i)\b(rs\.?|inr)(\s*)[\d,]+(?:\.\d{1,2})?""") to { m -> m.groupValues[1] + m.groupValues[2] + "<AMT>" },

        Regex("""\b\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b""") to { "<DATE>" },
        Regex("""\b\d{4}-\d{1,2}-\d{1,2}\b""") to { "<DATE>" },
        Regex("""\b\d{1,2}[\s\-]?[A-Za-z]{3}[a-z]*[\s\-]?\d{2,4}\b""") to { "<DATE>" },
        Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""") to { "<TIME>" },

        // Reference / UTR: keep the label, mask the identifier.
        Regex("""(?i)\b(ref|refno|utr|rrn|txn|trn|imps|neft)([\s.:#]*(?:no\.?|id)?[\s.:#]*)([A-Za-z]*\d[A-Za-z\d]{5,})""") to
            { m -> "${m.groupValues[1]}${m.groupValues[2]}<REF>" },

        // Phone numbers (helplines, "call 18002586161"); runs this late so refs are already gone.
        Regex("""\b(?:\+?91[\-\s]?)?\d{10,12}\b""") to { "<PHONE>" },

        // UPI / email handles: the owner goes, the fact that it *is* a handle stays.
        Regex("""[\w.\-]+@[\w.\-]+""") to { "<VPA>" },
    )

    fun redact(body: String): String = RULES.fold(body.trim()) { text, (regex, replacement) ->
        regex.replace(text, replacement)
    }

    /**
     * What the console flags on inbound submissions (`unredactedHints` in its `lib/grouping.js`).
     * A non-empty result means redaction missed something, and the app must not upload the template
     * (REQUIREMENTS §6.1.1).
     */
    fun unredactedHints(template: String): List<String> {
        val plain = template.replace(Regex("""<[A-Z]+\d*>"""), " ")
        return buildList {
            if (Regex("""\d{5,}""").containsMatchIn(plain)) add("a long number")
            if (Regex("""[\w.\-]+@[\w.\-]+""").containsMatchIn(plain)) add("an email or UPI handle")
            if (Regex("""(?i)https?://|www\.""").containsMatchIn(plain) || URL.containsMatchIn(plain)) add("a link")
        }
    }
}
