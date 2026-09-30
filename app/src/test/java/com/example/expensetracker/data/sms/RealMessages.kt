package com.example.expensetracker.data.sms

/**
 * Real bank messages, copied verbatim from the user's phone — the ground truth for tier 1
 * (`RealMessageTest`) and for redaction faithfulness
 * (`com.example.expensetracker.data.remoterules.RedactorInvariantTest`).
 *
 * When a new bank format shows up, add it here first and watch it fail before touching a regex.
 * Keep these byte-exact: a message rewritten to look tidier stops being evidence.
 */
object RealMessages {

    /** Axis: multi-line auto-debit with no payee line. */
    val axisApy = """
        Debit INR 292.00
        Axis Bank A/c XX4795
        01-08-26 15:48:58
        APY/500405010905/920010018
        WhatsApp BAL to 917036165000
        Not You? SMS BLOCKALL CustID to 919951860002
    """.trimIndent()

    /** HDFC UPI: multi-line with labelled From/To lines. */
    val hdfcUpi = """
        Sent Rs.58.00
        From HDFC Bank A/C *3941
        To Google India Digital Serv
        On 24/02/26
        Ref 119088866187
        Not You?
        Call 18002586161/SMS BLOCK UPI to 7308080808
    """.trimIndent()

    /** NPS, debit leg: the real purpose is buried at the end of the `Info:` narration. */
    val npsDebit = "UPDATE: INR 5,000.00 debited from HDFC Bank XX3941 on 05-MAR-26. Info: NEFT " +
        "Dr-UTIB0CCH274-SAKTHI KAVIN S S-SANDOZ - MUM-HDFCH00842011992-NET BANKING SI -NPS " +
        "Contribution M. Avl bal:INR 84,966.79"

    /** NPS, credit leg: the other half of the same payment, carrying the same bank reference. */
    val npsCredit = "HDFC Bank : NEFT money transfer Txn No HDFCH00842011992 for Rs INR 5,000.00 " +
        "has been credited to SAKTHI KAVIN S S on 05-03-2026 at 04:01:54"

    /**
     * TMB: the message whose uploaded template was lossy enough that the console's published v7
     * rules passed every sample and matched no real message (FAITHFUL_REDACTION.md §1). Its
     * `Current AVBL bal is Rs.10214.44` is the reason the balance rule keeps the currency token.
     */
    val tmbCredit = "Your A/c No.XXXX0017 is credited with Rs.5,000.00 on 30-09-2026 11:03 AM and " +
        "HDFC A/c linked to sakthikavincit-2@okaxis is debited (UPI Ref No.663989123496)." +
        "Current AVBL bal is Rs.10214.44 - TMB"

    val tmbDebit = "Your A/c No.XXXX0017 is debited with Rs.1,783.60 on 30-09-2026 10:37 AM and " +
        "UTIB A/c linked to pinelabs.11093315@pineaxis is credited (UPI Ref No.130455129208)." +
        "Current AVBL bal is Rs.5214.44 - TMB"

    /**
     * Federal Bank: single line, `Bal Rs 42727.9.` with a space before the number. `SmsRepositoryTest`
     * keeps its own copy — it's in `androidTest`, which can't see this source set.
     */
    val federalUpi = "Debited Rs 1.00 from a/c X6686 on 01Aug26 19:00 via UPI to KEERTHANA KU. " +
        "Ref 621312687340.Bal Rs 42727.9. -Federal Bank"

    /** HDFC: a declined transaction, carrying a per-customer short link. */
    val hdfcDeclined = "TXN DECLINED: Rs.500 on 29-09-26 at 19:00 on HDFC Bank Debit Card xx1234. " +
        "Reason: Online set Limit Exceeded. Modify:https://1.hdfc.bank.in/HDFCBK/s/a/E0WMgeP0"

    val all = listOf(axisApy, hdfcUpi, npsDebit, npsCredit, tmbCredit, tmbDebit, federalUpi, hdfcDeclined)
}
