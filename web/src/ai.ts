import type { LocalDb } from './db'
import { addMonths, byTopCategory, categories, inMonth, monthTotals, thisMonth, txns, counted, money as fmt, categoryPath } from './logic'

/** Removes identifiers before anything is sent to Gemini (same rules as the phone). */
export function mask(text: string): string {
  let s = text
  s = s.replace(/\b[\w.+-]+@[\w.-]+\.[a-z]{2,}\b/gi, '[email]')
  s = s.replace(/\b[\w.-]{2,}@[a-z]+\b/gi, '[upi-id]')
  s = s.replace(/(?<!\d)(\+?91[\s-]?)?[6-9]\d{9}(?!\d)/g, '[phone]')
  s = s.replace(/\b[A-Z]{4}0[A-Z0-9]{6}\b/g, '[ifsc]').replace(/\b[A-Z]{5}\d{4}[A-Z]\b/g, '[pan]')
  s = s.replace(/\b(otp|password|pin|cvv)\b[^\n]{0,40}/gi, '[secret removed]')
  s = s.replace(/(?<![\d.,])([Xx*•]*\d[\d\s-]{6,}\d)(?![\d.,])/g, m => '••' + m.replace(/\D/g, '').slice(-2))
  return s.slice(0, 1200)
}

export const MODELS = ['gemini-flash-latest', 'gemini-flash-lite-latest', 'gemini-2.5-flash']
export const aiSettings = {
  get key() { return localStorage.getItem('kosh.geminiKey') || '' },
  set key(v: string) { v ? localStorage.setItem('kosh.geminiKey', v.trim()) : localStorage.removeItem('kosh.geminiKey') },
  get model() { return localStorage.getItem('kosh.geminiModel') || MODELS[0] },
  set model(v: string) { localStorage.setItem('kosh.geminiModel', v.trim() || MODELS[0]) },
}
const SYSTEM = 'You are Kosh, a careful personal-finance assistant for one user in India. Amounts are Indian rupees (₹). Financial year runs April to March. ' +
  'Personal identifiers have been removed on purpose; never ask for them. Never ask for or mention passwords, PINs or OTPs. Be brief and concrete.'

