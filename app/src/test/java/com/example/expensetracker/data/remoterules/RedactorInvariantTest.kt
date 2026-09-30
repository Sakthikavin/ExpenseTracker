package com.example.expensetracker.data.remoterules

import com.example.expensetracker.data.sms.RealMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the one property every rule author depends on (FAITHFUL_REDACTION.md §2): a template
 * differs from its message *only* where a value became a placeholder.
 *
 * Checked by fitting the template back over the original — each placeholder standing for some run
 * of characters, everything else matching literally. A rule that drops a currency word, inserts a
 * space or normalises a separator fails this, which is the class of bug that made the console's
 * published TMB rules match nothing: they were written against `bal is <BAL>`, a shape no real
 * message has.
 */
class RedactorInvariantTest {

    private val placeholder = Regex("""<[A-Z]+\d*>""")

    /** Kotlin's `split` drops delimiters, so walk the placeholder matches and slice between them. */
    private fun fitsBack(original: String, template: String): Boolean {
        val pattern = buildString {
            var last = 0
            for (match in placeholder.findAll(template)) {
                append(Regex.escape(template.substring(last, match.range.first)))
                append(".+?")
                last = match.range.last + 1
            }
            append(Regex.escape(template.substring(last)))
        }
        return Regex(pattern, RegexOption.DOT_MATCHES_ALL).matches(original.trim())
    }

    @Test
    fun `every real message's template fits back over the message`() {
        for (message in RealMessages.all) {
            val template = Redactor.redact(message)
            assertTrue(
                "redaction changed more than the values:\n  message:  $message\n  template: $template",
                fitsBack(message, template),
            )
        }
    }

    /**
     * The other half of faithfulness: a template the app then refuses to upload (§6.1.1) is no use
     * either. Two real messages used to fail here — the Axis mandate id and an unlabelled reference
     * inside the NPS narration — which meant exactly the messages needing a rule couldn't ask.
     */
    @Test
    fun `every real message produces a template the app is allowed to upload`() {
        for (message in RealMessages.all) {
            val template = Redactor.redact(message)
            assertEquals(
                "redaction left something the pre-upload check rejects:\n  template: $template",
                emptyList<String>(),
                Redactor.unredactedHints(template),
            )
        }
    }

    @Test
    fun `an identifier no other rule recognises is still masked`() {
        // A mandate id, and a reference hyphen-joined into a narration with no label beside it.
        assertTrue(Redactor.redact(RealMessages.axisApy).contains("APY/<NUM>/<NUM>"))
        assertTrue(Redactor.redact(RealMessages.npsDebit).contains("MUM-HDFCH<NUM>-NET BANKING"))
    }

    /** `HDFCH` prefixes every HDFC NEFT reference, so it's shape — and an anchor a rule can use. */
    @Test
    fun `masking a bare identifier keeps a constant prefix`() {
        assertEquals("MUM-HDFCH<NUM>-NET", Redactor.redact("MUM-HDFCH00842011992-NET"))
    }

    @Test
    fun `real phone numbers are still recognised as phone numbers`() {
        for (number in listOf("917036165000", "919951860002", "7308080808", "18002586161")) {
            assertEquals("<PHONE>", Redactor.redact(number))
        }
    }

    /** A 12-digit mandate id is not a phone number, and a template must not claim it is. */
    @Test
    fun `a long id that is not a phone number is masked as a number`() {
        assertEquals("<NUM>", Redactor.redact("500405010905"))
    }

    /** The regression itself: this is the template the console was given, minus its `Rs.`. */
    @Test
    fun `the balance keeps its currency token and spacing`() {
        assertTrue(Redactor.redact(RealMessages.tmbCredit).endsWith("Current AVBL bal is Rs.<BAL> - TMB"))
        assertTrue(Redactor.redact(RealMessages.npsDebit).endsWith("Avl bal:INR <BAL>"))
        assertTrue(Redactor.redact(RealMessages.federalUpi).contains("Bal Rs <BAL>. -Federal Bank"))
    }

    @Test
    fun `masked digits report how many digits were masked`() {
        assertEquals("A/c XX<D2>", Redactor.redact("A/c XX12"))
        assertEquals("A/c XX<D4>", Redactor.redact("A/c XX1234"))
        assertEquals("xx<D6>", Redactor.redact("xx123456"))
    }

    /** The whitespace between the mask and the digits is shape too: `XX 12345` is not `XX12345`. */
    @Test
    fun `masked digits keep the gap the bank left`() {
        assertEquals("XX <D5>", Redactor.redact("XX 12345"))
    }

    /** A rule's date group is usually `\S+`, so the console has to know the date has a space in it. */
    @Test
    fun `a spaced date is marked as one`() {
        assertEquals("<DATEW>", Redactor.redact("30 Sep 2026"))
        assertEquals("<DATE>", Redactor.redact("30-Sep-26"))
        assertEquals("<DATE>", Redactor.redact("01Aug26"))
    }

    /** `<D5>`/`<DATEW>` are still placeholders, so the upload check must not read them as leftovers. */
    @Test
    fun `the new placeholders do not trip the pre-upload check`() {
        assertEquals(emptyList<String>(), Redactor.unredactedHints("A/c XX<D5> on <DATEW> bal Rs.<BAL>"))
    }
}
