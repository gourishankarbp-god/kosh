import { describe, expect, it } from 'vitest'
import { LocalDb, MemoryPersist, istMonth, firstKeptMonth } from '../src/db'
import { MemoryStore } from '../src/remote'
import { deriveKey, encrypt, decrypt, b64, encryptJson, CHECK_TEXT } from '../src/crypto'
import { parseCsv, parseByBalance, clean } from '../src/statement'
import { importRows } from '../src/importer'
import { mask, validate } from '../src/ai'
import { money, compact, moneySummary, budgetStatus, budgetImpact, thisMonth, nextDue, fyLabel, fyStart } from '../src/logic'

async function space(store: MemoryStore, pass: string) {
  // What the phone writes when sync is first turned on.
  const salt = crypto.getRandomValues(new Uint8Array(16)); const key = await deriveKey(pass, salt)
  await store.upload('kosh-meta.json', new TextEncoder().encode(JSON.stringify({ format: 1, salt: b64(salt), iterations: 210000, check: b64(await encrypt(key, new TextEncoder().encode(CHECK_TEXT))) })))
  const now = Date.now()
  const changes = [
    { t: 'accounts', u: 'acct:cash', v: '0000000000001-0000-p1', r: { name: 'Cash', kind: 'CASH', bank: null, last4: null, openingBalancePaise: 50000, archived: 0 } },
    { t: 'accounts', u: 'acct:hdfc:4821:bank', v: '0000000000001-0001-p1', r: { name: 'HDFC ••4821', kind: 'BANK', bank: 'HDFC', last4: '4821', openingBalancePaise: 1000000, archived: 0 } },
    { t: 'categories', u: 'food', v: '0000000000001-0002-p1', r: { id: 'food', name: 'Food', parentId: null, kind: 'EXPENSE', icon: 'x', sortOrder: 1, hidden: 0, isFixed: 0 } },
    { t: 'categories', u: 'food.delivery', v: '0000000000001-0003-p1', r: { id: 'food.delivery', name: 'Delivery', parentId: 'food', kind: 'EXPENSE', icon: 'x', sortOrder: 2, hidden: 0, isFixed: 0 } },
    { t: 'categories', u: 'other', v: '0000000000001-0004-p1', r: { id: 'other', name: 'Other', parentId: null, kind: 'EXPENSE', icon: 'x', sortOrder: 3, hidden: 0, isFixed: 0 } },
    { t: 'categories', u: 'transfers', v: '0000000000001-0005-p1', r: { id: 'transfers', name: 'Transfers', parentId: null, kind: 'TRANSFER', icon: 'x', sortOrder: 4, hidden: 0, isFixed: 0 } },
    { t: 'categories', u: 'transfers.cash', v: '0000000000001-0006-p1', r: { id: 'transfers.cash', name: 'Cash withdrawal', parentId: 'transfers', kind: 'TRANSFER', icon: 'x', sortOrder: 5, hidden: 0, isFixed: 0 } },
    { t: 'merchants', u: 'swiggy', v: '0000000000001-0007-p1', r: { key: 'swiggy', displayName: 'Swiggy', categoryId: 'food.delivery', source: 'SEED', aliases: 'swiggy|bundl' } },
    { t: 'transactions', u: 't1', v: '0000000000002-0000-p1', r: { type: 'EXPENSE', direction: 'DEBIT', status: 'POSTED', amountPaise: 25000, dateMillis: now, accountId: 'acct:hdfc:4821:bank', accountLast4: '4821', merchantKey: 'swiggy', merchantName: 'Swiggy', categoryId: 'food.delivery', method: 'UPI', reference: '432112345678', note: null, confidence: 100, sources: 'SMS', linkedTxnId: null, createdAt: now, updatedAt: now, recurringId: null, toAccountId: null } },
  ]
  await store.upload('ops-p1-0000000001.kosh', await encryptJson(key, { format: 1, device: 'p1', seq: 1, createdAt: now, changes }))
}

describe('crypto', () => {
  it('round-trips and rejects a wrong key', async () => {
    const salt = new Uint8Array(16); const a = await deriveKey('one', salt); const b = await deriveKey('two', salt)
    const enc = await encrypt(a, new TextEncoder().encode('hello'))
    expect(new TextDecoder().decode(await decrypt(a, enc))).toBe('hello')
    await expect(decrypt(b, enc)).rejects.toThrow('Wrong sync passphrase')
  })
})

