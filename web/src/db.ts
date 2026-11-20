import { complete, REFS, STRING_KEYED, TABLES, TABLE_ORDER } from './schema'
import { b64, decryptJson, deriveKey, encryptJson, Hlc, CHECK_TEXT, unb64, WrongPassphrase, decrypt, encrypt } from './crypto'
import type { RemoteStore } from './remote'

export type Row = Record<string, any> & { uid: string }
export type Rec = { v: string; r: Record<string, any> | null }
type State = { deviceId: string; seq: number; cursors: Record<string, number>; hlcM: number; hlcC: number; key?: CryptoKey; lastSyncAt?: number }

export interface Persist {
  load(): Promise<{ recs: [string, Rec][]; state?: State; outbox?: string[] }>
  save(recs: [string, Rec | undefined][], state: State, outbox: string[]): Promise<void>
}

/** IndexedDB persistence: one store of records ("table|uid"), one for state. CryptoKey is stored non-extractable. */
export class IdbPersist implements Persist {
  private db: Promise<IDBDatabase>
  constructor(name = 'kosh') {
    this.db = new Promise((res, rej) => {
      const r = indexedDB.open(name, 1)
      r.onupgradeneeded = () => { r.result.createObjectStore('recs'); r.result.createObjectStore('kv') }
      r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error)
    })
  }
  async load() {
    const db = await this.db
    const tx = db.transaction(['recs', 'kv'], 'readonly')
    const req = <T,>(r: IDBRequest<T>) => new Promise<T>((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error) })
    const [keys, vals, state, outbox] = await Promise.all([
      req(tx.objectStore('recs').getAllKeys()), req(tx.objectStore('recs').getAll()),
      req(tx.objectStore('kv').get('state')), req(tx.objectStore('kv').get('outbox')),
    ])
    return { recs: (keys as string[]).map((k, i) => [k, vals[i]] as [string, Rec]), state: state as State | undefined, outbox: outbox as string[] | undefined }
  }
  async save(recs: [string, Rec | undefined][], state: State, outbox: string[]) {
    const db = await this.db
    const tx = db.transaction(['recs', 'kv'], 'readwrite')
    const s = tx.objectStore('recs')
    for (const [k, v] of recs) v ? s.put(v, k) : s.delete(k)
    tx.objectStore('kv').put(state, 'state'); tx.objectStore('kv').put(outbox, 'outbox')
    await new Promise<void>((res, rej) => { tx.oncomplete = () => res(); tx.onerror = () => rej(tx.error) })
  }
  async wipe() { const db = await this.db; const tx = db.transaction(['recs', 'kv'], 'readwrite'); tx.objectStore('recs').clear(); tx.objectStore('kv').clear() }
}
export class MemoryPersist implements Persist {
  data = new Map<string, Rec>(); state?: State; outbox: string[] = []
  async load() { return { recs: [...this.data.entries()], state: this.state, outbox: this.outbox } }
  async save(recs: [string, Rec | undefined][], state: State, outbox: string[]) {
    for (const [k, v] of recs) v ? this.data.set(k, v) : this.data.delete(k); this.state = state; this.outbox = outbox
  }
}

const OPS = /^ops-([A-Za-z0-9-]+)-(\d{10})\.kosh$/
const pad = (n: number) => String(n).padStart(10, '0')
const IST_OFFSET = 330 * 60_000
export const istMonth = (ms: number) => new Date(ms + IST_OFFSET).toISOString().slice(0, 7)
export function firstKeptMonth(now = Date.now()): string {
  const [y, m] = istMonth(now).split('-').map(Number)
  const d = new Date(Date.UTC(y, m - 1 - 11, 1)); return d.toISOString().slice(0, 7)
}

/**
 * The web app's copy of the data, in the same shape as the sync files: every record is identified by
 * its uid and references other records by uid. Changes are versioned with the same hybrid clock as
 * the phone and merged newest-wins.
 */
export class LocalDb {
  tables = new Map<string, Map<string, Rec>>()
  state: State
  outbox = new Set<string>()
  hlc: Hlc
  version = 0
  private listeners = new Set<() => void>()
  private dirty = new Map<string, Rec | undefined>()
  private flushing: Promise<void> | null = null

  private constructor(private persist: Persist, state: State) {
    this.state = state
    this.hlc = new Hlc(state.hlcM, state.hlcC, state.deviceId)
    for (const t of TABLE_ORDER) this.tables.set(t, new Map())
  }

