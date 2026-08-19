package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.entity.Direction

/**
 * Tier 1 of the three-tier parsing design (see spec §3): a built-in regex per known sender.
 *
 * The body regex here is a generic "Rs X debited/credited ... to/from Y" shape that covers
 * the common wording across most Indian bank and UPI alerts. It is a starting point, not a
 * verified-against-real-traffic parser — refine per bank once real message samples are in
 * hand. Anything it misses still degrades gracefully into the review queue (tier 2).
 */
interface BankTemplate {
    val name: String
    fun matchesSender(sender: String): Boolean
    fun extract(body: String): ParsedSms?
}

private const val CURRENCY = """(?:rs\.?|inr)"""

/** Must start with a digit — `[\d,]+` alone matches a bare comma, which is not a number. */
private const val AMOUNT = """(\d[\d,]*(?:\.\d{1,2})?)"""

/**
 * The merchant, with a guard against grabbing the account the money moved out of: a message like
 * "Debited Rs 1.00 from a/c X6686 ... to KEERTHANA KU" offers "a/c X6686" first, and without the
 * lookahead that would win and the real payee would be lost.
 */
private const val MERCHANT = """(?!a/c\b|ac\b|account\b)([A-Za-z0-9@._/\-\s]{2,40}?)"""

/** What ends the merchant — otherwise the lazy group runs on into the rest of the sentence. */
private const val STOP = """(?:\s+on\b|\s+ref\b|\s+via\b|[.,]|$)"""

private const val DEBIT_VERB = """(?:debited|debit|spent|paid|withdrawn|sent|transferred)"""
private const val CREDIT_VERB = """(?:credited|received)"""

/** "towards" precedes "to" so the shorter alternative can't win on a prefix. */
private const val DEBIT_PREPOSITION = """(?:towards|to|at|from)"""
/** "credited **to** SAKTHI KAVIN S S" names the beneficiary, as much as "credited from X" does. */
private const val CREDIT_PREPOSITION = """(?:from|by|to)"""

/** "debited **by** Rs.320", "debited Rs 320" — banks are inconsistent about the connector. */
private const val CONNECTOR = """(?:by\s+|of\s+|for\s+)?"""

/**
 * Real bank alerts put the amount on either side of the verb — "Rs 450 debited to X" (HDFC) and
 * "Debited Rs 1.00 ... to X" (Federal) are both common — so each direction needs both orderings.
 */
private fun directionRegexes(verb: String, preposition: String) = listOf(
    Regex("""(?i)$CURRENCY\s*$AMOUNT\s+(?:has\s+been\s+)?$verb.*?\b$preposition\s+$MERCHANT$STOP"""),
    Regex("""(?i)$verb\s+$CONNECTOR$CURRENCY\s*$AMOUNT\b.*?\b$preposition\s+$MERCHANT$STOP"""),
)

private val DEBIT_REGEXES = directionRegexes(DEBIT_VERB, DEBIT_PREPOSITION)
private val CREDIT_REGEXES = directionRegexes(CREDIT_VERB, CREDIT_PREPOSITION)

/**
 * The account the money moved out of — "a/c X6686", "A/c XX1234", "Card XX12", but also the
 * label-free "HDFC Bank XX3941" and "A/C *3941" that NEFT and UPI alerts use.
 */
private val ACCOUNT_LABEL = Regex(
    """(?i)\b(?:a/c|ac|account|card|bank)\s*(?:no\.?\s*)?[:\-]?\s*((?:X|\*)+\d+|\d{4,})\b""",
)

/** A masked account number in any of the shapes banks mask them with. */
private val MASKED_ACCOUNT = Regex("""(?i)(?:X{2,}|\*{1,})\d+|\bA/C\b|\bA/c\b""")

/**
 * Rejects merchant candidates that are really the account the money came out of, or a helpline.
 *
 * "INR 5,000 debited from HDFC Bank XX3941" and "Sent Rs.58.00 From HDFC Bank A/C *3941" both offer
 * the source account where a payee should be; "WhatsApp BAL to 917036165000" offers a phone number.
 * None of these are merchants.
 */
