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
            "Rs.<AMT> debited from A/c XX<D4> to VPA <VPA> Ref <REF> on <DATE>.\nAvl Bal <BAL>",
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
    fun `flags a stray digit run left behind`() {
        assertEquals(listOf("a long number"), Redactor.unredactedHints("Rs.<AMT> debited from A/c 123456789 to <VPA>"))
    }

    @Test
    fun `flags a stray handle left behind`() {
        assertEquals(listOf("an email or UPI handle"), Redactor.unredactedHints("paid to ravi@ybl on <DATE>"))
    }
}
