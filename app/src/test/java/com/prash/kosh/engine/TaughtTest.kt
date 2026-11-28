package com.prash.kosh.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Message formats taught by the owner: salary credits and OPD reimbursements. */
class TaughtTest {

    private val now = 1_795_000_000_000L
    private fun sms(sender: String, body: String) = RawInput(CaptureSource.SMS, sender, body, now)

    private val salarySms = sms("VM-HDFCBK-S",
        "Dear Customer, Your A/c XX4821 is credited with INR 85,000.00 on 30-11-2026 by NEFT-SAL-ACME TECHNOLOGIES PVT LTD. Avl Bal INR 1,02,345.67 -HDFC Bank")
    private val opdSms = sms("AX-UNIONB-S",
        "Rs.2,450.00 credited to your A/c XXXX1234 towards OPD Reimbursement claim no 55821 on 28-11-26. Bal Rs.12,000.00 - Union Bank of India")

    private val salary = TaughtPattern(1, "Salary", "HDFCBK", listOf("sal", "neft"), Direction.CREDIT, TxnType.INCOME, "income.salary", 0, null)
    private val opd = TaughtPattern(2, "OPD reimbursement", "UNIONB", listOf("opd", "reimbursement"), Direction.CREDIT, TxnType.INCOME,
        Taught.REIMBURSEMENT_CATEGORY, 0, 7)

    private fun engine(vararg p: TaughtPattern) = RecognitionEngine(RuleSet(taught = p.toList()))

    @Test fun salaryIsReadByTaughtPatternAndWaitsInCheck() {
        val r = (engine(salary).recognise(salarySms) as EngineResult.Recognised).recognition
        assertEquals(1L, r.taughtPatternId)
        assertEquals(8_500_000L, r.fields.amountPaise)
        assertEquals(TxnType.INCOME, r.type)
        assertEquals("income.salary", r.categoryId)
        assertEquals("Salary", r.merchant?.displayName)
        assertEquals("4821", r.fields.accountLast4)
        // Owner chose "Check first": even with a low auto-save threshold it is not counted.
        assertEquals(TxnStatus.PENDING_VERIFICATION, r.statusFor(50, 40))
        assertEquals(TxnStatus.PENDING_VERIFICATION, r.statusByConfidence)
    }

    @Test fun opdReimbursementUsesItsCategoryAndAccount() {
        val r = (engine(salary, opd).recognise(opdSms) as EngineResult.Recognised).recognition
        assertEquals(2L, r.taughtPatternId)
        assertEquals(245_000L, r.fields.amountPaise)
        assertEquals("income.reimbursement", r.categoryId)
        assertEquals(7L, r.taughtAccountId)
    }

    @Test fun amountIndexPicksTheRightNumber() {
        val r = (engine(salary.copy(amountIndex = 1)).recognise(salarySms) as EngineResult.Recognised).recognition
        assertEquals(10_234_567L, r.fields.amountPaise)
        assertEquals(listOf(8_500_000L, 10_234_567L), Taught.amounts(salarySms.body).map { it.paise })
    }

    @Test fun otherSenderOrMissingWordDoesNotMatch() {
        assertFalse(Taught.matches(salary, salarySms.copy(sender = "AX-UNIONB-S")))
        assertTrue(Taught.matches(salary.copy(sender = null), salarySms.copy(sender = "AX-UNIONB-S")))
        assertFalse(Taught.matches(salary, sms("VM-HDFCBK-S", "Your A/c XX4821 is credited with INR 500.00 by UPI. -HDFC Bank")))
        // "sal" must be a whole word, not part of "salon".
        assertFalse(Taught.matches(salary.copy(keywords = listOf("sal")), sms("VM-HDFCBK-S", "Rs 500 debited at SALON GLAM -HDFC")))
        val plain = engine(salary).recognise(opdSms)
        assertTrue(plain !is EngineResult.Recognised || (plain as EngineResult.Recognised).recognition.taughtPatternId == null)
    }

    @Test fun otpIsNeverReadEvenWithTaughtWords() {
        val otp = sms("VM-HDFCBK-S", "OTP 482913 for NEFT SAL transfer of Rs 85,000. Do not share with anyone. -HDFC Bank")
        val res = engine(salary.copy(keywords = listOf("sal"))).recognise(otp)
        assertTrue(res is EngineResult.Ignored)
        assertEquals(IgnoreReason.OTP, (res as EngineResult.Ignored).reason)
    }

    @Test fun mostSpecificPatternWins() {
        val broad = salary.copy(id = 9, keywords = listOf("neft"), categoryId = "income.other")
        assertEquals(1L, Taught.find(listOf(broad, salary), salarySms)?.id)
    }

    @Test fun teachingHelpers() {
        assertEquals("HDFCBK", Taught.senderCode("VM-HDFCBK-S"))
        assertEquals("HDFCBK", Taught.senderCode("hdfcbk"))
        val words = Taught.candidateWords(opdSms.body)
        assertTrue("opd" in words && "reimbursement" in words && "credited" in words)
        assertFalse("rs" in words || "bal" in words)
        assertEquals("income.reimbursement", Taught.suggestCategory(listOf("opd"), Direction.CREDIT))
        assertEquals("income.salary", Taught.suggestCategory(listOf("sal"), Direction.CREDIT))
        assertEquals(SeedData.FALLBACK_EXPENSE, Taught.suggestCategory(listOf("sal"), Direction.DEBIT))
        assertNull(Taught.recognise(salary, sms("VM-HDFCBK-S", "NEFT SAL processed")))
    }

    @Test fun reimbursementCategoryIsSeeded() {
        assertTrue(SeedData.categories.any { it.id == Taught.REIMBURSEMENT_CATEGORY && it.kind == SeedData.Kind.INCOME })
    }
}
