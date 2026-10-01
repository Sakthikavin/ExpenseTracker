package com.example.expensetracker.data.remoterules

/**
 * Turns a raw bank SMS into the **template** that gets uploaded for rule authoring (REQUIREMENTS §6):
 * the shape of the message, with every value a regex doesn't need stripped out.
 *
 * Placeholders match the console's vocabulary (`<[A-Z]+\d*>`); anything outside that set is
 * invisible to its template grouping and starter-regex builder.
 *
 * **The invariant:** a template differs from its message *only* where a value became a
 * placeholder. Currency words, separators, spacing and digit counts are shape, not personal data,
 * and every rule written on the console depends on them — so dropping any of it shows rule authors
 * a message that doesn't exist. `RedactorInvariantTest` holds this over the real-message corpus.
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

    /**
     * Codes someone could actually use: a voucher code, a coupon, an OTP, a PIN.
     *
     * `Code: 346QH2VK` has no run of five digits, so neither `<NUM>` nor [unredactedHints] saw it
     * and the code uploaded verbatim. Only alphanumeric runs containing a digit are masked, so
     * "Code: apply" and "Your PIN has been changed" keep their words.
     */
    private val CODE =
        Regex("""(?i)\b(code|coupon|otp|pin|passcode)(\s*(?:is|:|-)?\s*)(?=[A-Za-z]*\d)([A-Za-z0-9]{4,12})\b""")

    /**
     * `(?!\s+to\b)`: Axis messages end "WhatsApp BAL to 917036165000", and without it the helpline
     * number reads as the balance — which is how the published `axisbk_debit_v1` came to capture a
     * phone number as one. Excluded here, the number falls through to the `<PHONE>` rule.
     */
    private val BALANCE = Regex(
        """(?i)\b((?:avl|available|closing|updated)?\s*(?:bal|balance)\b(?!\s+to\b)[^\d]{0,12}?)""" +
            """(rs\.?|inr)?(\s*)[\d,]+(?:\.\d{1,2})?""",
    )

    private val RULES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // First, so digits inside a link can't be half-masked into <DATE> or <REF> fragments.
        URL to { "<URL>" },

        // Before <NUM> and before the ref rule: a six-digit OTP would otherwise become <NUM>,
        // which hides it but tells the rule author it's an identifier rather than a secret.
        CODE to { m -> m.groupValues[1] + m.groupValues[2] + "<CODE>" },

        // Balance: only the number goes. The currency token and its spacing are message shape that
        // rules anchor on — dropping them (as this used to) showed rule authors a `bal is <BAL>`
        // that no real message has, and every rule written against it matched nothing.
        BALANCE to { m -> m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + "<BAL>" },

        // Account / card numbers: keep the masking the bank already applied, drop the visible
        // digits — but say how many there were, since a rule has to match that many.
        Regex("""(?i)\b(a/c|ac|acct|account|card)(\s*(?:no\.?|number)?\s*)([xX*]+)(\s*)(\d{2,6})""") to
            { m -> m.groupValues.slice(1..4).joinToString("") + maskedDigits(m.groupValues[5]) },
        Regex("""\b([xX*]{2,})(\s*)(\d{2,6})\b""") to
            { m -> m.groupValues[1] + m.groupValues[2] + maskedDigits(m.groupValues[3]) },

        // Amounts: the currency token and its spacing stay so a rule can anchor on them.
        Regex("""(?i)\b(rs\.?|inr)(\s*)[\d,]+(?:\.\d{1,2})?""") to { m -> m.groupValues[1] + m.groupValues[2] + "<AMT>" },

        Regex("""\b\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b""") to { "<DATE>" },
        Regex("""\b\d{4}-\d{1,2}-\d{1,2}\b""") to { "<DATE>" },
        // A spaced date ("30 Sep 2026") gets its own placeholder: a rule's date group is usually
        // `\S+`, which can't read one, so the console has to be able to tell the two apart.
        Regex("""\b\d{1,2}[\s\-]?[A-Za-z]{3}[a-z]*[\s\-]?\d{2,4}\b""") to
            { m -> if (m.value.any(Char::isWhitespace)) "<DATEW>" else "<DATE>" },
        Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""") to { "<TIME>" },

        // Reference / UTR: keep the label, mask the identifier.
        Regex("""(?i)\b(ref|refno|utr|rrn|txn|trn|imps|neft)([\s.:#]*(?:no\.?|id)?[\s.:#]*)([A-Za-z]*\d[A-Za-z\d]{5,})""") to
            { m -> "${m.groupValues[1]}${m.groupValues[2]}<REF>" },

        // Phone numbers (helplines, "call 18002586161"); runs this late so refs are already gone.
        // Narrow to shapes that really are phone numbers — an Indian mobile, optionally with the
        // country code, or a 1800 helpline. A bare `\d{10,12}` also swallowed the Axis mandate id
        // `APY/500405010905/…`, and a template claiming that's a phone number misleads whoever
        // writes the rule exactly as the old balance shape did.
        Regex("""\b(?:\+?91[\-\s]?)?[6-9]\d{9}\b|\b1800\d{6,7}\b""") to { "<PHONE>" },

        // UPI / email handles: the owner goes, the fact that it *is* a handle stays.
        Regex("""[\w.\-]+@[\w.\-]+""") to { "<VPA>" },

        // Last resort, and last in the list on purpose: any digit run long enough for
        // [unredactedHints] to flag, that none of the rules above recognised — a mandate id, a
        // customer id, a reference buried in a narration with no label beside it. Without this the
        // app can build a template it then refuses to upload, which leaves exactly the messages
        // that need a rule unable to ask for one. Placing it after the handle rule matters: run
        // earlier and it would break `pinelabs.11093315@pineaxis` apart before `<VPA>` sees it.
        Regex("""\d{5,}""") to { "<NUM>" },
    )

    /** `<D4>` for `1234` — the count is shape a rule needs, the digits themselves are not. */
    private fun maskedDigits(digits: String) = "<D${digits.length}>"

    fun redact(body: String): String = RULES.fold(body.trim()) { text, (regex, replacement) ->
        regex.replace(text, replacement)
    }

    /**
     * What the console flags on inbound submissions (`unredactedHints` in its `lib/grouping.js`).
     * A non-empty result means redaction missed something, and the app must not upload the template
     * (REQUIREMENTS §6.1.1).
     *
     * The `<NUM>` catch-all above shares this function's 5-digit threshold, so "a long number"
     * should now be unreachable for anything [redact] produces — `RedactorInvariantTest` holds that
     * over the real corpus. This stays as the check of record: it's the same rule the console
     * applies to inbound submissions, and it must keep guarding templates from anywhere else.
     */
    fun unredactedHints(template: String): List<String> {
        val plain = template.replace(Regex("""<[A-Z]+\d*>"""), " ")
        return buildList {
            if (Regex("""\d{5,}""").containsMatchIn(plain)) add("a long number")
            if (Regex("""[\w.\-]+@[\w.\-]+""").containsMatchIn(plain)) add("an email or UPI handle")
            if (Regex("""(?i)https?://|www\.""").containsMatchIn(plain) || URL.containsMatchIn(plain)) add("a link")
            // Deliberately looser than [CODE]'s 12-character ceiling: a longer code the rule left
            // alone must still stop the upload rather than leave with it.
            if (Regex("""(?i)\b(?:code|coupon|otp|pin)\b\s*(?:is|:|-)?\s*(?=[A-Za-z]*\d)[A-Za-z0-9]{4,}""")
                    .containsMatchIn(plain)
            ) {
                add("a code")
            }
        }
    }
}
