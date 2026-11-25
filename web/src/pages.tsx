import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useApp, useDb, go } from './app'
import type { LocalDb, Row } from './db'
import { firstKeptMonth, istMonth } from './db'
import {
  accounts, accountLabel, addMonths, balanceOf, budgetImpact, budgetStatus, byTopCategory, cashAccount, categories, categoryPath, compact, counted, csvEscape,
  downloadText, expenseLines, flows, fmtDay, fromIstDate, fyLabel, fyMonths, fyStart, greeting, inMonth, investmentHistory, istDate, METHODS, money,
  monthLabel, monthRange, monthTotals, moneySummary, nextDue, parseRupees, PLAN_KINDS, REVIEW_STATUSES, thisMonth, totals, txns, TYPES, FREQUENCIES,
  debitCards, debitCardLink, isDebitCard, resolveSpendAccount,
  type Account, type Category, type Txn,
} from './logic'
import { loanList, createLoan, loanMove, closeLoan, reopenLoan, type Loan, type LoanKind, type LoanStep } from './loans'
import { readStatement, type StmtRow } from './statement'
import { importRows, readScreenshot } from './importer'
import * as AI from './ai'
import { toast } from './toast'

// ====================================================================== building blocks
export const Card = ({ title, children, actions }: { title?: ReactNode; children: ReactNode; actions?: ReactNode }) => (
  <section className="card">{(title || actions) && <header><h3>{title}</h3><div className="actions">{actions}</div></header>}{children}</section>
)
const Stat = ({ label, value, tone }: { label: string; value: string; tone?: string }) => <div className={'stat ' + (tone ?? '')}><span>{label}</span><b>{value}</b></div>
const Page = ({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) => (
  <div className="page"><div className="page-head"><h1>{title}</h1><div className="actions">{actions}</div></div>{children}</div>
)
function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => { const f = (e: KeyboardEvent) => e.key === 'Escape' && onClose(); addEventListener('keydown', f); return () => removeEventListener('keydown', f) }, [onClose])
  return <div className="modal-bg" onMouseDown={e => e.target === e.currentTarget && onClose()}><div className={'modal' + (wide ? ' wide' : '')}><header><h3>{title}</h3><button className="link" onClick={onClose}>✕</button></header>{children}</div></div>
}
const Field = ({ label, children }: { label: string; children: ReactNode }) => <label className="field"><span>{label}</span>{children}</label>
const Bar = ({ pct, warn, ok }: { pct: number; warn?: boolean; ok?: boolean }) => <div className="bar"><i style={{ width: Math.min(100, pct) + '%' }} className={ok ? '' : warn || pct >= 100 ? 'warn' : pct >= 80 ? 'amber' : ''} /></div>

/** Shown while adding/editing an expense: how it moves the matching budget(s). */
function BudgetImpact({ lines, dateMillis, exclude, counts }: { lines: { categoryId: string; amountPaise: number }[]; dateMillis: number; exclude?: string; counts: boolean }) {
  const db = useDb()
  const rows = budgetImpact(db, lines.filter(l => l.amountPaise > 0), istMonth(dateMillis), exclude)
  if (!rows.length) return null
  return <div className="impact">{rows.map(r => <div key={r.uid} className="impact-row">
    <div className="kv"><b>{r.name} budget</b><span>{money(r.before, { paise: false })} → <b className={r.pctAfter >= 100 ? 'err' : ''}>{money(r.after, { paise: false })}</b> of {money(r.available, { paise: false })}</span></div>
    <Bar pct={r.pctAfter} />
    <small className={r.pctAfter >= 100 ? 'err' : 'muted'}>{r.pctAfter >= 100 ? `Over by ${money(-r.left)}` : `${money(r.left)} left after this`}{!counts && ' · counts once you approve it'}</small>
  </div>)}</div>
}
const Empty = ({ children }: { children: ReactNode }) => <p className="empty">{children}</p>

function MonthSwitch({ ym, set }: { ym: string; set: (s: string) => void }) {
  return <div className="month"><button onClick={() => set(addMonths(ym, -1))}>‹</button><b>{monthLabel(ym)}</b><button disabled={ym >= thisMonth()} onClick={() => set(addMonths(ym, 1))}>›</button></div>
}

function CategorySelect({ value, onChange, kinds, allowEmpty }: { value: string | null; onChange: (v: string | null) => void; kinds?: string[]; allowEmpty?: boolean }) {
  const db = useDb()
  const all = db.rows<Category>('categories').filter(c => !c.hidden && (!kinds || kinds.includes(c.kind))).sort((a, b) => a.sortOrder - b.sortOrder)
  const tops = all.filter(c => !c.parentId)
  return <select value={value ?? ''} onChange={e => onChange(e.target.value || null)}>
    {allowEmpty !== false && <option value="">— any / none —</option>}
    {tops.map(t => <optgroup key={t.id} label={t.name}><option value={t.id}>{t.name}</option>{all.filter(c => c.parentId === t.id).map(c => <option key={c.id} value={c.id}>{t.name} › {c.name}</option>)}</optgroup>)}
  </select>
}
function AccountSelect({ value, onChange, empty = 'None', kinds, cards }: { value: string | null; onChange: (v: string | null) => void; empty?: string; kinds?: string[]; cards?: boolean }) {
  const db = useDb()
  return <select value={value ?? ''} onChange={e => onChange(e.target.value || null)}><option value="">{empty}</option>
    {accounts(db).filter(a => !kinds || kinds.includes(a.kind)).map(a => <option key={a.uid} value={a.uid}>{a.name}</option>)}
    {cards && debitCards(db).length > 0 && <optgroup label="Debit cards (charged to the linked bank account)">{debitCards(db).map(a => <option key={a.uid} value={a.uid}>{a.name}</option>)}</optgroup>}</select>
}

const typeLabel = (t: string) => ({ EXPENSE: 'Expense', INCOME: 'Income', TRANSFER: 'Transfer', REFUND: 'Refund', INVESTMENT: 'Investment' } as any)[t] ?? t
const statusLabel = (s: string) => ({ POSTED: 'Saved', PENDING_VERIFICATION: 'Check', NEEDS_REVIEW: 'Review', SUSPECTED_DUPLICATE: 'Duplicate?', REJECTED: 'Rejected' } as any)[s] ?? s
const sign = (t: Txn) => (t.type === 'INCOME' || t.type === 'REFUND' ? '+' : t.type === 'TRANSFER' ? '' : '−')

function TxnTable({ list, limit = 300 }: { list: Txn[]; limit?: number }) {
  const db = useDb(); const cats = categories(db)
  const [open, setOpen] = useState<string | null>(null)
  if (!list.length) return <Empty>No transactions.</Empty>
  return <>
    <table className="tbl"><thead><tr><th>Date</th><th>Merchant / note</th><th>Category</th><th>Account</th><th>Status</th><th className="r">Amount</th></tr></thead>
      <tbody>{list.slice(0, limit).map(t => <tr key={t.uid} onClick={() => setOpen(t.uid)} className="click">
        <td>{fmtDay(t.dateMillis)}</td><td>{t.merchantName ?? t.note ?? typeLabel(t.type)}{t.sources.includes('IMPORT') && <small className="tag">statement</small>}</td>
        <td>{categoryPath(t.categoryId, cats)}</td><td>{accountLabel(db, t.accountId)}{t.toAccountId && <> → {accountLabel(db, t.toAccountId)}</>}</td>
        <td><span className={'pill ' + t.status}>{statusLabel(t.status)}</span></td>
        <td className={'r amt ' + t.type}>{sign(t)}{money(t.amountPaise)}</td></tr>)}</tbody></table>
    {list.length > limit && <p className="muted">Showing {limit} of {list.length}. Narrow the filters to see more.</p>}
    {open && <TxnEditor uid={open} onClose={() => setOpen(null)} />}
  </>
}

