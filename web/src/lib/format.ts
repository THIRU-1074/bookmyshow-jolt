// Formatting and small domain rules shared by pages. Money is integer paise (SPEC section 2).
import type { Booking, BookingStatus } from '../api/types';

export const DEFAULT_ZONE = 'Asia/Kolkata';

const whole = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
const exact = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2 });

/** Whole rupees print without decimals; anything else shows both paise digits (₹450.50). */
export function money(paise: number): string {
  return paise % 100 === 0 ? whole.format(paise / 100) : exact.format(paise / 100);
}

/** Spoken form for aria-labels: "250 rupees" or "250 rupees 50 paise". */
export function moneySpoken(paise: number): string {
  const r = Math.floor(paise / 100);
  const p = paise % 100;
  return p ? `${r} rupees ${p} paise` : `${r} rupees`;
}

export function formatDateTime(iso: string, timeZone = DEFAULT_ZONE): string {
  return new Intl.DateTimeFormat('en-IN', {
    timeZone,
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(iso));
}

export function formatTime(iso: string, timeZone = DEFAULT_ZONE): string {
  return new Intl.DateTimeFormat('en-IN', { timeZone, hour: 'numeric', minute: '2-digit' }).format(new Date(iso));
}

export function formatLongDate(iso: string, timeZone = DEFAULT_ZONE): string {
  return new Intl.DateTimeFormat('en-IN', { timeZone, weekday: 'long', day: 'numeric', month: 'long' }).format(
    new Date(iso),
  );
}

/** Today's date as YYYY-MM-DD in the given IANA zone. */
export function todayIn(timeZone = DEFAULT_ZONE, now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}

export function addDays(isoDate: string, days: number): string {
  const [y, m, d] = isoDate.split('-').map(Number) as [number, number, number];
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

export function dateParts(isoDate: string) {
  const [y, m, d] = isoDate.split('-').map(Number) as [number, number, number];
  const t = Date.UTC(y, m - 1, d);
  const f = (o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat('en-IN', { timeZone: 'UTC', ...o }).format(t);
  return { weekday: f({ weekday: 'short' }), day: f({ day: 'numeric' }), month: f({ month: 'short' }), long: f({ weekday: 'long', day: 'numeric', month: 'long' }) };
}

/**
 * Converts a wall-clock "YYYY-MM-DDTHH:mm" in timeZone to an ISO UTC instant, using the zone's
 * offset at that instant (exact outside DST gaps; the seeded zones have no DST).
 */
export function zonedToUtc(local: string, timeZone = DEFAULT_ZONE): string {
  const [date = '', time = '00:00'] = local.split('T');
  const [y, mo, d] = date.split('-').map(Number) as [number, number, number];
  const [hh, mm] = time.split(':').map(Number) as [number, number];
  const guess = Date.UTC(y, mo - 1, d, hh, mm);
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
      .formatToParts(guess)
      .map((p) => [p.type, p.value]),
  );
  const asZone = Date.UTC(+parts.year!, +parts.month! - 1, +parts.day!, +parts.hour!, +parts.minute!);
  return new Date(guess - (asZone - guess)).toISOString().replace('.000Z', 'Z');
}

export function runtime(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h ? `${h}h ${m.toString().padStart(2, '0')}m` : `${m}m`;
}

export const LANGUAGES: Record<string, string> = {
  en: 'English',
  hi: 'Hindi',
  ta: 'Tamil',
  te: 'Telugu',
  kn: 'Kannada',
  ml: 'Malayalam',
  mr: 'Marathi',
  bn: 'Bengali',
};
export const GENRES = ['Action', 'Comedy', 'Drama', 'Thriller', 'Romance', 'Animation', 'Sci-Fi', 'Horror', 'Family'];
export const CERTIFICATES = ['U', 'UA', 'A', 'S'] as const;
export const FORMATS = ['2D', '3D', 'IMAX'] as const;

export const CERT_LABEL: Record<string, string> = {
  U: 'Universal',
  UA: 'Parental guidance',
  A: 'Adults only',
  S: 'Restricted audience',
};

export function languageName(code: string): string {
  return LANGUAGES[code] ?? code;
}

export const STATUS_LABELS: Record<BookingStatus, string> = {
  HELD: 'Seats held',
  PAYMENT_PENDING: 'Payment pending',
  CONFIRMED: 'Confirmed',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
  REFUNDED: 'Refunded',
};

export interface RefundPreview {
  allowed: boolean;
  amount: number;
  note: string;
}

/**
 * Refund preview from SPEC section 4: more than 24 h before start 100 %, 4 to 24 h 50 % rounded
 * down to the paisa, under 4 h nothing, after start not cancellable. The server decides.
 */
export function refundPreview(b: Pick<Booking, 'status' | 'amount' | 'show'>, now = Date.now()): RefundPreview {
  if (b.status === 'HELD') return { allowed: true, amount: 0, note: 'No payment was made, so nothing is refunded.' };
  if (b.status === 'PAYMENT_PENDING')
    return { allowed: false, amount: 0, note: 'A payment is in progress. Finish or fail it first.' };
  if (b.status !== 'CONFIRMED') return { allowed: false, amount: 0, note: 'This booking cannot be cancelled.' };
  const t = new Date(b.show.startsAt).getTime() - now;
  const H = 3_600_000;
  if (t <= 0) return { allowed: false, amount: 0, note: 'The show has started.' };
  if (t > 24 * H) return { allowed: true, amount: b.amount, note: 'Full refund: more than 24 hours before the show.' };
  if (t >= 4 * H)
    return { allowed: true, amount: Math.floor(b.amount / 2), note: 'Half refund: between 4 and 24 hours before the show.' };
  return { allowed: true, amount: 0, note: 'No refund within 4 hours of the show. Your seats go back on sale.' };
}

export function mmss(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

export function sortSeatIds(ids: Iterable<string>): string[] {
  return [...ids].sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));
}