  static async open(persist: Persist): Promise<LocalDb> {
    const d = await persist.load()
    const state: State = d.state ?? { deviceId: 'w' + crypto.randomUUID().replace(/-/g, '').slice(0, 10), seq: 1, cursors: {}, hlcM: 0, hlcC: 0 }
    const db = new LocalDb(persist, state)
    for (const [k, rec] of d.recs) { const i = k.indexOf('|'); db.tables.get(k.slice(0, i))?.set(k.slice(i + 1), rec) }
    d.outbox?.forEach(k => db.outbox.add(k))
    if (!d.state) await db.flush()
    return db
  }

  subscribe(f: () => void) { this.listeners.add(f); return () => { this.listeners.delete(f) } }
  private changed() { this.version++; this.listeners.forEach(f => f()) }

  private mark(t: string, u: string, rec: Rec | undefined) { this.dirty.set(`${t}|${u}`, rec) }
  async flush() {
    const recs = [...this.dirty.entries()]; this.dirty.clear()
    this.state.hlcM = this.hlc.millis; this.state.hlcC = this.hlc.counter
    await this.persist.save(recs, this.state, [...this.outbox])
  }
  private scheduleFlush() { if (!this.flushing) this.flushing = Promise.resolve().then(() => this.flush()).finally(() => { this.flushing = null }) }

  // ---------------------------------------------------------------- reading
  rows<T extends Row = Row>(t: string): T[] {
    const out: T[] = []
    for (const [u, rec] of this.tables.get(t)!) if (rec.r) out.push({ ...rec.r, uid: u } as T)
    return out
  }
  get<T extends Row = Row>(t: string, u: string | null | undefined): T | undefined {
    if (!u) return undefined
    const rec = this.tables.get(t)!.get(u); return rec?.r ? ({ ...rec.r, uid: u } as T) : undefined
  }

  // ---------------------------------------------------------------- local edits
  /** Creates or updates a record. Returns its uid. */
  put(t: string, row: Record<string, any>, uid?: string): string {
    const key = TABLES[t].key
    const u = uid ?? row.uid ?? (STRING_KEYED.has(t) ? String(row[key]) : crypto.randomUUID())
    const prev = this.tables.get(t)!.get(u)?.r ?? {}
    const { uid: _drop, ...rest } = row
    const rec: Rec = { v: this.hlc.next(), r: complete(t, { ...prev, ...rest }) }
    this.tables.get(t)!.set(u, rec); this.mark(t, u, rec); this.outbox.add(`${t}|${u}`)
    this.scheduleFlush(); this.changed()
    return u
  }
  remove(t: string, u: string) {
    const rec: Rec = { v: this.hlc.next(), r: null }
    this.tables.get(t)!.set(u, rec); this.mark(t, u, rec); this.outbox.add(`${t}|${u}`)
    this.scheduleFlush(); this.changed()
  }

  // ---------------------------------------------------------------- sync
  get key() { return this.state.key }
  get connected() { return !!this.state.key }

  /** Joins the encrypted space the phone created in Drive. */
  async connect(store: RemoteStore, passphrase: string) {
    const metaFile = (await store.list('kosh-meta')).find(f => f.name === 'kosh-meta.json')
    if (!metaFile) throw new Error('No Kosh data in this Google account yet. Turn on Sync in the Kosh phone app first, then connect here with the same Google account.')
    const meta = JSON.parse(new TextDecoder().decode(await store.download(metaFile.id)))
    const key = await deriveKey(passphrase, unb64(meta.salt))
    const check = new TextDecoder().decode(await decrypt(key, unb64(meta.check)))
    if (check !== CHECK_TEXT) throw new WrongPassphrase()
    this.state.key = key
    await this.flush()
    return this.pull(store)
  }

  async disconnect(wipe: boolean) {
    this.state.key = undefined; this.state.cursors = {}; this.state.seq = this.state.seq
    if (wipe) { for (const m of this.tables.values()) m.clear(); this.outbox.clear(); if (this.persist instanceof IdbPersist) await this.persist.wipe() }
    await this.flush(); this.changed()
  }

  async push(store: RemoteStore): Promise<number> {
    if (!this.key || this.outbox.size === 0) return 0
    const keys = [...this.outbox]
    const changes = keys.map(k => {
      const i = k.indexOf('|'); const t = k.slice(0, i); const u = k.slice(i + 1)
      const rec = this.tables.get(t)!.get(u)
      if (!rec) return null
      return rec.r ? { t, u, v: rec.v, r: rec.r } : { t, u, v: rec.v, d: true }
    }).filter(Boolean)
    const seq = this.state.seq
    const body = { format: 1, device: this.state.deviceId, seq, createdAt: Date.now(), changes }
    await store.upload(`ops-${this.state.deviceId}-${pad(seq)}.kosh`, await encryptJson(this.key, body))
    this.state.seq = seq + 1
    keys.forEach(k => this.outbox.delete(k))
    await this.flush(); this.changed()
    return changes.length
  }

