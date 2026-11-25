import type { LocalDb } from './db'
import { balanceOf, counted, flows, isLoanAcct, resolveSpendAccount, txns, type Account, type Txn } from './logic'

/**
 * Money lent to / borrowed from people.
 * Each loan is one WALLET account named "Lent · Ravi" / "Borrowed · Ravi" (tagged in `bank`). Giving, receiving back and
 * paying back are plain TRANSFERs between your account and that loan account, category "To people" — so balances stay right
 * and nothing is counted as income or spending. The phone app already understands all of this; it shows the loan as a wallet.
 * "Closing" a loan archives its account (manual, as you asked).
 */
export type LoanKind = 'LENT' | 'BORROWED'
/** principal = the original/extra money going out (lent) or coming in (borrowed); repayment = money coming back / being paid back. */
export type LoanStep = 'principal' | 'repayment'
export const LOAN_CATEGORY = 'transfers.people'
const label = (k: LoanKind) => (k === 'LENT' ? 'Lent' : 'Borrowed')

export type Loan = { uid: string; kind: LoanKind; person: string; note: string | null; principal: number; settled: number; pending: number; startMillis: number; closed: boolean; entries: Txn[] }

export function loanList(db: LocalDb): Loan[] {
  const f = flows(db); const all = txns(db).filter(t => t.status !== 'REJECTED')
  return db.rows<Account>('accounts').filter(isLoanAcct).map(a => {
    const kind: LoanKind = a.bank === 'Borrowed' ? 'BORROWED' : 'LENT'
    const entries = all.filter(t => t.accountId === a.uid || t.toAccountId === a.uid).sort((x, y) => x.dateMillis - y.dateMillis)
    const sum = (pred: (t: Txn) => boolean) => entries.filter(t => counted(t) && pred(t)).reduce((s, t) => s + t.amountPaise, 0)
    const into = sum(t => t.toAccountId === a.uid); const out = sum(t => t.accountId === a.uid)
    const principal = kind === 'LENT' ? into : out; const settled = kind === 'LENT' ? out : into
    const bal = balanceOf(a, f)
    return { uid: a.uid, kind, person: a.name.replace(/^(Lent|Borrowed) · /, ''), note: entries.map(t => t.note).find(n => n && !/^(Lent to|Borrowed from|Received back from|Paid back to) /.test(n)) ?? null, principal, settled,
      pending: kind === 'LENT' ? bal : -bal, startMillis: entries[0]?.dateMillis ?? 0, closed: !!a.archived, entries }
  }).sort((a, b) => b.startMillis - a.startMillis)
}

type Move = { amountPaise: number; dateMillis: number; accountId: string | null; method: string; note?: string | null }

/** Records one movement of money on a loan. `accountId` is your own account (bank, cash…) — a debit card resolves to its linked bank account. */
export function loanMove(db: LocalDb, loanUid: string, kind: LoanKind, person: string, step: LoanStep, m: Move): string {
  const mine = resolveSpendAccount(db, m.accountId)
  if (!mine.accountId) throw new Error('Choose the account the money moved through.')
  // Lent: giving goes into the loan, receiving back comes out. Borrowed: the reverse.
  const intoLoan = (kind === 'LENT') === (step === 'principal')
  const now = Date.now()
  const row = {
    type: 'TRANSFER', direction: 'DEBIT', status: 'POSTED', amountPaise: m.amountPaise, dateMillis: m.dateMillis,
    accountId: intoLoan ? mine.accountId : loanUid, toAccountId: intoLoan ? loanUid : mine.accountId, accountLast4: intoLoan ? mine.accountLast4 : null,
    categoryId: LOAN_CATEGORY, method: m.method, merchantName: person,
    note: m.note || (kind === 'LENT' ? (step === 'principal' ? `Lent to ${person}` : `Received back from ${person}`) : (step === 'principal' ? `Borrowed from ${person}` : `Paid back to ${person}`)),
    sources: 'MANUAL', confidence: 100, createdAt: now, updatedAt: now,
  }
  const id = db.put('transactions', row)
  db.put('audit_log', { transactionId: id, at: now, action: 'CREATED', before: null, after: JSON.stringify(row) })
  return id
}

export function createLoan(db: LocalDb, kind: LoanKind, person: string, m: Move): string {
  if (!person.trim()) throw new Error("Enter the person's name.")
  const uid = db.put('accounts', { name: `${label(kind)} · ${person.trim()}`, kind: 'WALLET', bank: label(kind), last4: null, openingBalancePaise: 0, archived: 0 })
  try { loanMove(db, uid, kind, person.trim(), 'principal', m) } catch (e) { db.remove('accounts', uid); throw e }
  return uid
}

export const closeLoan = (db: LocalDb, uid: string) => { db.put('accounts', { archived: 1 }, uid) }
export const reopenLoan = (db: LocalDb, uid: string) => { db.put('accounts', { archived: 0 }, uid) }
