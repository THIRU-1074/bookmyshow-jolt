// "/admin": tabs for movies, cinemas and screens (layout JSON editor with preview), shows and
// reports. Lists reuse the public GET endpoints; mutations go to /api/admin with an Idempotency-Key.
import { ApiError, api, newIdempotencyKey } from '../api.js';
import {
  CERTIFICATES, FORMATS, GENRES, LANGUAGES, addDays, clear, errorBox, formatDateTime, h, money, pageHeading, spinner,
  todayIn, toast, zonedToUtc,
} from '../util.js';

const TABS = [
  ['movies', 'Movies'],
  ['cinemas', 'Cinemas and screens'],
  ['shows', 'Shows'],
  ['reports', 'Reports'],
];

const SAMPLE_LAYOUT = {
  categories: [{ code: 'CLASSIC', name: 'Classic' }, { code: 'PRIME', name: 'Prime' }],
  rows: [
    { row: 'A', seats: 12, category: 'CLASSIC', aisleAfter: [4, 8], blocked: [1] },
    { row: 'B', seats: 12, category: 'PRIME', aisleAfter: [4, 8], blocked: [] },
  ],
};

const post = (path, body) => api(`/api/admin${path}`, { method: 'POST', body, idempotencyKey: newIdempotencyKey() });
const put = (path, body) => api(`/api/admin${path}`, { method: 'PUT', body });
const del = (path) => api(`/api/admin${path}`, { method: 'DELETE' });

export async function render(ctx) {
  const { main, query } = ctx;
  let active = TABS.some(([k]) => k === query.get('tab')) ? query.get('tab') : 'movies';
  const panel = h('div', { role: 'tabpanel', id: 'admin-panel', tabindex: '0' });
  const tablist = h('div', { class: 'tabs', role: 'tablist', 'aria-label': 'Admin sections' });

  function drawTabs() {
    clear(tablist);
    for (const [key, text] of TABS) {
      const on = key === active;
      tablist.append(h('button', {
        type: 'button', class: 'tab', role: 'tab', id: `tab-${key}`, 'aria-selected': String(on), 'aria-controls': 'admin-panel', tabindex: on ? '0' : '-1',
        onclick: () => select(key),
      }, text));
    }
  }
  function select(key) {
    active = key;
    history.replaceState(null, '', `/admin?tab=${key}`);
    drawTabs();
    panel.setAttribute('aria-labelledby', `tab-${key}`);
    clear(panel).append(spinner());
    tabs[key](panel, ctx).catch((e) => clear(panel).append(errorBox(e)));
  }
  tablist.addEventListener('keydown', (e) => {
    const i = TABS.findIndex(([k]) => k === active);
    const next = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : null;
    if (next === null) return;
    e.preventDefault();
    select(TABS[(next + TABS.length) % TABS.length][0]);
    tablist.querySelector('[aria-selected="true"]').focus();
  });

  main.append(pageHeading('Admin'), tablist, panel);
  select(active);
}

/** Inline form error display: field errors (422 `errors`) listed by path. */
function showError(slot, e) {
  clear(slot);
  if (e instanceof ApiError && Object.keys(e.fieldErrors).length) {
    slot.append(h('div', { class: 'alert alert-error', role: 'alert' }, h('p', null, e.message),
      h('ul', null, Object.entries(e.fieldErrors).map(([k, v]) => h('li', null, h('code', null, k), `: ${v}`)))));
  } else {
    slot.append(errorBox(e));
  }
}

function field(label, input, id) {
  input.id = id;
  return h('div', null, h('label', { for: id }, label), input);
}

function checks(name, values, labelFor = (v) => v) {
  return h('fieldset', { class: 'checks' }, h('legend', null, name),
    values.map((v) => h('label', null, h('input', { type: 'checkbox', value: v }), labelFor(v))));
}

const checked = (fs) => [...fs.querySelectorAll('input:checked')].map((i) => i.value);

