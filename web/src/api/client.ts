// Typed HTTP client for the SPEC section 7 API.
//
// - The access token lives only in memory. The refresh token is the HttpOnly `bms_refresh`
//   cookie (Path=/api/auth), so on load and on any 401 the client calls /api/auth/refresh once,
//   shared by every concurrent caller, and retries the original request.
// - /api/auth is behind Jolt's Csrf.protect(): the browser's Sec-Fetch-Site/Origin headers carry
//   the proof, and when a `csrf` cookie exists its value is echoed in X-CSRF-Token as well.
// - Every POST outside /api/auth gets an Idempotency-Key. Callers that may retry the same intent
//   (holding the same seats twice) pass their own stable key so a retry replays, not repeats.
// - Failures become ApiError: a friendly sentence, the stable `code`, field errors and the
//   requestId to quote to support.
import { storage } from '../lib/storage';
import type { AuthResponse, Problem, TokenResponse, User } from './types';

/**
 * A non-secret hint that this browser holds a refresh cookie. The cookie is HttpOnly, so the page
 * cannot see it; without the hint every anonymous visit would POST /api/auth/refresh on load and
 * log a 401 in the console. The cookie stays the only credential.
 */
const SESSION_HINT = 'bms.signedIn';

export type Query = Record<string, string | number | boolean | (string | number)[] | null | undefined>;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  query?: Query;
  /** A key to reuse across retries of one intent; `false` sends none. Defaults to a fresh UUID on POST. */
  idempotencyKey?: string | false;
  signal?: AbortSignal;
  /** Internal: whether a 401 may trigger a refresh and one retry. */
  retry?: boolean;
}

const FRIENDLY: Record<string, string> = {
  VALIDATION: 'Some details need fixing.',
  NOT_FOUND: 'We could not find that. It may have been removed.',
  SEAT_UNAVAILABLE: 'Some of those seats were just taken.',
  HOLD_EXPIRED: 'Your seat hold expired, so the seats went back on sale.',
  BOOKING_STATE: 'This booking can no longer do that.',
  PAYMENT_IN_PROGRESS: 'A payment is in progress for this booking. Finish or fail it first.',
  SHOW_STARTED: 'This show has already started.',
  LIMIT_EXCEEDED: 'You have reached the limit for this action.',
  VERSION_CONFLICT: 'Something changed while you were working. Reload and try again.',
  IDEMPOTENCY: 'That request is already being processed.',
  RATE_LIMITED: 'Too many requests. Wait a moment and try again.',
  UNAUTHENTICATED: 'Sign in to continue.',
  FORBIDDEN: 'Your account cannot do that.',
};

export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;
  readonly requestId: string | undefined;

  constructor(status: number, problem: Problem, requestId?: string) {
    super(ApiError.describe(status, problem));
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.requestId = problem.requestId ?? requestId;
  }

  get code(): string | undefined {
    return this.problem.code;
  }

  /** Field errors from a 422, keyed by path such as `seats[2]` or `rows[3].seats`. */
  get fieldErrors(): Record<string, string> {
    return this.problem.errors ?? {};
  }

  get conflicts(): string[] {
    return this.problem.conflicts ?? [];
  }

  private static describe(status: number, p: Problem): string {
    if (p.detail) return p.detail;
    if (p.code && FRIENDLY[p.code]) return FRIENDLY[p.code] as string;
    if (status === 0) return 'Could not reach the server. Check your connection and try again.';
    if (status >= 500) return 'The server had a problem. Try again in a moment.';
    return p.title || `Request failed (${status}).`;
  }
}

export function newIdempotencyKey(): string {
  const c: Crypto = globalThis.crypto;
  if (typeof c.randomUUID === 'function') return c.randomUUID();
  // Non-secure contexts (plain-http LAN hosts) lack randomUUID; 128 random bits do the job.
  const b = new Uint8Array(16);
  c.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
}

function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null;
  const m = document.cookie.match(new RegExp(`(?:^|;\\s*)${name}=([^;]+)`));
  return m ? decodeURIComponent(m[1] as string) : null;
}

export function buildUrl(path: string, query?: Query): string {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v)) {
      if (v.length) params.set(k, v.join(','));
    } else {
      params.set(k, String(v));
    }
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

type Listener = (user: User | null) => void;

export class ApiClient {
  private token: string | null = null;
  private user: User | null = null;
  private refreshing: Promise<boolean> | null = null;
  private readonly listeners = new Set<Listener>();
  private readonly fetchImpl: typeof fetch;

