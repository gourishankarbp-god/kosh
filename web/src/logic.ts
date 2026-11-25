import type { LocalDb, Row } from './db'
import { firstKeptMonth, istMonth } from './db'

export type Txn = Row & {
  type: string; direction: string; status: string; amountPaise: number; dateMillis: number; accountId: string | null; accountLast4: string | null
  merchantKey: string | null; merchantName: string | null; categoryId: string | null; method: string; reference: string | null; note: string | null
  confidence: number; sources: string; linkedTxnId: string | null; createdAt: number; updatedAt: number; recurringId: string | null; toAccountId: string | null
}
export type Account = Row & { name: string; kind: string; bank: string | null; last4: string | null; openingBalancePaise: number; archived: number }
export type Category = Row & { id: string; name: string; parentId: string | null; kind: string; icon: string; sortOrder: number; hidden: number; isFixed: number }

export const IST = 330 * 60_000
export const TYPES = ['EXPENSE', 'INCOME', 'TRANSFER', 'REFUND', 'INVESTMENT']
export const METHODS = ['UPI', 'CARD', 'NETBANKING', 'NEFT', 'IMPS', 'RTGS', 'ATM', 'CASH', 'OTHER']
export const REVIEW_STATUSES = ['PENDING_VERIFICATION', 'NEEDS_REVIEW', 'SUSPECTED_DUPLICATE']

