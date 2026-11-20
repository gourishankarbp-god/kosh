import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { LocalDb, MemoryPersist } from '../src/db'
import type { RemoteStore } from '../src/remote'

const dir = path.resolve(__dirname, '../../build/cross-drive')
class FolderStore implements RemoteStore {
  async list(p: string) { return fs.readdirSync(dir).filter(n => n.startsWith(p)).sort().map(n => ({ id: n, name: n })) }
  async download(id: string) { return new Uint8Array(fs.readFileSync(path.join(dir, id))) }
  async upload(name: string, b: Uint8Array, existing?: string) { fs.writeFileSync(path.join(dir, existing ?? name), b); return { id: existing ?? name, name } }
  async remove(id: string) { fs.rmSync(path.join(dir, id)) }
}

describe.runIf(process.env.KOSH_CROSS === 'web')('phone <-> web', () => {
  it('reads the phone data and writes changes the phone can apply', async () => {
    const store = new FolderStore()
    const db = await LocalDb.open(new MemoryPersist())
    await db.connect(store, 'cross platform passphrase')
    const t = db.rows('transactions').find(x => x.amountPaise === 25000)!
    expect(t.categoryId).toBe('food.delivery')
    const acct = db.get('accounts', t.accountId)!
    expect(acct.last4).toBe('4821')
    expect(db.rows('categories').length).toBeGreaterThan(50)
    db.put('transactions', { note: 'edited on the web', updatedAt: Date.now() }, t.uid)
    const id = db.put('transactions', { type: 'EXPENSE', direction: 'DEBIT', status: 'POSTED', amountPaise: 1234500, dateMillis: Date.now(), accountId: acct.uid, merchantName: 'Web shop', categoryId: 'shopping', method: 'CARD', sources: 'MANUAL', confidence: 100, createdAt: Date.now(), updatedAt: Date.now() })
    db.put('transaction_splits', { transactionId: id, categoryId: 'shopping.clothing', amountPaise: 1000000 })
    db.put('transaction_splits', { transactionId: id, categoryId: 'food.restaurants', amountPaise: 234500 })
    db.put('budgets', { categoryId: 'food', monthlyLimitPaise: 500000, rollover: 0, startMonth: '2026-01' })
    expect(await db.push(store)).toBe(5)
  })
})
