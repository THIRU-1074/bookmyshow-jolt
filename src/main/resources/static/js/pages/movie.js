// "/movies/{id}": details, then show times grouped by cinema for the chosen city and date.
import { api } from '../api.js';
import { cityId, currentCity, openCityDialog } from '../city.js';
import {
  FORMATS, addDays, clear, dateParts, errorBox, formatDate, h, languageName, listParam, money,
  pageHeading, spinner, todayIn,
} from '../util.js';
import { poster } from './movies.js';

const AVAILABILITY = { AVAILABLE: 'Available', FILLING_FAST: 'Filling fast', SOLD_OUT: 'Sold out' };
const AVAILABILITY_ICON = { AVAILABLE: '●', FILLING_FAST: '▲', SOLD_OUT: '✕' };

export async function render(ctx) {
  const { main, params, query } = ctx;
  let movie;
  try {
    movie = await api(`/api/movies/${encodeURIComponent(params.id)}`);
  } catch (e) {
    main.append(pageHeading('Movie'), errorBox(e));
    return;
  }
  if (!ctx.isCurrent()) return;

  const city = await currentCity().catch(() => null);
  const today = todayIn(city?.timeZone);
  const state = {
    date: query.get('date') || today,
    language: listParam(query.get('language')),
    format: listParam(query.get('format')),
  };
  if (state.date < today || state.date > addDays(today, 6)) state.date = today;

  const shows = h('section', { 'aria-labelledby': 'shows-heading' });
  const dateStrip = h('div', { class: 'date-strip', role: 'group', 'aria-label': 'Show date' });
  const filters = h('div', { class: 'filters' });

  main.append(
    h('div', { class: 'detail' },
      poster(movie),
      h('div', null,
        pageHeading(movie.title),
        h('p', { class: 'detail-meta' },
          h('span', { class: 'cert', title: `Certificate ${movie.certificate}` }, movie.certificate),
          h('span', null, `${Math.floor(movie.durationMinutes / 60)}h ${movie.durationMinutes % 60}m`),
          h('span', null, movie.languages.map(languageName).join(', ')),
          h('span', null, movie.genres.join(' · '))),
        h('p', null, movie.description))),
    h('h2', { id: 'shows-heading' }, 'Show times'),
    dateStrip, filters, shows);

  function sync() {
    const p = new URLSearchParams();
    if (state.date !== today) p.set('date', state.date);
    if (state.language.length) p.set('language', state.language.join(','));
    if (state.format.length) p.set('format', state.format.join(','));
    const s = p.toString();
    history.replaceState(null, '', `/movies/${movie.id}${s ? `?${s}` : ''}`);
  }

  function toggle(key, value) {
    const set = new Set(state[key]);
    if (set.has(value)) set.delete(value); else set.add(value);
    state[key] = [...set];
    sync();
    draw();
    load();
  }

  function draw() {
    clear(dateStrip);
    for (let i = 0; i < 7; i++) {
      const d = addDays(today, i);
      const parts = dateParts(d);
      const on = d === state.date;
      dateStrip.append(h('button', {
        type: 'button', class: ['date-chip', on && 'is-on'], 'aria-pressed': String(on),
        'aria-label': `${parts.weekday} ${parts.day} ${parts.month}`,
        onclick: () => { state.date = d; sync(); draw(); load(); },
      }, h('span', { class: 'date-wd' }, i === 0 ? 'Today' : parts.weekday), h('span', { class: 'date-day' }, parts.day), h('span', { class: 'date-mon' }, parts.month)));
    }
    const group = (label, key, values, labelFor = (v) => v) => h('fieldset', { class: 'chip-group' },
      h('legend', null, label),
      values.map((v) => {
        const on = state[key].includes(v);
        return h('button', { type: 'button', class: ['chip', on && 'is-on'], 'aria-pressed': String(on), onclick: () => toggle(key, v) },
          on ? h('span', { 'aria-hidden': 'true' }, '✓ ') : null, labelFor(v));
      }));
    clear(filters).append(
      group('Language', 'language', movie.languages, languageName),
      group('Format', 'format', FORMATS));
  }

  let controller;
  async function load() {
    controller?.abort();
    controller = new AbortController();
    if (!cityId() || !city) {
      clear(shows).append(h('div', { class: 'alert alert-warn' },
        h('p', null, 'Choose a city to see show times.'),
        h('button', { type: 'button', class: 'button', onclick: openCityDialog }, 'Choose city')));
      return;
    }
    clear(shows).append(spinner('Loading show times'));
    try {
      const out = await api(`/api/movies/${movie.id}/shows`, {
        signal: controller.signal,
        query: { city: city.id, date: state.date, language: state.language, format: state.format },
      });
      if (!ctx.isCurrent()) return;
      clear(shows).append(h('p', { class: 'lede' }, `${formatDate(out.date)} in ${city.name}`));
      if (!out.cinemas.length) {
        shows.append(h('div', { class: 'empty' }, 'No shows on this date. Try another day.'));
        return;
      }
      for (const c of out.cinemas) {
        shows.append(h('article', { class: 'cinema-block' },
          h('h3', null, c.name),
          h('p', { class: 'cinema-addr' }, c.address),
          h('ul', { class: 'show-list', role: 'list' }, c.shows.map((s) => showLink(s)))));
      }
    } catch (e) {
      if (e.name === 'AbortError') return;
      clear(shows).append(errorBox(e, load));
    }
  }

  draw();
  load();
  return () => controller?.abort();
}

function showLink(s) {
  const soldOut = s.availability === 'SOLD_OUT';
  const label = `${s.localTime}, ${s.format}, ${languageName(s.language)}, from ${money(s.minPrice)}, ${AVAILABILITY[s.availability] || s.availability}`;
  const inner = [
    h('span', { class: 'show-time' }, s.localTime),
    h('span', { class: 'show-sub' }, `${s.format} · ${languageName(s.language)}`),
    h('span', { class: 'show-sub' }, `from ${money(s.minPrice)}`),
    h('span', { class: ['badge', `badge-${s.availability.toLowerCase()}`] },
      h('span', { 'aria-hidden': 'true' }, AVAILABILITY_ICON[s.availability] || ''), ' ', AVAILABILITY[s.availability] || s.availability),
  ];
  return h('li', null, soldOut
    ? h('span', { class: ['show-link', 'is-sold-out'], 'aria-label': label }, inner)
    : h('a', { class: 'show-link', href: `/shows/${s.id}`, 'aria-label': label }, inner));
}
