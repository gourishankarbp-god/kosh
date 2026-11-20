package com.prash.kosh.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RecognitionEngineTest {

    private val engine = RecognitionEngine()
    // 12 Nov 2024, 10:30 IST
    private val received = LocalDateTime.of(2024, 11, 12, 10, 30).atZone(TransactionParser.IST).toInstant().toEpochMilli()

    private fun sms(sender: String, body: String) = RawInput(CaptureSource.SMS, sender, body, received)
    private fun notif(pkg: String, body: String) = RawInput(CaptureSource.NOTIFICATION, pkg, body, received)

    private fun rec(input: RawInput): Recognition {
        val r = engine.recognise(input)
        assertTrue("Expected recognised, got $r", r is EngineResult.Recognised)
        return (r as EngineResult.Recognised).recognition
    }

    private fun ignored(input: RawInput): IgnoreReason {
        val r = engine.recognise(input)
        assertTrue("Expected ignored, got $r", r is EngineResult.Ignored)
        return (r as EngineResult.Ignored).reason
    }

    private fun dateOf(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(TransactionParser.IST).toLocalDate()

    // ------------------------------------------------------------ bank templates

    @Test fun hdfcUpiSent() {
        val r = rec(sms("VM-HDFCBK-S", "Sent Rs.250.00\nFrom HDFC Bank A/C *4821\nTo SWIGGY\nOn 12/11/24\nRef 432112345678\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808"))
        assertEquals(25000L, r.fields.amountPaise)
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals("4821", r.fields.accountLast4)
        assertEquals("432112345678", r.fields.reference)
        assertEquals("swiggy", r.merchant?.key)
        assertEquals("food.delivery", r.categoryId)
        assertEquals(LocalDate.of(2024, 11, 12), dateOf(r.txnDateMillis))
        assertEquals(100, r.confidence)
        assertEquals(TxnStatus.POSTED, r.statusByConfidence)
    }

    @Test fun hdfcCardSpent() {
        val r = rec(sms("AD-HDFCBK", "Spent Rs.1,249 From HDFC Bank Card x7712 At AMAZON PAY INDIA On 2024-11-11:21:04:55 Bal Rs.45,000 Not You? Call 18002586161"))
        assertEquals(124900L, r.fields.amountPaise)
        assertEquals("7712", r.fields.accountLast4)
        assertEquals("amazon", r.merchant?.key)
        assertEquals("shopping.online", r.categoryId)
        assertEquals(LocalDate.of(2024, 11, 11), dateOf(r.txnDateMillis))
        assertEquals(PaymentMethod.CARD, r.fields.method)
    }

    @Test fun hdfcCredit() {
        val r = rec(sms("VM-HDFCBK", "Rs. 5000.00 credited to HDFC Bank A/c XX4821 on 10-11-24 by a/c linked to VPA rahul.k@okicici (UPI Ref No 431598765432)."))
        assertEquals(500000L, r.fields.amountPaise)
        assertEquals(TxnType.INCOME, r.type)
        assertEquals("431598765432", r.fields.reference)
        assertEquals(LocalDate.of(2024, 11, 10), dateOf(r.txnDateMillis))
    }

    @Test fun iciciUpiDebit() {
        val r = rec(sms("JM-ICICIT-S", "ICICI Bank Acct XX903 debited for Rs 450.00 on 12-Nov-24; ZOMATO credited. UPI:432298765432. Call 18002662 for dispute. SMS BLOCK 903 to 9215676766."))
        assertEquals(45000L, r.fields.amountPaise)
        assertEquals("zomato", r.merchant?.key)
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals("432298765432", r.fields.reference)
        assertEquals(100, r.confidence)
    }

    @Test fun iciciCardSpent() {
        val r = rec(sms("VK-ICICIB", "INR 3,499.00 spent using ICICI Bank Card XX1007 on 09-Nov-24 on DECATHLON SPORTS. Avl Limit: INR 1,52,301.00. If not you, call 1800 2662/SMS BLOCK 1007 to 9215676766"))
        assertEquals(349900L, r.fields.amountPaise)
        assertEquals("decathlon", r.merchant?.key)
        assertEquals("shopping.clothing", r.categoryId)
    }

    @Test fun sbiUpiDebitWithoutRupeeSymbol() {
        val r = rec(sms("BZ-SBIUPI", "Dear UPI user A/C X3310 debited by 120.0 on date 12Nov24 trf to RAPIDO Refno 432255551111. If not u? call 1800111109. -SBI"))
        assertEquals(12000L, r.fields.amountPaise)
        assertEquals("rapido", r.merchant?.key)
        assertEquals("transport.cabs", r.categoryId)
        assertEquals("432255551111", r.fields.reference)
    }

    @Test fun sbiCredit() {
        val r = rec(sms("VM-SBIINB", "Dear SBI User, your A/c X3310-credited by Rs.2,000 on 11Nov24 transfer from PRIYA S Ref No 431812341234 -SBI"))
        assertEquals(200000L, r.fields.amountPaise)
        assertEquals(TxnType.INCOME, r.type)
        assertEquals(LocalDate.of(2024, 11, 11), dateOf(r.txnDateMillis))
    }

    @Test fun axisUpiDebit() {
        val r = rec(sms("AX-AXISBK", "INR 899.00 debited\nA/c no. XX5521\n12-11-24, 09:14:22\nUPI/P2M/432233334444/BLINKIT\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank"))
        assertEquals(89900L, r.fields.amountPaise)
        assertEquals("blinkit", r.merchant?.key)
        assertEquals("groceries.quick", r.categoryId)
    }

    @Test fun kotakSent() {
        val r = rec(sms("VM-KOTAKB", "Sent Rs.640.00 from Kotak Bank AC X8890 to bescom@ybl on 12-11-24.UPI Ref 432277778888. Not you, https://kotak.com/KBANKT/Fraud"))
        assertEquals(64000L, r.fields.amountPaise)
        assertEquals("bescom", r.merchant?.key)
        assertEquals("utilities.electricity", r.categoryId)
    }

    // ------------------------------------------------------------ generic parser

    @Test fun genericUnknownBankDebit() {
        val r = rec(sms("VM-FEDBNK", "Rs 1,500.00 debited from your A/c XX2201 on 12-11-2024 towards Sri Lakshmi Medicals. UPI Ref 432200001111. Avl Bal Rs 22,340.10"))
        assertEquals(150000L, r.fields.amountPaise)
        assertEquals(TxnType.EXPENSE, r.type)
        assertEquals("2201", r.fields.accountLast4)
        assertEquals("health.pharmacy", r.categoryId) // keyword rule "medicals"
    }

    @Test fun balanceIsNotMistakenForAmount() {
        assertEquals(50000L, TransactionParser.findAmount("Avl Bal Rs 22,340.10. Rs 500.00 debited from A/c XX1234"))
    }

    @Test fun genericDateFormats() {
        assertEquals(LocalDate.of(2024, 11, 5), dateOf(TransactionParser.parseDate("on 05/11/2024", received)!!))
        assertEquals(LocalDate.of(2024, 11, 5), dateOf(TransactionParser.parseDate("on 05-Nov-2024", received)!!))
        assertEquals(LocalDate.of(2024, 11, 5), dateOf(TransactionParser.parseDate("on 05Nov24", received)!!))
        assertEquals(LocalDate.of(2024, 11, 5), dateOf(TransactionParser.parseDate("2024-11-05", received)!!))
        assertNull(TransactionParser.parseDate("on 05-11-2031", received)) // future
    }

    // ------------------------------------------------------------ notifications

    @Test fun gpayPaidNotification() {
        val r = rec(notif("com.google.android.apps.nbu.paisa.user", "₹250 paid to Swiggy"))
        assertEquals(25000L, r.fields.amountPaise)
        assertEquals("swiggy", r.merchant?.key)
        // template 40 + amount 20 + direction 10 + merchant 10 + dictionary 15 + known app 5
        assertEquals(100, r.confidence)
    }

    @Test fun phonepeReceived() {
        val r = rec(notif("com.phonepe.app", "Received ₹1,000 from Anil Kumar"))
        assertEquals(100000L, r.fields.amountPaise)
        assertEquals(TxnType.INCOME, r.type)
    }

    @Test fun untrustedAppIgnored() {
        assertEquals(IgnoreReason.UNTRUSTED_APP, ignored(notif("com.whatsapp", "Paid ₹500 to Ravi")))
    }

    // ------------------------------------------------------------ filters

    @Test fun otpIgnored() {
        assertEquals(IgnoreReason.OTP, ignored(sms("VM-HDFCBK", "OTP is 482913 for txn of Rs 2,000.00 at AMAZON on HDFC Bank card ending 7712. Valid till 10:41. Do not share OTP for security reasons.")))
    }

    @Test fun promoIgnored() {
        assertEquals(IgnoreReason.PROMOTIONAL, ignored(sms("VM-ICICIB", "Congratulations! You are pre-approved for a Personal Loan of Rs 5,00,000. Apply now: icici.co/pl")))
    }

    @Test fun failedIgnored() {
        assertEquals(IgnoreReason.FAILED, ignored(sms("VM-HDFCBK", "Your UPI txn of Rs 300.00 to SWIGGY has failed. Amount if debited will be reversed.")))
    }

    @Test fun collectRequestIgnored() {
        assertEquals(IgnoreReason.COLLECT_REQUEST, ignored(sms("VM-SBIUPI", "Ramesh has requested money from you on Google Pay. On approving the request, Rs 500 will be debited from your account.")))
    }

    @Test fun dueReminderIgnored() {
        assertEquals(IgnoreReason.REMINDER, ignored(sms("VM-HDFCBK", "Your HDFC Bank Credit Card XX7712 statement is generated. Total Amount Due Rs 12,450.00. Minimum amount due Rs 620.00. Pay by 28-Nov-24.")))
    }

    // ------------------------------------------------------------ type rules

    @Test fun sipIsInvestmentNotExpense() {
        val r = rec(sms("VM-HDFCBK", "Rs 5,000.00 debited from A/c XX4821 on 05-11-24 towards NACH-MF ICCL SIP. Avl Bal Rs 80,112.00"))
        assertEquals(TxnType.INVESTMENT, r.type)
        assertTrue(r.categoryId!!.startsWith("investments"))
    }

    @Test fun licPremiumIsInvestment() {
        val r = rec(sms("VM-SBIINB", "Your A/c X3310 is debited for Rs.12,600.00 on 02Nov24 towards LIC OF INDIA premium. -SBI"))
        assertEquals(TxnType.INVESTMENT, r.type)
        assertEquals("investments.lic", r.categoryId)
    }

    @Test fun creditCardBillIsTransfer() {
        val r = rec(sms("VM-HDFCBK", "Payment of Rs 12,450.00 has been received towards your HDFC Bank Credit Card XX7712 on 10-11-24. Thank you."))
        assertEquals(TxnType.TRANSFER, r.type)
        assertEquals("transfers.ccbill", r.categoryId)
    }

    @Test fun credPaymentIsTransfer() {
        val r = rec(notif("com.dreamplug.androidapp", "Paid ₹12,450 to CRED Club"))
        assertEquals(TxnType.TRANSFER, r.type)
    }

    @Test fun atmWithdrawalIsTransfer() {
        val r = rec(sms("VM-SBIINB", "Rs 2,000 withdrawn at ATM S1BW000123 from A/c X3310 on 11Nov24. Avl Bal Rs 18,000. -SBI"))
        assertEquals(TxnType.TRANSFER, r.type)
        assertEquals("transfers.cash", r.categoryId)
    }

    @Test fun refundRecognised() {
        val r = rec(sms("VM-HDFCBK", "Rs 1,249.00 refund from AMAZON has been credited to your HDFC Bank Card x7712 on 12-11-24."))
        assertEquals(TxnType.REFUND, r.type)
        assertEquals("income.refunds", r.categoryId)
        assertEquals("amazon", r.merchant?.key)
    }

    @Test fun salaryIsIncome() {
        val r = rec(sms("VM-HDFCBK", "Rs 85,000.00 credited to A/c XX4821 on 01-11-24 Info: NEFT-SALARY ACME CORP. Avl Bal Rs 1,02,000.00"))
        assertEquals(TxnType.INCOME, r.type)
        assertEquals("income.salary", r.categoryId)
    }

    // ------------------------------------------------------------ learning

    @Test fun userRuleBeatsDictionary() {
        val custom = RecognitionEngine(RuleSet(userMerchantRules = mapOf("amazon" to "groceries.supermarket")))
        val r = (custom.recognise(sms("AD-HDFCBK", "Spent Rs.500 From HDFC Bank Card x7712 At AMAZON On 2024-11-11:21:04:55")) as EngineResult.Recognised).recognition
        assertEquals("groceries.supermarket", r.categoryId)
        assertEquals(CategorySource.USER_RULE, r.categorySource)
    }

    @Test fun userRuleOnUnknownMerchant() {
        val custom = RecognitionEngine(RuleSet(userMerchantRules = mapOf("sri lakshmi medicals" to "health.pharmacy")))
        val r = (custom.recognise(sms("VM-FEDBNK", "Rs 150.00 debited from your A/c XX2201 on 12-11-2024 towards Sri Lakshmi Medicals. UPI Ref 432200001111.")) as EngineResult.Recognised).recognition
        assertEquals("health.pharmacy", r.categoryId)
    }

    // ------------------------------------------------------------ merchants

    @Test fun merchantNormalisation() {
        val n = MerchantNormalizer(SeedData.merchants)
        assertEquals("swiggy", n.normalise("swiggy.stores@axb")?.key)
        assertEquals("swiggy", n.normalise("UPI-SWIGGY-1234")?.key)
        assertEquals("instamart", n.normalise("SWIGGY INSTAMART")?.key)
        assertEquals("dmart", n.normalise("AVENUE SUPERMARTS LTD")?.key)
        assertEquals("vi", n.normalise("VI POSTPAID")?.key)
        assertTrue(n.normalise("VIJAY STORES")?.key != "vi")
        assertTrue(n.normalise("CREDIT ACCESS GRAMEEN")?.key != "cred")
        assertNotNull(n.normalise("Sri Lakshmi Medicals"))
    }

    // ------------------------------------------------------------ money

    @Test fun moneyFormatting() {
        assertEquals("₹1,23,456.70", Money.format(12345670))
        assertEquals("₹999.00", Money.format(99900))
        assertEquals("₹1.2L", Money.compact(12000000))
        assertEquals(1234567L, Money.parseToPaise("12,345.67"))
        assertEquals(50L, Money.parseToPaise("0.5"))
        assertNull(Money.parseToPaise("0"))
        assertEquals(25, Money.savingsRatePercent(100000, 75000))
        assertNull(Money.savingsRatePercent(0, 100))
    }
}