private fun looksLikeAccountOrNumber(candidate: String): Boolean {
    val trimmed = candidate.trim()
    if (trimmed.isBlank()) return true
    if (MASKED_ACCOUNT.containsMatchIn(trimmed)) return true
    // Phone numbers, card suffixes, reference digits — a payee always has letters.
    if (trimmed.none(Char::isLetter)) return true
    // "HDFC Bank", "Axis Bank" on their own name the institution, not who was paid.
    if (Regex("""(?i)^[A-Za-z]+\s+bank$""").matches(trimmed)) return true
    return false
}

/** Shared extraction logic every per-bank template delegates to until it needs to diverge. */
private fun extractGeneric(body: String): ParsedSms? =
    firstMatch(DEBIT_REGEXES, body, Direction.DEBIT)
        ?: firstMatch(CREDIT_REGEXES, body, Direction.CREDIT)
        ?: BlockFormat.extract(body)

private fun firstMatch(regexes: List<Regex>, body: String, direction: Direction): ParsedSms? {
    for (regex in regexes) {
        val match = regex.find(body) ?: continue
        // An amount we can't convert means we haven't really understood the message; let it fall
        // through to the review queue rather than storing a wrong number.
        val amountMinor = parseAmountToMinorUnits(match.groupValues[1]) ?: continue
        val candidate = match.groupValues[2].trim()
        val merchant = if (looksLikeAccountOrNumber(candidate)) resolveNonMerchant(body) ?: continue else candidate
        return parsedSms(body, amountMinor, direction, merchant)
    }
    return null
}

/**
 * The account on the far side of a movement: "To ICICI Bank A/C XX4795", "to a/c XX4795".
 *
 * When this is one of the user's own accounts the message is a self-transfer rather than spending,
 * which is what [com.example.expensetracker.data.repository.TransferMatcher] needs to know.
 */
private val COUNTERPARTY_ACCOUNT = Regex(
    """(?im)\bto\s+[A-Za-z&.\s]{0,30}?\b(?:a/c|ac|account)\s*(?:no\.?\s*)?[:\-]?\s*((?:X|\*)*\d{3,})\b""",
)

/** The institution on the far side: "To **ICICI Bank** A/C XX4795". */
private val COUNTERPARTY_INSTITUTION = Regex(
    """(?im)\bto\s+([A-Za-z][A-Za-z&.]*(?:\s+[A-Za-z&.]+){0,3}?)\s+(?:a/c|ac|account)\b""",
)

/**
 * Called when the text where a payee should be turns out to name an account instead.
 *
 * A transfer to your own account is still a real, parseable transaction — it just has no merchant.
 * Naming the destination institution keeps the row readable until the account is registered and the
 * UI can show its nickname; a NEFT alert instead carries its purpose in the trailing Info: blob.
 */
private fun resolveNonMerchant(body: String): String? =
    COUNTERPARTY_INSTITUTION.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        ?: remarkMerchant(body)

/** The free-text purpose a NEFT/IMPS alert carries, e.g. `Info: NEFT Dr-...-NPS Contribution M`. */
private val REMARK = Regex("""(?i)\b(?:info|remarks?|narration|desc)\s*[:\-]\s*(.+?)(?:\.\s*avl\b|$)""")

/** Codes, references and IFSCs that appear as segments of a NEFT narration but name nobody. */
private val CODE_SEGMENT = Regex("""^(?:[A-Z]{4}\d[A-Z0-9]*|[A-Z]{2,6}\d{6,}|[A-Z]{2,4})$""")

/**
 * Picks the payee out of a hyphen-separated NEFT narration.
 *
 * Banks and beneficiary names are written in block capitals; the purpose the human typed keeps its
 * mixed case ("NPS Contribution"), which is what makes it findable in the soup.
 */
private fun remarkMerchant(body: String): String? {
    val remark = REMARK.find(body)?.groupValues?.get(1)?.trim() ?: return null
    val segments = remark.split('-')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.any(Char::isLetter) && !CODE_SEGMENT.matches(it) }

    val chosen = segments.lastOrNull { it.any(Char::isLowerCase) } ?: segments.lastOrNull() ?: return null
    // Narrations often end in a stray one-letter marker ("NPS Contribution M").
    return chosen.trim().removeSuffix(".").trim()
        .split(' ')
        .dropLastWhile { it.length == 1 }
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
}

