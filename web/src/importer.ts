import type { LocalDb } from './db'
import { classify, txns, type Account, type Txn } from './logic'
import { clean, type StmtRow } from './statement'

const DAY = 86_400_000
async function sha(text: string) {
  const h = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)))
  return [...h].map(b => b.toString(16).padStart(2, '0')).join('')
}

export type ImportResult = { saved: number; review: number; merged: number; skipped: number }

/**
 * Adds statement rows. A row matching a transaction already captured (same account and amount within
 * two days, references not conflicting) is merged instead of doubled. Confidence decides whether a row
 * is saved straight away (≥95), waits in Check (80–94) or in Review (<80) — like on the phone.
 */
export async function importRows(db: LocalDb, rows: StmtRow[], account: Account, thresholds = { auto: 95, review: 80 }): Promise<ImportResult> {
  const res: ImportResult = { saved: 0, review: 0, merged: 0, skipped: 0 }
  const existing = txns(db).filter(t => t.status !== 'REJECTED')
  const seenHashes = new Set(db.rows('raw_messages').map(r => r.hash))
  for (const r of rows) {
    const { name, ref } = clean(r.narration)
    const reference = ref ?? r.reference?.replace(/^0+/, '') ?? null
    const body = `${r.debit ? 'Debit' : 'Credit'} ${r.amountPaise / 100} on ${new Date(r.dateMillis).toISOString().slice(0, 10)} ${account.name}: ${r.narration}`
    const hash = await sha('IMPORT|' + account.uid + '|' + body)
    if (seenHashes.has(hash)) { res.skipped++; continue }
    seenHashes.add(hash)
    const direction = r.debit ? 'DEBIT' : 'CREDIT'
    const match = existing.find(t => t.amountPaise === r.amountPaise && t.direction === direction && Math.abs(t.dateMillis - r.dateMillis) <= 2 * DAY &&
      (t.accountId === account.uid || t.toAccountId === account.uid || (!t.accountId && t.accountLast4 === account.last4)) &&
      !(reference && t.reference && t.reference !== reference) && !t.sources.split(',').includes('IMPORT'))
    const now = Date.now()
    let txnUid: string
    if (match) {
      txnUid = match.uid
      db.put('transactions', { sources: [...new Set([...match.sources.split(','), 'IMPORT'])].join(','), reference: match.reference ?? reference, accountId: match.accountId ?? account.uid, updatedAt: now }, match.uid)
      res.merged++
    } else {
      const c = classify(db, name + ' ' + r.narration, r.debit)
      const status = c.confidence >= thresholds.auto ? 'POSTED' : c.confidence >= thresholds.review ? 'PENDING_VERIFICATION' : 'NEEDS_REVIEW'
      const cash = c.categoryId === 'transfers.cash' || c.categoryId === 'transfers.cashdep' ? db.rows('accounts').find(a => a.kind === 'CASH')?.uid ?? null : null
      const t: Partial<Txn> = { type: c.type, direction, status, amountPaise: r.amountPaise, dateMillis: r.dateMillis, accountId: account.uid, accountLast4: account.last4,
        merchantKey: c.merchantKey, merchantName: c.merchantName ?? (name !== r.narration ? titleCase(name) : null), categoryId: c.categoryId,
        method: /^upi/i.test(r.narration) ? 'UPI' : /neft/i.test(r.narration) ? 'NEFT' : /imps/i.test(r.narration) ? 'IMPS' : /atm|nwd/i.test(r.narration) ? 'ATM' : /^pos/i.test(r.narration) ? 'CARD' : 'OTHER',
        reference, confidence: c.confidence, sources: 'IMPORT', createdAt: now, updatedAt: now, toAccountId: cash }
      txnUid = db.put('transactions', t)
      db.put('audit_log', { transactionId: txnUid, at: now, action: 'CREATED', before: null, after: JSON.stringify(t) })
      status === 'POSTED' ? res.saved++ : res.review++
      existing.push({ ...(t as Txn), uid: txnUid })
    }
    db.put('raw_messages', { source: 'IMPORT', sender: 'STATEMENT', body, receivedAt: r.dateMillis, hash, outcome: match ? 'MERGED' : 'RECOGNISED', transactionId: txnUid })
  }
  return res
}

const titleCase = (s: string) => s.toLowerCase().replace(/\b[a-z]/g, c => c.toUpperCase())

/** Reads a payment screenshot in the browser (Tesseract, on this computer) and pulls out amount, date and payee. */
export async function readScreenshot(file: File): Promise<{ text: string; amountPaise: number | null; date: string | null; payee: string | null }> {
  const { createWorker } = await import('tesseract.js')
  const w = await createWorker('eng')
  try {
    const { data } = await w.recognize(file)
    return { text: data.text, ...extract(data.text) }
  } finally { await w.terminate() }
}

export function extract(text: string) {
  const amt = text.match(/(?:₹|rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)/i) ?? text.match(/\b([\d,]{2,}\.\d{2})\b/)
  const amountPaise = amt ? Math.round(Number(amt[1].replace(/,/g, '')) * 100) || null : null
  const m = text.match(/(\d{1,2})\s+([A-Za-z]{3})[a-z]*,?\s+(\d{4})/) ?? null
  const months: Record<string, string> = { jan: '01', feb: '02', mar: '03', apr: '04', may: '05', jun: '06', jul: '07', aug: '08', sep: '09', oct: '10', nov: '11', dec: '12' }
  const date = m && months[m[2].toLowerCase()] ? `${m[3]}-${months[m[2].toLowerCase()]}-${m[1].padStart(2, '0')}` : null
  const payee = text.match(/(?:paid to|sent to|to:?|payment to)\s+([A-Za-z][A-Za-z0-9 .&'-]{1,40})/i)?.[1]?.trim() ?? null
  return { amountPaise, date, payee }
}
