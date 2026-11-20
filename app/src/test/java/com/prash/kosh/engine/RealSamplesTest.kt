package com.prash.kosh.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Real messages from Prash's banks (Nov 2026). Keep adding to this as new formats show up. */
class RealSamplesTest {
    private val at = LocalDateTime.of(2026, 11, 19, 18, 0).atZone(TransactionParser.IST).toInstant().toEpochMilli()
    private val engine = RecognitionEngine()
    private fun rec(sender: String, body: String): Recognition {
        val r = engine.recognise(RawInput(CaptureSource.SMS, sender, body, at))
        assertTrue("Expected recognised, got $r", r is EngineResult.Recognised)
        return (r as EngineResult.Recognised).recognition
    }
    private fun day(m: Long) = java.time.Instant.ofEpochMilli(m).atZone(TransactionParser.IST).toLocalDate()

    @Test fun unionBankDebitToPerson() {
        val r = rec("AX-UNIONB", "Union Bank of India A/c*1131 Dr Rs3000.00 on 18-11-26 10:39 by MB 538434129626 Fv.GOURI, Bal Rs22162.60 Not U?Call 18002333/SMS BLOCK 1131 to 8879635472")
        assertEquals(300000L, r.fields.amountPaise)
        assertEquals(Direction.DEBIT, r.fields.direction)
        assertEquals("1131", r.fields.accountLast4)
        assertEquals("538434129626", r.fields.reference)
        assertEquals("GOURI", r.fields.merchantRaw)
        assertEquals("union_debit", r.fields.templateId)
        assertEquals(LocalDate.of(2026, 11, 18), day(r.txnDateMillis))
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals(90, r.confidence) // counted, flagged so you can pick a category for the payee
    }

    @Test fun unionBankCreditWithSafetyFooterIsNotAnOtp() {
        val r = rec("AX-UNIONB", "A/c *1131 Credited for Rs:20000.00 on 16-11-2026 21:51:10 by Mob Bk ref no 664460646081 Avl Bal Rs:45162.60.Never Share OTP/PIN/CVV-Union Bank of India")
        assertEquals(2000000L, r.fields.amountPaise)
        assertEquals(Direction.CREDIT, r.fields.direction)
        assertEquals("664460646081", r.fields.reference)
        assertEquals(LocalDate.of(2026, 11, 16), day(r.txnDateMillis))
        assertEquals(TxnType.INCOME, r.type)
        assertTrue(r.confidence >= 80)
    }

    @Test fun hdfcPaymentToCredIsCardBillTransfer() {
        val r = rec("VM-HDFCBK", "Sent Rs.26200.00\nFrom HDFC Bank A/C *1690\nTo CRED Club\nOn 18/11/26\nRef 668819657999\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808.")
        assertEquals(2620000L, r.fields.amountPaise)
        assertEquals(TxnType.TRANSFER, r.type)
        assertEquals("transfers.ccbill", r.categoryId)
        assertEquals(LocalDate.of(2026, 11, 18), day(r.txnDateMillis))
        assertEquals(100, r.confidence)
    }

    @Test fun realOtpStillIgnored() {
        val r = engine.recognise(RawInput(CaptureSource.SMS, "AX-UNIONB", "Your OTP is 482913 for txn of Rs 2,000.00. Never share OTP/PIN/CVV-Union Bank of India", at))
        assertEquals(EngineResult.Ignored(IgnoreReason.OTP), r)
    }
}

class RealSamplesNov19Test {
    private val at = LocalDateTime.of(2026, 11, 19, 19, 0).atZone(TransactionParser.IST).toInstant().toEpochMilli()
    private val engine = RecognitionEngine()
    private fun rec(sender: String, body: String): Recognition {
        val r = engine.recognise(RawInput(CaptureSource.SMS, sender, body, at))
        assertTrue("Expected recognised, got $r", r is EngineResult.Recognised)
        return (r as EngineResult.Recognised).recognition
    }
    private fun day(m: Long) = java.time.Instant.ofEpochMilli(m).atZone(TransactionParser.IST).toLocalDate()

