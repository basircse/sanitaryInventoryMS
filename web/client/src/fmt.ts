import { bangla } from './i18n'

/*
 * Display formatting, ported from the Android app's Fmt. In Bangla mode digits are Bangla numerals
 * and taka uses lakh/crore grouping (১২,৩৪,৫৬৭). Money is in poisha, quantities in thousandths,
 * so no floating-point arithmetic touches stored values.
 */

export const ZONE = 'Asia/Dhaka'
const BN_DIGITS = '০১২৩৪৫৬৭৮৯'

export function digits(text: string): string {
  return bangla ? text.replace(/[0-9]/g, (d) => BN_DIGITS[Number(d)]) : text
}

/** Bangla and ASCII digits -> ASCII, for parsing input. */
export function toAscii(text: string): string {
  return text.replace(/[০-৯]/g, (d) => String(BN_DIGITS.indexOf(d)))
}

/** 1234567 -> 12,34,567 */
function group(d: string): string {
  const neg = d.startsWith('-')
  const n = neg ? d.slice(1) : d
  if (n.length <= 3) return d
  const last3 = n.slice(-3)
  const rest = n.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',')
  return (neg ? '-' : '') + rest + ',' + last3
}

export function num(n: number): string {
  return digits(group(String(Math.trunc(n))))
}

function scaled(value: number, scale: number): string {
  const neg = value < 0
  const s = String(Math.abs(value)).padStart(scale + 1, '0')
  const whole = s.slice(0, -scale)
  const frac = s.slice(-scale).replace(/0+$/, '')
  return (neg ? '-' : '') + whole + (frac ? '.' + frac : '')
}

export function taka(poisha: number): string {
  const neg = poisha < 0
  const abs = Math.abs(poisha)
  const whole = group(String(Math.floor(abs / 100)))
  const frac = abs % 100
  const text = frac === 0 ? whole : `${whole}.${String(frac).padStart(2, '0')}`
  return (neg ? '-' : '') + '৳' + digits(text)
}

/** Quantity as an editable ASCII string ("12.5"). */
export function plainQty(milli: number): string {
  return scaled(milli, 3)
}

export function plainMoney(poisha: number): string {
  return scaled(poisha, 2)
}

export function qty(milli: number): string {
  return digits(plainQty(milli))
}

/** Parses "12.5" / "১২.৫" into thousandths; null if not a non-negative number. */
export function parseQty(text: string): number | null {
  return parseScaled(text, 3)
}

/** Parses taka text into poisha. */
export function parseMoney(text: string): number | null {
  return parseScaled(text, 2)
}

function parseScaled(text: string, scale: number): number | null {
  const clean = toAscii(text.trim()).replace(/,/g, '')
  const m = /^(\d*)(?:\.(\d*))?$/.exec(clean)
  if (!m || (!m[1] && !m[2])) return null
  const frac = m[2] ?? ''
  let v = BigInt(m[1] || '0') * 10n ** BigInt(scale) + BigInt((frac.slice(0, scale) || '0').padEnd(scale, '0'))
  if (frac.length > scale && Number(frac[scale]) >= 5) v += 1n // round half up
  return v > BigInt(Number.MAX_SAFE_INTEGER) ? null : Number(v)
}

/** Money × quantity (thousandths), rounded half up — same as the server. */
export function lineTotal(unitPoisha: number, qtyMilli: number): number {
  const p = BigInt(unitPoisha) * BigInt(qtyMilli)
  const neg = p < 0n
  const r = ((neg ? -p : p) * 2n + 1000n) / 2000n
  return Number(neg ? -r : r)
}

function format(ms: number | Date, opts: Intl.DateTimeFormatOptions): string {
  return digits(new Intl.DateTimeFormat(bangla ? 'bn-BD' : 'en-GB', { timeZone: ZONE, ...opts }).format(ms))
}

export function date(ms: number | Date): string {
  return format(ms, { day: 'numeric', month: 'short', year: 'numeric' })
}

export function dateTime(ms: number): string {
  return format(ms, { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit', hour12: true })
}

/** Today's date in the shop's time zone, as yyyy-mm-dd. */
export function today(): string {
  return isoDate(new Date())
}

export function isoDate(d: Date): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d)
}

/** Adds days to a yyyy-mm-dd date. */
export function addDays(iso: string, days: number): string {
  const d = new Date(iso + 'T12:00:00Z')
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

/** A yyyy-mm-dd date for display. */
export function isoToDisplay(iso: string): string {
  return date(new Date(iso + 'T12:00:00+06:00'))
}
