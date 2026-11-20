import { createRoot } from 'react-dom/client'
import { LocalDb, IdbPersist, MemoryPersist } from './db'
import { App } from './app'
import './styles.css'

const demo = new URLSearchParams(location.search).has('demo')
LocalDb.open(demo ? new MemoryPersist() : new IdbPersist()).then(async db => {
  if (demo) (await import('./demo')).loadDemo(db)
  createRoot(document.getElementById('root')!).render(<App db={db} />)
})
if ('serviceWorker' in navigator && import.meta.env.PROD) navigator.serviceWorker.register(import.meta.env.BASE_URL + 'sw.js').catch(() => {})