// ====================================================================== transaction editor
export function TxnEditor({ uid, initial, onClose }: { uid?: string; initial?: Partial<Txn>; onClose: () => void }) {
  const db = useDb()
  const orig = uid ? db.get<Txn>('transactions', uid) : undefined
  const [t, setT] = useState<Partial<Txn>>(orig ?? { type: 'EXPENSE', direction: 'DEBIT', status: 'POSTED', method: 'UPI', dateMillis: fromIstDate(istDate(Date.now())), ...initial })
  const [amt, setAmt] = useState(t.amountPaise ? String(t.amountPaise / 100) : '')
  const existingSplits = uid ? db.rows('transaction_splits').filter(s => s.transactionId === uid) : []
  const [splits, setSplits] = useState<{ categoryId: string | null; amt: string; uid?: string }[]>(existingSplits.map(s => ({ categoryId: s.categoryId, amt: String(s.amountPaise / 100), uid: s.uid })))
  const [learn, setLearn] = useState(false)
  const [err, setErr] = useState('')
  const [person, setPerson] = useState('')
  const set = (p: Partial<Txn>) => setT(x => ({ ...x, ...p }))
  // "Lent" / "Borrowed" are choices in the Type list for new entries; they are saved as a tracked loan, not as a transaction type.
  const loanMode = !uid && (t.type === 'LENT' || t.type === 'BORROWED')
  const picked = t.accountId ? db.get<Account>('accounts', t.accountId) : undefined
  const cardLink = picked && isDebitCard(picked) ? debitCardLink(db, picked) : undefined
  const amountPaise = parseRupees(amt) ?? 0
  const splitSum = splits.reduce((s, x) => s + (parseRupees(x.amt) ?? 0), 0)

  const save = (status?: string) => {
    if (!amountPaise) return setErr('Enter an amount.')
    if (splits.length && splitSum !== amountPaise) return setErr(`Split parts add up to ${money(splitSum)}, not ${money(amountPaise)}.`)
    if (loanMode) {
      if (!person.trim()) return setErr("Enter the person's name.")
      if (!t.accountId) return setErr(t.type === 'LENT' ? 'Choose the account you paid from.' : 'Choose the account the money came into.')
      try { createLoan(db, t.type as LoanKind, person, { amountPaise, dateMillis: t.dateMillis!, accountId: t.accountId, method: t.method ?? 'UPI', note: t.note }) } catch (e: any) { return setErr(e.message) }
      toast(t.type === 'LENT' ? `Lent ${money(amountPaise, { paise: false })} to ${person.trim()} — track it under Plan › Loans` : `Borrowed ${money(amountPaise, { paise: false })} from ${person.trim()} — track it under Plan › Loans`)
      return onClose()
    }
    // Paying with a debit card means paying from the bank account it is linked to.
    const spend = t.type === 'TRANSFER' ? undefined : resolveSpendAccount(db, t.accountId)
    if (spend?.linkMissing) return setErr('This debit card is not linked to a bank account any more. Edit the card under Money › Accounts.')
    const now = Date.now()
    const direction = t.type === 'INCOME' || t.type === 'REFUND' ? 'CREDIT' : t.type === 'TRANSFER' ? (t.direction ?? 'DEBIT') : 'DEBIT'
    const row = { ...t, ...(spend?.card ? { accountId: spend.accountId, accountLast4: spend.accountLast4 } : {}), amountPaise, direction, status: status ?? t.status ?? 'POSTED', updatedAt: now, createdAt: t.createdAt ?? now, sources: t.sources ?? 'MANUAL', confidence: t.confidence ?? 100 }
    const id = db.put('transactions', row, uid)
    const mainLines = splits.length ? splits.map(s => ({ categoryId: s.categoryId ?? 'other', amountPaise: parseRupees(s.amt) ?? 0 })) : [{ categoryId: row.categoryId ?? 'other', amountPaise }]
    if (row.type === 'EXPENSE' && row.status === 'POSTED') {
      // The expense is now saved, so this is the budget position after it (it is already counted).
      const cats = categories(db)
      budgetStatus(db, istMonth(row.dateMillis!)).filter(b => mainLines.some(l => l.categoryId === b.categoryId || cats.get(l.categoryId)?.parentId === b.categoryId)).forEach(b => {
        if (b.pct >= 100) toast(`${b.name}: over budget by ${money(b.spent - b.available, { paise: false })}`, 'bad')
        else toast(`${b.name}: ${money(b.available - b.spent, { paise: false })} left (${b.pct}% used)`, b.pct >= 80 ? 'warn' : 'ok')
      })
    }
    db.put('audit_log', { transactionId: id, at: now, action: uid ? (status && status !== orig?.status ? 'STATUS_' + status : 'EDITED') : 'CREATED', before: orig ? JSON.stringify(orig) : null, after: JSON.stringify(row) })
    existingSplits.filter(s => !splits.some(x => x.uid === s.uid)).forEach(s => db.remove('transaction_splits', s.uid))
    splits.forEach(s => db.put('transaction_splits', { transactionId: id, categoryId: s.categoryId ?? 'other', amountPaise: parseRupees(s.amt) ?? 0, note: null }, s.uid))
    if (learn && t.merchantKey && t.categoryId) db.put('merchants', { categoryId: t.categoryId, source: 'USER' }, t.merchantKey)
    onClose()
  }
  const del = () => {
    if (!uid || !confirm('Delete this transaction?')) return
    existingSplits.forEach(s => db.remove('transaction_splits', s.uid))
    db.put('audit_log', { transactionId: uid, at: Date.now(), action: 'DELETED', before: JSON.stringify(orig), after: null })
    db.remove('transactions', uid); onClose()
  }
  const raws = uid ? db.rows('raw_messages').filter(r => r.transactionId === uid) : []
  return <Modal title={uid ? 'Edit transaction' : 'Add transaction'} onClose={onClose} wide>
    <div className="grid2">
      <Field label="Type"><select value={t.type} onChange={e => set({ type: e.target.value })}>{TYPES.map(x => <option key={x} value={x}>{typeLabel(x)}</option>)}{!uid && <><option value="LENT">Lent (I gave money to someone)</option><option value="BORROWED">Borrowed (I took money from someone)</option></>}</select></Field>
      <Field label="Amount (₹)"><input inputMode="decimal" value={amt} onChange={e => setAmt(e.target.value)} autoFocus={!uid} /></Field>
      <Field label="Date"><input type="date" value={istDate(t.dateMillis!)} onChange={e => e.target.value && set({ dateMillis: fromIstDate(e.target.value) })} /></Field>
      <Field label="Method"><select value={t.method} onChange={e => { const m = e.target.value; const cash = m === 'CASH' && (t.type === 'EXPENSE' || loanMode) ? cashAccount(db) : undefined; set(cash ? { method: m, accountId: cash.uid, accountLast4: null } : { method: m }) }}>{METHODS.map(m => <option key={m}>{m}</option>)}</select></Field>
      <Field label={loanMode ? (t.type === 'LENT' ? 'Paid from account' : 'Received into account') : t.type === 'TRANSFER' ? 'From account' : 'Account'}><AccountSelect cards={t.type !== 'TRANSFER' && !loanMode} value={t.accountId ?? null}
        onChange={v => { const a = v ? db.get<Account>('accounts', v) : undefined; set({ accountId: v, accountLast4: a && !isDebitCard(a) ? a.last4 : null, ...(a && isDebitCard(a) ? { method: 'CARD' } : {}) }) }} /></Field>
      {t.type === 'TRANSFER' && <Field label="To account (cash, card, own account)"><AccountSelect value={t.toAccountId ?? null} onChange={v => set({ toAccountId: v })} /></Field>}
      {loanMode && <Field label={t.type === 'LENT' ? 'Lent to (person)' : 'Borrowed from (person)'}><input value={person} onChange={e => setPerson(e.target.value)} placeholder="Name" /></Field>}
      {!loanMode && <Field label="Merchant / payee"><input value={t.merchantName ?? ''} onChange={e => set({ merchantName: e.target.value || null })} /></Field>}
      {!loanMode && <Field label="Category"><CategorySelect value={t.categoryId ?? null} onChange={v => set({ categoryId: v })} /></Field>}
      <Field label="Note"><input value={t.note ?? ''} onChange={e => set({ note: e.target.value || null })} placeholder={loanMode ? 'What it was for, when it is due…' : ''} /></Field>
      {!loanMode && <Field label="Status"><select value={t.status} onChange={e => set({ status: e.target.value })}>{['POSTED', ...REVIEW_STATUSES, 'REJECTED'].map(s => <option key={s} value={s}>{statusLabel(s)}</option>)}</select></Field>}
    </div>
    {cardLink && <p className="muted">Debit card payment — charged to {cardLink.name}.</p>}
    {picked && isDebitCard(picked) && !cardLink && <p className="err">This debit card has no linked bank account. Edit it under Money › Accounts.</p>}
    {loanMode && <p className="muted">{t.type === 'LENT' ? 'Saved as a loan you can track and close under Plan › Loans. It is not counted as spending, and your account balance goes down by this amount until it is paid back.' : 'Saved as a loan you can track and close under Plan › Loans. It is not counted as income.'}</p>}
    {t.type === 'EXPENSE' && amountPaise > 0 && <BudgetImpact exclude={uid} counts={(t.status ?? 'POSTED') === 'POSTED'} dateMillis={t.dateMillis!}
      lines={splits.length ? splits.map(s => ({ categoryId: s.categoryId ?? 'other', amountPaise: parseRupees(s.amt) ?? 0 })) : [{ categoryId: t.categoryId ?? 'other', amountPaise }]} />}
    {t.merchantKey && t.categoryId !== orig?.categoryId && <label className="check"><input type="checkbox" checked={learn} onChange={e => setLearn(e.target.checked)} /> Always use this category for {t.merchantName}</label>}
    {t.type === 'EXPENSE' && <div className="splits">
      <b>Split across categories</b> <button className="link" onClick={() => setSplits([...splits, { categoryId: t.categoryId ?? null, amt: '' }])}>+ add part</button>
      {splits.map((s, i) => <div key={i} className="row"><CategorySelect value={s.categoryId} allowEmpty={false} kinds={['EXPENSE']} onChange={v => setSplits(splits.map((x, j) => j === i ? { ...x, categoryId: v } : x))} />
        <input inputMode="decimal" placeholder="₹" value={s.amt} onChange={e => setSplits(splits.map((x, j) => j === i ? { ...x, amt: e.target.value } : x))} />
        <button className="link" onClick={() => setSplits(splits.filter((_, j) => j !== i))}>remove</button></div>)}
      {splits.length > 0 && <small className={splitSum === amountPaise ? 'muted' : 'err'}>Parts: {money(splitSum)} of {money(amountPaise)}</small>}
    </div>}
    {raws.length > 0 && <details><summary>Source messages ({raws.length})</summary>{raws.map(r => <pre key={r.uid}>{r.sender}: {r.body || '(text removed after the retention period)'}</pre>)}</details>}
    {err && <p className="err">{err}</p>}
    <footer>
      {uid && <button className="danger" onClick={del}>Delete</button>}
      <span className="grow" />
      {uid && REVIEW_STATUSES.includes(orig?.status ?? '') && <button onClick={() => save('POSTED')}>Approve</button>}
      <button className="primary" onClick={() => save()}>Save</button>
    </footer>
  </Modal>
}

// ====================================================================== Home
export function Home() {
  const db = useDb(); const cats = categories(db)
  const [ym, setYm] = useState(thisMonth())
  const tot = monthTotals(db, ym); const ms = moneySummary(db)
  const top = byTopCategory(db, ym).slice(0, 8); const max = top[0]?.[1] ?? 1
  const review = txns(db).filter(t => REVIEW_STATUSES.includes(t.status)).length
  const recent = txns(db).filter(t => t.status !== 'REJECTED').sort((a, b) => b.dateMillis - a.dateMillis).slice(0, 8)
  const budgets = budgetStatus(db, thisMonth()).slice(0, 4)
  const openLoans = loanList(db).filter(l => !l.closed)
  const owedToMe = openLoans.filter(l => l.kind === 'LENT').reduce((a, l) => a + Math.max(0, l.pending), 0)
  const iOwe = openLoans.filter(l => l.kind === 'BORROWED').reduce((a, l) => a + Math.max(0, l.pending), 0)
  const name = localStorage.getItem('kosh.name')
  const [adding, setAdding] = useState(false)
  return <Page title={`${greeting()}${name ? ', ' + name : ''}`} actions={<button className="primary" onClick={() => setAdding(true)}>+ Add transaction</button>}>
    <MonthSwitch ym={ym} set={setYm} />
    <div className="stats">
      <Stat label="Income" value={money(tot.income, { paise: false })} tone="pos" /><Stat label="Spent" value={money(tot.expense, { paise: false })} tone="neg" />
      <Stat label="Invested & premiums" value={money(tot.invested, { paise: false })} /><Stat label="Left over" value={money(tot.income + tot.refunds - tot.expense - tot.invested, { paise: false })} />
    </div>
    {tot.archived && <p className="muted">This month is archived in Drive: totals only. Open it under Archive for the transactions.</p>}
    <div className="cols">
      <Card title="Where the money went">{top.length ? top.map(([c, v]) => <div key={c} className="catrow"><span>{cats.get(c)?.name ?? c}</span><Bar pct={v * 100 / max} /><b>{compact(v)}</b></div>) : <Empty>No spending recorded.</Empty>}</Card>
      <div>
        <Card title="Money">
          <div className="kv"><span>In bank accounts</span><b>{money(ms.bank, { paise: false })}</b></div>
          <div className="kv"><span>Cash in hand</span><b>{money(ms.cash, { paise: false })}</b></div>
          <div className="kv"><span>Credit cards owed</span><b>{money(ms.cards, { paise: false })}</b></div>
          <a href="#/accounts" className="small">Accounts →</a>
        </Card>
        {openLoans.length > 0 && <Card title="Lent & borrowed" actions={<a href="#/loans">Loans →</a>}>
          {owedToMe > 0 && <div className="kv"><span>Owed to you ({openLoans.filter(l => l.kind === 'LENT' && l.pending > 0).length})</span><b>{money(owedToMe, { paise: false })}</b></div>}
          {iOwe > 0 && <div className="kv"><span>You owe ({openLoans.filter(l => l.kind === 'BORROWED' && l.pending > 0).length})</span><b>{money(iOwe, { paise: false })}</b></div>}
        </Card>}
        {review > 0 && <Card title="Waiting for you"><p>{review} transaction(s) to check. <a href="#/review">Review →</a></p></Card>}
        {budgets.length > 0 && <Card title="Budgets this month">{budgets.map(b => <a key={b.uid} href="#/budgets" className="catrow plain" title={`${money(b.available - b.spent, { paise: false })} left`}><span>{b.name}</span><Bar pct={b.pct} warn={b.pct >= 100} /><b>{b.pct}%</b></a>)}</Card>}
      </div>
    </div>
    <Card title="Recent" actions={<a href="#/txns">All transactions →</a>}><TxnTable list={recent} /></Card>
    {adding && <TxnEditor onClose={() => setAdding(false)} />}
  </Page>
}

// ====================================================================== Transactions (search & filters)
export function Transactions() {
  const db = useDb()
  const [f, setF] = useState({ from: istDate(fromIstDate(addMonths(thisMonth(), -2) + '-01')), to: istDate(Date.now()), q: '', min: '', max: '', category: null as string | null, account: null as string | null, method: '', type: '', status: '' })
  const [adding, setAdding] = useState(false)
  const set = (p: Partial<typeof f>) => setF(x => ({ ...x, ...p }))
  const cats = categories(db)
  const list = useMemo(() => {
    const [a, b] = [fromIstDate(f.from, 0), fromIstDate(f.to, 0) + 86_400_000 - 1]
    const q = f.q.toLowerCase(); const min = parseRupees(f.min); const max = parseRupees(f.max)
    return txns(db).filter(t => t.dateMillis >= a && t.dateMillis <= b && (!f.type || t.type === f.type) && (!f.method || t.method === f.method) &&
      (f.status ? t.status === f.status : t.status !== 'REJECTED') && (!f.account || t.accountId === f.account || t.toAccountId === f.account) &&
      (!f.category || t.categoryId === f.category || cats.get(t.categoryId ?? '')?.parentId === f.category) &&
      (min == null || t.amountPaise >= min) && (max == null || t.amountPaise <= max) &&
      (!q || [t.merchantName, t.note, t.reference, t.categoryId].some(x => x?.toLowerCase().includes(q))))
      .sort((x, y) => y.dateMillis - x.dateMillis)
  }, [db.version, f])
  const tot = totals(list)
  const exportCsv = () => downloadText(`kosh-transactions-${f.from}-to-${f.to}.csv`, ['Date,Type,Status,Amount,Merchant,Category,Account,To account,Method,Reference,Note',
    ...list.map(t => [istDate(t.dateMillis), t.type, t.status, (t.amountPaise / 100).toFixed(2), t.merchantName, categoryPath(t.categoryId, cats), accountLabel(db, t.accountId), t.toAccountId ? accountLabel(db, t.toAccountId) : '', t.method, t.reference, t.note].map(csvEscape).join(','))].join('\n'))
  return <Page title="Transactions" actions={<><button onClick={exportCsv}>Export CSV</button><button className="primary" onClick={() => setAdding(true)}>+ Add</button></>}>
    <div className="filters">
      <Field label="From"><input type="date" value={f.from} onChange={e => set({ from: e.target.value })} /></Field>
      <Field label="To"><input type="date" value={f.to} onChange={e => set({ to: e.target.value })} /></Field>
      <Field label="Search merchant, note, reference"><input value={f.q} onChange={e => set({ q: e.target.value })} placeholder="e.g. swiggy" /></Field>
      <Field label="Min ₹"><input inputMode="decimal" value={f.min} onChange={e => set({ min: e.target.value })} /></Field>
      <Field label="Max ₹"><input inputMode="decimal" value={f.max} onChange={e => set({ max: e.target.value })} /></Field>
      <Field label="Category"><CategorySelect value={f.category} onChange={v => set({ category: v })} /></Field>
      <Field label="Account"><AccountSelect value={f.account} onChange={v => set({ account: v })} empty="All accounts" /></Field>
      <Field label="Method"><select value={f.method} onChange={e => set({ method: e.target.value })}><option value="">All</option>{METHODS.map(m => <option key={m}>{m}</option>)}</select></Field>
      <Field label="Type"><select value={f.type} onChange={e => set({ type: e.target.value })}><option value="">All</option>{TYPES.map(m => <option key={m} value={m}>{typeLabel(m)}</option>)}</select></Field>
      <Field label="Status"><select value={f.status} onChange={e => set({ status: e.target.value })}><option value="">All but rejected</option>{['POSTED', ...REVIEW_STATUSES, 'REJECTED'].map(s => <option key={s} value={s}>{statusLabel(s)}</option>)}</select></Field>
    </div>
    <p className="muted">{list.length} transactions · spent {money(tot.expense)} · income {money(tot.income)} · invested {money(tot.invested)} (saved ones only). Older than {monthLabel(firstKeptMonth())}: see Archive.</p>
    <Card><TxnTable list={list} /></Card>
    {adding && <TxnEditor onClose={() => setAdding(false)} />}
  </Page>
}

