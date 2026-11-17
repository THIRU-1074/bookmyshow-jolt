// "/checkout/{bookingId}": hold countdown (mm:ss from expiresAt), warning under 60 s, expiry
// message, and "Pay" which opens the mock gateway (SPEC section 8).
import { ApiError, api, newIdempotencyKey } from '../api.js';
import { navigate } from '../router.js';
import { clear, errorBox, formatDateTime, h, money, pageHeading, statusBadge } from '../util.js';

const MARKS = [[120, 'Two minutes left to pay.'], [60, 'One minute left to pay.'], [30, 'Thirty seconds left to pay.']];

export async function render(ctx) {
  const { main, params } = ctx;
  let booking;
  try {
    booking = await api(`/api/bookings/${encodeURIComponent(params.id)}`);
  } catch (e) {
    main.append(pageHeading('Checkout'), errorBox(e), h('a', { href: '/bookings', class: 'button ghost' }, 'My bookings'));
    return;
  }
  if (!ctx.isCurrent()) return;
  if (booking.status === 'CONFIRMED' || booking.status === 'REFUNDED' || booking.status === 'CANCELLED') {
    navigate(`/bookings/${booking.id}`, { replace: true });
    return;
  }

  // The server clock is the authority; the countdown only displays expiresAt and every
  // transition is re-checked server side.
  const expiresAt = new Date(booking.expiresAt).getTime();
  const timer = h('p', { class: 'countdown', id: 'countdown' });
  const announce = h('p', { class: 'visually-hidden', role: 'status', 'aria-live': 'polite' });
  const body = h('div');
  let interval;
  let payKey = null;
  let paying = false;
  let lastAnnounced = Infinity;

  function remaining() {
    return Math.max(0, Math.round((expiresAt - Date.now()) / 1000));
  }

  function tick() {
    const s = remaining();
    timer.textContent = `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
    timer.classList.toggle('is-warning', s < 60 && s > 0);
    // Announce sparingly so screen readers are not flooded every second.
    // Threshold crossing rather than exact equality: a throttled background tab can skip seconds.
    const mark = [...MARKS].reverse().find(([at]) => s <= at && s > 0 && at < lastAnnounced);
    if (mark) {
      announce.textContent = mark[1];
      lastAnnounced = mark[0];
    }
    if (s === 0) {
      clearInterval(interval);
      expired();
    }
  }

  function expired() {
    clear(body).append(
      h('div', { class: 'alert alert-error', role: 'alert' }, 'Your hold has expired and the seats were released.'),
      h('a', { class: 'button', href: `/shows/${booking.show.id}` }, 'Pick seats again'));
    announce.textContent = 'Your hold has expired.';
  }

  async function pay() {
    if (paying) return;
    paying = true;
    payBtn.disabled = true;
    payKey ??= newIdempotencyKey();
    try {
      const out = await api(`/api/bookings/${booking.id}/checkout`, { method: 'POST', idempotencyKey: payKey });
      // checkoutUrl is a server-rendered page, so leave the SPA for it.
      location.assign(out.checkoutUrl);
    } catch (e) {
      paying = false;
      payBtn.disabled = false;
      payKey = null;
      const msg = e instanceof ApiError && e.status === 410 ? 'Your hold has expired.' : e.message;
      errorSlot.replaceChildren(h('div', { class: 'alert alert-error', role: 'alert' }, msg));
    }
  }

  async function cancel() {
    try {
      await api(`/api/bookings/${booking.id}/cancel`, { method: 'POST', idempotencyKey: newIdempotencyKey() });
      navigate('/bookings');
    } catch (e) {
      errorSlot.replaceChildren(errorBox(e));
    }
  }

  const errorSlot = h('div');
  const payBtn = h('button', { type: 'button', class: 'button', onclick: pay }, `Pay ${money(booking.amount)}`);
  const cancelBtn = h('button', { type: 'button', class: 'button ghost', onclick: cancel }, 'Release seats');
  const pending = booking.status === 'PAYMENT_PENDING';
  if (pending) cancelBtn.hidden = true; // the server refuses cancel while payment is in progress

  clear(body).append(
    h('p', null, 'Complete payment before the timer ends or the seats go back on sale.'),
    h('div', { class: 'actions' }, payBtn, cancelBtn),
    errorSlot);

  main.append(
    pageHeading('Checkout'),
    h('div', { class: 'card' },
      h('p', { class: 'lede' }, 'Time left to pay'),
      timer, announce,
      h('dl', { class: 'kv' },
        h('dt', null, 'Movie'), h('dd', null, booking.show.movieTitle),
        h('dt', null, 'Cinema'), h('dd', null, `${booking.show.cinemaName}, ${booking.show.screenName}`),
        h('dt', null, 'Show'), h('dd', null, formatDateTime(booking.show.startsAt)),
        h('dt', null, 'Seats'), h('dd', null, booking.seats.map((s) => s.id).join(', ')),
        h('dt', null, 'Status'), h('dd', null, statusBadge(booking.status)),
        h('dt', null, 'Total'), h('dd', null, money(booking.amount))),
      body));

  tick();
  if (remaining() > 0) interval = setInterval(tick, 1000);
  return () => clearInterval(interval);
}
