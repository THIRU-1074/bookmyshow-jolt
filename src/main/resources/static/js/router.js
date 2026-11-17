// History API router (SPEC section 8: hash-free routes). Each page module exports
// render(ctx) and may return a cleanup function that runs before the next route renders.

const routes = [];
let cleanup = null;
let renderToken = 0;
let guard = null;

export function route(pattern, load, options = {}) {
  const keys = [];
  const regex = new RegExp(`^${pattern.replace(/:([a-zA-Z]+)/g, (_, k) => { keys.push(k); return '([^/]+)'; })}/?$`);
  routes.push({ regex, keys, load, options });
}

/** guard(route options, location) returns a redirect path or null. */
export function setGuard(fn) {
  guard = fn;
}

export function navigate(path, { replace = false } = {}) {
  if (replace) history.replaceState(null, '', path);
  else history.pushState(null, '', path);
  return render();
}

/** Re-renders the current URL without adding history, used after sign-in or city change. */
export function reload() {
  return render({ keepScroll: true });
}

export async function render({ keepScroll = false } = {}) {
  const token = ++renderToken;
  const main = document.getElementById('main');
  const { pathname, search } = location;
  let match = null;
  let params = {};
  for (const r of routes) {
    const m = pathname.match(r.regex);
    if (m) {
      match = r;
      params = Object.fromEntries(r.keys.map((k, i) => [k, decodeURIComponent(m[i + 1])]));
      break;
    }
  }
  if (typeof cleanup === 'function') {
    try { cleanup(); } catch { /* a failing cleanup must not block navigation */ }
  }
  cleanup = null;

  if (match && guard) {
    const redirect = await guard(match.options, location);
    if (token !== renderToken) return;
    if (redirect) {
      history.replaceState(null, '', redirect);
      return render();
    }
  }

  const page = match ? await match.load() : await import('./pages/notfound.js');
  if (token !== renderToken) return;
  // Each render gets its own container, so a slow render that loses the race writes into a
  // detached node instead of the page.
  const view = document.createElement('div');
  view.className = 'view';
  main.replaceChildren(view);
  main.removeAttribute('aria-busy');
  const ctx = {
    main: view,
    params,
    query: new URLSearchParams(search),
    isCurrent: () => token === renderToken,
  };
  try {
    cleanup = await page.render(ctx);
  } catch (e) {
    console.error(e);
    main.replaceChildren();
    const p = document.createElement('p');
    p.className = 'alert alert-error';
    p.setAttribute('role', 'alert');
    p.textContent = e.message || 'This page failed to load.';
    main.append(p);
  }
  if (token !== renderToken) return;
  markActiveNav(pathname);
  if (!keepScroll) window.scrollTo(0, 0);
  // Focus moves to the page heading on route change (SPEC section 8).
  const heading = main.querySelector('h1');
  if (heading) {
    heading.setAttribute('tabindex', '-1');
    heading.focus({ preventScroll: keepScroll });
    document.title = `${heading.textContent.trim()} | BookMyShow on Jolt`;
  }
}

function markActiveNav(pathname) {
  for (const a of document.querySelectorAll('.site-nav a')) {
    const href = a.getAttribute('href');
    const active = href === '/' ? pathname === '/' : pathname === href || pathname.startsWith(`${href}/`);
    if (active) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  }
}

/** Intercepts same-origin link clicks so navigation stays in the app. */
export function start() {
  document.addEventListener('click', (e) => {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const a = e.target.closest('a[href]');
    if (!a || a.target || a.hasAttribute('download') || a.dataset.external !== undefined) return;
    const url = new URL(a.href, location.href);
    if (url.origin !== location.origin) return;
    if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/mock-gateway/') || url.pathname.startsWith('/_jolt/')) return;
    if (url.pathname === location.pathname && url.search === location.search && url.hash) return;
    e.preventDefault();
    navigate(url.pathname + url.search);
  });
  window.addEventListener('popstate', () => render({ keepScroll: true }));
  return render();
}