// ====================================================================== Review
export function Review() {
  const db = useDb()
  const list = txns(db).filter(t => REVIEW_STATUSES.includes(t.status)).sort((a, b) => b.dateMillis - a.dateMillis)
  const [sel, setSel] = useState<Set<string>>(new Set())
  const [open, setOpen] = useState<string | null>(null)
  const cats = categories(db)
  const setStatus = (ids: string[], status: string) => { ids.forEach(id => { db.put('transactions', { status, updatedAt: Date.now() }, id); db.put('audit_log', { transactionId: id, at: Date.now(), action: 'STATUS_' + status, before: null, after: null }) }); setSel(new Set()) }
  return <Page title="Review" actions={sel.size > 0 && <><button onClick={() => setStatus([...sel], 'REJECTED')}>Reject {sel.size}</button><button className="primary" onClick={() => setStatus([...sel], 'POSTED')}>Approve {sel.size}</button></>}>
    <p className="muted">"Check" = 80–94% confident, "Review" = below 80% or read by AI, "Duplicate?" = may be a copy of another entry. None of these count in totals until approved.</p>
    {!list.length ? <Empty>Nothing to review. 🎉</Empty> : <Card>
      <table className="tbl"><thead><tr><th><input type="checkbox" checked={sel.size === list.length} onChange={e => setSel(e.target.checked ? new Set(list.map(t => t.uid)) : new Set())} /></th><th>Date</th><th>Merchant</th><th>Category</th><th>Why</th><th className="r">Amount</th><th /></tr></thead>
        <tbody>{list.map(t => <tr key={t.uid}>
          <td><input type="checkbox" checked={sel.has(t.uid)} onChange={e => { const s = new Set(sel); e.target.checked ? s.add(t.uid) : s.delete(t.uid); setSel(s) }} /></td>
          <td>{fmtDay(t.dateMillis)}</td><td>{t.merchantName ?? '—'}<br /><small className="muted">{accountLabel(db, t.accountId)}</small></td><td>{categoryPath(t.categoryId, cats)}</td>
          <td><span className={'pill ' + t.status}>{statusLabel(t.status)}</span> <small>{t.confidence}%</small>{t.linkedTxnId && <small> · see {fmtDay(db.get<Txn>('transactions', t.linkedTxnId)?.dateMillis ?? 0)}</small>}</td>
          <td className={'r amt ' + t.type}>{sign(t)}{money(t.amountPaise)}</td>
          <td className="nowrap"><button onClick={() => setStatus([t.uid], 'POSTED')}>{t.status === 'SUSPECTED_DUPLICATE' ? 'Keep both' : 'Approve'}</button> <button onClick={() => setOpen(t.uid)}>Edit</button> <button className="link" onClick={() => setStatus([t.uid], 'REJECTED')}>Reject</button></td>
        </tr>)}</tbody></table></Card>}
    {open && <TxnEditor uid={open} onClose={() => setOpen(null)} />}
  </Page>
}

// ====================================================================== Accounts
export function Accounts({ arg }: { arg?: string }) {
  const db = useDb(); const f = flows(db)
  const [edit, setEdit] = useState<Partial<Account> | null>(null)
  const [fix, setFix] = useState<Account | null>(null)
  if (arg) {
    const a = db.get<Account>('accounts', arg)
    if (!a) return <Page title="Account"><Empty>Not found.</Empty></Page>
    const list = txns(db).filter(t => (t.accountId === arg || t.toAccountId === arg) && t.status !== 'REJECTED').sort((x, y) => y.dateMillis - x.dateMillis)
    return <Page title={a.name} actions={<><a href="#/accounts">← All accounts</a><button className="primary" onClick={() => setFix(a)}>Correct balance</button></>}>
      <div className="stats"><Stat label={a.kind === 'CARD' ? 'Outstanding' : 'Balance'} value={money(a.kind === 'CARD' ? -balanceOf(a, f) : balanceOf(a, f))} /><Stat label="Opening balance" value={money(a.openingBalancePaise)} /></div>
      <Card><TxnTable list={list} /></Card>
      {fix && <BalanceFixer a={fix} onClose={() => setFix(null)} />}</Page>
  }
  const list = accounts(db); const cards = debitCards(db)
  return <Page title="Accounts" actions={<button className="primary" onClick={() => setEdit({ kind: 'BANK', openingBalancePaise: 0 })}>+ Add account</button>}>
    <p className="muted">Balances = opening balance + money in − money out from saved transactions. Transfers (ATM withdrawals, card bill payments) move money between accounts and are not spending or income.</p>
    <Card><table className="tbl"><thead><tr><th>Account</th><th>Type</th><th className="r">Balance / owed</th><th /></tr></thead>
      <tbody>{list.map(a => <tr key={a.uid}><td><a href={'#/accounts/' + a.uid}>{a.name}</a></td><td>{({ BANK: 'Bank', CARD: 'Credit card', CASH: 'Cash', WALLET: 'Wallet' } as any)[a.kind] ?? a.kind}</td>
        <td className="r">{a.kind === 'CARD' ? `${money(-balanceOf(a, f))} owed` : money(balanceOf(a, f))}</td><td className="r nowrap"><button onClick={() => setFix(a)}>Correct balance</button> <button className="link" onClick={() => setEdit(a)}>Edit</button></td></tr>)}</tbody></table></Card>
    <Card title="Debit cards" actions={<button onClick={() => setEdit({ kind: 'WALLET', bank: 'debit:', openingBalancePaise: 0 })}>+ Add debit card</button>}>
      {cards.length ? <table className="tbl"><tbody>{cards.map(c => { const l = debitCardLink(db, c); return <tr key={c.uid}><td>{c.name}</td>
        <td>{l ? <>spends are charged to <b>{l.name}</b></> : <span className="err">not linked to a bank account</span>}</td><td className="r"><button className="link" onClick={() => setEdit(c)}>Edit</button></td></tr> })}</tbody></table>
        : <Empty>No debit cards yet. A debit card holds no money of its own: pick it when adding a transaction and the amount comes out of the bank account you link it to.</Empty>}
    </Card>
    {edit && <AccountEditor a={edit} onClose={() => setEdit(null)} />}
    {fix && <BalanceFixer a={fix} onClose={() => setFix(null)} />}
  </Page>
}
/**
 * Fixes a balance (or a card's amount owed) that does not match the bank. Default: records the
 * difference as a "Balance adjustment" transfer dated today, so past months stay as they were and
 * nothing is counted as income or spending. Alternative: change the opening balance instead.
 */
function BalanceFixer({ a, onClose }: { a: Account; onClose: () => void }) {
  const db = useDb(); const f = flows(db)
  const card = a.kind === 'CARD'
  const recorded = card ? -balanceOf(a, f) : balanceOf(a, f)
  const [actual, setActual] = useState(String(recorded / 100)); const [date, setDate] = useState(istDate(Date.now()))
  const [how, setHow] = useState<'adjust' | 'opening'>('adjust'); const [note, setNote] = useState('')
  const val = Math.round(Number(actual.replace(/[,₹\s]/g, '')) * 100)
  const ok = actual.trim() !== '' && isFinite(val)
  const diffBalance = ok ? (card ? -val : val) - balanceOf(a, f) : 0
  const save = () => {
    if (!ok || diffBalance === 0) return onClose()
    const now = Date.now()
    if (how === 'opening') db.put('accounts', { openingBalancePaise: a.openingBalancePaise + diffBalance }, a.uid)
    else {
      const t = { type: 'TRANSFER', direction: diffBalance > 0 ? 'CREDIT' : 'DEBIT', status: 'POSTED', amountPaise: Math.abs(diffBalance), dateMillis: fromIstDate(date),
        accountId: a.uid, accountLast4: a.last4, categoryId: 'transfers.adjust', method: 'OTHER', merchantName: 'Balance adjustment',
        note: note || (card ? 'Amount owed corrected to match the card statement' : 'Balance corrected to match the bank'), sources: 'MANUAL', confidence: 100, createdAt: now, updatedAt: now }
      const id = db.put('transactions', t)
      db.put('audit_log', { transactionId: id, at: now, action: 'CREATED', before: null, after: JSON.stringify(t) })
    }
    onClose()
  }
  return <Modal title={`Correct ${card ? 'amount owed' : 'balance'} · ${a.name}`} onClose={onClose}>
    <p>Kosh shows <b>{money(recorded)}</b>{card ? ' owed' : ''}. Enter what your {card ? 'card statement or app shows as outstanding' : 'bank shows'} right now.</p>
    <Field label={card ? 'Actual amount owed (₹)' : 'Actual balance (₹)'}><input autoFocus inputMode="decimal" value={actual} onChange={e => setActual(e.target.value)} /></Field>
    {ok && diffBalance !== 0 && <p className="muted">Difference: {money(Math.abs(diffBalance))} {card ? (diffBalance > 0 ? 'less owed' : 'more owed') : (diffBalance > 0 ? 'more in the account' : 'less in the account')}.</p>}
    <label className="check"><input type="radio" checked={how === 'adjust'} onChange={() => setHow('adjust')} /> Record the difference as a balance adjustment (recommended — keeps past months unchanged; not counted as income or spending)</label>
    {how === 'adjust' && <div className="grid2"><Field label="Date"><input type="date" value={date} onChange={e => setDate(e.target.value)} /></Field><Field label="Note (optional)"><input value={note} onChange={e => setNote(e.target.value)} /></Field></div>}
    <label className="check"><input type="radio" checked={how === 'opening'} onChange={() => setHow('opening')} /> Change the opening balance instead (use if the starting figure was wrong)</label>
    <p className="muted">Saved here, this reaches your phone at its next sync.</p>
    <footer><span className="grow" /><button className="primary" disabled={!ok} onClick={save}>Save</button></footer>
  </Modal>
}

function AccountEditor({ a, onClose }: { a: Partial<Account>; onClose: () => void }) {
  const db = useDb()
  const debit = isDebitCard(a as Account) || a.bank === 'debit:'
  const [x, setX] = useState<Partial<Account>>({ ...a, kind: debit ? 'DEBIT' : a.kind }); const [ob, setOb] = useState(String((a.kind === 'CARD' ? -1 : 1) * (a.openingBalancePaise ?? 0) / 100))
  const [link, setLink] = useState<string | null>(debit && a.bank !== 'debit:' ? a.bank!.slice('debit:'.length) : null)
  const [last4, setLast4] = useState('')
  if (x.kind === 'DEBIT') {
    const bank = link ? db.get<Account>('accounts', link) : undefined
    const name = (x.name ?? '').trim() || (bank ? `${bank.name.replace(/\s*••\d+$/, '')} debit card${last4 ? ' ••' + last4 : ''}` : '')
    return <Modal title={a.uid ? 'Edit debit card' : 'Add debit card'} onClose={onClose}>
      <Field label="Type"><select value="DEBIT" onChange={e => setX({ ...x, kind: e.target.value })}><option value="BANK">Bank account</option><option value="DEBIT">Debit card</option><option value="CARD">Credit card</option><option value="WALLET">Wallet</option><option value="CASH">Cash</option></select></Field>
      <Field label="Linked bank account (payments come out of this account)"><AccountSelect kinds={['BANK']} empty="Choose a bank account" value={link} onChange={setLink} /></Field>
      <Field label="Last 4 digits of the card (optional, only these are stored)"><input maxLength={4} value={last4} onChange={e => setLast4(e.target.value.replace(/\D/g, ''))} /></Field>
      <Field label="Name (optional)"><input value={x.name ?? ''} onChange={e => setX({ ...x, name: e.target.value })} placeholder={name || 'HDFC debit card ••1234'} /></Field>
      <p className="muted">A debit card holds no money of its own. When you pick it on a transaction, the amount is taken from the linked bank account.</p>
      <footer>{a.uid && <button className="danger" onClick={() => { db.put('accounts', { archived: 1 }, a.uid); onClose() }}>Remove card</button>}<span className="grow" />
        <button className="primary" disabled={!link || !name} onClick={() => { db.put('accounts', { name, kind: 'WALLET', bank: 'debit:' + link, last4: null, openingBalancePaise: 0, archived: 0 }, a.uid); onClose() }}>Save</button></footer>
    </Modal>
  }
  return <Modal title={a.uid ? 'Edit account' : 'Add account'} onClose={onClose}>
    <Field label="Name"><input value={x.name ?? ''} onChange={e => setX({ ...x, name: e.target.value })} placeholder="HDFC ••4821" /></Field>
    <Field label="Type"><select value={x.kind} onChange={e => setX({ ...x, kind: e.target.value })}><option value="BANK">Bank account</option><option value="DEBIT">Debit card</option><option value="CARD">Credit card</option><option value="WALLET">Wallet</option><option value="CASH">Cash</option></select></Field>
    <Field label="Bank"><input value={x.bank ?? ''} onChange={e => setX({ ...x, bank: e.target.value || null })} /></Field>
    <Field label="Last 4 digits (only these are stored)"><input maxLength={4} value={x.last4 ?? ''} onChange={e => setX({ ...x, last4: e.target.value.replace(/\D/g, '') || null })} /></Field>
    <Field label={x.kind === 'CARD' ? 'Amount owed when you started using Kosh (₹)' : 'Opening balance when you started using Kosh (₹)'}><input value={ob} onChange={e => setOb(e.target.value)} /></Field>
    <footer>{a.uid && <button className="danger" onClick={() => { db.put('accounts', { archived: 1 }, a.uid); onClose() }}>Hide account</button>}<span className="grow" />
      <button className="primary" disabled={!x.name} onClick={() => { db.put('accounts', { ...x, openingBalancePaise: (x.kind === 'CARD' ? -1 : 1) * Math.round(Number(ob.replace(/[,₹\s]/g, '') || 0) * 100), archived: 0 }, a.uid); onClose() }}>Save</button></footer>
  </Modal>
}

