// Statement reading for CSV, Excel and PDF. Same column detection as the phone (StatementParser.kt).
export type StmtRow = { dateMillis: number; narration: string; amountPaise: number; debit: boolean; reference: string | null }
const IST = 330 * 60_000
const MON: Record<string, number> = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, sept: 9, oct: 10, nov: 11, dec: 12 }

export function parseDate(s: unknown): string | null {
  if (s instanceof Date) return s.toISOString().slice(0, 10)
  if (typeof s === 'number' && s > 20000 && s < 80000) return new Date(Date.UTC(1899, 11, 30) + s * 86400000).toISOString().slice(0, 10) // Excel serial
  const t = String(s ?? '').trim()
  let m = t.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/)
  if (m) return iso(+m[1], +m[2], +m[3])
  m = t.match(/^(\d{1,2})[\/\-. ](\d{1,2})[\/\-. ](\d{2,4})\b/)
  if (m) return iso(year(m[3]), +m[2], +m[1])
  m = t.match(/^(\d{1,2})[\-\s]?([A-Za-z]{3,4})[\-\s,]*(\d{2,4})\b/)
  if (m && MON[m[2].toLowerCase()]) return iso(year(m[3]), MON[m[2].toLowerCase()], +m[1])
  return null
}
const year = (y: string) => (y.length === 2 ? 2000 + +y : +y)
function iso(y: number, mo: number, d: number) {
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || y < 2000 || y > 2100) return null
  return `${y}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}`
}
const toMillis = (d: string) => Date.parse(d + 'T12:00:00Z') - IST

export function money(s: unknown): number | null {
  if (typeof s === 'number') return s ? Math.round(Math.abs(s) * 100) : null
  const t = String(s ?? '').replace(/inr|rs\.?|₹|,|\s/gi, '').replace(/(dr|cr)$/i, '')
  if (!t || t === '-' || !/^-?\d+(\.\d+)?$/.test(t)) return null
  const v = Math.round(Math.abs(Number(t)) * 100); return v > 0 ? v : null
}

export function splitCsv(line: string, sep = ','): string[] {
  const out: string[] = []; let cur = ''; let q = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (c === '"' && q && line[i + 1] === '"') { cur += '"'; i++ } else if (c === '"') q = !q
    else if (c === sep && !q) { out.push(cur.trim()); cur = '' } else cur += c
  }
  out.push(cur.trim()); return out
}

export function parseCsv(text: string): StmtRow[] {
  const lines = text.split(/\r?\n/).filter(l => l.trim())
  const head = lines.slice(0, 30).join('\n')
  const sep = (head.match(/;/g)?.length ?? 0) > (head.match(/,/g)?.length ?? 0) ? ';' : (head.match(/\t/g)?.length ?? 0) > (head.match(/,/g)?.length ?? 0) ? '\t' : ','
  return parseTable(lines.map(l => splitCsv(l, sep)))
}

export function parseTable(rows: unknown[][]): StmtRow[] {
  const low = rows.map(r => r.map(c => String(c ?? '').toLowerCase().trim()))
  const hi = low.findIndex(r => r.some(c => c.includes('date')) && r.some(c => /narration|description|particular|details|remarks/.test(c)))
  if (hi < 0) return []
  const h = low[hi]
  const col = (re: RegExp, not?: RegExp) => h.findIndex(c => re.test(c) && !(not && not.test(c)))
  let date = col(/txn date|transaction date|tran date/); if (date < 0) date = col(/date/, /value/)
  const desc = col(/narration|description|particular|details|remarks/)
  const debit = col(/withdrawal|debit|dr amount|paid out/)
  const credit = col(/deposit|credit|cr amount|paid in/)
  const amount = col(/amount/, /balance|withdrawal|deposit|debit|credit/)
  const drcr = h.findIndex(c => ['dr/cr', 'cr/dr', 'type', 'dr / cr'].includes(c))
  const ref = col(/ref|chq|cheque/)
  const out: StmtRow[] = []
  for (const r of rows.slice(hi + 1)) {
    const at = (i: number) => (i >= 0 && i < r.length ? r[i] : '')
    const d = parseDate(at(date)); if (!d) continue
    const narration = String(at(desc) ?? '').replace(/\s+/g, ' ').trim()
    if (!narration || /opening balance|closing balance/i.test(narration)) continue
    const dr = debit >= 0 ? money(at(debit)) : null
    const cr = credit >= 0 ? money(at(credit)) : null
    let paise: number | null = null; let isDebit = true
    if (dr) { paise = dr; isDebit = true } else if (cr) { paise = cr; isDebit = false } else if (amount >= 0) {
      const raw = String(at(amount)); paise = money(at(amount)); const flag = String(at(drcr)).toLowerCase()
      isDebit = flag.startsWith('cr') || /cr$/i.test(raw) ? false : true
      if (typeof at(amount) === 'number' && (at(amount) as number) > 0 && !flag) isDebit = !/cr/i.test(raw)
      if (raw.trim().startsWith('-')) isDebit = true
    }
    if (!paise) continue
    const rf = String(at(ref) ?? '').trim()
    out.push({ dateMillis: toMillis(d), narration, amountPaise: paise, debit: isDebit, reference: rf.length >= 6 ? rf : null })
  }
  return out
}

