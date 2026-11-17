// The selected city, remembered in localStorage (SPEC section 8, route "/").
import { cities } from './api.js';
import { DEFAULT_ZONE, clear, h, storage } from './util.js';

const KEY = 'bms.city';
const listeners = new Set();

export function cityId() {
  return storage.get(KEY);
}

export async function currentCity() {
  const id = cityId();
  if (!id) return null;
  const list = await cities();
  return list.find((c) => c.id === id) || null;
}

export async function cityZone() {
  return (await currentCity().catch(() => null))?.timeZone || DEFAULT_ZONE;
}

export function setCity(id) {
  storage.set(KEY, id);
  for (const fn of listeners) fn(id);
}

export function onCityChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/** Buttons for every city; used by the first-visit picker and the header dialog. */
export function cityButtons(list, onPick) {
  const selected = cityId();
  return list.map((c) => h('button', {
    type: 'button',
    class: ['city-option', c.id === selected && 'is-selected'],
    'aria-pressed': String(c.id === selected),
    onclick: () => onPick(c),
  }, h('span', { class: 'city-initial', 'aria-hidden': 'true' }, c.name.slice(0, 1)), h('span', null, c.name)));
}

export async function openCityDialog() {
  const dialog = document.getElementById('city-dialog');
  const listEl = clear(document.getElementById('city-dialog-list'));
  try {
    const list = await cities();
    listEl.append(...cityButtons(list, (c) => {
      setCity(c.id);
      dialog.close();
    }));
  } catch (e) {
    listEl.append(h('p', { class: 'alert alert-error' }, e.message));
  }
  dialog.showModal();
}