// ====================================================================== Cash
export function Cash() {
  const db = useDb(); const cash = cashAccount(db); const f = flows(db); const cats = categories(db)
  const [form, setForm] = useState<null | 'atm' | 'spend' | 'deposit'>(null)
  const [amt, setAmt] = useState(''); const [bank, setBank] = useState<string | null>(null); const [cat, setCat] = useState<string | null>('other'); const [note, setNote] = useState(''); const [date, setDate] = useState(istDate(Date.now()))
  if (!cash) return <Page title="Cash"><Empty>The Cash account arrives from your phone after the first sync.</Empty></Page>
  const list = txns(db).filter(t => (t.accountId === cash.uid || t.toAccountId === cash.uid) && t.status !== 'REJECTED').sort((a, b) => b.dateMillis - a.dateMillis)
  const ym = thisMonth(); const month = list.filter(t => inMonth(t, ym) && counted(t))
  const withdrawn = month.filter(t => t.toAccountId === cash.uid).reduce((s, t) => s + t.amountPaise, 0)
  const spent = month.filter(t => t.accountId === cash.uid && t.type === 'EXPENSE').reduce((s, t) => s + t.amountPaise, 0)
  const save = () => {
    const p = parseRupees(amt); if (!p) return
    const now = Date.now(); const d = fromIstDate(date)
    const base = { amountPaise: p, dateMillis: d, status: 'POSTED', confidence: 100, sources: 'MANUAL', createdAt: now, updatedAt: now, note: note || null }
    if (form === 'atm') db.put('transactions', { ...base, type: 'TRANSFER', direction: 'DEBIT', accountId: bank, toAccountId: cash.uid, categoryId: 'transfers.cash', method: 'ATM', merchantName: 'ATM withdrawal' })
    if (form === 'deposit') db.put('transactions', { ...base, type: 'TRANSFER', direction: 'DEBIT', accountId: cash.uid, toAccountId: bank, categoryId: 'transfers.cashdep', method: 'CASH', merchantName: 'Cash deposit' })
    if (form === 'spend') db.put('transactions', { ...base, type: 'EXPENSE', direction: 'DEBIT', accountId: cash.uid, categoryId: cat, method: 'CASH', merchantName: note || null })
    setForm(null); setAmt(''); setNote('')
  }
  return <Page title="Cash" actions={<><button onClick={() => setForm('atm')}>ATM withdrawal</button><button onClick={() => setForm('deposit')}>Cash deposit</button><button className="primary" onClick={() => setForm('spend')}>Spent cash</button></>}>
    <div className="stats"><Stat label="Cash in hand" value={money(balanceOf(cash, f))} /><Stat label="Withdrawn this month" value={money(withdrawn)} /><Stat label="Spent in cash this month" value={money(spent)} /></div>
    <p className="muted">An ATM withdrawal moves money from your bank to Cash (not spending). Spending happens when you record what the cash was used for.</p>
    <Card><TxnTable list={list} /></Card>
    {form && <Modal title={{ atm: 'ATM withdrawal', spend: 'Spent cash', deposit: 'Cash deposit' }[form]} onClose={() => setForm(null)}>
      <Field label="Amount (₹)"><input autoFocus inputMode="decimal" value={amt} onChange={e => setAmt(e.target.value)} /></Field>
      <Field label="Date"><input type="date" value={date} onChange={e => setDate(e.target.value)} /></Field>
      {form !== 'spend' && <Field label={form === 'atm' ? 'From bank account' : 'Into bank account'}><AccountSelect value={bank} onChange={setBank} kinds={['BANK']} /></Field>}
      {form === 'spend' && <Field label="Category"><CategorySelect value={cat} onChange={setCat} kinds={['EXPENSE']} allowEmpty={false} /></Field>}
      <Field label={form === 'spend' ? 'What for' : 'Note'}><input value={note} onChange={e => setNote(e.target.value)} /></Field>
      <footer><span className="grow" /><button className="primary" onClick={save}>Save</button></footer>
    </Modal>}
    <span hidden>{cats.size}</span>
  </Page>
}

// ====================================================================== Budgets
export function Budgets() {
  const db = useDb(); const [ym, setYm] = useState(thisMonth())
  const list = budgetStatus(db, ym)
  const [edit, setEdit] = useState<Row | null>(null)
  const [cat, setCat] = useState<string | null>(null); const [lim, setLim] = useState(''); const [roll, setRoll] = useState(false)
  const open = (b?: Row) => { setEdit(b ?? ({} as Row)); setCat(b?.categoryId ?? null); setLim(b ? String(b.monthlyLimitPaise / 100) : ''); setRoll(!!b?.rollover) }
  return <Page title="Budgets" actions={<button className="primary" onClick={() => open()}>+ Set a budget</button>}>
    <MonthSwitch ym={ym} set={setYm} />
    {!list.length ? <Empty>No budgets yet. Set a monthly limit for any category; a parent category includes its subcategories.</Empty> :
      <Card>{list.map(b => <div key={b.uid} className="budget" onClick={() => open(b)}><div className="kv"><b>{b.name}</b><span>{money(b.spent, { paise: false })} of {money(b.available, { paise: false })}{b.available > b.monthlyLimitPaise && <small> (incl. carry-over)</small>}</span></div><Bar pct={b.pct} warn={b.pct >= 100} /><small className={b.pct >= 100 ? 'err' : 'muted'}>{b.pct >= 100 ? `Over by ${money(b.spent - b.available)}` : `${money(b.available - b.spent)} left`}{b.pending > 0 && ` · ${money(b.pending, { paise: false })} more waiting in Review`}</small></div>)}</Card>}
    {edit && <Modal title="Budget" onClose={() => setEdit(null)}>
      <Field label="Category"><CategorySelect value={cat} onChange={setCat} kinds={['EXPENSE']} allowEmpty={false} /></Field>
      <Field label="Monthly limit (₹)"><input inputMode="decimal" value={lim} onChange={e => setLim(e.target.value)} /></Field>
      <label className="check"><input type="checkbox" checked={roll} onChange={e => setRoll(e.target.checked)} /> Carry unspent amount to next month</label>
      <footer>{edit.uid && <button className="danger" onClick={() => { db.remove('budgets', edit.uid); setEdit(null) }}>Remove</button>}<span className="grow" />
        <button className="primary" disabled={!cat || !parseRupees(lim)} onClick={() => { db.put('budgets', { categoryId: cat, monthlyLimitPaise: parseRupees(lim), rollover: roll ? 1 : 0, startMonth: edit.startMonth ?? thisMonth() }); setEdit(null) }}>Save</button></footer>
    </Modal>}
  </Page>
}

// ====================================================================== Recurring & due
export function Recurring() {
  const db = useDb(); const today = istDate(Date.now())
  const rules = db.rows('recurring_rules').map(r => ({ ...r, due: r.active ? nextDue(r.startDate, r.frequency, today) : null }) as Row & { due: string | null }).sort((a, b) => (a.due ?? '9') < (b.due ?? '9') ? -1 : 1)
  const plans = db.rows('investments').filter(i => i.status === 'ACTIVE' && i.installmentPaise && i.frequency !== 'ONE_TIME').map(i => ({ ...i, due: nextDue(i.startDate, i.frequency, today) }) as Row & { due: string | null }).filter(i => i.due && (!i.maturityDate || i.due <= i.maturityDate))
  const [edit, setEdit] = useState<Row | null>(null)
  return <Page title="Recurring & due" actions={<button className="primary" onClick={() => setEdit({ type: 'EXPENSE', frequency: 'MONTHLY', startDate: today, remindDaysBefore: 3, active: 1 } as any)}>+ Add recurring payment</button>}>
    <Card title="Recurring payments">{!rules.length ? <Empty>Rent, subscriptions, EMIs, bills… add them to see what is due.</Empty> :
      <table className="tbl"><thead><tr><th>Name</th><th>Every</th><th>Next due</th><th>Last paid</th><th className="r">Amount</th></tr></thead>
        <tbody>{rules.map(r => <tr key={r.uid} className="click" onClick={() => setEdit(r)}><td>{r.name}{!r.active && <small className="tag">paused</small>}</td><td>{r.frequency.toLowerCase().replace('_', '-')}</td><td>{r.due ? fmtDay(fromIstDate(r.due)) : '—'}</td><td>{r.lastPaidDate ? fmtDay(fromIstDate(r.lastPaidDate)) : '—'}</td><td className="r">{money(r.amountPaise)}</td></tr>)}</tbody></table>}</Card>
    <Card title="Policy premiums & instalments due">{!plans.length ? <Empty>No upcoming premiums.</Empty> :
      <table className="tbl"><tbody>{plans.map(p => <tr key={p.uid} className="click" onClick={() => go('policies/' + p.uid)}><td>{p.name}</td><td>{PLAN_KINDS[p.kind] ?? p.kind}</td><td>{fmtDay(fromIstDate(p.due!))}</td><td className="r">{money(p.installmentPaise)}</td></tr>)}</tbody></table>}</Card>
    {edit && <RecurringEditor r={edit} onClose={() => setEdit(null)} />}
  </Page>
}
function RecurringEditor({ r, onClose }: { r: Row; onClose: () => void }) {
  const db = useDb(); const [x, setX] = useState<any>(r); const [amt, setAmt] = useState(r.amountPaise ? String(r.amountPaise / 100) : '')
  return <Modal title={r.uid ? 'Recurring payment' : 'Add recurring payment'} onClose={onClose}>
    <div className="grid2">
      <Field label="Name"><input value={x.name ?? ''} onChange={e => setX({ ...x, name: e.target.value })} /></Field>
      <Field label="Amount (₹)"><input value={amt} onChange={e => setAmt(e.target.value)} /></Field>
      <Field label="Type"><select value={x.type} onChange={e => setX({ ...x, type: e.target.value })}>{TYPES.map(t => <option key={t} value={t}>{typeLabel(t)}</option>)}</select></Field>
      <Field label="Every"><select value={x.frequency} onChange={e => setX({ ...x, frequency: e.target.value })}>{FREQUENCIES.map(f => <option key={f}>{f}</option>)}</select></Field>
      <Field label="First due date"><input type="date" value={x.startDate} onChange={e => setX({ ...x, startDate: e.target.value, nextDueDate: e.target.value })} /></Field>
      <Field label="Remind days before"><input type="number" value={x.remindDaysBefore} onChange={e => setX({ ...x, remindDaysBefore: Number(e.target.value) })} /></Field>
      <Field label="Category"><CategorySelect value={x.categoryId} onChange={v => setX({ ...x, categoryId: v })} /></Field>
      <Field label="Account"><AccountSelect value={x.accountId} onChange={v => setX({ ...x, accountId: v })} /></Field>
    </div>
    <label className="check"><input type="checkbox" checked={!!x.active} onChange={e => setX({ ...x, active: e.target.checked ? 1 : 0 })} /> Active</label>
    <footer>{r.uid && <button className="danger" onClick={() => { db.remove('recurring_rules', r.uid); onClose() }}>Delete</button>}<span className="grow" />
      <button className="primary" disabled={!x.name || !parseRupees(amt)} onClick={() => { db.put('recurring_rules', { ...x, amountPaise: parseRupees(amt), nextDueDate: nextDue(x.startDate, x.frequency, istDate(Date.now())) ?? x.startDate }, r.uid); onClose() }}>Save</button></footer>
  </Modal>
}

