package com.example.expensetracker.data.sms

/**
 * The only message-reading left in the app (`PARSING_ARCHITECTURE.md` §2).
 *
 * Reading a message *as a transaction* is the published rule set's job, so nothing here extracts a
 * value to store. These functions answer one question each, about a message no rule matched: is it
 * worth a person's attention ([looksFinancial]), is it worth a row at all ([mentionsAmount]), and
 * is it describing money that hasn't moved yet ([looksLikePreNotice]).
 *
 * They stay on the device because they decide what reaches the review queue — the place a message
 * waits for a rule to be written for it. A heuristic shipped in the APK can be wrong without
 * costing anything: over-admission is corrected from the console with an ignore rule, and
 * under-admission leaves the message in the skipped list where it can still be found.
 */

private val AMOUNT_HINT = Regex("""(?i)(?:rs\.?|inr)\s*[\d,]+""")

/**
 * The wording a bank uses for money that moved. No list of verbs can keep up with how each bank
 * abbreviates its own alerts — Canara writes `Dr.`/`Cr.`, an ATM writes `W/D` — which is why this
 * is only one of the two ways a message can qualify.
 */
private val TRANSACTIONAL_KEYWORD_HINT = Regex(
    """(?i)\b(debited|credited|debit|credit|spent|paid|received|withdrawn|purchase|sent|transferred|transfer)\b""",
)

/**
 * The account the money moved out of — "a/c X6686", "A/c XX1234", "Card XX12", but also the
 * label-free "HDFC Bank XX3941" and "A/C *3941" that NEFT and UPI alerts use.
 */
private val ACCOUNT_LABEL = Regex(
    """(?i)\b(?:a/c|ac|account|card|bank)\s*(?:no\.?\s*)?[:\-]?\s*((?:X|\*)+\d+|\d{4,})\b""",
)

/** A masked account number in any of the shapes banks mask them with. */
private val MASKED_ACCOUNT = Regex("""(?i)(?:X{2,}|\*{1,})\d+|\bA/C\b|\bA/c\b""")

/** `swiggy@icici` — a payee handle is as much a transaction marker as an account number. */
private val UPI_HANDLE = Regex("""[\w.\-]+@[\w.\-]+""")

/**
 * A balance and the amount that moved, together — "Dr. INR 26.00 … Bal INR 49,511.88". Matched so
 * the balance can be taken out of the message before asking whether any amount is left: a balance
 * enquiry's *only* amount is its balance, and that's what separates it from a real alert.
 *
 * `(?!\s+to\b)` for the same reason `Redactor`'s balance rule has it: Axis messages end "WhatsApp
 * BAL to 917036165000", and reading that as a balance gave every such message a balance marker —
 * so a promotion quoting an amount ("personal loan of Rs 5,00,000 … WhatsApp BAL to …") looked
 * structurally like a bank alert and reached the review queue.
 */
private val BALANCE_STATEMENT = Regex(
    """(?i)\b(?:avl|available|closing|updated)?\s*(?:bal|balance)\b(?!\s+to\b)""" +
        """[^\d]{0,12}?(?:rs\.?|inr)?\s*[\d,]+(?:\.\d{1,2})?""",
)

/**
 * Structure only a bank alert has: the account the money left, the reference it moved under, the
 * balance it left behind, the handle it was paid to. Unlike a verb, none of this depends on the
 * bank's choice of wording — which is the point, since the wording is what keeps changing.
 */
private fun hasBankAlertMarker(body: String): Boolean =
    MASKED_ACCOUNT.containsMatchIn(body) ||
        ACCOUNT_LABEL.containsMatchIn(body) ||
        BALANCE_STATEMENT.containsMatchIn(body) ||
        UPI_HANDLE.containsMatchIn(body) ||
        SmsReferenceParser.referencesIn(body).isNotEmpty()

/**
 * Mentions money at all. The line between a message worth keeping a record of having turned away
 * ([ParseOutcome.Discarded]) and one not worth a row ([ParseOutcome.Ignored]) — an OTP, a delivery
 * notification and a personal text all fall on the far side of it, which is what keeps the
 * skipped-messages bucket from becoming a copy of the inbox.
 */
fun mentionsAmount(body: String): Boolean = AMOUNT_HINT.containsMatchIn(body)

/**
 * Looks financial enough to surface for manual review, when no rule read it.
 *
 * Two ways to qualify, and an amount is needed either way. A transactional verb is one. Failing
 * that, the *shape* of a bank alert plus an amount that isn't the balance — which admits an alert
 * whose direction is spelled in a way no list here anticipated (Canara's `Dr.` went missing for
 * months), while still leaving a bare balance enquiry out.
 */
fun looksFinancial(body: String): Boolean {
    if (!AMOUNT_HINT.containsMatchIn(body)) return false
    if (TRANSACTIONAL_KEYWORD_HINT.containsMatchIn(body)) return true
    return hasBankAlertMarker(body) && AMOUNT_HINT.containsMatchIn(BALANCE_STATEMENT.replace(body, " "))
}

private val LOOSE_AMOUNT = Regex("""(?i)(?:rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)""")

/**
 * Loosely reads the first Rs/INR amount mentioned, for the amount-floor filter in [SmsParser].
 * Deliberately rough: it only decides whether to surface a message for review, never what to
 * record — a rule does that.
 */
fun looseAmountMinor(body: String): Long? =
    LOOSE_AMOUNT.find(body)?.groupValues?.get(1)?.let(::parseAmountToMinorUnits)

/**
 * Mandate reminders, bill-due nudges, and "how to stop this" footers describe money that hasn't
 * moved yet, but mention an amount and a debit-shaped verb just like a real alert — which is why
 * [looksFinancial] alone routes them into the review queue. Sender-independent by design: the
 * mandate notice and the bill reminder come from unrelated senders but share this same future-tense
 * signature.
 */
private val FUTURE_TENSE_HINT = Regex(
    """(?i)\b(?:will\s+be\s+debited|due\s+for\s+payment|upcoming\s+mandate|to\s+stop\s+execution)\b""",
)

/**
 * A confirmation verb found outside the matched future-tense phrase means the message also states a
 * debit/credit that already happened — e.g. "Last EMI of Rs 500 was debited; next EMI will be
 * debited on the 5th" is a real transaction, footer or not. The phrase itself is stripped first
 * because "will be **debited**" would otherwise always self-match.
 */
private val CONFIRMATION_VERB = Regex(
    """(?i)\b(?:debited|debit|spent|paid|withdrawn|sent|transferred|credited|received)\b""",
)

/** A future-tense notice with no separate past-tense confirmation is not a transaction. */
fun looksLikePreNotice(body: String): Boolean {
    if (!FUTURE_TENSE_HINT.containsMatchIn(body)) return false
    val remainder = FUTURE_TENSE_HINT.replace(body, " ")
    return !CONFIRMATION_VERB.containsMatchIn(remainder)
}
