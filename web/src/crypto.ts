// Same format as the phone (SyncCrypto.kt): "KSY1" | 12-byte IV | AES-256-GCM(gzip(data)), AAD "KSY1",
// key = PBKDF2-HMAC-SHA256(passphrase, salt, 210 000, 256 bits).
const MAGIC = new TextEncoder().encode('KSY1')
export const ITERATIONS = 210_000
export const CHECK_TEXT = 'kosh-sync-check-v1'
export class WrongPassphrase extends Error { constructor() { super('Wrong sync passphrase') } }

const subtle = globalThis.crypto.subtle

export async function deriveKey(passphrase: string, salt: Uint8Array, extractable = false): Promise<CryptoKey> {
  const base = await subtle.importKey('raw', new TextEncoder().encode(passphrase), 'PBKDF2', false, ['deriveKey'])
  return subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: ITERATIONS }, base,
    { name: 'AES-GCM', length: 256 }, extractable, ['encrypt', 'decrypt'])
}

async function pipe(data: Uint8Array, stream: CompressionStream | DecompressionStream): Promise<Uint8Array> {
  const out = new Blob([data]).stream().pipeThrough(stream)
  return new Uint8Array(await new Response(out).arrayBuffer())
}
export const gzip = (d: Uint8Array) => pipe(d, new CompressionStream('gzip'))
export const gunzip = (d: Uint8Array) => pipe(d, new DecompressionStream('gzip'))

export async function encrypt(key: CryptoKey, plain: Uint8Array): Promise<Uint8Array> {
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12))
  const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: MAGIC }, key, await gzip(plain)))
  const out = new Uint8Array(4 + 12 + ct.length)
  out.set(MAGIC, 0); out.set(iv, 4); out.set(ct, 16)
  return out
}

export async function decrypt(key: CryptoKey, data: Uint8Array): Promise<Uint8Array> {
  if (data.length <= 16 || MAGIC.some((b, i) => data[i] !== b)) throw new Error('Not a Kosh sync file')
  let plain: ArrayBuffer
  try {
    plain = await subtle.decrypt({ name: 'AES-GCM', iv: data.slice(4, 16), additionalData: MAGIC }, key, data.slice(16))
  } catch { throw new WrongPassphrase() }
  return gunzip(new Uint8Array(plain))
}

export const encryptJson = async (key: CryptoKey, o: unknown) => encrypt(key, new TextEncoder().encode(JSON.stringify(o)))
export const decryptJson = async <T = any>(key: CryptoKey, d: Uint8Array): Promise<T> => JSON.parse(new TextDecoder().decode(await decrypt(key, d)))

export const b64 = (b: Uint8Array) => btoa(String.fromCharCode(...b))
export const unb64 = (s: string) => Uint8Array.from(atob(s), c => c.charCodeAt(0))

/** Hybrid logical clock, same string format as the phone: 13-digit millis - 4-digit counter - device. */
export class Hlc {
  constructor(public millis: number, public counter: number, private device: string) {}
  next(now = Date.now()): string {
    if (now > this.millis) { this.millis = now; this.counter = 0 } else this.counter++
    return Hlc.format(this.millis, this.counter, this.device)
  }
  observe(v: string) {
    const p = Hlc.parse(v); if (!p) return
    if (p[0] > this.millis || (p[0] === this.millis && p[1] > this.counter)) { this.millis = p[0]; this.counter = p[1] }
  }
  static format(m: number, c: number, d: string) { return `${String(m).padStart(13, '0')}-${String(c).padStart(4, '0')}-${d}` }
  static parse(v: string): [number, number] | null { const p = v.split('-'); return p.length >= 3 ? [Number(p[0]), Number(p[1])] : null }
}
