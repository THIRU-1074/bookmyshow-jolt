// "/bookings/{id}": booking status; once CONFIRMED, the ticket. After the mock gateway redirects
// back the webhook may still be in flight, so a PAYMENT_PENDING booking is polled briefly.
import { api } from '../api.js';
import { clear, errorBox, formatDateTime, h, money, pageHeading, statusBadge } from '../util.js';

const POLL_MS = 2000;
const POLL_LIMIT = 15;

export async function render(ctx) {
  const { main, params } = ctx;
  const holder = h('div');
  main.append(pageHeading('Booking'), holder);
  let timer;
  let polls = 0;

  async function load() {
    try {
      const b = await api(`/api/bookings/${encodeURIComponent(params.id)}`);
      if (!ctx.isCurrent()) return;
      draw(b);
      if (b.status === 'PAYMENT_PENDING' && polls++ < POLL_LIMIT) timer = setTimeout(load, POLL_MS);
    } catch (e) {
      clear(holder).append(errorBox(e, load));
    }
  }

  function draw(b) {
    clear(holder).append(
      h('div', { class: 'card' },
        h('dl', { class: 'kv' },
          h('dt', null, 'Booking'), h('dd', null, h('span', { class: 'booking-code' }, b.code)),
          h('dt', null, 'Status'), h('dd', null, statusBadge(b.status)),
          h('dt', null, 'Movie'), h('dd', null, b.show.movieTitle),
          h('dt', null, 'Cinema'), h('dd', null, `${b.show.cinemaName}, ${b.show.screenName}`),
          h('dt', null, 'Show'), h('dd', null, formatDateTime(b.show.startsAt)),
          h('dt', null, 'Seats'), h('dd', null, b.seats.map((s) => `${s.id} (${s.category})`).join(', ')),
          h('dt', null, 'Amount'), h('dd', null, money(b.amount)),
          b.refundAmount ? [h('dt', null, 'Refunded'), h('dd', null, money(b.refundAmount))] : null),
        stateNote(b)),
      b.status === 'CONFIRMED' ? ticketSection(b) : null);
  }

  function stateNote(b) {
    if (b.status === 'PAYMENT_PENDING') {
      return h('p', { class: 'alert alert-warn', role: 'status' }, 'Waiting for the payment result. This page updates by itself.');
    }
    if (b.status === 'HELD') {
      return h('a', { class: 'button', href: `/checkout/${b.id}` }, 'Continue to payment');
    }
    return null;
  }

  function ticketSection(b) {
    const box = h('div', { class: 'card', 'aria-label': 'Ticket' }, h('p', { class: 'loading' }, 'Loading ticket'));
    (async () => {
      try {
        const res = await api(`/api/bookings/${b.id}/ticket`, { raw: true });
        const doc = new DOMParser().parseFromString(await res.text(), 'text/html');
        // The ticket is server-rendered, but it is still parsed into an inert document and stripped
        // of scripts so nothing in it can run in the app's origin.
        doc.querySelectorAll('script, iframe, object, embed').forEach((n) => n.remove());
        clear(box).append(h('h2', null, 'Your ticket'), ...[...doc.body.childNodes].map((n) => document.importNode(n, true)));
        box.append(h('div', { class: 'actions' }, h('button', { type: 'button', class: 'button ghost', onclick: () => window.print() }, 'Print ticket')));
      } catch (e) {
        clear(box).append(errorBox(e));
      }
    })();
    return box;
  }

  load();
  return () => clearTimeout(timer);
}