const tabs = {
  async movies(panel) {
    const form = h('form', { class: 'form form-wide' });
    const slot = h('div');
    const languages = checks('Languages', Object.keys(LANGUAGES), (c) => LANGUAGES[c]);
    const genres = checks('Genres', GENRES);
    const cert = h('select', null, CERTIFICATES.map((c) => h('option', { value: c }, c)));
    form.append(
      h('h2', null, 'Add a movie'),
      field('Title', h('input', { name: 'title', required: true, maxlength: '120' }), 'm-title'),
      h('div', { class: 'form-row' },
        field('Certificate', cert, 'm-cert'),
        field('Duration (minutes)', h('input', { name: 'duration', type: 'number', min: '1', max: '400', required: true }), 'm-dur'),
        field('Release date', h('input', { name: 'release', type: 'date', required: true }), 'm-rel')),
      languages, genres,
      field('Poster URL', h('input', { name: 'poster', placeholder: 'https://... or /posters/x.jpg' }), 'm-poster'),
      field('Description', h('textarea', { name: 'description', maxlength: '2000', rows: '3' }), 'm-desc'),
      h('button', { type: 'submit', class: 'button' }, 'Create movie'), slot);
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(form));
      try {
        await post('/movies', {
          title: d.title, certificate: cert.value, durationMinutes: Number(d.duration), releaseDate: d.release,
          languages: checked(languages), genres: checked(genres), posterUrl: d.poster, description: d.description,
        });
        toast('Movie created.');
        tabs.movies(panel);
      } catch (err) {
        showError(slot, err);
      }
    });

    const page = await api('/api/movies', { query: { size: 100, sort: 'title' } });
    clear(panel).append(
      h('h2', null, `Movies (${page.total})`),
      h('div', { class: 'table-scroll' }, h('table', null,
        h('thead', null, h('tr', null, ['Title', 'Cert', 'Languages', 'Genres', ''].map((t) => h('th', { scope: 'col' }, t)))),
        h('tbody', null, page.items.map((m) => h('tr', null,
          h('td', null, m.title), h('td', null, m.certificate), h('td', null, m.languages.join(', ')), h('td', null, m.genres.join(', ')),
          h('td', null, h('button', {
            type: 'button', class: 'link-button', onclick: async () => {
              if (!window.confirm(`Delete "${m.title}"?`)) return;
              try { await del(`/movies/${m.id}`); toast('Deleted.'); tabs.movies(panel); } catch (err) { toast(err.message, 'error'); }
            },
          }, 'Delete', h('span', { class: 'visually-hidden' }, ` ${m.title}`)))))))),
      form);
  },

  async cinemas(panel) {
    const cities = await api('/api/cities');
    const cinemas = await api('/api/cinemas', { query: { size: 100 } });
    const citySelect = h('select', null, cities.map((c) => h('option', { value: c.id }, c.name)));
    const cinemaForm = h('form', { class: 'form' });
    const cslot = h('div');
    cinemaForm.append(h('h2', null, 'Add a cinema'),
      field('City', citySelect, 'c-city'),
      field('Name', h('input', { name: 'name', required: true }), 'c-name'),
      field('Address', h('input', { name: 'address', required: true }), 'c-addr'),
      h('button', { type: 'submit', class: 'button' }, 'Create cinema'), cslot);
    cinemaForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      const d = Object.fromEntries(new FormData(cinemaForm));
      try { await post('/cinemas', { cityId: citySelect.value, name: d.name, address: d.address }); toast('Cinema created.'); tabs.cinemas(panel); } catch (err) { showError(cslot, err); }
    });

    // Screen form with a layout JSON editor and a live preview of what the JSON describes.
    const cinemaSelect = h('select', null, cinemas.items.map((c) => h('option', { value: c.id }, `${c.name} (${c.cityId})`)));
    const layout = h('textarea', { 'aria-describedby': 'layout-hint', spellcheck: 'false' });
    layout.value = JSON.stringify(SAMPLE_LAYOUT, null, 2);
    const preview = h('div', { 'aria-label': 'Layout preview', role: 'group' });
    const sslot = h('div');
    const screenForm = h('form', { class: 'form form-wide' });
    function drawPreview() {
      clear(preview);
      try {
        const doc = JSON.parse(layout.value);
        layout.removeAttribute('aria-invalid');
        const map = h('div', { class: 'mini-map' });
        let capacity = 0;
        for (const r of doc.rows || []) {
          const aisles = new Set(r.aisleAfter || []);
          const blocked = new Set(r.blocked || []);
          const row = h('div', { class: 'mini-row' }, h('span', { class: 'row-label', 'aria-hidden': 'true' }, r.row));
          for (let n = 1; n <= r.seats; n++) {
            row.append(h('span', { class: ['mini-seat', blocked.has(n) && 'is-blocked'], title: `${r.row}${n} ${r.category}` }));
            if (aisles.has(n)) row.append(h('span', { class: 'mini-gap' }));
          }
          capacity += r.seats - blocked.size;
          map.append(row);
        }
        preview.append(h('p', { class: 'field-hint' }, `Capacity ${capacity}. Dashed seats are blocked.`), map);
      } catch (e) {
        layout.setAttribute('aria-invalid', 'true');
        preview.append(h('p', { class: 'field-error' }, `Invalid JSON: ${e.message}`));
      }
    }
    layout.addEventListener('input', drawPreview);
    screenForm.append(h('h2', null, 'Add a screen'),
      field('Cinema', cinemaSelect, 's-cinema'),
      field('Name', h('input', { name: 'name', required: true }), 's-name'),
      field('Layout JSON', layout, 's-layout'),
      h('p', { id: 'layout-hint', class: 'field-hint' }, 'Rows run from A, seats 1..50 per row. Field errors from the server show the failing path.'),
      preview,
      h('button', { type: 'submit', class: 'button' }, 'Create screen'), sslot);
    screenForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      let doc;
      try { doc = JSON.parse(layout.value); } catch { return showError(sslot, new Error('Layout is not valid JSON.')); }
      const d = Object.fromEntries(new FormData(screenForm));
      try { await post(`/cinemas/${cinemaSelect.value}/screens`, { name: d.name, layout: doc }); toast('Screen created.'); } catch (err) { showError(sslot, err); }
    });

    clear(panel).append(
      h('h2', null, `Cinemas (${cinemas.total})`),
      h('div', { class: 'table-scroll' }, h('table', null,
        h('thead', null, h('tr', null, ['Name', 'City', 'Address', ''].map((t) => h('th', { scope: 'col' }, t)))),
        h('tbody', null, cinemas.items.map((c) => h('tr', null,
          h('td', null, c.name), h('td', null, c.cityId), h('td', null, c.address),
          h('td', null, h('button', {
            type: 'button', class: 'link-button', onclick: async () => {
              if (!window.confirm(`Delete "${c.name}"?`)) return;
              try { await del(`/cinemas/${c.id}`); toast('Deleted.'); tabs.cinemas(panel); } catch (err) { toast(err.message, 'error'); }
            },
          }, 'Delete', h('span', { class: 'visually-hidden' }, ` ${c.name}`)))))))),
      cinemaForm, screenForm);
    drawPreview();
  },

  async shows(panel) {
    const [movies, cinemas, cities] = await Promise.all([
      api('/api/movies', { query: { size: 100, sort: 'title' } }), api('/api/cinemas', { query: { size: 100 } }), api('/api/cities')]);
    const zone = new Map(cities.map((c) => [c.id, c.timeZone]));
    const cinemaDetails = await Promise.all(cinemas.items.map((c) => api(`/api/cinemas/${c.id}`)));
    const screens = cinemaDetails.flatMap((c) => c.screens.map((s) => ({ ...s, label: `${c.name} / ${s.name}`, cityId: cinemas.items.find((x) => x.id === c.id).cityId })));

    const movieSel = h('select', null, movies.items.map((m) => h('option', { value: m.id }, m.title)));
    const screenSel = h('select', null, screens.map((s) => h('option', { value: s.id }, s.label)));
    const formatSel = h('select', null, FORMATS.map((f) => h('option', { value: f }, f)));
    const langSel = h('select', null, Object.entries(LANGUAGES).map(([c, n]) => h('option', { value: c }, n)));
    const when = h('input', { type: 'datetime-local', required: true });
    const prices = h('input', { placeholder: 'CLASSIC=150,PRIME=250 (rupees)', required: true });
    const slot = h('div');
    const form = h('form', { class: 'form form-wide' },
      h('h2', null, 'Schedule a show'),
      field('Movie', movieSel, 'sh-movie'), field('Screen', screenSel, 'sh-screen'),
      h('div', { class: 'form-row' }, field('Language', langSel, 'sh-lang'), field('Format', formatSel, 'sh-format')),
      field('Starts (cinema local time)', when, 'sh-when'),
      field('Prices per category', prices, 'sh-prices'),
      h('p', { class: 'field-hint' }, 'Every category in the screen layout needs a price between 1 and 10000 rupees.'),
      h('button', { type: 'submit', class: 'button' }, 'Create show'), slot);
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const screen = screens.find((s) => String(s.id) === screenSel.value);
      const priceMap = {};
      for (const part of prices.value.split(',')) {
        const [k, v] = part.split('=').map((s) => s.trim());
        if (k && v) priceMap[k] = Math.round(Number(v) * 100); // paise, never floats on the wire
      }
      try {
        await post('/shows', {
          movieId: Number(movieSel.value), screenId: Number(screenSel.value), language: langSel.value, format: formatSel.value,
          startsAt: zonedToUtc(when.value, zone.get(screen.cityId)), prices: priceMap,
        });
        toast('Show created.');
      } catch (err) { showError(slot, err); }
    });

    // Cancel-a-show helper: pick a movie, list its upcoming shows in a city.
    const cancelId = h('input', { type: 'number', min: '1', placeholder: 'Show id' });
    const cslot = h('div');
    const cancelForm = h('form', { class: 'form' }, h('h2', null, 'Cancel a show'),
      field('Show id', cancelId, 'sh-cancel'),
      h('p', { class: 'field-hint' }, 'Confirmed bookings are refunded in full; held ones are cancelled.'),
      h('button', { type: 'submit', class: 'button danger' }, 'Cancel show'), cslot);
    cancelForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      if (!window.confirm(`Cancel show ${cancelId.value}? Every booking on it is cancelled or refunded.`)) return;
      try { await post(`/shows/${cancelId.value}/cancel`, {}); toast('Show cancelled.'); } catch (err) { showError(cslot, err); }
    });
    clear(panel).append(form, cancelForm);
  },

  async reports(panel, ctx) {
    const city = (await api('/api/cities'))[0]?.id;
    const today = todayIn();
    const from = h('input', { type: 'date', value: addDays(today, -7) });
    const to = h('input', { type: 'date', value: addDays(today, 7) });
    const out = h('div');
    const stats = await api('/api/admin/stats').catch(() => null);
    async function run() {
      clear(out).append(spinner());
      try {
        const page = await api('/api/admin/reports/shows', { query: { city, from: from.value, to: to.value, size: 50 } });
        if (!ctx.isCurrent()) return;
        clear(out).append(h('div', { class: 'table-scroll' }, h('table', null,
          h('thead', null, h('tr', null, ['Movie', 'Cinema', 'Starts', 'Capacity', 'Booked', 'Occupancy', 'Revenue', 'Refunded'].map((t) => h('th', { scope: 'col', class: ['Capacity', 'Booked', 'Occupancy', 'Revenue', 'Refunded'].includes(t) ? 'num' : '' }, t)))),
          h('tbody', null, page.items.map((r) => h('tr', null,
            h('td', null, r.movieTitle), h('td', null, r.cinemaName), h('td', null, formatDateTime(r.startsAt)),
            h('td', { class: 'num' }, r.capacity), h('td', { class: 'num' }, r.booked), h('td', { class: 'num' }, `${r.occupancyPercent}%`),
            h('td', { class: 'num' }, money(r.revenue)), h('td', { class: 'num' }, money(r.refunded))))))));
        if (!page.items.length) out.append(h('div', { class: 'empty' }, 'No shows in this range.'));
      } catch (e) { clear(out).append(errorBox(e, run)); }
    }
    clear(panel).append(
      h('h2', null, 'Reports'),
      stats ? h('p', { class: 'lede' }, `${stats.activeHolds} active holds · ${stats.bookingsConfirmedToday} confirmed today · ${stats.sseStreams} live viewers`) : null,
      h('div', { class: 'form-row' }, field('From', from, 'r-from'), field('To', to, 'r-to'), h('div', null, h('button', { type: 'button', class: 'button', onclick: run }, 'Run report'))),
      out);
    run();
  },
};
