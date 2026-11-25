import { useEffect, useState } from 'react'

type T = { id: number; msg: string; tone: 'ok' | 'warn' | 'bad' }
let seq = 0
const subs = new Set<(t: T) => void>()
/** Small pop-up message in the corner; used for budget feedback. */
export const toast = (msg: string, tone: T['tone'] = 'ok') => subs.forEach(f => f({ id: ++seq, msg, tone }))

export function Toasts() {
  const [list, setList] = useState<T[]>([])
  useEffect(() => {
    const f = (t: T) => { setList(l => [...l, t]); setTimeout(() => setList(l => l.filter(x => x.id !== t.id)), 5000) }
    subs.add(f)
    return () => { subs.delete(f) }
  }, [])
  return <div className="toasts" aria-live="polite">{list.map(t => <div key={t.id} className={'toast ' + t.tone} onClick={() => setList(l => l.filter(x => x.id !== t.id))}>{t.msg}</div>)}</div>
}
