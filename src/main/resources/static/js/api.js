// HTTP client for the SPEC section 7 API. The access token lives only in memory; the refresh
// token is the HttpOnly bms_refresh cookie, so a reload calls /api/auth/refresh to sign back in.

export class ApiError extends Error {
  constructor(status, problem) {
    super(problem?.detail || problem?.title || `Request failed (${status})`);
    this.status = status;
    this.problem = problem || {};
    this.code = this.problem.code;
  }

  /** Field errors from a 422 VALIDATION problem, keyed like "seats[2]". */
  get fieldErrors() {
    return this.problem.errors || {};
  }
}

let accessToken = null;
let currentUser = null;
let refreshing = null;
const listeners = new Set();

export function user() {
  return currentUser;
}

export function isAdmin() {
  return Boolean(currentUser?.roles?.includes('ADMIN'));
}

export function onAuthChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function setSession(token, me) {
  accessToken = token;
  currentUser = me;
  for (const fn of listeners) fn(currentUser);
}

function csrfCookie() {
  const match = document.cookie.match(/(?:^|;\s*)csrf=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

export function newIdempotencyKey() {
  return crypto.randomUUID();
}

/**
 * Calls the API. Options: method, body (JSON), query (object; arrays become comma lists),
 * idempotencyKey (sent as Idempotency-Key, SPEC section 5.7). A 401 triggers one refresh and retry.
 */
export async function api(path, options = {}) {
  const { method = 'GET', body, query, idempotencyKey, signal, raw = false, retry = true } = options;
  const url = new URL(path, location.origin);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v === undefined || v === null || v === '' || (Array.isArray(v) && !v.length)) continue;
      url.searchParams.set(k, Array.isArray(v) ? v.join(',') : String(v));
    }
  }
  const send = async () => {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    const csrf = csrfCookie();
    if (csrf && method !== 'GET') headers['X-CSRF-Token'] = csrf;
    return fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
      signal,
    });
  };

  let res = await send();
  if (retry && res.status === 401 && !path.startsWith('/api/auth/') && (await refresh())) {
    res = await send();
  }
  if (raw && res.ok) return res;
  return parse(res);
}

async function parse(res) {
  if (res.status === 204) return null;
  const type = res.headers.get('Content-Type') || '';
  const data = type.includes('json') ? await res.json().catch(() => null) : await res.text();
  if (!res.ok) {
    const problem = data && typeof data === 'object' ? data : { title: res.statusText, detail: typeof data === 'string' && data.length < 200 ? data : undefined };
    if (res.status === 429 && !problem.detail) {
      const wait = res.headers.get('Retry-After');
      problem.detail = `Too many requests. Try again${wait ? ` in ${wait} seconds` : ' shortly'}.`;
    }
    throw new ApiError(res.status, problem);
  }
  return data;
}

/** Exchanges the refresh cookie for an access token. Concurrent callers share one request. */
export function refresh() {
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const res = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' } });
        if (!res.ok) {
          setSession(null, null);
          return false;
        }
        const { accessToken: token } = await res.json();
        accessToken = token;
        // retry:false, otherwise a 401 here would await this very refresh and never settle.
        const me = await api('/api/me', { retry: false });
        setSession(token, me);
        return true;
      } catch {
        setSession(null, null);
        return false;
      } finally {
        refreshing = null;
      }
    })();
  }
  return refreshing;
}

export async function login(email, password) {
  const out = await api('/api/auth/login', { method: 'POST', body: { email, password } });
  setSession(out.accessToken, out.user);
  return out.user;
}

export async function register(name, email, password) {
  const out = await api('/api/auth/register', { method: 'POST', body: { name, email, password } });
  setSession(out.accessToken, out.user);
  return out.user;
}

export async function logout() {
  try {
    await api('/api/auth/logout', { method: 'POST' });
  } finally {
    setSession(null, null);
  }
}

let citiesCache = null;
export async function cities() {
  if (!citiesCache) citiesCache = api('/api/cities').catch((e) => { citiesCache = null; throw e; });
  return citiesCache;
}