/** "UPI-SWIGGY-swiggy@icici-ICIC0000001-527312345678-Payment" -> name "SWIGGY", ref "527312345678". */
export function clean(narration: string): { name: string; ref: string | null } {
  const ref = narration.match(/(?<!\d)\d{12}(?!\d)/)?.[0] ?? null
  const parts = narration.split(/[-\/|]/).map(s => s.trim()).filter(Boolean)
  const head = (parts[0] ?? '').toUpperCase()
  let name = narration
  if (parts.length > 1 && (head.startsWith('UPI') || ['NEFT CR', 'NEFT DR', 'IMPS', 'NEFT', 'RTGS', 'POS', 'ACH D', 'ACH C', 'NACH', 'MMT'].includes(head))) {
    name = parts.slice(1).find(p => /[a-z]/i.test(p) && !/^[\w.-]+@[\w.-]+$/.test(p) && !/^[A-Z]{4}0[A-Z0-9]{6}$/.test(p) && !['P2A', 'P2M', 'UPI', 'DR', 'CR'].includes(p.toUpperCase())) ?? narration
  }
  return { name: name.slice(0, 60), ref }
}

export async function readXlsx(buf: ArrayBuffer): Promise<StmtRow[]> {
  const XLSX = await import('xlsx')
  const wb = XLSX.read(buf, { type: 'array', cellDates: true })
  for (const n of wb.SheetNames) {
    const rows = XLSX.utils.sheet_to_json<unknown[]>(wb.Sheets[n], { header: 1, raw: true, defval: '' })
    const r = parseTable(rows); if (r.length) return r
  }
  return []
}

/** PDF statements: rebuild lines from text positions, then read either as a table or by balance changes. */
export async function readPdf(buf: ArrayBuffer, password?: string): Promise<StmtRow[]> {
  const pdfjs = await import('pdfjs-dist')
  pdfjs.GlobalWorkerOptions.workerSrc = new URL('pdfjs-dist/build/pdf.worker.min.mjs', import.meta.url).toString()
  const doc = await pdfjs.getDocument({ data: buf, password }).promise
  const lines: { cells: string[]; text: string }[] = []
  for (let p = 1; p <= doc.numPages; p++) {
    const page = await doc.getPage(p)
    const items = (await page.getTextContent()).items as { str: string; transform: number[]; width: number }[]
    const byY = new Map<number, typeof items>()
    for (const it of items) { if (!it.str.trim()) continue; const y = Math.round(it.transform[5] / 3) * 3; if (!byY.has(y)) byY.set(y, []); byY.get(y)!.push(it) }
    for (const y of [...byY.keys()].sort((a, b) => b - a)) {
      const row = byY.get(y)!.sort((a, b) => a.transform[4] - b.transform[4])
      const cells: string[] = []; let lastEnd = -1e9
      for (const it of row) { const x = it.transform[4]; if (x - lastEnd > 6 || !cells.length) cells.push(it.str.trim()); else cells[cells.length - 1] += ' ' + it.str.trim(); lastEnd = x + it.width }
      lines.push({ cells, text: cells.join(' ') })
    }
  }
  const table = parseTable(lines.map(l => l.cells))
  if (table.length) return table
  return parseByBalance(lines.map(l => l.text))
}

/** Fallback for free-form statements: date at line start, amount and running balance at line end. */
export function parseByBalance(lines: string[]): StmtRow[] {
  const out: StmtRow[] = []; let prevBal: number | null = null
  const num = /-?[\d,]+\.\d{2}(?:\s?(?:Dr|Cr))?/gi
  let cur: { d: string; text: string; nums: string[] } | null = null
  const flush = () => {
    if (!cur) return
    const nums = cur.nums.map(n => ({ v: money(n)!, cr: /cr$/i.test(n), dr: /dr$/i.test(n) })).filter(n => n.v)
    if (nums.length >= 2) {
      const bal = nums[nums.length - 1].v; const amt = nums[nums.length - 2]
      let debit = amt.dr ? true : amt.cr ? false : prevBal != null ? bal < prevBal : true
      out.push({ dateMillis: toMillis(cur.d), narration: cur.text.replace(num, '').replace(/\s+/g, ' ').trim(), amountPaise: amt.v, debit, reference: null })
      prevBal = bal
    } else if (nums.length === 1 && /opening|brought forward|b\/f/i.test(cur.text)) prevBal = nums[0].v
    cur = null
  }
  for (const l of lines) {
    const d = parseDate(l)
    if (d) { flush(); const dateTok = /^\s*(\d{4}-\d{2}-\d{2}|\d{1,2}[\/\-. ](?:\d{1,2}|[A-Za-z]{3,4})[\/\-. ,]*\d{2,4})\s*/; let rest = l.replace(dateTok, ''); if (parseDate(rest)) rest = rest.replace(dateTok, ''); cur = { d, text: rest, nums: l.match(num) ?? [] } }
    else if (cur) { cur.text += ' ' + l; cur.nums.push(...(l.match(num) ?? [])) }
    else if (/opening balance/i.test(l)) { const n = l.match(num); if (n) prevBal = money(n[n.length - 1]) }
  }
  flush()
  return out
}

/** Picks the right reader by file name. */
export async function readStatement(file: File, password?: string): Promise<StmtRow[]> {
  const name = file.name.toLowerCase()
  if (name.endsWith('.pdf')) return readPdf(await file.arrayBuffer(), password)
  if (/\.(xlsx|xls|ods)$/.test(name)) return readXlsx(await file.arrayBuffer())
  return parseCsv(await file.text())
}
