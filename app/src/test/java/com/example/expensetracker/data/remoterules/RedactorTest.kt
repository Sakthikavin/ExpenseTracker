package com.example.expensetracker.data.remoterules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun `masks amount account vpa ref date and balance`() {
        val body = "Rs.500.00 debited from A/c XX1234 to VPA merchant@ybl Ref 123456789012 on 29-09-26.\nAvl Bal Rs.15,342.50"

        val template = Redactor.redact(body)

        assertEquals(
            "Rs.<AMT> debited from A/c XX<D4> to VPA <VPA> Ref <REF> on <DATE>.\nAvl Bal Rs.<BAL>",
            template,
        )
        assertEquals(emptyList<String>(), Redactor.unredactedHints(template))
    }

    @Test
    fun `keeps the merchant name and the sender-routing words`() {
        val template = Redactor.redact("INR 249 spent on Card *5566 at SWIGGY on 01-Sep-26 at 19:00")

        assertTrue(template, template.contains("SWIGGY"))
        assertTrue(template, template.contains("Card *<D4>"))
        assertTrue(template, template.contains("<TIME>"))
    }

    @Test
    fun `masks a bank short link, code and all`() {
        val template = Redactor.redact(
            "TXN DECLINED: Rs.500 on 29-09-26 at 19:00 on HDFC Bank Debit Card xx1234. " +
                "Reason: Online set Limit Exceeded. Modify:https://1.hdfc.bank.in/HDFCBK/s/a/E0WMgeP0",
        )

        assertTrue(template, template.endsWith("Modify:<URL>"))
        assertEquals(emptyList<String>(), Redactor.unredactedHints(template))
    }

    @Test
    fun `masks a bare domain link with no scheme`() {
        val template = Redactor.redact("Not you? Visit 1.hdfc.bank.in/HDFCBK/s/a/E0WMgeP0 to block")

        assertTrue(template, template.contains("<URL>"))
        assertEquals(emptyList<String>(), Redactor.unredactedHints(template))
    }

    /** The spec's looser bare-domain pattern would swallow this; a letters-only TLD is what saves it. */
    @Test
    fun `leaves a decimal amount followed by a slash alone`() {
        val template = Redactor.redact("Avl Bal Rs.1234.56/- after txn")

        assertTrue(template, template.contains("<BAL>"))
        assertTrue(template, !template.contains("<URL>"))
    }

    /**
     * A voucher code is claimable, and an OTP or a PIN is worse. Neither carries a five-digit run,
     * so `<NUM>` and the pre-upload check both used to let them through.
     */
    @Test
    fun `masks a claimable code`() {
        assertEquals("Code: <CODE>", Redactor.redact("Code: 346QH2VK"))
        assertEquals("OTP is <CODE>", Redactor.redact("OTP is 4821"))
        assertEquals("Your PIN has been changed", Redactor.redact("Your PIN has been changed"))
    }

    @Test
    fun `flags a code left behind`() {
        assertEquals(listOf("a code"), Redactor.unredactedHints("Rs.<AMT> voucher. Code: 346QH2VK"))
        assertEquals(emptyList<String>(), Redactor.unredactedHints("Rs.<AMT> voucher. Code: <CODE>"))
    }

    /**
     * "WhatsApp BAL to 917036165000" is a helpline, not a balance. The published `axisbk_debit_v1`
     * captured that number as the balance because the template told it to.
     */
    @Test
    fun `a BAL to helpline is a phone number, not a balance`() {
        assertEquals("WhatsApp BAL to <PHONE>", Redactor.redact("WhatsApp BAL to 917036165000"))
        assertEquals("Avl Bal Rs.<BAL>", Redactor.redact("Avl Bal Rs.1,234.00"))
    }

    @Test
    fun `flags a raw link left behind`() {
        assertEquals(listOf("a link"), Redactor.unredactedHints("Blocked. Modify:https://1.hdfc.bank.in/s/a/E0WMgeP0"))
    }

    @Test
    fun `flags a stray digit run left behind`() {
        assertEquals(listOf("a long number"), Redactor.unredactedHints("Rs.<AMT> debited from A/c 123456789 to <VPA>"))
    }

    @Test
    fun `flags a stray handle left behind`() {
        assertEquals(listOf("an email or UPI handle"), Redactor.unredactedHints("paid to ravi@ybl on <DATE>"))
    }
}
