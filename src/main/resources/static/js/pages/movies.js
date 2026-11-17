// "/" and "/movies": city picker on first visit, then the movie grid with search, filter chips,
// a 7-day date strip and pagination. Filter state lives in the URL so it survives reload and back.
import { api, cities } from '../api.js';
import { cityButtons, cityId, currentCity, setCity } from '../city.js';
import { navigate } from '../router.js';
import {
  CERTIFICATES, FORMATS, GENRES, LANGUAGES, addDays, clear, dateParts, debounce, errorBox, h,
  languageName, listParam, pageHeading, spinner, todayIn,
} from '../util.js';

const PAGE_SIZE = 12;

export async function render(ctx) {
  const { main, query } = ctx;
  if (!cityId()) return renderCityPicker(main);
  const city = await currentCity().catch(() => null);
  if (!city) {
    setCity(null);
    return renderCityPicker(main);
  }

  const state = {
    q: query.get('q') || '',
    date: query.get('date') || todayIn(city.timeZone),
    genre: listParam(query.get('genre')),
    language: listParam(query.get('language')),
    certificate: listParam(query.get('certificate')),
    format: listParam(query.get('format')),
    page: Math.max(1, Number(query.get('page')) || 1),
  };
  const today = todayIn(city.timeZone);
  if (state.date < today || state.date > addDays(today, 6)) state.date = today;

  const results = h('section', { class: 'results', 'aria-labelledby': 'results-heading' });
  const resultsStatus = h('p', { class: 'visually-hidden', role: 'status', 'aria-live': 'polite' });

  function urlFor(next) {
    const p = new URLSearchParams();
    if (next.q) p.set('q', next.q);
    if (next.date !== today) p.set('date', next.date);
    for (const k of ['genre', 'language', 'certificate', 'format']) if (next[k].length) p.set(k, next[k].join(','));
    if (next.page > 1) p.set('page', String(next.page));
    const s = p.toString();
    return `/movies${s ? `?${s}` : ''}`;
  }

  function update(changes, { push = false } = {}) {
    Object.assign(state, changes);
    const url = urlFor(state);
    if (push) history.pushState(null, '', url);
    else history.replaceState(null, '', url);
    refreshControls();
    load();
  }

  // Search
  const search = h('input', {
    type: 'search', id: 'movie-search', name: 'q', value: state.q, placeholder: 'Search movies', autocomplete: 'off', minlength: '2',
  });
  const searchHint = h('p', { id: 'search-hint', class: 'field-hint' }, 'Type at least 2 letters.');
  search.setAttribute('aria-describedby', 'search-hint');
  search.addEventListener('input', debounce(() => {
    const q = search.value.trim();
    if (q.length === 1) return;
    update({ q, page: 1 });
  }, 300));

  // Date strip
  const dateStrip = h('div', { class: 'date-strip', role: 'group', 'aria-label': 'Show date' });
  // Filter chips
  const chipGroups = h('div', { class: 'filters' });

  function chipGroup(label, key, values, labelFor = (v) => v) {
    return h('fieldset', { class: 'chip-group' },
      h('legend', null, label),
      values.map((v) => {
        const on = state[key].includes(v);
        return h('button', {
          type: 'button',
          class: ['chip', on && 'is-on'],
          'aria-pressed': String(on),
          onclick: () => {
            const set = new Set(state[key]);
            if (set.has(v)) set.delete(v); else set.add(v);
            update({ [key]: [...set], page: 1 });
          },
        }, on ? h('span', { 'aria-hidden': 'true' }, '✓ ') : null, labelFor(v));
      }));
  }

  function refreshControls() {
    clear(dateStrip);
    for (let i = 0; i < 7; i++) {
      const d = addDays(today, i);
      const parts = dateParts(d);
      const on = d === state.date;
      dateStrip.append(h('button', {
        type: 'button',
        class: ['date-chip', on && 'is-on'],
        'aria-pressed': String(on),
        'aria-label': `${i === 0 ? 'Today, ' : i === 1 ? 'Tomorrow, ' : ''}${parts.weekday} ${parts.day} ${parts.month}`,
        onclick: () => update({ date: d, page: 1 }),
      }, h('span', { class: 'date-wd' }, i === 0 ? 'Today' : parts.weekday), h('span', { class: 'date-day' }, parts.day), h('span', { class: 'date-mon' }, parts.month)));
    }
    clear(chipGroups).append(
      chipGroup('Genre', 'genre', GENRES),
      chipGroup('Language', 'language', Object.keys(LANGUAGES), languageName),
      chipGroup('Certificate', 'certificate', CERTIFICATES),
      chipGroup('Format', 'format', FORMATS),
    );
    const active = state.genre.length + state.language.length + state.certificate.length + state.format.length;
    clearBtn.hidden = !active && !state.q;
  }

  const clearBtn = h('button', {
    type: 'button', class: 'link-button', onclick: () => {
      search.value = '';
      update({ q: '', genre: [], language: [], certificate: [], format: [], page: 1 });
    },
  }, 'Clear filters');

  let controller;
  async function load() {
    controller?.abort();
    controller = new AbortController();
    clear(results).append(h('h2', { id: 'results-heading', class: 'visually-hidden' }, 'Movies'), spinner('Loading movies'));
    results.setAttribute('aria-busy', 'true');
    try {
      const page = await api('/api/movies', {
        signal: controller.signal,
        query: {
          city: city.id, date: state.date, q: state.q.length >= 2 ? state.q : '', genre: state.genre, language: state.language,
          certificate: state.certificate, format: state.format, sort: 'release', page: state.page, size: PAGE_SIZE,
        },
      });
      if (!ctx.isCurrent()) return;
      renderResults(page);
    } catch (e) {
      if (e.name === 'AbortError') return;
      clear(results).append(errorBox(e, load));
    } finally {
      results.removeAttribute('aria-busy');
    }
  }

  function renderResults(page) {
    clear(results).append(h('h2', { id: 'results-heading', class: 'section-title' },
      `${page.total} ${page.total === 1 ? 'movie' : 'movies'} in ${city.name}`));
    resultsStatus.textContent = `${page.total} movies found`;
    if (!page.items.length) {
      results.append(h('div', { class: 'empty' },
        h('p', null, 'No movies match. Try another date or fewer filters.')));
      return;
    }
    results.append(h('ul', { class: 'movie-grid', role: 'list' }, page.items.map((m) => h('li', null, movieCard(m, state.date, today)))));
    if (page.totalPages > 1) {
      results.append(h('nav', { class: 'pager', 'aria-label': 'Pages' },
        h('button', { type: 'button', class: 'button ghost', disabled: page.page <= 1, onclick: () => { update({ page: page.page - 1 }, { push: true }); window.scrollTo(0, 0); } }, '← Previous'),
        h('span', { class: 'pager-status' }, `Page ${page.page} of ${page.totalPages}`),
        h('button', { type: 'button', class: 'button ghost', disabled: page.page >= page.totalPages, onclick: () => { update({ page: page.page + 1 }, { push: true }); window.scrollTo(0, 0); } }, 'Next →')));
    }
  }

  main.append(
    h('div', { class: 'page-head' },
      pageHeading(`Movies in ${city.name}`),
      h('p', { class: 'lede' }, 'Pick a date, then a movie to see its show times.')),
    h('div', { class: 'toolbar' },
      h('div', { class: 'search-field' }, h('label', { for: 'movie-search', class: 'visually-hidden' }, 'Search movies'), h('span', { class: 'search-icon', 'aria-hidden': 'true' }), search, searchHint),
      dateStrip),
    h('details', { class: 'filter-panel', open: window.matchMedia('(min-width: 900px)').matches },
      h('summary', null, 'Filters'), chipGroups, clearBtn),
    resultsStatus,
    results,
  );
  refreshControls();
  load();
  return () => controller?.abort();
}