// ====================================================================== Goals
export function Goals() {
  const db = useDb(); const goals = db.rows('savings_goals').filter(g => !g.archived); const contrib = db.rows('goal_contributions')
  const [edit, setEdit] = useState<any>(null); const [add, setAdd] = useState<Row | null>(null); const [amt, setAmt] = useState('')
  return <Page title="Goals" actions={<button className="primary" onClick={() => setEdit({ kind: 'SAVINGS', createdAt: Date.now() })}>+ New goal</button>}>
    {!goals.length && <Empty>Emergency fund, a trip, a purchase — set a target and add to it.</Empty>}
    <div className="cards">{goals.map(g => { const saved = contrib.filter(c => c.goalId === g.uid).reduce((s, c) => s + c.amountPaise, 0); const pct = Math.round(saved * 100 / Math.max(1, g.targetPaise))
      return <Card key={g.uid} title={g.name} actions={<><button className="link" onClick={() => setEdit(g)}>Edit</button><button onClick={() => { setAdd(g); setAmt('') }}>+ Add money</button></>}>
        <div className="kv"><span>{money(saved, { paise: false })} of {money(g.targetPaise, { paise: false })}</span><b>{pct}%</b></div><Bar pct={pct} />
        {g.targetDate && <small className="muted">By {fmtDay(fromIstDate(g.targetDate))}</small>}</Card> })}</div>
    {edit && <Modal title="Goal" onClose={() => setEdit(null)}>
      <Field label="Name"><input value={edit.name ?? ''} onChange={e => setEdit({ ...edit, name: e.target.value })} /></Field>
      <Field label="Kind"><select value={edit.kind} onChange={e => setEdit({ ...edit, kind: e.target.value })}><option value="SAVINGS">Savings</option><option value="EMERGENCY">Emergency fund</option><option value="PURCHASE">Purchase</option></select></Field>
      <Field label="Target (₹)"><input value={edit.t ?? (edit.targetPaise ? edit.targetPaise / 100 : '')} onChange={e => setEdit({ ...edit, t: e.target.value })} /></Field>
      <Field label="Target date (optional)"><input type="date" value={edit.targetDate ?? ''} onChange={e => setEdit({ ...edit, targetDate: e.target.value || null })} /></Field>
      <footer>{edit.uid && <button className="danger" onClick={() => { db.put('savings_goals', { archived: 1 }, edit.uid); setEdit(null) }}>Archive</button>}<span className="grow" />
        <button className="primary" onClick={() => { const { t, ...g } = edit; const p = parseRupees(String(t ?? g.targetPaise / 100)); if (!g.name || !p) return; db.put('savings_goals', { ...g, targetPaise: p, archived: 0 }, g.uid); setEdit(null) }}>Save</button></footer></Modal>}
    {add && <Modal title={`Add to ${add.name}`} onClose={() => setAdd(null)}><Field label="Amount (₹)"><input autoFocus value={amt} onChange={e => setAmt(e.target.value)} /></Field>
      <footer><span className="grow" /><button className="primary" onClick={() => { const p = parseRupees(amt); if (p) db.put('goal_contributions', { goalId: add.uid, amountPaise: p, dateMillis: Date.now() }); setAdd(null) }}>Add</button></footer></Modal>}
  </Page>
}

