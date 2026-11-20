export type RemoteFile = { id: string; name: string; size?: number }
export interface RemoteStore {
  list(prefix: string): Promise<RemoteFile[]>
  download(id: string): Promise<Uint8Array>
  upload(name: string, bytes: Uint8Array, existingId?: string): Promise<RemoteFile>
  remove(id: string): Promise<void>
}
export class AuthRequired extends Error { constructor() { super('Google sign-in needed') } }

/** Google Drive appDataFolder (scope drive.appdata): only Kosh's own hidden files. */
export class DriveStore implements RemoteStore {
  constructor(private token: () => Promise<string>) {}
  private async call(url: string, init: RequestInit = {}): Promise<Response> {
    const t = await this.token()
    const r = await fetch(url, { ...init, headers: { ...(init.headers || {}), Authorization: `Bearer ${t}` } })
    if (r.status === 401) throw new AuthRequired()
    if (!r.ok && !(init.method === 'DELETE' && r.status === 404)) throw new Error(`Drive error ${r.status}: ${(await r.text()).slice(0, 200)}`)
    return r
  }
  async list(prefix: string) {
    const out: RemoteFile[] = []; let page: string | undefined
    do {
      const q = encodeURIComponent(`name contains '${prefix}' and trashed = false`)
      const r = await (await this.call(`https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=1000&fields=nextPageToken,files(id,name,size)&q=${q}${page ? `&pageToken=${page}` : ''}`)).json()
      for (const f of r.files || []) if (f.name.startsWith(prefix)) out.push({ id: f.id, name: f.name, size: Number(f.size || 0) })
      page = r.nextPageToken
    } while (page)
    return out.sort((a, b) => a.name < b.name ? -1 : 1)
  }
  async download(id: string) { return new Uint8Array(await (await this.call(`https://www.googleapis.com/drive/v3/files/${id}?alt=media`)).arrayBuffer()) }
  async upload(name: string, bytes: Uint8Array, existingId?: string) {
    if (existingId) {
      await this.call(`https://www.googleapis.com/upload/drive/v3/files/${existingId}?uploadType=media`, { method: 'PATCH', body: bytes, headers: { 'Content-Type': 'application/octet-stream' } })
      return { id: existingId, name }
    }
    const form = new FormData()
    form.append('metadata', new Blob([JSON.stringify({ name, parents: ['appDataFolder'] })], { type: 'application/json' }))
    form.append('file', new Blob([bytes], { type: 'application/octet-stream' }))
    const r = await (await this.call('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name', { method: 'POST', body: form })).json()
    return { id: r.id, name }
  }
  async remove(id: string) { await this.call(`https://www.googleapis.com/drive/v3/files/${id}`, { method: 'DELETE' }) }
}

/** In-memory store for tests. */
export class MemoryStore implements RemoteStore {
  files = new Map<string, Uint8Array>()
  async list(prefix: string) { return [...this.files.keys()].filter(n => n.startsWith(prefix)).sort().map(n => ({ id: n, name: n })) }
  async download(id: string) { const f = this.files.get(id); if (!f) throw new Error('missing ' + id); return f }
  async upload(name: string, bytes: Uint8Array, existingId?: string) { this.files.set(existingId || name, bytes); return { id: existingId || name, name } }
  async remove(id: string) { this.files.delete(id) }
}

/** Google Identity Services token client (loaded from accounts.google.com/gsi/client). */
declare const google: any
let cached: { token: string; until: number } | null = null
export function googleToken(clientId: string, interactive: boolean): Promise<string> {
  if (cached && cached.until > Date.now()) return Promise.resolve(cached.token)
  return new Promise((resolve, reject) => {
    if (typeof google === 'undefined' || !google.accounts?.oauth2) return reject(new Error('Google sign-in did not load. Check your internet connection.'))
    const client = google.accounts.oauth2.initTokenClient({
      client_id: clientId, scope: 'https://www.googleapis.com/auth/drive.appdata',
      callback: (r: any) => {
        if (r.error || !r.access_token) return reject(new AuthRequired())
        cached = { token: r.access_token, until: Date.now() + (Number(r.expires_in || 3600) - 120) * 1000 }
        localStorage.setItem('kosh.signedIn', '1')
        resolve(r.access_token)
      },
      error_callback: () => reject(new AuthRequired()),
    })
    client.requestAccessToken({ prompt: interactive ? 'consent' : '' })
  })
}
export const hasToken = () => !!cached && cached.until > Date.now()
export function forgetToken() { cached = null; localStorage.removeItem('kosh.signedIn') }