/**
 * Handles the "labelled block" layout, where each fact sits on its own line:
 *
 * ```
 * Sent Rs.58.00
 * From HDFC Bank A/C *3941
 * To Google India Digital Serv
 * On 24/02/26
 * ```
 *
 * The single-line regexes cannot reach these, because `.` does not match a newline in Kotlin — and
 * simply enabling that would be worse than useless: on an Axis alert the first `to` reachable across
 * lines belongs to "WhatsApp BAL to 917036165000", so the payee would become a phone number.
 * Reading the lines structurally keeps each fact bound to the line that states it.
 */
private object BlockFormat {

    private val AMOUNT_LINE_VERB_FIRST =
        Regex("""(?i)^($DEBIT_VERB|$CREDIT_VERB)\s+$CONNECTOR$CURRENCY\s*$AMOUNT\s*$""")
    private val AMOUNT_LINE_AMOUNT_FIRST =
        Regex("""(?i)^$CURRENCY\s*$AMOUNT\s+($DEBIT_VERB|$CREDIT_VERB)\s*$""")

    /** "To Google India Digital Serv" — the payee, on its own line. */
    private val PAYEE_LINE = Regex("""(?i)^(?:to|payee|beneficiary)\s*[:\-]?\s*(.+)$""")

    /**
     * A mandate or scheme reference like `APY/500405010905/920010018`. Its leading token names the
     * scheme (APY = Atal Pension Yojana, NACH, ACH), which is the only merchant-like text such an
     * auto-debit carries.
     */
    private val SCHEME_LINE = Regex("""^([A-Z]{2,10})/\S+""")

    fun extract(body: String): ParsedSms? {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null

        val (amountMinor, direction) = findAmount(lines) ?: return null
        val merchant = findMerchant(lines) ?: return null
        return parsedSms(body, amountMinor, direction, merchant)
    }

    private fun findAmount(lines: List<String>): Pair<Long, Direction>? {
        for (line in lines) {
            val verbFirst = AMOUNT_LINE_VERB_FIRST.find(line)
            if (verbFirst != null) {
                val amount = parseAmountToMinorUnits(verbFirst.groupValues[2]) ?: continue
                return amount to directionOf(verbFirst.groupValues[1])
            }
            val amountFirst = AMOUNT_LINE_AMOUNT_FIRST.find(line)
            if (amountFirst != null) {
                val amount = parseAmountToMinorUnits(amountFirst.groupValues[1]) ?: continue
                return amount to directionOf(amountFirst.groupValues[2])
            }
        }
        return null
    }

    private fun findMerchant(lines: List<String>): String? {
        val payeeLine = lines.firstNotNullOfOrNull { line ->
            PAYEE_LINE.find(line)?.groupValues?.get(1)?.trim()
        }
        if (payeeLine != null) {
            // "To ICICI Bank A/C XX4795" is a real destination, just not a merchant — name the
            // institution rather than discarding the message.
            if (!looksLikeAccountOrNumber(payeeLine)) return payeeLine
            resolveNonMerchant("to $payeeLine")?.let { return it }
        }

        // No payee line: an auto-debit mandate names only its scheme.
        return lines.firstNotNullOfOrNull { SCHEME_LINE.find(it)?.groupValues?.get(1) }
    }

    private fun directionOf(verb: String): Direction =
        if (Regex("""(?i)^$CREDIT_VERB$""").matches(verb)) Direction.CREDIT else Direction.DEBIT
}

/** Builds the result, filling in the fields that are read from the whole message rather than a span. */
private fun parsedSms(
    body: String,
    amountMinor: Long,
    direction: Direction,
    merchant: String,
): ParsedSms = ParsedSms(
    amountMinor = amountMinor,
    direction = direction,
    merchant = merchant,
    accountLabel = ACCOUNT_LABEL.find(body)?.groupValues?.get(1).orEmpty(),
    occurredAt = SmsDateParser.parse(body),
    referenceId = SmsReferenceParser.primaryReference(body),
    counterpartyAccount = COUNTERPARTY_ACCOUNT.find(body)?.groupValues?.get(1),
)