// ====================================================================== LIC, insurance, bonds, FD/RD
export function Policies({ arg }: { arg?: string }) {
  const db = useDb(); const { sync } = useApp()
  const [edit, setEdit] = useState<any>(null); const [pay, setPay] = useState(false); const [amt, setAmt] = useState(''); const [date, setDate] = useState(istDate(Date.now())); const [msg, setMsg] = useState('')
  const list = db.rows('investments').sort((a, b) => a.kind.localeCompare(b.kind) || a.name.localeCompare(b.name))
  if (arg) {
    const i = db.get('investments', arg); if (!i) return <Page title="Policy"><Empty>Not found.</Empty></Page>
    const hist = investmentHistory(db, arg); const paid = hist.filter(e => e.kind !== 'WITHDRAWAL').reduce((s, e) => s + e.amountPaise, 0)
    const docs = db.rows('documents').filter(d => d.investmentId === arg)
    const due = i.status === 'ACTIVE' && i.installmentPaise ? nextDue(i.startDate, i.frequency, istDate(Date.now())) : null
    const open = async (d: Row) => { setMsg('Downloading…'); try { const b = await db.downloadDocument(sync.store, d.fileKey); if (!b) return setMsg('Not in Drive yet — open sync on the phone once.'); const u = URL.createObjectURL(new Blob([b], { type: d.mime })); window.open(u, '_blank'); setMsg('') } catch (e: any) { setMsg(e.message) } }
    const upload = async (file: File) => { setMsg('Uploading (encrypted)…'); try { const key = crypto.randomUUID(); await db.uploadDocument(sync.store, key, new Uint8Array(await file.arrayBuffer())); db.put('documents', { investmentId: arg, name: file.name, mime: file.type || 'application/octet-stream', sizeBytes: file.size, fileKey: key, addedAt: Date.now() }); setMsg('Uploaded.') } catch (e: any) { setMsg(e.message) } }
    return <Page title={i.name} actions={<><a href="#/policies">← All</a><button onClick={() => setEdit(i)}>Edit</button><button className="primary" onClick={() => { setPay(true); setAmt(i.installmentPaise ? String(i.installmentPaise / 100) : '') }}>+ Record payment</button></>}>
      <div className="stats"><Stat label={PLAN_KINDS[i.kind] ?? i.kind} value={i.status === 'ACTIVE' ? 'Active' : i.status} /><Stat label="Paid so far" value={money(paid, { paise: false })} />
        {i.installmentPaise && <Stat label={`Premium / instalment (${String(i.frequency).toLowerCase()})`} value={money(i.installmentPaise, { paise: false })} />}{due && <Stat label="Next due" value={fmtDay(fromIstDate(due))} />}
        {i.sumAssuredPaise && <Stat label="Sum assured" value={compact(i.sumAssuredPaise)} />}{i.maturityDate && <Stat label="Matures" value={fmtDay(fromIstDate(i.maturityDate))} />}</div>
      <Card title="Payment history" actions={<button onClick={() => downloadText(`${i.name}-payments.csv`, ['Date,Kind,Amount,Note', ...hist.map(e => [istDate(e.dateMillis), e.kind, (e.amountPaise / 100).toFixed(2), e.note].map(csvEscape).join(','))].join('\n'))}>Export</button>}>
        {!hist.length ? <Empty>No payments yet. Payments matched from bank messages appear here automatically.</Empty> :
          <table className="tbl"><tbody>{hist.map(e => <tr key={e.uid}><td>{fmtDay(e.dateMillis)}</td><td>{e.kind.toLowerCase()}</td><td>{e.note}</td><td className="r">{money(e.amountPaise)}</td><td className="r"><button className="link" onClick={() => db.remove('investment_entries', e.uid)}>remove</button></td></tr>)}</tbody></table>}</Card>
      <Card title="Documents" actions={<label className="btn">Upload<input type="file" hidden onChange={e => e.target.files?.[0] && upload(e.target.files[0])} /></label>}>
        {!docs.length ? <Empty>Policy bond, receipts, certificates — stored encrypted in your Drive.</Empty> : docs.map(d => <div key={d.uid} className="kv"><button className="link" onClick={() => open(d)}>{d.name}</button><small>{Math.ceil(d.sizeBytes / 1024)} KB · {fmtDay(d.addedAt)}</small></div>)}
        {msg && <p className="muted">{msg}</p>}</Card>
      {pay && <Modal title="Record payment" onClose={() => setPay(false)}>
        <Field label="Amount (₹)"><input value={amt} onChange={e => setAmt(e.target.value)} /></Field><Field label="Date"><input type="date" value={date} onChange={e => setDate(e.target.value)} /></Field>
        <footer><span className="grow" /><button className="primary" onClick={() => { const p = parseRupees(amt); if (p) db.put('investment_entries', { investmentId: arg, kind: 'CONTRIBUTION', amountPaise: p, dateMillis: fromIstDate(date), note: 'Added on web' }); setPay(false) }}>Save</button></footer></Modal>}
      {edit && <PolicyEditor p={edit} onClose={() => setEdit(null)} />}
    </Page>
  }
  return <Page title="LIC, insurance & deposits" actions={<button className="primary" onClick={() => setEdit({ kind: 'LIC', frequency: 'YEARLY', status: 'ACTIVE', startDate: istDate(Date.now()), matchKeywords: '' })}>+ Add</button>}>
    <p className="muted">Kosh records what you pay (premiums, bond instalments, deposits) — not market value or returns.</p>
    {!list.length ? <Empty>Add your LIC policies, insurance, bonds and any new FD or RD.</Empty> :
      <Card><table className="tbl"><thead><tr><th>Name</th><th>Kind</th><th>Status</th><th className="r">Paid so far</th></tr></thead><tbody>{list.map(i => <tr key={i.uid} className="click" onClick={() => go('policies/' + i.uid)}>
        <td>{i.name}</td><td>{PLAN_KINDS[i.kind] ?? i.kind}</td><td>{i.status.toLowerCase()}</td><td className="r">{money(investmentHistory(db, i.uid).filter(e => e.kind !== 'WITHDRAWAL').reduce((s, e) => s + e.amountPaise, 0), { paise: false })}</td></tr>)}</tbody></table></Card>}
    {edit && <PolicyEditor p={edit} onClose={() => setEdit(null)} />}
  </Page>
}
function PolicyEditor({ p, onClose }: { p: any; onClose: () => void }) {
  const db = useDb(); const [x, setX] = useState<any>({ ...p, inst: p.installmentPaise ? p.installmentPaise / 100 : '', sa: p.sumAssuredPaise ? p.sumAssuredPaise / 100 : '', mat: p.maturityAmountPaise ? p.maturityAmountPaise / 100 : '' })
  const s = (k: string) => (e: any) => setX({ ...x, [k]: e.target.value })
  return <Modal title={p.uid ? 'Edit' : 'Add policy or deposit'} onClose={onClose} wide>
    <div className="grid2">
      <Field label="Kind"><select value={x.kind} onChange={s('kind')}>{Object.entries(PLAN_KINDS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}{!PLAN_KINDS[x.kind] && <option value={x.kind}>{x.kind}</option>}</select></Field>
      <Field label="Name"><input value={x.name ?? ''} onChange={s('name')} placeholder="LIC Jeevan Anand" /></Field>
      <Field label="Insurer / bank / issuer"><input value={x.institution ?? ''} onChange={s('institution')} /></Field>
      <Field label="Policy/account no. — last 4 only"><input maxLength={4} value={x.refLast4 ?? ''} onChange={e => setX({ ...x, refLast4: e.target.value.replace(/\D/g, '') })} /></Field>
      <Field label="Start date"><input type="date" value={x.startDate} onChange={s('startDate')} /></Field>
      <Field label="Maturity / end date"><input type="date" value={x.maturityDate ?? ''} onChange={s('maturityDate')} /></Field>
      <Field label="Premium / instalment (₹)"><input value={x.inst} onChange={s('inst')} /></Field>
      <Field label="Paid every"><select value={x.frequency} onChange={s('frequency')}>{FREQUENCIES.map(f => <option key={f}>{f}</option>)}</select></Field>
      <Field label="Sum assured (₹)"><input value={x.sa} onChange={s('sa')} /></Field>
      <Field label="Maturity amount (₹)"><input value={x.mat} onChange={s('mat')} /></Field>
      <Field label="Interest rate % (FD/RD/bond)"><input value={x.ratePercent ?? ''} onChange={s('ratePercent')} /></Field>
      <Field label="Status"><select value={x.status} onChange={s('status')}><option>ACTIVE</option><option>MATURED</option><option>CLOSED</option><option>LAPSED</option></select></Field>
    </div>
    <Field label="Words that identify its payments in bank messages (comma separated)"><input value={x.matchKeywords ?? ''} onChange={s('matchKeywords')} placeholder="LIC, 4821" /></Field>
    <footer>{p.uid && <button className="danger" onClick={() => { if (confirm('Delete this and its payment history?')) { investmentHistory(db, p.uid).forEach(e => db.remove('investment_entries', e.uid)); db.remove('investments', p.uid); onClose(); go('policies') } }}>Delete</button>}<span className="grow" />
      <button className="primary" disabled={!x.name} onClick={() => { const { inst, sa, mat, ...r } = x; db.put('investments', { ...r, installmentPaise: parseRupees(String(inst)), sumAssuredPaise: parseRupees(String(sa)), maturityAmountPaise: parseRupees(String(mat)), ratePercent: x.ratePercent ? Number(x.ratePercent) : null, maturityDate: x.maturityDate || null }, p.uid); onClose() }}>Save</button></footer>
  </Modal>
}

// ====================================================================== Reports
const REPORTS = ['Monthly', 'Annual', 'Category', 'Merchant', 'Income', 'Cash', 'Transfers', 'LIC & insurance premiums', 'Bond instalments'] as const
export function Reports() {
  const db = useDb(); const cats = categories(db)
  const [kind, setKind] = useState<typeof REPORTS[number]>('Monthly')
  const [fy, setFy] = useState(fyStart(thisMonth())); const [period, setPeriod] = useState<'fy' | string>('fy')
  const months = fyMonths(fy)
  const [from, to] = period === 'fy' ? [monthRange(months[0])[0], monthRange(months[11])[1]] : monthRange(period)
  const inRange = txns(db).filter(t => t.dateMillis >= from && t.dateMillis <= to && counted(t))
  const years = [...new Set([...txns(db).map(t => fyStart(istMonth(t.dateMillis))), ...db.rows('archive_index').map(a => fyStart(a.uid)), fyStart(thisMonth())])].sort()
  let head: string[] = []; let rows: (string | number)[][] = []; let money_cols = new Set<number>()
  const sumBy = (list: Txn[], key: (t: Txn) => string) => { const m = new Map<string, number>(); list.forEach(t => m.set(key(t), (m.get(key(t)) ?? 0) + t.amountPaise)); return [...m.entries()].sort((a, b) => b[1] - a[1]) }
  const premium = (kinds: string[], catPrefix: string[]) => {
    const inv = new Map(db.rows('investments').filter(i => kinds.includes(i.kind)).map(i => [i.uid, i]))
    const fromEntries = db.rows('investment_entries').filter(e => inv.has(e.investmentId) && e.dateMillis >= from && e.dateMillis <= to).map(e => [istDate(e.dateMillis), inv.get(e.investmentId)!.name, e.kind.toLowerCase(), e.amountPaise, e.transactionId])
    const linked = new Set(fromEntries.map(e => e[4]))
    const fromTxns = inRange.filter(t => !linked.has(t.uid) && catPrefix.some(p => (t.categoryId ?? '').startsWith(p))).map(t => [istDate(t.dateMillis), t.merchantName ?? categoryPath(t.categoryId, cats), 'payment (not linked)', t.amountPaise, ''])
    return [...fromEntries, ...fromTxns].sort((a, b) => String(a[0]) < String(b[0]) ? -1 : 1).map(r => r.slice(0, 4) as (string | number)[])
  }
  switch (kind) {
    case 'Monthly': head = ['Month', 'Income', 'Spent', 'Invested & premiums', 'Refunds', 'Left over', 'Saved %']; money_cols = new Set([1, 2, 3, 4, 5])
      rows = months.filter(m => m <= thisMonth()).map(m => { const t = monthTotals(db, m); const left = t.income + t.refunds - t.expense - t.invested; return [monthLabel(m), t.income, t.expense, t.invested, t.refunds, left, t.income ? Math.round(left * 100 / t.income) + '%' : '—'] }); break
    case 'Annual': head = ['Financial year', 'Income', 'Spent', 'Invested & premiums', 'Left over']; money_cols = new Set([1, 2, 3, 4])
      rows = years.map(y => { const ts = fyMonths(y).map(m => monthTotals(db, m)); const s = (k: 'income' | 'expense' | 'invested' | 'refunds') => ts.reduce((a, t) => a + t[k], 0); return [fyLabel(y), s('income'), s('expense'), s('invested'), s('income') + s('refunds') - s('expense') - s('invested')] }); break
    case 'Category': { head = ['Category', 'Spent', 'Share']; money_cols = new Set([1]); const lines = expenseLines(db, inRange); const total = lines.reduce((s, l) => s + l.amountPaise, 0)
      const m = new Map<string, number>(); lines.forEach(l => m.set(l.categoryId, (m.get(l.categoryId) ?? 0) + l.amountPaise))
      rows = [...m.entries()].sort((a, b) => b[1] - a[1]).map(([c, v]) => [categoryPath(c, cats), v, Math.round(v * 1000 / Math.max(1, total)) / 10 + '%']); break }
    case 'Merchant': head = ['Merchant', 'Spent', 'Payments']; money_cols = new Set([1])
      rows = sumBy(inRange.filter(t => t.type === 'EXPENSE'), t => t.merchantName ?? 'Unknown').map(([n, v]) => [n, v, inRange.filter(t => t.type === 'EXPENSE' && (t.merchantName ?? 'Unknown') === n).length]); break
    case 'Income': head = ['Source', 'Amount']; money_cols = new Set([1]); rows = sumBy(inRange.filter(t => t.type === 'INCOME' || t.type === 'REFUND'), t => categoryPath(t.categoryId, cats) + (t.merchantName ? ' · ' + t.merchantName : '')); break
    case 'Cash': { const c = cashAccount(db)?.uid; head = ['Month', 'Withdrawn', 'Spent in cash', 'Deposited']; money_cols = new Set([1, 2, 3])
      rows = (period === 'fy' ? months : [period]).filter(m => m <= thisMonth()).map(m => { const l = txns(db).filter(t => inMonth(t, m) && counted(t)); return [monthLabel(m), l.filter(t => t.toAccountId === c && t.categoryId === 'transfers.cash').reduce((s, t) => s + t.amountPaise, 0), l.filter(t => t.accountId === c && t.type === 'EXPENSE').reduce((s, t) => s + t.amountPaise, 0), l.filter(t => t.accountId === c && t.categoryId === 'transfers.cashdep').reduce((s, t) => s + t.amountPaise, 0)] }); break }
    case 'Transfers': head = ['Date', 'Kind', 'From', 'To', 'Amount']; money_cols = new Set([4])
      rows = inRange.filter(t => t.type === 'TRANSFER').sort((a, b) => a.dateMillis - b.dateMillis).map(t => [istDate(t.dateMillis), categoryPath(t.categoryId, cats), accountLabel(db, t.accountId), t.toAccountId ? accountLabel(db, t.toAccountId) : (t.merchantName ?? '—'), t.amountPaise]); break
    case 'LIC & insurance premiums': head = ['Date', 'Policy', 'Kind', 'Amount']; money_cols = new Set([3]); rows = premium(['LIC', 'INSURANCE'], ['investments.lic', 'insurance']); break
    case 'Bond instalments': head = ['Date', 'Bond', 'Kind', 'Amount']; money_cols = new Set([3]); rows = premium(['BOND'], ['investments.bonds']); break
  }
  const total = kind === 'Annual' ? [] : head.map((_, i) => money_cols.has(i) ? rows.reduce((s, r) => s + (Number(r[i]) || 0), 0) : null)
  const fileBase = `kosh-${kind.toLowerCase().replace(/[^a-z]+/g, '-')}-${period === 'fy' ? fyLabel(fy).replace(/\s/g, '') : period}`
  const asCsv = () => downloadText(fileBase + '.csv', [head, ...rows.map(r => r.map((v, i) => money_cols.has(i) ? (Number(v) / 100).toFixed(2) : v))].map(r => r.map(csvEscape).join(',')).join('\n'))
  const asXlsx = async () => { const XLSX = await import('xlsx'); const ws = XLSX.utils.aoa_to_sheet([head, ...rows.map(r => r.map((v, i) => money_cols.has(i) ? Number(v) / 100 : v))]); const wb = XLSX.utils.book_new(); XLSX.utils.book_append_sheet(wb, ws, kind.slice(0, 30)); XLSX.writeFile(wb, fileBase + '.xlsx') }
  return <Page title="Reports" actions={<><button onClick={asCsv}>CSV</button><button onClick={asXlsx}>Excel</button><button onClick={() => print()}>Print / PDF</button></>}>
    <div className="tabs">{REPORTS.map(r => <button key={r} className={r === kind ? 'on' : ''} onClick={() => setKind(r)}>{r}</button>)}</div>
    {kind !== 'Annual' && <div className="filters">
      <Field label="Financial year"><select value={fy} onChange={e => { setFy(Number(e.target.value)); setPeriod('fy') }}>{years.map(y => <option key={y} value={y}>{fyLabel(y)}</option>)}</select></Field>
      {kind !== 'Monthly' && <Field label="Period"><select value={period} onChange={e => setPeriod(e.target.value)}><option value="fy">Whole year (Apr–Mar)</option>{months.filter(m => m <= thisMonth()).map(m => <option key={m} value={m}>{monthLabel(m)}</option>)}</select></Field>}
    </div>}
    {['Category', 'Merchant', 'Income', 'Transfers'].includes(kind) && from < monthRange(firstKeptMonth())[0] && <p className="muted">Months before {monthLabel(firstKeptMonth())} are archived; item-level reports cover only months kept here (totals reports include archived months).</p>}
    <Card>{!rows.length ? <Empty>Nothing in this period.</Empty> : <table className="tbl"><thead><tr>{head.map((h, i) => <th key={h} className={money_cols.has(i) ? 'r' : ''}>{h}</th>)}</tr></thead>
      <tbody>{rows.map((r, j) => <tr key={j}>{r.map((v, i) => <td key={i} className={money_cols.has(i) ? 'r' : ''}>{money_cols.has(i) ? money(Number(v)) : v}</td>)}</tr>)}</tbody>
      {total.some(x => x != null) && <tfoot><tr>{total.map((v, i) => <td key={i} className={money_cols.has(i) ? 'r' : ''}>{i === 0 ? 'Total' : v != null ? money(v) : ''}</td>)}</tr></tfoot>}</table>}</Card>
  </Page>
}

// ====================================================================== Import
export function Import() {
  const db = useDb()
  const [rows, setRows] = useState<StmtRow[] | null>(null); const [acct, setAcct] = useState<string | null>(null); const [msg, setMsg] = useState(''); const [pw, setPw] = useState(''); const [file, setFile] = useState<File | null>(null)
  const [shot, setShot] = useState<Partial<Txn> | null>(null)
  const load = async (f: File, password?: string) => {
    setFile(f); setMsg('Reading…'); setRows(null)
    try { const r = await readStatement(f, password); setRows(r); setMsg(r.length ? '' : 'No transactions found in this file. If it is a scanned PDF, download the CSV/Excel version from net banking instead.') }
    catch (e: any) { setMsg(e?.name === 'PasswordException' ? 'This PDF is password-protected. Enter its password (often your customer ID or date of birth) and read again.' : 'Could not read: ' + e.message) }
  }
  const run = async () => {
    const a = acct && db.get<Account>('accounts', acct); if (!a || !rows) return
    setMsg('Importing…'); const r = await importRows(db, rows, a); setRows(null)
    setMsg(`Done: ${r.saved} saved, ${r.review} waiting in Review, ${r.merged} matched entries already captured by SMS, ${r.skipped} already imported.`)
  }
  const ocr = async (f: File) => {
    setMsg('Reading the screenshot on this computer…')
    try { const r = await readScreenshot(f); setMsg(''); setShot({ amountPaise: r.amountPaise ?? undefined, dateMillis: r.date ? fromIstDate(r.date) : fromIstDate(istDate(Date.now())), merchantName: r.payee, status: 'NEEDS_REVIEW', sources: 'SHARED', confidence: 60, note: 'From screenshot', type: 'EXPENSE', method: 'UPI' }) }
    catch (e: any) { setMsg('Could not read the image: ' + e.message) }
  }
  return <Page title="Import">
    <Card title="Bank or card statement">
      <p>CSV, Excel (.xlsx/.xls) or PDF statements from any bank. Files are read in this browser — nothing is uploaded except the resulting (encrypted) transactions. Entries already captured from SMS on your phone are matched, not doubled.</p>
      <div className="row"><input type="file" accept=".csv,.txt,.xlsx,.xls,.ods,.pdf" onChange={e => e.target.files?.[0] && load(e.target.files[0])} />
        {file?.name.toLowerCase().endsWith('.pdf') && <><input placeholder="PDF password (if any)" type="password" value={pw} onChange={e => setPw(e.target.value)} /><button onClick={() => file && load(file, pw)}>Read again</button></>}</div>
      {rows && rows.length > 0 && <>
        <div className="row"><b>{rows.length} rows</b> · Account: <AccountSelect value={acct} onChange={setAcct} empty="Choose the account" kinds={['BANK', 'CARD', 'WALLET']} /><button className="primary" disabled={!acct} onClick={run}>Import</button></div>
        <table className="tbl small"><thead><tr><th>Date</th><th>Narration</th><th className="r">Debit</th><th className="r">Credit</th></tr></thead>
          <tbody>{rows.slice(0, 40).map((r, i) => <tr key={i}><td>{fmtDay(r.dateMillis)}</td><td>{r.narration}</td><td className="r">{r.debit ? money(r.amountPaise) : ''}</td><td className="r">{r.debit ? '' : money(r.amountPaise)}</td></tr>)}</tbody></table>
        {rows.length > 40 && <p className="muted">…and {rows.length - 40} more.</p>}</>}
    </Card>
    <Card title="Screenshot of a payment"><p>UPI receipts, card alerts, bills. Text is read in this browser (Tesseract); you confirm the details before saving.</p>
      <input type="file" accept="image/*" onChange={e => e.target.files?.[0] && ocr(e.target.files[0])} /></Card>
    {msg && <p className="note">{msg}</p>}
    {shot && <TxnEditor initial={shot} onClose={() => setShot(null)} />}
  </Page>
}

// ====================================================================== AI assistant
export function Assistant() {
  const db = useDb()
  const [q, setQ] = useState(''); const [a, setA] = useState(''); const [busy, setBusy] = useState(''); const [err, setErr] = useState('')
  const [ym, setYm] = useState(addMonths(thisMonth(), -1))
  const saved = db.rows('summaries').find(s => s.month === ym)
  const [sugg, setSugg] = useState<(AI.Suggestion & { on: boolean })[]>([])
  const unknown = AI.unknownMessages(db).length
  const run = async (label: string, f: () => Promise<void>) => { setBusy(label); setErr(''); try { await f() } catch (e: any) { setErr(e.message) } setBusy('') }
  const hasKey = !!AI.aiSettings.key
  return <Page title="AI assistant">
    {!hasKey && <p className="note">Add your free Gemini API key in <a href="#/settings">Settings</a> to use the assistant.</p>}
    <Card title="Ask about your money"><p className="muted">Gemini sees monthly totals, categories and top merchants only — never account numbers, names or message text.</p>
      <div className="row"><input className="grow" value={q} onChange={e => setQ(e.target.value)} onKeyDown={e => e.key === 'Enter' && q && run('ask', async () => setA(await AI.ask(db, q)))} placeholder="How much did I spend on food delivery this financial year?" />
        <button className="primary" disabled={!hasKey || !q || !!busy} onClick={() => run('ask', async () => setA(await AI.ask(db, q)))}>{busy === 'ask' ? 'Thinking…' : 'Ask'}</button></div>
      {a && <div className="answer">{a}</div>}</Card>
    <Card title="Monthly summary" actions={<MonthSwitch ym={ym} set={setYm} />}>
      {saved ? <div className="answer">{saved.text}</div> : <Empty>No summary for {monthLabel(ym)} yet.</Empty>}
      <button disabled={!!busy} onClick={() => run('sum', async () => { const text = await AI.summary(db, ym); db.put('summaries', { month: ym, text, source: hasKey ? 'AI' : 'LOCAL', createdAt: Date.now() }, saved?.uid) })}>{busy === 'sum' ? 'Writing…' : saved ? 'Write again' : 'Write summary'}</button></Card>
    <Card title="Messages the rules could not read"><p>{unknown} skipped message(s) mention an amount. Gemini reads them with identifiers masked; results go to Review for your approval.</p>
      <button disabled={!hasKey || !unknown || !!busy} onClick={() => run('read', async () => { const s = await AI.readUnknown(db); setSugg(s.map(x => ({ ...x, on: true }))); if (!s.length) setErr('No transactions found in those messages.') })}>{busy === 'read' ? 'Reading…' : 'Read with AI'}</button>
      {sugg.length > 0 && <><table className="tbl"><tbody>{sugg.map((s, i) => <tr key={i}><td><input type="checkbox" checked={s.on} onChange={e => setSugg(sugg.map((x, j) => j === i ? { ...x, on: e.target.checked } : x))} /></td><td>{fmtDay(s.dateMillis)}</td><td>{s.merchant ?? '—'}</td><td>{typeLabel(s.type)}</td><td>{s.categoryId}</td><td className="r">{money(s.amountPaise)}</td></tr>)}</tbody></table>
        <button className="primary" onClick={() => { const now = Date.now(); sugg.filter(s => s.on).forEach(s => { const raw = db.get('raw_messages', s.rawUid); const id = db.put('transactions', { type: s.type, direction: s.direction, status: 'NEEDS_REVIEW', amountPaise: s.amountPaise, dateMillis: s.dateMillis, merchantName: s.merchant, categoryId: s.categoryId, confidence: s.confidence, sources: raw?.source ?? 'SMS', note: 'Read by AI — please check', createdAt: now, updatedAt: now, method: 'OTHER' }); db.put('raw_messages', { outcome: 'RECOGNISED', transactionId: id, confidence: s.confidence }, s.rawUid) }); setSugg([]); go('review') }}>Add selected to Review</button></>}</Card>
    {err && <p className="err">{err}</p>}
  </Page>
}

// ====================================================================== Archive
export function Archive({ arg }: { arg?: string }) {
  const db = useDb(); const { sync } = useApp(); const cats = categories(db)
  const [rows, setRows] = useState<Txn[] | null>(null); const [err, setErr] = useState('')
  useEffect(() => { if (!arg) return; setRows(null); setErr(''); db.fetchArchive(sync.store, arg).then(r => setRows(r as Txn[]), e => setErr(e.message)) }, [arg])
  if (arg) return <Page title={`Archive · ${monthLabel(arg)}`} actions={<a href="#/archive">← All months</a>}>
    <p className="muted">Fetched from your Google Drive on request; read-only.</p>{err && <p className="err">{err}</p>}{!rows && !err && <p>Loading…</p>}
    {rows && <Card><table className="tbl"><tbody>{rows.sort((a, b) => a.dateMillis - b.dateMillis).map(t => <tr key={t.uid}><td>{fmtDay(t.dateMillis)}</td><td>{t.merchantName ?? '—'}</td><td>{categoryPath(t.categoryId, cats)}</td><td>{statusLabel(t.status)}</td><td className={'r amt ' + t.type}>{sign(t)}{money(t.amountPaise)}</td></tr>)}</tbody></table></Card>}
  </Page>
  const list = db.rows('archive_index').sort((a, b) => a.uid < b.uid ? 1 : -1)
  return <Page title="Archive"><p className="muted">Kosh keeps the last 12 months on your devices. Older months are moved (encrypted) to your Google Drive by the phone; their totals stay available everywhere.</p>
    {!list.length ? <Empty>Nothing archived yet.</Empty> : <Card><table className="tbl"><thead><tr><th>Month</th><th className="r">Income</th><th className="r">Spent</th><th className="r">Invested</th><th className="r">Transactions</th></tr></thead>
      <tbody>{list.map(a => <tr key={a.uid} className="click" onClick={() => go('archive/' + a.uid)}><td>{monthLabel(a.uid)}</td><td className="r">{money(a.income, { paise: false })}</td><td className="r">{money(a.expense, { paise: false })}</td><td className="r">{money(a.invested, { paise: false })}</td><td className="r">{a.transactionCount}</td></tr>)}</tbody></table></Card>}
  </Page>
}

// ====================================================================== Settings
export function Settings() {
  const db = useDb(); const { sync } = useApp()
  const [cid, setCid] = useState(sync.clientId); const [pass, setPass] = useState(''); const [err, setErr] = useState(''); const [name, setName] = useState(localStorage.getItem('kosh.name') ?? '')
  const [key, setKey] = useState(''); const [model, setModel] = useState(AI.aiSettings.model); const [aiMsg, setAiMsg] = useState('')
  return <Page title="Settings & sync">
    <Card title="Sync with your phone (Google Drive)">
      <p>Your data lives encrypted in a hidden Kosh folder in your own Google Drive. This browser keeps a copy of the last 12 months (in IndexedDB) and syncs changes both ways.</p>
      <Field label="Google OAuth client ID (from your Google Cloud project)"><input value={cid} onChange={e => setCid(e.target.value)} onBlur={() => sync.setClientId(cid)} placeholder="1234-abc.apps.googleusercontent.com" /></Field>
      {!db.connected ? <>
        <Field label="Sync passphrase (the one you set on the phone)"><input type="password" value={pass} onChange={e => setPass(e.target.value)} /></Field>
        <button className="primary" disabled={!cid || !pass || sync.busy} onClick={async () => { setErr(''); sync.setClientId(cid); try { await sync.connect(pass); setPass('') } catch (e: any) { setErr(e.message) } }}>{sync.busy ? 'Connecting…' : 'Sign in with Google & connect'}</button>
      </> : <>
        <p><b>Connected.</b> {sync.status}. Pending local changes: {db.outbox.size}.</p>
        <div className="row"><button className="primary" disabled={sync.busy} onClick={() => sync.run(true)}>{sync.busy ? 'Syncing…' : 'Sync now'}</button>
          <button onClick={() => db.disconnect(false)}>Disconnect (keep data here)</button>
          <button className="danger" onClick={() => confirm('Remove all Kosh data from this browser? Your Drive and phone keep everything.') && db.disconnect(true)}>Disconnect & clear this browser</button></div>
      </>}
      {err && <p className="err">{err}</p>}
      <p className="muted">Battery and data: uploads happen 20 seconds after your last edit; new data is fetched when you return to this tab and every 10 minutes only while it is visible.</p>
    </Card>
    <Card title="Greeting"><Field label="Your name"><input value={name} onChange={e => setName(e.target.value)} onBlur={() => localStorage.setItem('kosh.name', name.trim())} /></Field></Card>
    <Card title="AI (Gemini free tier)">
      <p>{AI.aiSettings.key ? 'A key is saved in this browser.' : 'Paste a free key from Google AI Studio (aistudio.google.com → Get API key).'} On a shared computer, remove it when you are done.</p>
      <div className="grid2"><Field label="Gemini API key"><input type="password" value={key} onChange={e => setKey(e.target.value)} /></Field>
        <Field label="Model"><input list="models" value={model} onChange={e => setModel(e.target.value)} /><datalist id="models">{AI.MODELS.map(m => <option key={m} value={m} />)}</datalist></Field></div>
      <div className="row"><button className="primary" onClick={async () => { if (key) AI.aiSettings.key = key; AI.aiSettings.model = model; setKey(''); setAiMsg('Testing…'); try { setAiMsg('Gemini replied: ' + (await AI.gemini('Reply with the single word OK.', false, 10)).trim()) } catch (e: any) { setAiMsg(e.message) } }}>Save & test</button>
        {AI.aiSettings.key && <button onClick={() => { AI.aiSettings.key = ''; setAiMsg('Key removed.') }}>Remove key</button>}</div>
      {aiMsg && <p className="muted">{aiMsg}</p>}
    </Card>
    <Card title="Security">
      <ul className="muted"><li>Kosh never asks for or stores bank passwords, UPI PINs or OTPs.</li><li>Only the last 4 digits of account, card and policy numbers are kept.</li>
        <li>Everything in Drive is encrypted with AES-256 using a key derived from your passphrase; the passphrase never leaves your devices.</li>
        <li>The AI assistant only receives masked text or totals, and AI findings always wait for your approval.</li><li>Every change to a transaction is kept in an audit log.</li></ul>
    </Card>
  </Page>
}

// ====================================================================== Categories & rules
export function Categories() {
  const db = useDb()
  const [tab, setTab] = useState<'cats' | 'merchants' | 'keywords'>('cats')
  const all = db.rows<Category>('categories').sort((a, b) => a.sortOrder - b.sortOrder)
  const tops = all.filter(c => !c.parentId)
  const cats = categories(db)
  const used = new Map<string, number>(); txns(db).forEach(t => t.categoryId && used.set(t.categoryId, (used.get(t.categoryId) ?? 0) + 1))
  const [edit, setEdit] = useState<{ c?: Category; parent?: Category; name: string; fixed: boolean; hidden: boolean } | null>(null)
  const [q, setQ] = useState('')
  const [kw, setKw] = useState<{ uid?: string; keyword: string; categoryId: string | null } | null>(null)

  const saveCat = () => {
    if (!edit || !edit.name.trim()) return
    if (edit.c) db.put('categories', { name: edit.name.trim(), isFixed: edit.fixed ? 1 : 0, hidden: edit.hidden ? 1 : 0 }, edit.c.id)
    else {
      const slug = edit.name.trim().toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '') || 'custom'
      let id = edit.parent ? `${edit.parent.id}.${slug}` : slug; let n = 2
      while (cats.has(id)) id = `${edit.parent ? edit.parent.id + '.' : ''}${slug}${n++}`
      db.put('categories', { id, name: edit.name.trim(), parentId: edit.parent?.id ?? null, kind: edit.parent?.kind ?? 'EXPENSE', icon: edit.parent?.icon ?? 'category',
        sortOrder: Math.max(0, ...all.map(c => c.sortOrder)) + 1, hidden: 0, isFixed: edit.fixed ? 1 : 0 })
    }
    setEdit(null)
  }
  const merchants = db.rows('merchants').filter(m => !q || String(m.displayName).toLowerCase().includes(q.toLowerCase())).sort((a, b) => String(a.displayName).localeCompare(b.displayName))
  const keywords = db.rows('keyword_rules').sort((a, b) => a.sortOrder - b.sortOrder)

  return <Page title="Categories & rules" actions={tab === 'cats' ? <button className="primary" onClick={() => setEdit({ name: '', fixed: false, hidden: false })}>+ New category</button> : tab === 'keywords' ? <button className="primary" onClick={() => setKw({ keyword: '', categoryId: null })}>+ New keyword</button> : undefined}>
    <div className="tabs"><button className={tab === 'cats' ? 'on' : ''} onClick={() => setTab('cats')}>Categories</button><button className={tab === 'merchants' ? 'on' : ''} onClick={() => setTab('merchants')}>Merchant rules</button><button className={tab === 'keywords' ? 'on' : ''} onClick={() => setTab('keywords')}>Keyword rules</button></div>
    {tab === 'cats' && <>
      <p className="muted">Rename, add subcategories, hide ones you don't use, and mark fixed costs (rent, bills, insurance) for the recurring-expense ratio. Changes sync to your phone.</p>
      {tops.map(t => <Card key={t.id} title={<>{t.name}{t.hidden ? <small className="tag">hidden</small> : null}{t.isFixed ? <small className="tag">fixed</small> : null} <small className="muted">· {({ EXPENSE: 'spending', INCOME: 'income', TRANSFER: 'transfer', INVESTMENT: 'investment' } as any)[t.kind] ?? t.kind}</small></>}
        actions={<><button className="link" onClick={() => setEdit({ c: t, name: t.name, fixed: !!t.isFixed, hidden: !!t.hidden })}>Edit</button><button onClick={() => setEdit({ parent: t, name: '', fixed: false, hidden: false })}>+ Subcategory</button></>}>
        <div className="chips">{all.filter(c => c.parentId === t.id).map(c => <button key={c.id} className={'chip' + (c.hidden ? ' off' : '')} onClick={() => setEdit({ c, name: c.name, fixed: !!c.isFixed, hidden: !!c.hidden })}>{c.name}{c.isFixed ? ' · fixed' : ''}{used.get(c.id) ? <small> {used.get(c.id)}</small> : null}</button>)}</div>
      </Card>)}
    </>}
    {tab === 'merchants' && <>
      <p className="muted">Which category each merchant goes to. Changing one here also applies to new transactions on your phone.</p>
      <input placeholder="Search merchants" value={q} onChange={e => setQ(e.target.value)} />
      <Card><table className="tbl"><thead><tr><th>Merchant</th><th>Category</th><th>Set by</th></tr></thead><tbody>{merchants.slice(0, 300).map(m => <tr key={m.uid}>
        <td>{m.displayName}</td><td><CategorySelect value={m.categoryId} onChange={v => db.put('merchants', { categoryId: v, source: 'USER' }, m.uid)} /></td><td className="muted">{m.source === 'USER' ? 'you' : 'built-in'}</td></tr>)}</tbody></table></Card>
    </>}
    {tab === 'keywords' && <>
      <p className="muted">Used when the merchant is unknown: if a message or statement line contains the word, it gets this category. Checked top to bottom.</p>
      <Card><table className="tbl"><tbody>{keywords.map(k => <tr key={k.uid} className="click" onClick={() => setKw({ uid: k.uid, keyword: k.keyword, categoryId: k.categoryId })}><td>{k.keyword}</td><td>{categoryPath(k.categoryId, cats)}</td></tr>)}</tbody></table></Card>
    </>}
    {edit && <Modal title={edit.c ? 'Edit category' : edit.parent ? `New subcategory of ${edit.parent.name}` : 'New category'} onClose={() => setEdit(null)}>
      <Field label="Name"><input autoFocus value={edit.name} onChange={e => setEdit({ ...edit, name: e.target.value })} /></Field>
      <label className="check"><input type="checkbox" checked={edit.fixed} onChange={e => setEdit({ ...edit, fixed: e.target.checked })} /> Fixed / recurring cost (rent, bills, insurance, EMIs)</label>
      {edit.c && <label className="check"><input type="checkbox" checked={edit.hidden} onChange={e => setEdit({ ...edit, hidden: e.target.checked })} /> Hide from pickers (existing transactions keep it)</label>}
      {edit.c && used.get(edit.c.id) ? <p className="muted">{used.get(edit.c.id)} transaction(s) use this category.</p> : null}
      <footer><span className="grow" /><button className="primary" onClick={saveCat}>Save</button></footer>
    </Modal>}
    {kw && <Modal title={kw.uid ? 'Keyword rule' : 'New keyword rule'} onClose={() => setKw(null)}>
      <Field label="Word or phrase"><input autoFocus value={kw.keyword} onChange={e => setKw({ ...kw, keyword: e.target.value })} placeholder="e.g. petrol" /></Field>
      <Field label="Category"><CategorySelect value={kw.categoryId} onChange={v => setKw({ ...kw, categoryId: v })} allowEmpty={false} /></Field>
      <footer>{kw.uid && <button className="danger" onClick={() => { db.remove('keyword_rules', kw.uid!); setKw(null) }}>Delete</button>}<span className="grow" />
        <button className="primary" disabled={!kw.keyword.trim() || !kw.categoryId} onClick={() => { db.put('keyword_rules', { keyword: kw.keyword.trim().toLowerCase(), categoryId: kw.categoryId, sortOrder: kw.uid ? db.get('keyword_rules', kw.uid)?.sortOrder : Math.max(0, ...keywords.map(k => k.sortOrder)) + 1 }, kw.uid ?? `kw:${kw.keyword.trim().toLowerCase()}`); setKw(null) }}>Save</button></footer>
    </Modal>}
  </Page>
}


