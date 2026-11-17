import { isAdmin, logout, onAuthChange, refresh, user } from './api.js';
import { currentCity, onCityChange, openCityDialog } from './city.js';
import { navigate, reload, route, setGuard, start } from './router.js';
import { clear, h, storage, toast } from './util.js';

route('/', () => import('./pages/movies.js'), { home: true });
route('/movies', () => import('./pages/movies.js'));
route('/movies/:id', () => import('./pages/movie.js'));
route('/shows/:id', () => import('./pages/show.js'));
route('/checkout/:id', () => import('./pages/checkout.js'), { auth: true });
route('/bookings', () => import('./pages/bookings.js'), { auth: true });
route('/bookings/:id', () => import('./pages/booking.js'), { auth: true });
route('/login', () => import('./pages/auth.js'));
route('/register', () => import('./pages/auth.js'));
route('/admin', () => import('./pages/admin.js'), { auth: true, admin: true });

setGuard((options, loc) => {
  if (options.auth && !user()) return `/login?next=${encodeURIComponent(loc.pathname + loc.search)}`;
  if (options.admin && !isAdmin()) {
    toast('That page is for administrators.', 'error');
    return '/';
  }
  return null;
});

// Theme: follows prefers-color-scheme until the toggle stores an explicit choice.
const THEME_KEY = 'bms.theme';
const media = window.matchMedia('(prefers-color-scheme: dark)');
function effectiveTheme() {
  return storage.get(THEME_KEY) || (media.matches ? 'dark' : 'light');
}
function applyTheme() {
  const theme = effectiveTheme();
  document.documentElement.dataset.theme = theme;
  const btn = document.getElementById('theme-toggle');
  btn.setAttribute('aria-pressed', String(theme === 'dark'));
}
document.getElementById('theme-toggle').addEventListener('click', () => {
  storage.set(THEME_KEY, effectiveTheme() === 'dark' ? 'light' : 'dark');
  applyTheme();
});
media.addEventListener('change', applyTheme);
applyTheme();

function renderNav(me) {
  const ul = clear(document.getElementById('nav-links'));
  ul.append(h('li', null, h('a', { href: '/movies' }, 'Movies')));
  if (me) {
    ul.append(h('li', null, h('a', { href: '/bookings' }, 'My bookings')));
    if (me.roles?.includes('ADMIN')) ul.append(h('li', null, h('a', { href: '/admin' }, 'Admin')));
    ul.append(h('li', { class: 'nav-user' },
      h('span', { class: 'nav-name' }, me.name),
      h('button', {
        type: 'button',
        class: 'link-button',
        onclick: async () => {
          await logout().catch(() => {});
          toast('Signed out.');
          navigate('/');
        },
      }, 'Sign out')));
  } else {
    ul.append(h('li', null, h('a', { href: '/login' }, 'Sign in')));
  }
}

async function renderCityButton() {
  const btn = document.getElementById('city-button');
  const city = await currentCity().catch(() => null);
  btn.hidden = !city;
  clear(btn).append(h('span', { 'aria-hidden': 'true' }, '◉ '), city ? city.name : '');
  btn.setAttribute('aria-label', city ? `City: ${city.name}. Change city` : 'Choose city');
}
document.getElementById('city-button').addEventListener('click', openCityDialog);

onAuthChange(renderNav);
onCityChange(() => {
  renderCityButton();
  reload();
});

// Sign back in from the refresh cookie before the first render (SPEC section 8).
await refresh();
renderNav(user());
renderCityButton();
start();
