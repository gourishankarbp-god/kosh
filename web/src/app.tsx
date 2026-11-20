import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import type { LocalDb } from './db'
import { DriveStore, googleToken, hasToken, forgetToken, AuthRequired, type RemoteStore } from './remote'
import { WrongPassphrase } from './crypto'
import * as P from './pages'
import { REVIEW_STATUSES, txns } from './logic'

type Sync = { store: RemoteStore; status: string; busy: boolean; needsSignIn: boolean; run: (interactive?: boolean) => Promise<void>; connect: (pass: string) => Promise<void>; clientId: string; setClientId: (s: string) => void }
const Ctx = createContext<{ db: LocalDb; sync: Sync }>(null as any)
export const useApp = () => useContext(Ctx)
/** Re-renders when the local data changes. */
export function useDb() { const { db } = useApp(); useSyncExternalStore(f => db.subscribe(f), () => db.version); return db }

const ROUTES: [string, string, (p: { arg?: string }) => JSX.Element][] = [
  ['home', 'Home', P.Home], ['txns', 'Transactions', P.Transactions], ['review', 'Review', P.Review], ['accounts', 'Accounts', P.Accounts],
  ['cash', 'Cash', P.Cash], ['budgets', 'Budgets', P.Budgets], ['recurring', 'Recurring & due', P.Recurring], ['goals', 'Goals', P.Goals],
  ['policies', 'LIC, insurance & deposits', P.Policies], ['reports', 'Reports', P.Reports], ['import', 'Import', P.Import],
  ['assistant', 'AI assistant', P.Assistant], ['archive', 'Archive', P.Archive], ['settings', 'Settings & sync', P.Settings],
]

function useRoute(): [string, string | undefined] {
  const [h, setH] = useState(location.hash)
  useEffect(() => { const f = () => setH(location.hash); addEventListener('hashchange', f); return () => removeEventListener('hashchange', f) }, [])
  const [r, arg] = h.replace(/^#\/?/, '').split('/')
  return [r || 'home', arg ? decodeURIComponent(arg) : undefined]
}
const DEMO = new URLSearchParams(location.search).has('demo')
export const go = (r: string) => { location.hash = '/' + r }

export function App({ db }: { db: LocalDb }) {
  const [clientId, setCid] = useState(localStorage.getItem('kosh.clientId') || import.meta.env.VITE_GOOGLE_CLIENT_ID || '')
  const [status, setStatus] = useState(db.state.lastSyncAt ? `Last synced ${new Date(db.state.lastSyncAt).toLocaleString('en-IN')}` : 'Not synced yet')
  const [busy, setBusy] = useState(false)
  const [needsSignIn, setNeeds] = useState(db.connected && !hasToken())
  const store = useMemo(() => new DriveStore(() => googleToken(clientId, false)), [clientId])
  const running = useRef(false)

  const run = useCallback(async (interactive = false) => {
    if (!db.connected || running.current) return
    if (!hasToken()) { if (!interactive) { setNeeds(true); return } await googleToken(clientId, true) }
    running.current = true; setBusy(true); setNeeds(false)
    try { const r = await db.sync(store); setStatus(`Synced ${new Date().toLocaleTimeString('en-IN')} · sent ${r.pushed}, received ${r.pulled}`) }
    catch (e: any) { if (e instanceof AuthRequired) { forgetToken(); setNeeds(true); setStatus('Sign in to Google again') } else setStatus('Sync failed: ' + e.message) }
    finally { running.current = false; setBusy(false) }
  }, [db, store, clientId])

  const connect = useCallback(async (pass: string) => {
    setBusy(true)
    try { await googleToken(clientId, true); const n = await db.connect(store, pass); setStatus(`Connected · received ${n} records`); setNeeds(false) }
    catch (e: any) { throw e instanceof WrongPassphrase ? new Error('That passphrase does not match the one set on your phone.') : e }
    finally { setBusy(false) }
  }, [db, store, clientId])

  // Battery/CPU friendly: upload ~20 s after the last edit; fetch every 10 min only while this tab is visible.
  useEffect(() => {
    let t: any
    const unsub = db.subscribe(() => { if (db.outbox.size && hasToken()) { clearTimeout(t); t = setTimeout(() => run(), 20_000) } })
    const poll = setInterval(() => { if (document.visibilityState === 'visible' && hasToken()) run() }, 10 * 60_000)
    const vis = () => { if (document.visibilityState === 'visible' && hasToken() && Date.now() - (db.state.lastSyncAt ?? 0) > 5 * 60_000) run() }
    document.addEventListener('visibilitychange', vis)
    return () => { unsub(); clearInterval(poll); clearTimeout(t); document.removeEventListener('visibilitychange', vis) }
  }, [db, run])

  const sync: Sync = { store, status, busy, needsSignIn, run, connect, clientId, setClientId: (s: string) => { localStorage.setItem('kosh.clientId', s.trim()); setCid(s.trim()) } }
  const [route, arg] = useRoute()
  const Page = ROUTES.find(r => r[0] === route)?.[2] ?? P.Home
  const [theme, setTheme] = useState(localStorage.getItem('kosh.theme') || 'system')
  useEffect(() => { document.documentElement.dataset.theme = theme; localStorage.setItem('kosh.theme', theme) }, [theme])

  return (
    <Ctx.Provider value={{ db, sync }}>
      <div className="shell">
        <nav className="side">
          <div className="brand">Kosh</div>
          {ROUTES.map(([r, label]) => <a key={r} href={'#/' + r} className={r === route ? 'on' : ''}>{label}{r === 'review' && <ReviewBadge />}</a>)}
          <div className="side-foot">
            <button className="link" onClick={() => setTheme(theme === 'dark' ? 'light' : theme === 'light' ? 'system' : 'dark')}>Theme: {theme}</button>
          </div>
        </nav>
        <main>
          {db.connected && needsSignIn && <div className="banner">Signed out of Google — <button onClick={() => run(true)}>Sign in & sync</button></div>}
          {DEMO && <div className="banner">Demo data — changes are not saved. Remove ?demo from the address to use your own data.</div>}
          {!DEMO && !db.connected && route !== 'settings' && <div className="banner">Not connected yet. <a href="#/settings">Connect to your phone's data</a> (Google Drive + your sync passphrase).</div>}
          <Page arg={arg} />
        </main>
      </div>
    </Ctx.Provider>
  )
}

function ReviewBadge() {
  const db = useDb(); const n = txns(db).filter(t => REVIEW_STATUSES.includes(t.status)).length
  return n ? <span className="badge">{n}</span> : null
}