// ====================================================================== Loans (lent & borrowed)
const LOAN_TITLES: Record<string, string> = {
  'LENT:principal': 'Lend more', 'LENT:repayment': 'Money received back', 'BORROWED:principal': 'Borrow more', 'BORROWED:repayment': 'Pay back',
}
export function Loans() {
  const db = useDb(); const all = loanList(db)
  const open = all.filter(l => !l.closed); const closed = all.filter(l => l.closed)
  const lentPending = open.filter(l => l.kind === 'LENT').reduce((a, l) => a + Math.max(0, l.pending), 0)
  const owePending = open.filter(l => l.kind === 'BORROWED').reduce((a, l) => a + Math.max(0, l.pending), 0)
  const [modal, setModal] = useState<{ kind: LoanKind; loan?: Loan; step: LoanStep } | null>(null)
  const [history, setHistory] = useState<string | null>(null)
  const close = (l: Loan) => {
    const msg = l.pending > 0 ? `${money(l.pending)} is still ${l.kind === 'LENT' ? 'unpaid by' : 'unpaid to'} ${l.person}. Close this loan anyway (treat it as settled / written off)?` : `Close the loan with ${l.person}?`
    if (confirm(msg)) { closeLoan(db, l.uid); toast(`Loan with ${l.person} closed`) }
  }
  const card = (l: Loan) => {
    const pct = l.principal > 0 ? Math.round(l.settled * 100 / l.principal) : 0
    const lent = l.kind === 'LENT'
    return <section key={l.uid} className="card loan">
      <header><h3>{l.person} <small className="tag">{lent ? 'you lent' : 'you borrowed'}</small></h3><span className="muted">{l.startMillis ? fmtDay(l.startMillis) : ''}</span></header>
      {l.note && <p className="muted">{l.note}</p>}
      <div className="kv"><span>{lent ? 'Lent' : 'Borrowed'}</span><b>{money(l.principal, { paise: false })}</b></div>
      <div className="kv"><span>{lent ? 'Received back' : 'Paid back'}</span><b>{money(l.settled, { paise: false })}</b></div>
      <Bar pct={pct} ok />
      <div className="kv"><span>{l.pending < 0 ? (lent ? 'Received extra' : 'Paid extra') : 'Still pending'}</span><b className={l.pending > 0 ? (lent ? '' : 'err') : ''}>{money(Math.abs(l.pending), { paise: false })}</b></div>
      <div className="row">
        <button className="primary" onClick={() => setModal({ kind: l.kind, loan: l, step: 'repayment' })}>{lent ? 'Money received' : 'I paid back'}</button>
        <button onClick={() => setModal({ kind: l.kind, loan: l, step: 'principal' })}>{lent ? 'Lend more' : 'Borrow more'}</button>
        <button className="link" onClick={() => setHistory(history === l.uid ? null : l.uid)}>{history === l.uid ? 'Hide history' : 'History'}</button>
        <span className="grow" /><button onClick={() => close(l)}>Close loan</button>
      </div>
      {history === l.uid && <TxnTable list={[...l.entries].reverse()} />}
    </section>
  }
  return <Page title="Loans" actions={<><button onClick={() => setModal({ kind: 'BORROWED', step: 'principal' })}>+ I borrowed</button><button className="primary" onClick={() => setModal({ kind: 'LENT', step: 'principal' })}>+ I lent</button></>}>
    <div className="stats"><Stat label="Owed to you" value={money(lentPending, { paise: false })} tone="pos" /><Stat label="You owe" value={money(owePending, { paise: false })} tone={owePending ? 'neg' : ''} /><Stat label="Open loans" value={String(open.length)} /></div>
    {!open.length ? <Empty>No open loans. Use “+ I lent” when you give money to someone — it comes out of the account you choose, is not counted as spending, and stays here until you close it yourself.</Empty>
      : <div className="cards">{open.map(card)}</div>}
    {closed.length > 0 && <Card title={`Closed loans (${closed.length})`}><table className="tbl"><tbody>{closed.map(l => <tr key={l.uid}><td>{l.person}</td><td>{l.kind === 'LENT' ? 'lent' : 'borrowed'} {money(l.principal, { paise: false })}</td>
      <td>{l.pending !== 0 ? <span className="muted">{money(Math.abs(l.pending), { paise: false })} {l.pending > 0 ? 'written off' : 'extra'}</span> : <span className="muted">settled</span>}</td>
      <td className="r"><button className="link" onClick={() => reopenLoan(db, l.uid)}>Reopen</button></td></tr>)}</tbody></table></Card>}
    {modal && <LoanModal {...modal} onClose={() => setModal(null)} />}
  </Page>
}