// ---------------------------------------------------------------- money & dates
export function money(paise: number, opts: { paise?: boolean } = {}): string {
  const neg = paise < 0; const abs = Math.abs(paise)
  const rupees = Math.floor(abs / 100); const p = abs % 100
  const s = String(rupees); const last3 = s.slice(-3); const rest = s.slice(0, -3)
  const grouped = rest ? rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',') + ',' + last3 : last3
  return `${neg ? '−' : ''}₹${grouped}${opts.paise === false || p === 0 ? '' : '.' + String(p).padStart(2, '0')}`
}
export function compact(paise: number): string {
  const r = paise / 100; const a = Math.abs(r)
  const t = (v: number) => (Math.round(v * 10) / 10).toString()
  return (r < 0 ? '−' : '') + (a >= 1e7 ? `₹${t(a / 1e7)}Cr` : a >= 1e5 ? `₹${t(a / 1e5)}L` : a >= 1e3 ? `₹${t(a / 1e3)}K` : `₹${Math.round(a)}`)
}
export const parseRupees = (s: string): number | null => {
  const v = Number(s.replace(/[₹,\s]/g, '')); return isFinite(v) && v > 0 ? Math.round(v * 100) : null
}
export const istDate = (ms: number) => new Date(ms + IST).toISOString().slice(0, 10)
export const fromIstDate = (d: string, hour = 12) => Date.parse(d + 'T00:00:00Z') - IST + hour * 3600_000
export const fmtDay = (ms: number) => new Date(ms + IST).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
export const monthLabel = (ym: string) => new Date(ym + '-01T00:00:00Z').toLocaleDateString('en-IN', { month: 'long', year: 'numeric', timeZone: 'UTC' })
export const thisMonth = () => istMonth(Date.now())
export function addMonths(ym: string, n: number) { const [y, m] = ym.split('-').map(Number); return new Date(Date.UTC(y, m - 1 + n, 1)).toISOString().slice(0, 7) }
export const monthRange = (ym: string): [number, number] => [fromIstDate(ym + '-01', 0), fromIstDate(addMonths(ym, 1) + '-01', 0) - 1]
export const fyStart = (ym: string) => { const [y, m] = ym.split('-').map(Number); return m >= 4 ? y : y - 1 }
export const fyLabel = (y: number) => `FY ${y}-${String((y + 1) % 100).padStart(2, '0')}`
export const fyMonths = (y: number) => Array.from({ length: 12 }, (_, i) => addMonths(`${y}-04`, i))
export function greeting(d = new Date()) { const h = (d.getUTCHours() + 5 + Math.floor((d.getUTCMinutes() + 30) / 60)) % 24; return h < 5 ? 'Good night' : h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening' }

// ---------------------------------------------------------------- queries
export const txns = (db: LocalDb) => db.rows<Txn>('transactions')
export const counted = (t: Txn) => t.status === 'POSTED'
export const inMonth = (t: Txn, ym: string) => istMonth(t.dateMillis) === ym
export const categories = (db: LocalDb) => new Map(db.rows<Category>('categories').map(c => [c.id, c]))
// Loans and debit cards are stored as ordinary WALLET accounts, tagged in the `bank` column, so the phone app syncs them without any change:
//   loan:        bank = 'Lent' | 'Borrowed'  (balance = money still owed to you / by you)
//   debit card:  bank = 'debit:<uid of the linked bank account>'  (never holds money; spending is charged to the linked account)
export const DEBIT_PREFIX = 'debit:'
export const isLoanAcct = (a: Account) => a.kind === 'WALLET' && (a.bank === 'Lent' || a.bank === 'Borrowed')
export const isDebitCard = (a: Account) => a.kind === 'WALLET' && !!a.bank?.startsWith(DEBIT_PREFIX)
/** Real money accounts (bank, credit card, cash, wallet): no loan books, no debit cards. */
export const accounts = (db: LocalDb) => db.rows<Account>('accounts').filter(a => !a.archived && !isLoanAcct(a) && !isDebitCard(a)).sort((a, b) => a.name.localeCompare(b.name))
export const debitCards = (db: LocalDb) => db.rows<Account>('accounts').filter(a => !a.archived && isDebitCard(a)).sort((a, b) => a.name.localeCompare(b.name))
export const debitCardLink = (db: LocalDb, a: Account): Account | undefined => { const l = db.get<Account>('accounts', a.bank?.slice(DEBIT_PREFIX.length)); return l && !l.archived ? l : undefined }
/** Paying with a debit card is paying from the account it is linked to. Returns the account to charge, or null when the card has no usable link. */
export function resolveSpendAccount(db: LocalDb, uid: string | null | undefined): { accountId: string | null; accountLast4: string | null; card?: Account; linkMissing?: boolean } {
  const a = uid ? db.get<Account>('accounts', uid) : undefined
  if (!a) return { accountId: uid ?? null, accountLast4: null }
  if (!isDebitCard(a)) return { accountId: a.uid, accountLast4: a.last4 }
  const l = debitCardLink(db, a)
  return l ? { accountId: l.uid, accountLast4: l.last4, card: a } : { accountId: null, accountLast4: null, card: a, linkMissing: true }
}
export const cashAccount = (db: LocalDb) => db.rows<Account>('accounts').find(a => a.kind === 'CASH')

export function categoryPath(id: string | null, cats: Map<string, Category>) {
  if (!id) return 'Uncategorised'
  const c = cats.get(id); if (!c) return id
  const p = c.parentId ? cats.get(c.parentId) : undefined
  return p ? `${p.name} › ${c.name}` : c.name
}

/** Expense lines: a split transaction contributes its parts, others contribute themselves. */
export function expenseLines(db: LocalDb, list: Txn[]): { categoryId: string; amountPaise: number; t: Txn }[] {
  const splits = new Map<string, Row[]>()
  for (const s of db.rows('transaction_splits')) { const k = s.transactionId; if (!splits.has(k)) splits.set(k, []); splits.get(k)!.push(s) }
  return list.filter(t => t.type === 'EXPENSE' && counted(t)).flatMap(t =>
    splits.get(t.uid)?.map(s => ({ categoryId: s.categoryId as string, amountPaise: s.amountPaise as number, t })) ?? [{ categoryId: t.categoryId ?? 'other', amountPaise: t.amountPaise, t }])
}

export type Totals = { income: number; expense: number; refunds: number; invested: number; transfers: number; count: number }
export function totals(list: Txn[]): Totals {
  const c = list.filter(counted); const s = (ty: string) => c.filter(t => t.type === ty).reduce((a, t) => a + t.amountPaise, 0)
  return { income: s('INCOME'), expense: s('EXPENSE'), refunds: s('REFUND'), invested: s('INVESTMENT'), transfers: s('TRANSFER'), count: c.length }
}

/** Month totals, falling back to the archive index for months moved to Drive. */
export function monthTotals(db: LocalDb, ym: string): Totals & { archived: boolean } {
  if (ym < firstKeptMonth()) {
    const a = db.get('archive_index', ym)
    if (a) return { income: a.income, expense: a.expense, refunds: a.refunds, invested: a.invested, transfers: a.transfers, count: a.transactionCount, archived: true }
  }
  return { ...totals(txns(db).filter(t => inMonth(t, ym))), archived: false }
}

export function byTopCategory(db: LocalDb, ym: string): [string, number][] {
  const cats = categories(db)
  if (ym < firstKeptMonth()) { const a = db.get('archive_index', ym); if (a) return Object.entries<number>(JSON.parse(a.categoryTotalsJson || '{}')).sort((x, y) => y[1] - x[1]) }
  const m = new Map<string, number>()
  for (const l of expenseLines(db, txns(db).filter(t => inMonth(t, ym)))) {
    const top = cats.get(l.categoryId)?.parentId ?? l.categoryId.split('.')[0]
    m.set(top, (m.get(top) ?? 0) + l.amountPaise)
  }
  return [...m.entries()].sort((a, b) => b[1] - a[1])
}

// ---------------------------------------------------------------- balances (same rule as the phone)
export function flows(db: LocalDb) {
  const f = new Map<string, { in: number; out: number }>()
  const add = (a: string | null, i: number, o: number) => { if (!a) return; const x = f.get(a) ?? { in: 0, out: 0 }; x.in += i; x.out += o; f.set(a, x) }
  for (const t of txns(db)) if (counted(t)) {
    const credit = t.direction === 'CREDIT'
    add(t.accountId, credit ? t.amountPaise : 0, credit ? 0 : t.amountPaise)
    add(t.toAccountId, credit ? 0 : t.amountPaise, credit ? t.amountPaise : 0)
  }
  return f
}
export const balanceOf = (a: Account, f: Map<string, { in: number; out: number }>) => a.openingBalancePaise + (f.get(a.uid)?.in ?? 0) - (f.get(a.uid)?.out ?? 0)
export function moneySummary(db: LocalDb) {
  const f = flows(db); const acc = accounts(db)
  return {
    bank: acc.filter(a => a.kind === 'BANK' || a.kind === 'WALLET').reduce((s, a) => s + balanceOf(a, f), 0),
    cash: acc.filter(a => a.kind === 'CASH').reduce((s, a) => s + balanceOf(a, f), 0),
    cards: acc.filter(a => a.kind === 'CARD').reduce((s, a) => s - balanceOf(a, f), 0),
  }
}
export const accountLabel = (db: LocalDb, uid: string | null) => (uid && db.get<Account>('accounts', uid)?.name) || '—'

// ---------------------------------------------------------------- budgets / recurring / plans
export type BudgetRow = Row & { name: string; available: number; spent: number; pct: number; pending: number }
/** Budgets with spend so far. Only POSTED expenses count (cash entries and phone-captured SMS alike);
 *  `pending` is spend waiting in Check/Review that will count once approved. `exclude` leaves one transaction out (used while editing it). */
export function budgetStatus(db: LocalDb, ym: string, exclude?: string): BudgetRow[] {
  const cats = categories(db)
  const all = txns(db).filter(t => t.uid !== exclude)
  const lines = expenseLines(db, all.filter(t => inMonth(t, ym)))
  const pendingLines = all.filter(t => t.type === 'EXPENSE' && REVIEW_STATUSES.includes(t.status) && inMonth(t, ym)).map(t => ({ categoryId: t.categoryId ?? 'other', amountPaise: t.amountPaise }))
  const prevLines = expenseLines(db, all.filter(t => inMonth(t, addMonths(ym, -1))))
  const spentIn = (ls: { categoryId: string; amountPaise: number }[], cat: string) => ls.filter(l => l.categoryId === cat || cats.get(l.categoryId)?.parentId === cat).reduce((s, l) => s + l.amountPaise, 0)
  return db.rows('budgets').filter(b => b.startMonth <= ym).map(b => {
    const prevApplied = b.startMonth <= addMonths(ym, -1)
    const carry = b.rollover && prevApplied ? Math.max(0, b.monthlyLimitPaise - spentIn(prevLines, b.categoryId)) : 0
    const available = b.monthlyLimitPaise + carry
    const spent = spentIn(lines, b.categoryId)
    return { ...b, name: categoryPath(b.categoryId, cats), available, spent, pending: spentIn(pendingLines, b.categoryId), pct: available > 0 ? Math.round(spent * 100 / available) : 0 } as BudgetRow
  }).sort((a, b) => b.pct - a.pct)
}

/** What adding `lines` would do to every budget that covers them (the category itself or its parent), for the month of the transaction. */
export function budgetImpact(db: LocalDb, lines: { categoryId: string; amountPaise: number }[], ym: string, exclude?: string) {
  const cats = categories(db)
  return budgetStatus(db, ym, exclude).flatMap(b => {
    const add = lines.filter(l => l.categoryId === b.categoryId || cats.get(l.categoryId)?.parentId === b.categoryId).reduce((s, l) => s + l.amountPaise, 0)
    if (!add) return []
    const after = b.spent + add
    return [{ uid: b.uid as string, name: b.name, available: b.available, before: b.spent, after, add, pctAfter: b.available > 0 ? Math.round(after * 100 / b.available) : 0, left: b.available - after }]
  })
}
/** Percent used per budget this month, to notice when a synced SMS expense pushes one over a threshold. */
export const budgetPcts = (db: LocalDb) => new Map(budgetStatus(db, thisMonth()).map(b => [b.uid as string, { pct: b.pct, name: b.name }]))

const FREQ_MONTHS: Record<string, number> = { MONTHLY: 1, QUARTERLY: 3, HALF_YEARLY: 6, YEARLY: 12, ONE_TIME: 0 }
export const FREQUENCIES = Object.keys(FREQ_MONTHS)
export function addMonthsToDate(d: string, n: number) {
  const [y, m, day] = d.split('-').map(Number)
  const last = new Date(Date.UTC(y, m - 1 + n + 1, 0)).getUTCDate()
  return new Date(Date.UTC(y, m - 1 + n, Math.min(day, last))).toISOString().slice(0, 10)
}
export function nextDue(start: string, freq: string, onOrAfter: string): string | null {
  const step = FREQ_MONTHS[freq]; if (step === undefined) return null
  if (step === 0) return start >= onOrAfter ? start : null
  let n = 0, d = start
  while (d < onOrAfter && n < 2400) { n++; d = addMonthsToDate(start, n * step) }
  return d
}

export const PLAN_KINDS: Record<string, string> = { LIC: 'LIC policy', INSURANCE: 'Insurance', BOND: 'Bond', FD: 'Fixed deposit', RD: 'Recurring deposit' }
export function investmentHistory(db: LocalDb, uid: string) {
  return db.rows('investment_entries').filter(e => e.investmentId === uid).sort((a, b) => b.dateMillis - a.dateMillis)
}

// ---------------------------------------------------------------- import categorisation (merchant dictionary + keyword rules, synced from the phone)
const squash = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '')
export function categorise(db: LocalDb, text: string): { merchantKey: string | null; merchantName: string | null; categoryId: string | null; byMerchant: boolean } {
  const words = ' ' + text.toLowerCase().replace(/[^a-z0-9]+/g, ' ') + ' '
  const sq = squash(text)
  for (const m of db.rows('merchants')) {
    const aliases = String(m.aliases || '').split('|').filter(Boolean)
    for (const a of aliases) {
      const s = squash(a)
      const hit = s.length <= 4 ? words.includes(' ' + a.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim() + ' ') : sq.includes(s)
      if (hit) return { merchantKey: m.uid, merchantName: m.displayName, categoryId: m.categoryId, byMerchant: !!m.categoryId }
    }
  }
  for (const k of db.rows('keyword_rules').sort((a, b) => a.sortOrder - b.sortOrder)) {
    if (new RegExp(`\\b${k.keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'i').test(text)) return { merchantKey: null, merchantName: null, categoryId: k.categoryId, byMerchant: false }
  }
  return { merchantKey: null, merchantName: null, categoryId: null, byMerchant: false }
}

const INVEST = /\b(lic|insurance|premium|bond|bonds|retail direct|ncd|recurring deposit|rd installment|fd booked|fixed deposit)\b/i
const TRANSFER_CARD = /\b(credit card|cc bill|card bill|cred club|dreamplug)\b/i
const ATM = /\b(atm|cash withdrawal|atw|nwd|cash wdl)\b/i
const CASHDEP = /\b(cash deposit|cash dep|cdm)\b/i
const SELF = /\b(self transfer|own account|to self)\b/i
const SALARY = /\b(salary|sal cr|payroll)\b/i
const REFUND = /\b(refund|reversal|reversed|chargeback)\b/i
const INTEREST = /\b(int\.? pd|interest|int cr)\b/i

/** Type and category for an imported statement line. */
export function classify(db: LocalDb, narration: string, debit: boolean) {
  const c = categorise(db, narration)
  let type = debit ? 'EXPENSE' : 'INCOME'; let categoryId = c.categoryId
  if (ATM.test(narration) && debit) { type = 'TRANSFER'; categoryId = 'transfers.cash' }
  else if (CASHDEP.test(narration) && !debit) { type = 'TRANSFER'; categoryId = 'transfers.cashdep' }
  else if (TRANSFER_CARD.test(narration) || SELF.test(narration)) { type = 'TRANSFER'; categoryId = categoryId?.startsWith('transfers') ? categoryId : TRANSFER_CARD.test(narration) ? 'transfers.ccbill' : 'transfers.self' }
  else if (!debit && REFUND.test(narration)) { type = 'REFUND'; categoryId = 'income.refunds' }
  else if (debit && INVEST.test(narration)) { type = 'INVESTMENT'; categoryId = categoryId?.startsWith('investments') || categoryId?.startsWith('insurance') ? categoryId : /\b(lic|insurance|premium)\b/i.test(narration) ? 'investments.lic' : /\b(bond|bonds|retail direct|ncd)\b/i.test(narration) ? 'investments.bonds' : 'investments.deposits' }
  else if (!debit && SALARY.test(narration)) categoryId = 'income.salary'
  else if (!debit && INTEREST.test(narration)) categoryId = 'income.interest'
  const cats = categories(db)
  if (categoryId && !cats.has(categoryId)) categoryId = cats.has(categoryId.split('.')[0]) ? categoryId.split('.')[0] : null
  if (!categoryId) categoryId = type === 'INCOME' ? (cats.has('income.other') ? 'income.other' : 'income') : type === 'EXPENSE' ? 'other' : categoryId
  return { ...c, type, categoryId, confidence: c.byMerchant ? 96 : categoryId && categoryId !== 'other' ? 88 : 70 }
}

export function csvEscape(v: unknown) { const s = v == null ? '' : String(v); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s }
export function downloadText(name: string, text: string, mime = 'text/csv') {
  const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([text], { type: mime })); a.download = name; a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 5000)
}