export function movieCard(m, date, today) {
  const href = `/movies/${m.id}${date && date !== today ? `?date=${date}` : ''}`;
  return h('article', { class: 'movie-card' },
    h('a', { href, class: 'movie-link' },
      poster(m),
      h('h3', { class: 'movie-title' }, m.title)),
    h('p', { class: 'movie-meta' },
      h('span', { class: 'cert', title: `Certificate ${m.certificate}` }, m.certificate), ' ',
      (m.languages || []).map(languageName).join(', ')),
    h('p', { class: 'movie-genres' }, (m.genres || []).join(' · ')));
}

/** Poster image with a typographic fallback when the URL fails or is missing. */
export function poster(m, cls = 'poster') {
  const fallback = () => h('div', { class: [cls, 'poster-fallback'], 'aria-hidden': 'true' },
    h('span', null, m.title.split(/\s+/).slice(0, 3).map((w) => w[0]).join('')));
  if (!m.posterUrl) return fallback();
  const img = h('img', { class: cls, src: m.posterUrl, alt: '', loading: 'lazy', decoding: 'async', width: '240', height: '360' });
  img.addEventListener('error', () => img.replaceWith(fallback()), { once: true });
  return img;
}

async function renderCityPicker(main) {
  main.append(h('div', { class: 'city-picker' },
    pageHeading('Where are you watching?'),
    h('p', { class: 'lede' }, 'Choose a city. We will remember it on this device.')));
  const holder = h('div', { class: 'city-list city-list-large' }, spinner('Loading cities'));
  main.querySelector('.city-picker').append(holder);
  try {
    const list = await cities();
    clear(holder).append(...cityButtons(list, (c) => {
      setCity(c.id);
      navigate('/movies', { replace: true });
    }));
  } catch (e) {
    clear(holder).append(errorBox(e, () => location.reload()));
  }
}