private class GenericBankTemplate(
    override val name: String,
    private val senderHints: List<String>,
) : BankTemplate {
    override fun matchesSender(sender: String): Boolean =
        senderHints.any { sender.contains(it, ignoreCase = true) }

    override fun extract(body: String): ParsedSms? = extractGeneric(body)
}

object BankTemplates {
    /**
     * Senders are matched against these hints. Indian sender IDs carry a rotating operator prefix
     * ("AD-HDFCBK", "VM-HDFCBK"), so a `contains` check on the bank token is the right shape.
     */
    val all: List<BankTemplate> = listOf(
        GenericBankTemplate("HDFC", listOf("HDFC")),
        GenericBankTemplate("SBI", listOf("SBI", "SBIINB", "SBIBNK")),
        GenericBankTemplate("ICICI", listOf("ICICI")),
        GenericBankTemplate("Axis", listOf("AXIS", "AXISBK")),
        GenericBankTemplate("Kotak", listOf("KOTAK")),
        GenericBankTemplate("Federal", listOf("FEDBNK", "FEDERAL")),
        GenericBankTemplate("IDFC", listOf("IDFC")),
        GenericBankTemplate("PNB", listOf("PNBSMS", "PUNJAB")),
        GenericBankTemplate("Bank of Baroda", listOf("BOBSMS", "BARODA")),
        GenericBankTemplate("Canara", listOf("CANBNK", "CANARA")),
        GenericBankTemplate("Yes Bank", listOf("YESBNK")),
        GenericBankTemplate("IndusInd", listOf("INDUSB", "INDUSIND")),
        GenericBankTemplate("Paytm", listOf("PAYTM")),
        GenericBankTemplate("PhonePe", listOf("PHONPE", "PHONEPE")),
        GenericBankTemplate("Google Pay", listOf("GPAY", "GOOGLEPAY")),
        GenericBankTemplate("Amazon Pay", listOf("AMZNPAY", "AMAZONPAY")),
    )

    /**
     * The catch-all. UPI and card alerts don't share a sender ID across providers, so anything the
     * named templates don't claim still gets parsed on body content alone.
     */
    private val fallback = GenericBankTemplate("Generic", emptyList())

    /**
     * Tries every template whose sender matches before falling back, so a bank-specific template
     * that fails to extract doesn't abort the whole match — with `firstOrNull { matchesSender }`
     * a single non-matching body would have ended the attempt.
     */
    fun findMatch(sender: String, body: String): ParsedSms? =
        all.filter { it.matchesSender(sender) }
            .firstNotNullOfOrNull { it.extract(body) }
            ?: fallback.extract(body)
}

private val AMOUNT_HINT = Regex("""(?i)(?:rs\.?|inr)\s*[\d,]+""")
/**
 * Must stay a superset of the parse verbs. A wording that parses but isn't listed here would be
 * fine, but one that is *missing* from both — as "sent" was — means the message is silently
 * discarded and the money never appears anywhere, not even the review queue.
 */
private val TRANSACTIONAL_KEYWORD_HINT = Regex(
    """(?i)\b(debited|credited|debit|credit|spent|paid|received|withdrawn|purchase|sent|transferred|transfer)\b""",
)

/** Heuristic for tier 2: looks financial enough to surface for manual review. */
fun looksFinancial(body: String): Boolean =
    AMOUNT_HINT.containsMatchIn(body) && TRANSACTIONAL_KEYWORD_HINT.containsMatchIn(body)

private val LOOSE_AMOUNT = Regex("""(?i)(?:rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)""")

/**
 * Loosely reads the first Rs/INR amount mentioned, for the amount-floor filter in [SmsParser] —
 * this doesn't need [BankTemplate.extract]'s rigor since it only decides whether to surface the
 * message for review, not what to record.
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
private val CONFIRMATION_VERB = Regex("""(?i)\b(?:$DEBIT_VERB|$CREDIT_VERB)\b""")

/** Tier-2 pre-filter: a future-tense notice with no separate past-tense confirmation is not a transaction. */
fun looksLikePreNotice(body: String): Boolean {
    if (!FUTURE_TENSE_HINT.containsMatchIn(body)) return false
    val remainder = FUTURE_TENSE_HINT.replace(body, " ")
    return !CONFIRMATION_VERB.containsMatchIn(remainder)
}