function LoanModal({ kind, loan, step, onClose }: { kind: LoanKind; loan?: Loan; step: LoanStep; onClose: () => void }) {
  const db = useDb(); const lent = kind === 'LENT'
  const intoLoan = lent === (step === 'principal') // money leaves one of my accounts
  const [person, setPerson] = useState('')
  const [amt, setAmt] = useState(loan && step === 'repayment' && loan.pending > 0 ? String(loan.pending / 100) : '')
  const [date, setDate] = useState(istDate(Date.now())); const [method, setMethod] = useState('UPI'); const [note, setNote] = useState('')
  const [acct, setAcct] = useState<string | null>(accounts(db).find(a => a.kind === 'BANK')?.uid ?? null)
  const [err, setErr] = useState('')
  const paise = parseRupees(amt) ?? 0
  const title = loan ? `${LOAN_TITLES[kind + ':' + step]} · ${loan.person}` : lent ? 'Lend money' : 'Borrow money'
  const save = () => {
    if (!paise) return setErr('Enter an amount.')
    if (!acct) return setErr(intoLoan ? 'Choose the account the money came from.' : 'Choose the account the money went into.')
    const m = { amountPaise: paise, dateMillis: fromIstDate(date), accountId: acct, method, note: note || null }
    try {
      if (loan) loanMove(db, loan.uid, kind, loan.person, step, m)
      else createLoan(db, kind, person, m)
    } catch (e: any) { return setErr(e.message) }
    if (loan && step === 'repayment' && paise >= loan.pending && loan.pending > 0) toast(`${loan.person} is fully settled — close the loan when you are ready`, 'ok')
    else toast(`${money(paise, { paise: false })} recorded`)
    onClose()
  }
  return <Modal title={title} onClose={onClose}>
    {!loan && <Field label={lent ? 'Lent to (person)' : 'Borrowed from (person)'}><input autoFocus value={person} onChange={e => setPerson(e.target.value)} placeholder="Name" /></Field>}
    <div className="grid2">
      <Field label="Amount (₹)"><input inputMode="decimal" autoFocus={!!loan} value={amt} onChange={e => setAmt(e.target.value)} /></Field>
      <Field label="Date"><input type="date" value={date} onChange={e => e.target.value && setDate(e.target.value)} /></Field>
      <Field label="Method"><select value={method} onChange={e => { setMethod(e.target.value); if (e.target.value === 'CASH') { const c = cashAccount(db); if (c) setAcct(c.uid) } }}>{METHODS.map(m => <option key={m}>{m}</option>)}</select></Field>
      <Field label={intoLoan ? 'Paid from account' : 'Received into account'}><AccountSelect cards value={acct} onChange={setAcct} /></Field>
    </div>
    <Field label="Note"><input value={note} onChange={e => setNote(e.target.value)} placeholder={loan ? '' : 'What it was for, when it is due…'} /></Field>
    {loan && step === 'repayment' && paise > loan.pending && loan.pending > 0 && <p className="muted">That is {money(paise - loan.pending)} more than what is pending (for example interest). It will show as extra.</p>}
    <p className="muted">Not counted as {intoLoan ? 'spending' : 'income'}. {intoLoan ? 'Your account balance goes down by this amount' : 'Your account balance goes up by this amount'}.</p>
    {err && <p className="err">{err}</p>}
    <footer><span className="grow" /><button className="primary" onClick={save}>Save</button></footer>
  </Modal>
}
