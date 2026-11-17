// DOM and formatting helpers shared by every page. Pages build DOM with h() rather than
// innerHTML so server data is never parsed as markup.

export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [key, value] of Object.entries(attrs)) {
      if (value === undefined || value === null || value === false) continue;
      if (key === 'class') el.className = Array.isArray(value) ? value.filter(Boolean).join(' ') : value;
      else if (key === 'dataset') Object.assign(el.dataset, value);
      else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
      else if (key === 'value' && 'value' in el) el.value = value;
      else if (key === 'checked' || key === 'selected' || key === 'disabled' || key === 'hidden' || key === 'required') el[key] = Boolean(value);
      else el.setAttribute(key, value === true ? '' : String(value));
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    if (Array.isArray(child)) append(el, child);
    else el.append(child instanceof Node ? child : String(child));
  }
}

export function clear(el) {
  while (el.firstChild) el.firstChild.remove();
  return el;
}

const rupees = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 0, maximumFractionDigits: 2 });

/** Formats paise; money is integer minor units everywhere (SPEC section 2). */
export function money(paise) {
  return rupees.format(Number(paise) / 100);
}

/** Spoken form for aria-labels: "250 rupees" or "250 rupees 50 paise". */
export function moneySpoken(paise) {
  const r = Math.floor(paise / 100);
  const p = paise % 100;
  return p ? `${r} rupees ${p} paise` : `${r} rupees`;
}

export const DEFAULT_ZONE = 'Asia/Kolkata';

export function formatDateTime(iso, timeZone = DEFAULT_ZONE) {
  return new Intl.DateTimeFormat('en-IN', { timeZone, weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }).format(new Date(iso));
}

export function formatTime(iso, timeZone = DEFAULT_ZONE) {
  return new Intl.DateTimeFormat('en-IN', { timeZone, hour: 'numeric', minute: '2-digit' }).format(new Date(iso));
}

export function formatDate(isoDate) {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Intl.DateTimeFormat('en-IN', { timeZone: 'UTC', weekday: 'long', day: 'numeric', month: 'long' }).format(Date.UTC(y, m - 1, d));
}

/** Today's date as YYYY-MM-DD in the given IANA zone. */
export function todayIn(timeZone = DEFAULT_ZONE) {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
}

export function addDays(isoDate, days) {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** Parts of a date for the date strip: weekday, day number, month. */
export function dateParts(isoDate) {
  const [y, m, d] = isoDate.split('-').map(Number);
  const t = Date.UTC(y, m - 1, d);
  const f = (o) => new Intl.DateTimeFormat('en-IN', { timeZone: 'UTC', ...o }).format(t);
  return { weekday: f({ weekday: 'short' }), day: f({ day: 'numeric' }), month: f({ month: 'short' }) };
}

/**
 * Converts a wall-clock "YYYY-MM-DDTHH:mm" in timeZone to an ISO UTC instant. Uses the zone's
 * offset at that instant, found by formatting a first guess, which is exact outside DST gaps.
 */
export function zonedToUtc(local, timeZone = DEFAULT_ZONE) {
  const [date, time] = local.split('T');
  const [y, mo, d] = date.split('-').map(Number);
  const [hh, mm] = time.split(':').map(Number);
  const guess = Date.UTC(y, mo - 1, d, hh, mm);
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    .formatToParts(guess).map((p) => [p.type, p.value]));
  const asZone = Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour, +parts.minute);
  return new Date(guess - (asZone - guess)).toISOString().replace('.000Z', 'Z');
}

export const storage = {
  get(key) {
    try { return localStorage.getItem(key); } catch { return null; }
  },
  set(key, value) {
    try {
      if (value === null) localStorage.removeItem(key);
      else localStorage.setItem(key, value);
    } catch { /* private mode: preference simply is not remembered */ }
  },
};

export const session = {
  get(key) {
    try { return JSON.parse(sessionStorage.getItem(key)); } catch { return null; }
  },
  set(key, value) {
    try {
      if (value === null) sessionStorage.removeItem(key);
      else sessionStorage.setItem(key, JSON.stringify(value));
    } catch { /* ignored */ }
  },
};

let toastTimer;
/** Shows a short message; the region is aria-live so screen readers hear it too. */
export function toast(message, kind = 'info') {
  const region = document.getElementById('toast-region');
  clear(region);
  region.append(h('div', { class: ['toast', `toast-${kind}`] }, message));
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => clear(region), 6000);
}

export function debounce(fn, ms) {
  let t;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
}

/** Comma list from a query value (the UI always sends comma lists, SPEC section 12). */
export function listParam(value) {
  return value ? value.split(',').map((s) => s.trim()).filter(Boolean) : [];
}

export function pageHeading(text, extra) {
  return h('h1', { class: 'page-title', tabindex: '-1' }, text, extra);
}

export function spinner(label = 'Loading') {
  return h('p', { class: 'loading', role: 'status' }, label);
}

export function errorBox(err, retry) {
  return h('div', { class: 'alert alert-error', role: 'alert' },
    h('p', null, err?.message || 'Something went wrong.'),
    retry ? h('button', { type: 'button', class: 'button ghost', onclick: retry }, 'Try again') : null);
}

export const STATUS_LABELS = {
  HELD: 'Seats held',
  PAYMENT_PENDING: 'Payment pending',
  CONFIRMED: 'Confirmed',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
  REFUNDED: 'Refunded',
};

const STATUS_ICONS = { HELD: '⏳', PAYMENT_PENDING: '↻', CONFIRMED: '✓', EXPIRED: '⏱', CANCELLED: '✕', REFUNDED: '↩' };

export function statusBadge(status) {
  return h('span', { class: ['badge', `badge-${status.toLowerCase()}`] },
    h('span', { 'aria-hidden': 'true' }, STATUS_ICONS[status] || ''), ' ', STATUS_LABELS[status] || status);
}

/**
 * Refund preview from SPEC section 4: more than 24 h before start 100 %, 4 to 24 h 50 % rounded
 * down to the paisa, under 4 h nothing, after start not cancellable. The server is the authority.
 */
export function refundPreview(booking, now = Date.now()) {
  if (booking.status === 'HELD') return { allowed: true, amount: 0, note: 'No payment has been made, so nothing is refunded.' };
  if (booking.status !== 'CONFIRMED') return { allowed: false, note: 'This booking cannot be cancelled.' };
  const t = new Date(booking.show.startsAt).getTime() - now;
  const H = 3600 * 1000;
  if (t <= 0) return { allowed: false, note: 'The show has started.' };
  if (t > 24 * H) return { allowed: true, amount: booking.amount, note: 'Full refund: more than 24 hours before the show.' };
  if (t >= 4 * H) return { allowed: true, amount: Math.floor(booking.amount / 2), note: '50% refund: between 4 and 24 hours before the show.' };
  return { allowed: true, amount: 0, note: 'No refund within 4 hours of the show. Your seats go back on sale.' };
}

export const GENRES = ['Action', 'Comedy', 'Drama', 'Thriller', 'Romance', 'Animation', 'Sci-Fi', 'Horror', 'Family'];
export const LANGUAGES = { en: 'English', hi: 'Hindi', ta: 'Tamil', te: 'Telugu', kn: 'Kannada' };
export const CERTIFICATES = ['U', 'UA', 'A', 'S'];
export const FORMATS = ['2D', '3D', 'IMAX'];

export function languageName(code) {
  return LANGUAGES[code] || code;
}