  async pull(store: RemoteStore): Promise<number> {
    if (!this.key) return 0
    const files = (await store.list('ops-')).map(f => { const m = OPS.exec(f.name); return m ? { dev: m[1], seq: Number(m[2]), f } : null })
      .filter((x): x is NonNullable<typeof x> => !!x && x.dev !== this.state.deviceId)
    const byDev = new Map<string, typeof files>()
    files.forEach(x => { if (!byDev.has(x.dev)) byDev.set(x.dev, []); byDev.get(x.dev)!.push(x) })
    const cursors = { ...this.state.cursors }
    let applied = 0
    const behind = [...byDev.entries()].some(([d, l]) => Math.min(...l.map(x => x.seq)) > (cursors[d] ?? 0) + 1)
    if (Object.keys(cursors).length === 0 || behind) {
      const snaps = await store.list('snap-')
      const snap = snaps.sort((a, b) => a.name < b.name ? -1 : 1).at(-1)
      if (snap) {
        const body = await decryptJson(this.key, await store.download(snap.id))
        applied += this.apply(body.records)
        for (const [d, s] of Object.entries<number>(body.cursors ?? {})) if (d !== this.state.deviceId) cursors[d] = Math.max(cursors[d] ?? 0, s)
      }
    }
    for (const [dev, list] of byDev) {
      for (const x of list.sort((a, b) => a.seq - b.seq)) {
        if (x.seq <= (cursors[dev] ?? 0)) continue
        const body = await decryptJson(this.key, await store.download(x.f.id))
        applied += this.apply(body.changes)
        cursors[dev] = x.seq
      }
    }
    this.state.cursors = cursors
    this.state.lastSyncAt = Date.now()
    await this.flush(); this.changed()
    return applied
  }

  async sync(store: RemoteStore) { const pushed = await this.push(store); const pulled = await this.pull(store); return { pushed, pulled } }

  /** Newest version wins; remote changes are not echoed back. */
  apply(changes: any[]): number {
    let n = 0
    const archived: string[] = []
    const sorted = [...changes].sort((a, b) => TABLE_ORDER.indexOf(a.t) - TABLE_ORDER.indexOf(b.t))
    for (const c of sorted) {
      const tbl = this.tables.get(c.t); if (!tbl) continue
      this.hlc.observe(c.v)
      const cur = tbl.get(c.u)
      if (cur && cur.v >= c.v) continue
      const rec: Rec = { v: c.v, r: c.d ? null : c.r }
      tbl.set(c.u, rec); this.mark(c.t, c.u, rec); this.outbox.delete(`${c.t}|${c.u}`)
      if (c.t === 'archive_index' && !c.d) archived.push(c.u)
      n++
    }
    archived.forEach(m => this.dropMonth(m))
    return n
  }

  /** Removes an archived month's transactions from this browser (they stay in Drive). */
  dropMonth(month: string) {
    if (month >= firstKeptMonth()) return
    const gone = new Set<string>()
    for (const [u, rec] of this.tables.get('transactions')!) if (rec.r && istMonth(rec.r.dateMillis) === month) { gone.add(u); this.tables.get('transactions')!.delete(u); this.mark('transactions', u, undefined) }
    for (const t of ['transaction_splits', 'raw_messages', 'audit_log']) {
      for (const [u, rec] of this.tables.get(t)!) if (rec.r && gone.has(rec.r.transactionId)) { this.tables.get(t)!.delete(u); this.mark(t, u, undefined) }
    }
  }

  /** Reads an archived month from Drive (read-only). */
  async fetchArchive(store: RemoteStore, month: string): Promise<Row[]> {
    if (!this.key) return []
    const f = (await store.list(`archive-${month}`)).find(x => x.name === `archive-${month}.kosh`)
    if (!f) return []
    const body = await decryptJson(this.key, await store.download(f.id))
    return body.records.filter((c: any) => c.t === 'transactions').map((c: any) => ({ ...c.r, uid: c.u }))
  }

  async downloadDocument(store: RemoteStore, fileKey: string): Promise<Uint8Array | null> {
    if (!this.key) return null
    const f = (await store.list(`doc-${fileKey}`)).find(x => x.name === `doc-${fileKey}.kosh`)
    return f ? decrypt(this.key, await store.download(f.id)) : null
  }
  async uploadDocument(store: RemoteStore, fileKey: string, bytes: Uint8Array) {
    if (!this.key) throw new Error('Connect sync first')
    await store.upload(`doc-${fileKey}.kosh`, await encrypt(this.key, bytes))
  }
}

export { REFS, b64 }
