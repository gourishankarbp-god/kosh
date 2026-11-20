package com.prash.kosh.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementParserTest {
    @Test fun hdfcStyleDebitCreditColumns() {
        val csv = """
            HDFC BANK Ltd. Statement of account
            Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/10/25,UPI-SWIGGY-swiggy@icici-ICIC0000001-527312345678-Payment,0000527312345678,01/10/25,250.00,,10000.00
            02/10/25,"NEFT CR-ACME PVT LTD-SALARY OCT",N12345678,02/10/25,,"85,000.00","95,000.00"
        """.trimIndent()
        val rows = StatementParser.parseCsv(csv)
        assertEquals(2, rows.size)
        assertEquals(25_000L, rows[0].amountPaise); assertTrue(rows[0].debit)
        assertEquals(8_500_000L, rows[1].amountPaise); assertTrue(!rows[1].debit)
        assertTrue(StatementParser.toMessage(rows[0], "HDFC", "4821").startsWith("Rs.250.00 debited from HDFC A/c XX4821 on 01-10-25"))
    }

    @Test fun singleAmountWithDrCrFlag() {
        val csv = "Txn Date;Description;Amount;Dr/Cr;Balance\n05-Oct-2025;POS AMAZON;1,299.00;DR;5000\n06-Oct-2025;REFUND AMAZON;299.00;CR;5299\nTotal;;;;"
        val rows = StatementParser.parseCsv(csv)
        assertEquals(2, rows.size)
        assertTrue(rows[0].debit); assertTrue(!rows[1].debit); assertEquals(129_900L, rows[0].amountPaise)
    }
}