describe('sync between browsers and phone', () => {
  it('joins, merges newest-wins, deletes, and never echoes', async () => {
    const store = new MemoryStore(); await space(store, 'pw pw pw pw')
    const w1 = await LocalDb.open(new MemoryPersist()); const w2 = await LocalDb.open(new MemoryPersist())
    await expect(w1.connect(store, 'nope nope nope')).rejects.toThrow()
    expect(await w1.connect(store, 'pw pw pw pw')).toBe(9)
    await w2.connect(store, 'pw pw pw pw')
    expect(w1.outbox.size).toBe(0)
    w1.put('transactions', { note: 'first' }, 't1'); await w1.push(store)
    w2.put('transactions', { note: 'second' }, 't1'); await w2.push(store)
    await w1.pull(store); await w2.pull(store)
    expect(w1.get('transactions', 't1')!.note).toBe('second'); expect(w2.get('transactions', 't1')!.note).toBe('second')
    w2.remove('transactions', 't1'); await w2.push(store); await w1.pull(store)
    expect(w1.get('transactions', 't1')).toBeUndefined()
  })
  it('drops archived months locally but keeps their totals', async () => {
    const store = new MemoryStore(); await space(store, 'pw pw pw pw')
    const w = await LocalDb.open(new MemoryPersist()); await w.connect(store, 'pw pw pw pw')
    const old = Date.now() - 400 * 86400000; const month = istMonth(old)
    expect(month < firstKeptMonth()).toBe(true)
    w.put('transactions', { type: 'EXPENSE', direction: 'DEBIT', status: 'POSTED', amountPaise: 999, dateMillis: old, method: 'CASH', sources: 'MANUAL' })
    w.apply([{ t: 'archive_index', u: month, v: '9999999999999-0000-p1', r: { month, income: 0, expense: 999, refunds: 0, invested: 0, transfers: 0, recurring: 0, quickCommerce: 0, categoryTotalsJson: '{}', transactionCount: 1, archivedAt: 1 } }])
    expect(w.rows('transactions').some(t => t.amountPaise === 999)).toBe(false)
    expect(w.get('archive_index', month)!.expense).toBe(999)
  })
})

describe('statements', () => {
  it('reads debit/credit columns and narrations', () => {
    const rows = parseCsv('Bank statement\nDate,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance\n01/10/25,UPI-SWIGGY-swiggy@icici-ICIC0000001-527312345678-Payment,0000527312345678,01/10/25,250.00,,10000.00\n02/10/25,"NEFT CR-ACME-SALARY",N1,02/10/25,,"85,000.00",95000\n')
    expect(rows).toHaveLength(2); expect(rows[0].debit).toBe(true); expect(rows[1].amountPaise).toBe(8500000)
    expect(clean(rows[0].narration)).toEqual({ name: 'SWIGGY', ref: '527312345678' })
  })
  it('reads free-form PDF lines by balance change', () => {
    const r = parseByBalance(['Opening Balance 10,000.00', '05-11-2025 UPI/ZEPTO/123 499.00 9,501.00', 'groceries', '06-11-2025 SALARY CREDIT 50,000.00 59,501.00'])
    expect(r).toHaveLength(2); expect(r[0]).toMatchObject({ amountPaise: 49900, debit: true }); expect(r[1]).toMatchObject({ amountPaise: 5000000, debit: false })
  })
  it('imports with merge against SMS and categorises from the merchant list', async () => {
    const store = new MemoryStore(); await space(store, 'pw pw pw pw')
    const w = await LocalDb.open(new MemoryPersist()); await w.connect(store, 'pw pw pw pw')
    const d = new Date(Date.now() + 330 * 60000).toISOString().slice(0, 10).split('-').reverse().join('/')
    const rows = parseCsv(`Date,Narration,Ref,Withdrawal,Deposit\n${d},UPI-SWIGGY-x@icici-432112345678,1,250.00,\n${d},ATM WDL MG ROAD,2,2000.00,\n${d},UPI-BUNDL TECH-y@ybl-777712345678,3,180.00,\n`)
    const r = await importRows(w, rows, w.get('accounts', 'acct:hdfc:4821:bank') as any)
    expect(r).toMatchObject({ merged: 1, saved: 1 })
    const atm = w.rows('transactions').find(t => t.amountPaise === 200000)!
    expect(atm).toMatchObject({ type: 'TRANSFER', categoryId: 'transfers.cash', toAccountId: 'acct:cash' })
    expect(w.rows('transactions').find(t => t.amountPaise === 18000)).toMatchObject({ categoryId: 'food.delivery', status: 'POSTED' })
    const again = await importRows(w, rows, w.get('accounts', 'acct:hdfc:4821:bank') as any)
    expect(again.skipped).toBe(3)
  })
})

