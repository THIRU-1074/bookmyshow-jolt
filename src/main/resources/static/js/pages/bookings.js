// "/bookings": my bookings with a status filter and cancel, showing the refund preview first.
import { api, newIdempotencyKey } from '../api.js';
import { clear, errorBox, formatDateTime, h, money, pageHeading, refundPreview, spinner, statusBadge, STATUS_LABELS, toast } from '../util.js';

export async function render(ctx) {
  const { main } = ctx;
  const state = { status: [], page: 1 };
  const list = h('div');
  const filters = h('fieldset', { class: 'chip-group' }, h('legend', null, 'Status'));

  function drawFilters() {
    clear(filters).append(h('legend', null, 'Status'));
    for (const s of Object.keys(STATUS_LABELS)) {
      const on = state.status.includes(s);
      filters.append(h('button', {
        type: 'button', class: ['chip', on && 'is-on'], 'aria-pressed': String(on),
        onclick: () => {
          const set = new Set(state.status);
          if (set.has(s)) set.delete(s); else set.add(s);
          state.status = [...set];
          state.page = 1;
          drawFilters();
          load();
        },
      }, on ? h('span', { 'aria-hidden': 'true' }, '✓ ') : null, STATUS_LABELS[s]));
    }
  }

  async function load() {
    clear(list).append(spinner('Loading bookings'));
    try {
      const page = await api('/api/bookings', { query: { status: state.status, page: state.page, size: 10 } });
      if (!ctx.isCurrent()) return;
      clear(list);
      if (!page.items.length) {
        list.append(h('div', { class: 'empty' }, 'No bookings yet. ', h('a', { href: '/movies' }, 'Find a movie')));
        return;
      }
      list.append(h('ul', { class: 'booking-list', role: 'list' }, page.items.map(item)));
      if (page.totalPages > 1) {
        list.append(h('nav', { class: 'pager', 'aria-label': 'Pages' },
          h('button', { type: 'button', class: 'button ghost', disabled: page.page <= 1, onclick: () => { state.page--; load(); } }, '← Previous'),
          h('span', { class: 'pager-status' }, `Page ${page.page} of ${page.totalPages}`),
          h('button', { type: 'button', class: 'button ghost', disabled: page.page >= page.totalPages, onclick: () => { state.page++; load(); } }, 'Next →')));
      }
    } catch (e) {
      clear(list).append(errorBox(e, load));
    }
  }

  function item(b) {
    const preview = refundPreview(b);
    const actions = h('div', { class: 'actions' }, h('a', { class: 'button ghost', href: b.status === 'HELD' ? `/checkout/${b.id}` : `/bookings/${b.id}` }, b.status === 'HELD' ? 'Pay now' : 'View'));
    if (preview.allowed && b.status !== 'PAYMENT_PENDING') {
      actions.append(h('button', { type: 'button', class: 'button danger', onclick: () => cancel(b, preview) }, 'Cancel booking'));
    }
    return h('li', { class: 'card booking-item' },
      h('div', null,
        h('h2', null, b.show.movieTitle),
        h('p', { class: 'movie-meta' }, `${b.show.cinemaName} · ${formatDateTime(b.show.startsAt)}`),
        h('p', { class: 'movie-meta' }, `Seats ${b.seats.map((s) => s.id).join(', ')} · ${money(b.amount)}`),
        h('p', { class: 'movie-meta' }, 'Booking ', h('span', { class: 'booking-code' }, b.code))),
      h('div', null, statusBadge(b.status), actions));
  }

  async function cancel(b, preview) {
    const refund = preview.amount ? `You will be refunded ${money(preview.amount)}.` : 'No refund applies.';
    if (!window.confirm(`Cancel booking ${b.code}?\n${preview.note}\n${refund}`)) return;
    try {
      const out = await api(`/api/bookings/${b.id}/cancel`, { method: 'POST', idempotencyKey: newIdempotencyKey() });
      toast(out.refundAmount ? `Cancelled. ${money(out.refundAmount)} refunded.` : 'Cancelled.');
      load();
    } catch (e) {
      toast(e.message, 'error');
      load();
    }
  }

  main.append(pageHeading('My bookings'), filters, list);
  drawFilters();
  load();
}