    @Test fun hdfcUpiToPerson() {
        val r = rec("VM-HDFCBK", "Sent Rs.82.00\nFrom HDFC Bank A/C *1690\nTo RUPALI DATTATRAY CHAVAN\nOn 19/11/26\nRef 828348136803\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808")
        assertEquals(8200L, r.fields.amountPaise)
        assertEquals("Rupali Dattatray Chavan", r.merchant?.displayName)
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals(90, r.confidence)
    }

    @Test fun karnatakaBankNeftCredit() {
        val r = rec("VM-KBLBNK", "Your Account x015723x has been CREDITED for Rs.714.28/- NEFT-ABHICL  CLAIMS PAYMENTS/HDFC/HDFCH0136227610. Balance is Rs.12934.2. 29-10-2026 19:23:44 -Karnataka Bank")
        assertEquals(71428L, r.fields.amountPaise)
        assertEquals(Direction.CREDIT, r.fields.direction)
        assertEquals("5723", r.fields.accountLast4)
        assertEquals("HDFCH0136227610", r.fields.reference)
        assertEquals("Karnataka Bank", r.fields.bank)
        assertEquals("kbl_neft_credit", r.fields.templateId)
        assertEquals(PaymentMethod.NEFT, r.fields.method)
        assertEquals("abhicl", r.merchant?.key)
        assertEquals(LocalDate.of(2026, 10, 29), day(r.txnDateMillis))
        assertEquals(TxnType.INCOME, r.type)
        assertTrue(r.confidence >= 80)
    }

    @Test fun karnatakaBankUnknownHeaderStillKnownBySignature() {
        val r = rec("JD-KARBNK", "Your Account x015723x has been CREDITED for Rs.714.28/- NEFT-ABHICL  CLAIMS PAYMENTS/HDFC/HDFCH0136227610. Balance is Rs.12934.2. 29-10-2026 19:23:44 -Karnataka Bank")
        assertTrue(r.knownSender)
    }

    @Test fun hdfcCreditAlertFromMaskedVpa() {
        val r = rec("VM-HDFCBK", "Credit Alert!\nRs.529.00 credited to HDFC Bank A/c XX1690 on 19-11-26 from VPA *8221@ybl (UPI 030835982809)")
        assertEquals(52900L, r.fields.amountPaise)
        assertEquals("hdfc_credit", r.fields.templateId)
        assertEquals("030835982809", r.fields.reference)
        assertEquals("1690", r.fields.accountLast4)
        assertEquals("UPI *8221@ybl", r.merchant?.displayName)
        assertEquals(TxnType.INCOME, r.type)
        assertEquals(90, r.confidence)
    }

    @Test fun hsbcCardAtPharmacy() {
        val r = rec("VM-HSBCIN", "HSBC Credit Card xx8820 used at APOLLO PHARMACIES LTD for INR 891.95 on 18/11/26. Avl limit INR 127560.53; due INR 26439.47. Call +914065118002 to report fraud.( Online)")
        assertEquals(89195L, r.fields.amountPaise)
        assertEquals("8820", r.fields.accountLast4)
        assertEquals(PaymentMethod.CARD, r.fields.method)
        assertEquals("apollo", r.merchant?.key)
        assertEquals("health.pharmacy", r.categoryId)
        assertEquals(LocalDate.of(2026, 11, 18), day(r.txnDateMillis))
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals(100, r.confidence)
    }

    @Test fun hsbcCardOnAmazon() {
        val r = rec("VM-HSBCIN", "HSBC Credit Card xx8820 used at AMAZON PAY INDIA PRIVATET for INR 804.00 on 19/11/26. Avl limit INR 126756.53; due INR 27243.47. Call +914065118002 to report fraud.")
        assertEquals(80400L, r.fields.amountPaise)
        assertEquals("amazon", r.merchant?.key)
        assertEquals("shopping.online", r.categoryId)
        assertEquals(100, r.confidence)
    }
}