export async function gemini(prompt: string, json = false, maxTokens = 1500): Promise<string> {
  const key = aiSettings.key; if (!key) throw new Error('Add a Gemini API key in Settings → AI.')
  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${aiSettings.model}:generateContent`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
    body: JSON.stringify({ systemInstruction: { parts: [{ text: SYSTEM }] }, contents: [{ role: 'user', parts: [{ text: prompt }] }],
      generationConfig: { temperature: 0.2, maxOutputTokens: maxTokens, ...(json ? { responseMimeType: 'application/json' } : {}) } }),
  })
  if (!r.ok) throw new Error(r.status === 429 ? 'Free-tier limit reached for now; try again in a minute.' : r.status === 404 ? `Model ${aiSettings.model} not found — pick another in Settings.` : r.status === 400 || r.status === 403 ? 'Gemini key not accepted.' : `Gemini error ${r.status}`)
  const j = await r.json()
  return (j.candidates?.[0]?.content?.parts ?? []).map((p: any) => p.text ?? '').join('')
}

/** Aggregates only: monthly totals, categories, top merchants. No account numbers, no message text. */
export function context(db: LocalDb): string {
  const cats = categories(db); const lines = [`Today: ${new Date().toISOString().slice(0, 10)}`]
  const arch = db.rows('archive_index').sort((a, b) => a.uid < b.uid ? -1 : 1).slice(-24)
  if (arch.length) { lines.push('Archived months (₹): month, income, expense, invested'); arch.forEach(a => lines.push(`${a.uid}, ${a.income / 100}, ${a.expense / 100}, ${a.invested / 100}`)) }
  lines.push('Recent months (₹): month | income | expense | invested | top categories')
  for (let k = 11; k >= 0; k--) {
    const ym = addMonths(thisMonth(), -k); const t = monthTotals(db, ym); if (!t.count) continue
    lines.push(`${ym} | ${t.income / 100} | ${t.expense / 100} | ${t.invested / 100} | ` + byTopCategory(db, ym).slice(0, 6).map(([c, v]) => `${cats.get(c)?.name ?? c} ${v / 100}`).join(', '))
    if (k <= 2) {
      const m = new Map<string, number>()
      txns(db).filter(x => inMonth(x, ym) && counted(x) && x.type === 'EXPENSE').forEach(x => m.set(x.merchantName ?? 'Unknown', (m.get(x.merchantName ?? 'Unknown') ?? 0) + x.amountPaise))
      lines.push(`  top merchants ${ym}: ` + [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8).map(([n, v]) => `${n} ${v / 100}`).join(', '))
    }
  }
  const b = db.rows('budgets'); if (b.length) lines.push('Budgets (₹/month): ' + b.map(x => `${categoryPath(x.categoryId, cats)} ${x.monthlyLimitPaise / 100}`).join(', '))
  const inv = db.rows('investments'); if (inv.length) lines.push('Policies/investments: ' + inv.map(i => `${i.kind} ${String(i.name).replace(/\d{4,}/g, '••')}`).join('; '))
  return lines.join('\n')
}

export const ask = (db: LocalDb, q: string) => gemini(`My finances (aggregated):\n${context(db)}\n\nQuestion: ${mask(q)}\nAnswer from the data only; say what is missing if it cannot be answered. Use ₹ and Indian number formatting.`, false, 1024)

export async function summary(db: LocalDb, ym: string): Promise<string> {
  const t = monthTotals(db, ym); const cats = categories(db)
  const facts = `Income ${fmt(t.income)}, spending ${fmt(t.expense)}, invested ${fmt(t.invested)}. Top: ` + byTopCategory(db, ym).slice(0, 5).map(([c, v]) => `${cats.get(c)?.name ?? c} ${fmt(v)}`).join(', ')
  if (!aiSettings.key) return facts
  return gemini(`Write my money summary for ${ym} in at most 8 short bullet points: income vs spending, biggest categories and changes from the previous month, budgets exceeded, one practical suggestion.\n\nFacts: ${facts}\n\nContext:\n${context(db)}`, false, 900)
}

export function unknownMessages(db: LocalDb) {
  return db.rows('raw_messages').filter(m => m.outcome === 'IGNORED' && !['OTP', 'PROMOTIONAL', 'COLLECT_REQUEST', 'FAILED'].includes(m.ignoreReason) && m.body && !m.transactionId && /(rs\.?|inr|₹)\s*[\d,]+/i.test(m.body))
    .sort((a, b) => b.receivedAt - a.receivedAt).slice(0, 20)
}

export type Suggestion = { rawUid: string; type: string; direction: string; amountPaise: number; merchant: string | null; categoryId: string | null; dateMillis: number; confidence: number }
export function validate(db: LocalDb, msgs: any[], out: any[]): Suggestion[] {
  const catIds = new Set(db.rows('categories').map(c => c.id))
  const res: Suggestion[] = []
  for (const o of out) {
    const m = msgs[o?.i]; if (!m || o.isTransaction !== true) continue
    const paise = Math.round(Number(o.amount) * 100)
    const nums = (String(m.body).match(/\d[\d,]*(\.\d{1,2})?/g) ?? []).map(n => Math.round(Number(n.replace(/,/g, '')) * 100))
    if (!(paise > 0) || !nums.includes(paise)) continue
    const type = String(o.type).toUpperCase(); if (!['EXPENSE', 'INCOME', 'TRANSFER', 'REFUND', 'INVESTMENT'].includes(type)) continue
    const dir = ['DEBIT', 'CREDIT'].includes(String(o.direction).toUpperCase()) ? String(o.direction).toUpperCase() : type === 'INCOME' || type === 'REFUND' ? 'CREDIT' : 'DEBIT'
    const d = /^\d{4}-\d{2}-\d{2}$/.test(o.date ?? '') && o.date <= new Date().toISOString().slice(0, 10) ? Date.parse(o.date + 'T06:30:00Z') : m.receivedAt
    res.push({ rawUid: m.uid, type, direction: dir, amountPaise: paise, merchant: o.merchant ? String(o.merchant).slice(0, 40) : null, categoryId: catIds.has(o.category) ? o.category : null, dateMillis: d, confidence: Math.min(79, Math.max(0, Number(o.confidence) || 60)) })
  }
  return res
}

export async function readUnknown(db: LocalDb): Promise<Suggestion[]> {
  const msgs = unknownMessages(db); if (!msgs.length) return []
  const cats = db.rows('categories').filter(c => !c.hidden)
  const prompt = 'Below are bank/payment messages my rules could not read. For each, decide if it records money actually moving (not an offer, reminder, OTP, failed or requested payment).\n' +
    'Return a JSON array: {"i": index, "isTransaction": bool, "type": "EXPENSE|INCOME|TRANSFER|REFUND|INVESTMENT", "direction": "DEBIT|CREDIT", "amount": rupees, "merchant": string|null, "category": id|null, "date": "yyyy-MM-dd"|null, "confidence": 0-100}.\n' +
    'Category ids: ' + cats.map(c => `${c.id} (${c.name})`).join(', ') + '\n' + msgs.map((m, i) => `[${i}] from ${String(m.sender).slice(0, 12)}: ${mask(m.body)}`).join('\n')
  const text = (await gemini(prompt, true, 2048)).trim().replace(/^```json|```$/g, '')
  let arr: any[] = []; try { arr = JSON.parse(text) } catch { return [] }
  return validate(db, msgs, Array.isArray(arr) ? arr : [])
}