describe('money & plans', () => {
  it('formats Indian amounts', () => { expect(money(12345678)).toBe('₹1,23,456.78'); expect(compact(250000000)).toBe('₹25L') })
  it('computes balances with transfers', async () => {
    const store = new MemoryStore(); await space(store, 'pw pw pw pw')
    const w = await LocalDb.open(new MemoryPersist()); await w.connect(store, 'pw pw pw pw')
    w.put('transactions', { type: 'TRANSFER', direction: 'DEBIT', status: 'POSTED', amountPaise: 200000, dateMillis: Date.now(), accountId: 'acct:hdfc:4821:bank', toAccountId: 'acct:cash', categoryId: 'transfers.cash', method: 'ATM', sources: 'MANUAL' })
    expect(moneySummary(w)).toMatchObject({ bank: 1000000 - 25000 - 200000, cash: 50000 + 200000 })
    w.put('budgets', { categoryId: 'food', monthlyLimitPaise: 100000, rollover: 0, startMonth: thisMonth() })
    expect(budgetStatus(w, thisMonth())[0]).toMatchObject({ spent: 25000, pct: 25 })
  })
  it('debits the food budget live from cash and SMS expenses, and previews the impact', async () => {
    const store = new MemoryStore(); await space(store, 'pw pw pw pw')
    const w = await LocalDb.open(new MemoryPersist()); await w.connect(store, 'pw pw pw pw')
    w.put('budgets', { categoryId: 'food', monthlyLimitPaise: 1000000, rollover: 0, startMonth: thisMonth() })
    const cash = w.put('transactions', { type: 'EXPENSE', direction: 'DEBIT', status: 'POSTED', amountPaise: 300000, dateMillis: Date.now(), accountId: 'acct:cash', categoryId: 'food', method: 'CASH', sources: 'MANUAL' })
    expect(budgetStatus(w, thisMonth())[0]).toMatchObject({ spent: 325000, pct: 33 })
    // waiting in Review: not counted yet, shown as pending
    w.put('transactions', { type: 'EXPENSE', direction: 'DEBIT', status: 'NEEDS_REVIEW', amountPaise: 40000, dateMillis: Date.now(), accountId: 'acct:hdfc:4821:bank', categoryId: 'food', method: 'UPI', sources: 'SMS' })
    expect(budgetStatus(w, thisMonth())[0]).toMatchObject({ spent: 325000, pending: 40000 })
    // editing the cash entry must not count it twice
    expect(budgetImpact(w, [{ categoryId: 'food', amountPaise: 500000 }], thisMonth(), cash)[0]).toMatchObject({ before: 25000, after: 525000, pctAfter: 53 })
    expect(budgetImpact(w, [{ categoryId: 'other', amountPaise: 100 }], thisMonth())).toEqual([])
  })
  it('dates', () => { expect(nextDue('2026-01-31', 'MONTHLY', '2026-02-10')).toBe('2026-02-28'); expect(fyLabel(fyStart('2026-02'))).toBe('FY 2025-26') })
})

describe('AI safety', () => {
  it('masks identifiers and rejects invented amounts', () => {
    const m = mask('Rs 500 from A/c 123456789012 to ravi@okhdfcbank ph 9876543210 OTP 4321')
    expect(m).not.toMatch(/123456789012|ravi@|9876543210|4321/)
    const msgs = [{ uid: 'r1', body: 'charged INR 1,180.00 on 03-11-25', receivedAt: 1 }]
    const ok = validate({ rows: () => [] } as any, msgs, [{ i: 0, isTransaction: true, type: 'EXPENSE', amount: 1180 }, { i: 0, isTransaction: true, type: 'EXPENSE', amount: 9999 }])
    expect(ok).toHaveLength(1); expect(ok[0].confidence).toBeLessThan(80)
  })
})