  constructor(fetchImpl?: typeof fetch) {
    this.fetchImpl = fetchImpl ?? ((...a) => fetch(...a));
  }

  get currentUser(): User | null {
    return this.user;
  }

  get hasToken(): boolean {
    return this.token !== null;
  }

  onSession(fn: Listener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  /** Whether an on-load refresh is worth trying (see SESSION_HINT). */
  get mayHaveSession(): boolean {
    return storage.get(SESSION_HINT) === '1';
  }

  private setSession(token: string | null, user: User | null) {
    this.token = token;
    this.user = user;
    storage.set(SESSION_HINT, user ? '1' : null);
    for (const fn of this.listeners) fn(user);
  }

  async request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
    const method = opts.method ?? 'GET';
    const isAuth = path.startsWith('/api/auth/');
    const key =
      opts.idempotencyKey === false || isAuth || method !== 'POST'
        ? null
        : (opts.idempotencyKey ?? newIdempotencyKey());
    const url = buildUrl(path, opts.query);

    const send = () => {
      const headers: Record<string, string> = { Accept: 'application/json' };
      if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
      if (this.token) headers.Authorization = `Bearer ${this.token}`;
      if (key) headers['Idempotency-Key'] = key;
      const csrf = readCookie('csrf');
      if (csrf && method !== 'GET') headers['X-CSRF-Token'] = csrf;
      return this.fetchImpl(url, {
        method,
        headers,
        body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
        credentials: 'same-origin',
        signal: opts.signal,
      });
    };

    let res: Response;
    try {
      res = await send();
      if (res.status === 401 && opts.retry !== false && !isAuth && (await this.refresh())) {
        res = await send();
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') throw e;
      throw new ApiError(0, {});
    }
    return parse<T>(res);
  }

  get<T>(path: string, query?: Query, signal?: AbortSignal) {
    return this.request<T>(path, { query, signal });
  }

  post<T>(path: string, body?: unknown, idempotencyKey?: string) {
    return this.request<T>(path, { method: 'POST', body, idempotencyKey });
  }

  put<T>(path: string, body: unknown) {
    return this.request<T>(path, { method: 'PUT', body });
  }

  del<T = null>(path: string) {
    return this.request<T>(path, { method: 'DELETE' });
  }

  /** Exchanges the refresh cookie for an access token; concurrent callers share one call. */
  refresh(): Promise<boolean> {
    if (!this.refreshing) {
      this.refreshing = (async () => {
        try {
          const out = await this.request<TokenResponse>('/api/auth/refresh', { method: 'POST', retry: false });
          this.token = out.accessToken;
          // retry:false, or a 401 here would wait on this very refresh and never settle.
          const me = await this.request<User>('/api/me', { retry: false });
          this.setSession(out.accessToken, me);
          return true;
        } catch {
          this.setSession(null, null);
          return false;
        } finally {
          this.refreshing = null;
        }
      })();
    }
    return this.refreshing;
  }

  async login(email: string, password: string): Promise<User> {
    const out = await this.post<AuthResponse>('/api/auth/login', { email, password });
    this.setSession(out.accessToken, out.user);
    return out.user;
  }

  async register(name: string, email: string, password: string): Promise<User> {
    const out = await this.post<AuthResponse>('/api/auth/register', { name, email, password });
    this.setSession(out.accessToken, out.user);
    return out.user;
  }

  async logout(): Promise<void> {
    try {
      await this.post('/api/auth/logout');
    } finally {
      this.setSession(null, null);
    }
  }
}

async function parse<T>(res: Response): Promise<T> {
  if (res.status === 204) return null as T;
  const type = res.headers.get('Content-Type') ?? '';
  const data: unknown = type.includes('json') ? await res.json().catch(() => null) : await res.text();
  if (!res.ok) {
    const problem: Problem =
      data && typeof data === 'object'
        ? (data as Problem)
        : { title: res.statusText, detail: typeof data === 'string' && data && data.length < 200 ? data : undefined };
    if (res.status === 429 && !problem.detail) {
      const wait = res.headers.get('Retry-After');
      problem.detail = `Too many requests. Try again ${wait ? `in ${wait} seconds` : 'shortly'}.`;
    }
    throw new ApiError(res.status, problem, res.headers.get('X-Request-Id') ?? undefined);
  }
  return data as T;
}

export const api = new ApiClient();
