// "/shows/{id}": seat map with live updates over SSE, selection up to 10, sticky summary and
// "Hold seats". Seats are buttons; arrow keys move within the map (roving tabindex).
import { ApiError, api, newIdempotencyKey, user } from '../api.js';
import { navigate } from '../router.js';
import { clear, errorBox, formatDateTime, h, languageName, money, moneySpoken, pageHeading, spinner, toast } from '../util.js';

const MAX_SEATS = 10;
const STATUS_TEXT = { AVAILABLE: 'available', HELD: 'held by another buyer', BOOKED: 'booked', BLOCKED: 'not for sale' };

export async function render(ctx) {
  const { main, params } = ctx;
  const showId = Number(params.id);
  let show;
  let map;
  try {
    [show, map] = await Promise.all([api(`/api/shows/${showId}`), api(`/api/shows/${showId}/seats`)]);
  } catch (e) {
    main.append(pageHeading('Seat selection'), errorBox(e));
    return;
  }
  if (!ctx.isCurrent()) return;

  const price = new Map(map.categories.map((c) => [c.code, c]));
  const status = new Map(); // seat id -> status
  const meta = new Map(); // seat id -> {row, number, category}
  const selected = new Set();
  const buttons = new Map();
  let holdKey = null; // reused if the same selection is retried, so a retry cannot double-hold
  let holdKeyFor = '';

  function ingest(snapshot) {
    status.clear();
    meta.clear();
    for (const r of snapshot.rows) {
      for (const s of r.seats) {
        status.set(s.id, s.status);
        meta.set(s.id, { row: r.row, number: s.number, category: s.category });
      }
    }
    map = snapshot;
  }
  ingest(map);

  const liveStatus = h('p', { class: 'live-status', role: 'status', 'aria-live': 'polite' }, 'Connecting for live updates');
  const mapEl = h('div', { class: 'seat-map', role: 'group', 'aria-label': 'Seat map' });
  const summary = h('aside', { class: 'summary', 'aria-label': 'Your selection' });
  const errorSlot = h('div');

  function label(id) {
    const m = meta.get(id);
    const cat = price.get(m.category);
    return `Row ${m.row} seat ${m.number}, ${cat?.name || m.category}, ${moneySpoken(cat?.amount ?? 0)}, ${STATUS_TEXT[status.get(id)]}`;
  }

  function seatButton(s) {
    const st = status.get(s.id);
    const taken = st !== 'AVAILABLE';
    const btn = h('button', {
      type: 'button',
      class: ['seat', st === 'HELD' && 'is-held', st === 'BOOKED' && 'is-booked', st === 'BLOCKED' && 'is-blocked'],
      id: `seat-${s.id}`,
      dataset: { seat: s.id },
      'aria-pressed': String(selected.has(s.id)),
      'aria-label': selected.has(s.id) ? `${label(s.id).replace(/, available$/, '')}, selected` : label(s.id),
      'aria-disabled': taken ? 'true' : undefined,
      tabindex: '-1',
      hidden: st === 'BLOCKED',
    }, h('span', { class: 'seat-num' }, String(s.number)));
    btn.addEventListener('click', () => toggleSeat(s.id));
    buttons.set(s.id, btn);
    return btn;
  }

  function drawMap() {
    const hadFocus = document.activeElement?.dataset?.seat;
    clear(mapEl);
    buttons.clear();
    for (const r of map.rows) {
      const aisles = new Set(r.aisleAfter || []);
      const row = h('div', { class: 'seat-row', role: 'group', 'aria-label': `Row ${r.row}` }, h('span', { class: 'row-label', 'aria-hidden': 'true' }, r.row));
      for (const s of r.seats) {
        row.append(seatButton(s));
        if (aisles.has(s.number)) row.append(h('span', { class: 'seat-gap', 'aria-hidden': 'true' }));
      }
      mapEl.append(row);
    }
    // Roving tabindex: exactly one seat is in the tab order.
    const first = hadFocus && buttons.get(hadFocus) ? hadFocus : firstFocusable();
    if (first) buttons.get(first).tabIndex = 0;
    if (hadFocus && buttons.get(hadFocus)) buttons.get(hadFocus).focus();
  }

  function firstFocusable() {
    for (const r of map.rows) for (const s of r.seats) if (status.get(s.id) !== 'BLOCKED') return s.id;
    return null;
  }

  function toggleSeat(id) {
    if (status.get(id) !== 'AVAILABLE') {
      toast(`Seat ${id} is not available.`, 'error');
      return;
    }
    if (selected.has(id)) selected.delete(id);
    else if (selected.size >= MAX_SEATS) {
      toast(`You can pick up to ${MAX_SEATS} seats.`, 'error');
      return;
    } else selected.add(id);
    const btn = buttons.get(id);
    btn.setAttribute('aria-pressed', String(selected.has(id)));
    btn.setAttribute('aria-label', selected.has(id) ? `${label(id).replace(/, available$/, '')}, selected` : label(id));
    drawSummary();
  }

  // Arrow keys move between seats, Home/End jump within a row.
  mapEl.addEventListener('keydown', (e) => {
    const cur = e.target.closest?.('.seat');
    if (!cur) return;
    const rows = map.rows.map((r) => r.seats.filter((s) => status.get(s.id) !== 'BLOCKED'));
    const ri = rows.findIndex((r) => r.some((s) => s.id === cur.dataset.seat));
    const ci = rows[ri].findIndex((s) => s.id === cur.dataset.seat);
    let target = null;
    if (e.key === 'ArrowRight') target = rows[ri][Math.min(ci + 1, rows[ri].length - 1)];
    else if (e.key === 'ArrowLeft') target = rows[ri][Math.max(ci - 1, 0)];
    else if (e.key === 'ArrowDown' && rows[ri + 1]) target = rows[ri + 1][Math.min(ci, rows[ri + 1].length - 1)];
    else if (e.key === 'ArrowUp' && rows[ri - 1]) target = rows[ri - 1][Math.min(ci, rows[ri - 1].length - 1)];
    else if (e.key === 'Home') target = rows[ri][0];
    else if (e.key === 'End') target = rows[ri][rows[ri].length - 1];
    if (!target) return;
    e.preventDefault();
    cur.tabIndex = -1;
    const next = buttons.get(target.id);
    next.tabIndex = 0;
    next.focus();
  });

  function total() {
    let sum = 0;
    for (const id of selected) sum += price.get(meta.get(id).category)?.amount ?? 0;
    return sum;
  }

  let holding = false;
  function drawSummary() {
    const ids = [...selected].sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));
    clear(summary).append(
      h('h2', null, 'Your selection'),
      h('dl', null,
        h('dt', null, 'Seats'), h('dd', null, ids.length ? ids.join(', ') : 'None yet'),
        h('dt', null, 'Count'), h('dd', null, `${ids.length} of ${MAX_SEATS}`),
        h('dt', { class: 'total' }, 'Total'), h('dd', { class: 'total' }, money(total()))),
      h('button', {
        type: 'button', class: 'button block', disabled: !ids.length || holding, onclick: hold,
      }, holding ? 'Holding' : 'Hold seats'),
      h('p', { class: 'field-hint' }, `Seats are held for ${Math.round(map.holdTtlSeconds / 60)} minutes while you pay.`));
  }

  async function hold() {
    if (!user()) {
      navigate(`/login?next=${encodeURIComponent(location.pathname)}`);
      return;
    }
    const seats = [...selected].sort();
    const signature = seats.join(',');
    if (holdKeyFor !== signature) {
      holdKey = newIdempotencyKey();
      holdKeyFor = signature;
    }
    holding = true;
    clear(errorSlot);
    drawSummary();
    try {
      const heldSeats = await api('/api/holds', { method: 'POST', body: { showId, seats }, idempotencyKey: holdKey });
      const booking = await api('/api/bookings', { method: 'POST', body: { holdId: heldSeats.id }, idempotencyKey: newIdempotencyKey() });
      navigate(`/checkout/${booking.id}`);
    } catch (e) {
      holding = false;
      if (e instanceof ApiError && e.code === 'SEAT_UNAVAILABLE') {
        for (const id of e.problem.conflicts || []) {
          selected.delete(id);
          status.set(id, 'HELD');
        }
        drawMap();
        errorSlot.append(h('div', { class: 'alert alert-error', role: 'alert' }, 'Some seats were just taken. We removed them from your selection; pick others to continue.'));
      } else {
        errorSlot.append(errorBox(e));
      }
      drawSummary();
    }
  }

  // Live updates: the snapshot replaces the map, later events patch it (SPEC section 7).
  let source;
  let closed = false;
  function connect() {
    source = new EventSource(`/api/shows/${showId}/events`);
    source.addEventListener('open', () => { liveStatus.textContent = 'Live: seat changes appear instantly.'; });
    source.addEventListener('error', () => {
      if (!closed) liveStatus.textContent = 'Reconnecting for live updates';
    });
    source.addEventListener('snapshot', (e) => {
      ingest(JSON.parse(e.data));
      for (const id of [...selected]) if (status.get(id) !== 'AVAILABLE') selected.delete(id);
      drawMap();
      drawSummary();
    });
    const patch = (to) => (e) => {
      const { seats } = JSON.parse(e.data);
      const dropped = [];
      for (const id of seats) {
        if (!status.has(id)) continue;
        status.set(id, typeof to === 'function' ? to() : to);
        if (selected.has(id) && status.get(id) !== 'AVAILABLE') dropped.push(id);
        const btn = buttons.get(id);
        if (btn) {
          btn.className = ['seat', status.get(id) === 'HELD' && 'is-held', status.get(id) === 'BOOKED' && 'is-booked'].filter(Boolean).join(' ');
          btn.setAttribute('aria-label', label(id));
          btn.setAttribute('aria-disabled', status.get(id) === 'AVAILABLE' ? 'false' : 'true');
          btn.setAttribute('aria-pressed', String(selected.has(id) && !dropped.includes(id)));
        }
      }
      for (const id of dropped) selected.delete(id);
      if (dropped.length) {
        liveStatus.textContent = `Seat ${dropped.join(', ')} was taken by someone else.`;
        toast(`Seat ${dropped.join(', ')} was taken.`, 'error');
      }
      drawSummary();
    };
    source.addEventListener('seat-held', patch('HELD'));
    source.addEventListener('seat-released', patch('AVAILABLE'));
    source.addEventListener('seat-booked', patch('BOOKED'));
  }

  const legendSeat = (cls, text) => h('li', null, h('span', { class: ['seat', cls], 'aria-hidden': 'true' }, text === 'selected' ? '✓' : ''), text);

  main.append(
    h('div', { class: 'page-head' },
      pageHeading(show.movie.title),
      h('p', { class: 'lede' }, `${show.cinema.name}, ${show.screen.name} · ${formatDateTime(show.startsAt)} · ${show.format} · ${languageName(show.language)}`)),
    h('div', { class: 'show-layout' },
      h('section', { 'aria-label': 'Choose seats' },
        h('div', { class: 'screen-bar' }, 'Screen this way'),
        h('div', { class: 'seat-scroll' }, mapEl),
        h('ul', { class: 'legend', 'aria-label': 'Legend' },
          legendSeat('', 'available'), legendSeat('is-held', 'held (dashed)'), legendSeat('is-booked', 'booked (struck through)'),
          h('li', null, h('span', { class: 'seat', 'aria-pressed': 'true', 'aria-hidden': 'true' }), 'selected (tick)')),
        h('ul', { class: 'legend', 'aria-label': 'Prices' }, map.categories.map((c) => h('li', null, `${c.name}: ${money(c.amount)}`))),
        liveStatus),
      h('div', null, summary, errorSlot)));
  drawMap();
  drawSummary();
  if (show.status === 'CANCELLED') {
    main.prepend(h('p', { class: 'alert alert-error', role: 'alert' }, 'This show was cancelled.'));
  } else {
    connect();
  }
  return () => {
    closed = true;
    source?.close();
  };
}
