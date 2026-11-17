import { describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiError, buildUrl } from './client';

type Call = { url: string; init: RequestInit };

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(body === null ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });
}

function fake(...responses: (Response | ((c: Call) => Response))[]) {
  const calls: Call[] = [];
  const fn = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    const call = { url: String(url), init: init ?? {} };
    calls.push(call);
    const next = responses.shift();
    if (!next) throw new Error(`unexpected request ${call.url}`);
    return typeof next === 'function' ? next(call) : next;
  });
  return { fn: fn as unknown as typeof fetch, calls };
}

const header = (c: Call, name: string) => (c.init.headers as Record<string, string>)[name];

describe('buildUrl', () => {
  it('joins arrays as comma lists and drops empty values', () => {
    expect(buildUrl('/api/movies', { city: 'pune', genre: ['Action', 'Drama'], q: '', language: [], page: 2, x: null })).toBe(
      '/api/movies?city=pune&genre=Action%2CDrama&page=2',
    );
  });
});

describe('ApiClient', () => {
  it('sends a fresh Idempotency-Key on every POST and none on GET', async () => {
    const { fn, calls } = fake(json(201, { id: 'h1' }), json(201, { id: 'h2' }), json(200, []));
    const api = new ApiClient(fn);
    await api.post('/api/holds', { showId: 1, seats: ['A1'] });
    await api.post('/api/holds', { showId: 1, seats: ['A1'] });
    await api.get('/api/cities');
    const k1 = header(calls[0]!, 'Idempotency-Key');
    const k2 = header(calls[1]!, 'Idempotency-Key');
    expect(k1).toMatch(/^[0-9a-f-]{32,36}$/);
    expect(k2).toBeTruthy();
    expect(k1).not.toBe(k2);
    expect(header(calls[2]!, 'Idempotency-Key')).toBeUndefined();
  });

  it('reuses a caller-supplied key so a retry replays the same intent', async () => {
    const { fn, calls } = fake(json(201, {}), json(201, {}));
    const api = new ApiClient(fn);
    await api.post('/api/holds', {}, 'same-key');
    await api.post('/api/holds', {}, 'same-key');
    expect(header(calls[0]!, 'Idempotency-Key')).toBe('same-key');
    expect(header(calls[1]!, 'Idempotency-Key')).toBe('same-key');
  });

  it('never sends Idempotency-Key to /api/auth routes', async () => {
    const user = { id: 1, email: 'a@b.c', name: 'A', roles: ['AUDIENCE'] };
    const { fn, calls } = fake(json(200, { user, accessToken: 't', expiresIn: 900 }));
    const api = new ApiClient(fn);
    await api.login('a@b.c', 'pw');
    expect(header(calls[0]!, 'Idempotency-Key')).toBeUndefined();
    expect(calls[0]!.init.credentials).toBe('same-origin');
  });

  it('remembers only a sign-in hint, so anonymous visits skip the on-load refresh', async () => {
    window.localStorage.clear();
    const user = { id: 1, email: 'a@b.c', name: 'A', roles: ['AUDIENCE'] };
    const { fn } = fake(json(200, { user, accessToken: 'secret-token', expiresIn: 900 }), json(204, null));
    const api = new ApiClient(fn);
    expect(api.mayHaveSession).toBe(false);
    await api.login('a@b.c', 'pw');
    expect(api.mayHaveSession).toBe(true);
    expect(JSON.stringify({ ...window.localStorage })).not.toContain('secret-token');
    await api.logout();
    expect(api.mayHaveSession).toBe(false);
  });

  it('keeps the access token in memory and sends it as a bearer token', async () => {
    const user = { id: 1, email: 'a@b.c', name: 'A', roles: ['AUDIENCE'] };
    const { fn, calls } = fake(json(200, { user, accessToken: 'tok-1', expiresIn: 900 }), json(200, { items: [] }));
    const api = new ApiClient(fn);
    await api.login('a@b.c', 'pw');
    await api.get('/api/bookings');
    expect(header(calls[1]!, 'Authorization')).toBe('Bearer tok-1');
    expect(JSON.stringify({ ...window.localStorage })).not.toContain('tok-1');
  });

  it('refreshes once on 401 and retries the request with the new token', async () => {
    const me = { id: 7, email: 'x@y.z', name: 'X', roles: ['AUDIENCE'] };
    const { fn, calls } = fake(
      json(401, { status: 401, code: 'UNAUTHENTICATED' }),
      json(200, { accessToken: 'fresh', expiresIn: 900 }),
      json(200, me),
      json(200, { ok: true }),
    );
    const api = new ApiClient(fn);
    const seen: unknown[] = [];
    api.onSession((u) => seen.push(u));
    await expect(api.get('/api/bookings')).resolves.toEqual({ ok: true });
    expect(calls.map((c) => c.url)).toEqual(['/api/bookings', '/api/auth/refresh', '/api/me', '/api/bookings']);
    expect(calls[1]!.init.method).toBe('POST');
    expect(header(calls[3]!, 'Authorization')).toBe('Bearer fresh');
    expect(seen).toEqual([me]);
    expect(api.currentUser).toEqual(me);
  });

  it('shares one refresh between concurrent 401s', async () => {
    let refreshes = 0;
    const me = { id: 1, email: 'a@b.c', name: 'A', roles: ['AUDIENCE'] };
    const fn = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
      const u = String(url);
      const auth = (init?.headers as Record<string, string>).Authorization;
      if (u === '/api/auth/refresh') {
        refreshes++;
        await new Promise((r) => setTimeout(r, 10));
        return json(200, { accessToken: 'new', expiresIn: 900 });
      }
      if (u === '/api/me') return json(200, me);
      return auth === 'Bearer new' ? json(200, { u }) : json(401, { status: 401 });
    }) as unknown as typeof fetch;
    const api = new ApiClient(fn);
    const out = await Promise.all([api.get('/api/a'), api.get('/api/b'), api.get('/api/c')]);
    expect(out).toEqual([{ u: '/api/a' }, { u: '/api/b' }, { u: '/api/c' }]);
    expect(refreshes).toBe(1);
  });

  it('signs out and surfaces the 401 when the refresh cookie is rejected', async () => {
    const { fn } = fake(json(401, { status: 401, code: 'UNAUTHENTICATED' }), json(401, { status: 401 }));
    const api = new ApiClient(fn);
    const seen: unknown[] = [];
    api.onSession((u) => seen.push(u));
    const err = await api.get('/api/bookings').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
    expect((err as ApiError).message).toBe('Sign in to continue.');
    expect(seen).toEqual([null]);
  });

  it('does not loop when /api/auth routes return 401', async () => {
    const { fn, calls } = fake(json(401, { status: 401, detail: 'Bad credentials' }));
    const api = new ApiClient(fn);
    await expect(api.login('a@b.c', 'nope')).rejects.toThrow('Bad credentials');
    expect(calls).toHaveLength(1);
  });

  it('turns problem+json into ApiError with code, field errors, conflicts and requestId', async () => {
    const { fn } = fake(
      json(409, {
        title: 'Conflict',
        status: 409,
        detail: 'Seat C7 is no longer available',
        code: 'SEAT_UNAVAILABLE',
        conflicts: ['C7'],
        requestId: 'req-123',
      }),
      json(422, { status: 422, code: 'VALIDATION', errors: { 'seats[0]': 'unknown seat' } }, { 'X-Request-Id': 'req-9' }),
    );
    const api = new ApiClient(fn);
    const e1 = (await api.post('/api/holds', {}).catch((e: unknown) => e)) as ApiError;
    expect(e1.message).toBe('Seat C7 is no longer available');
    expect(e1.code).toBe('SEAT_UNAVAILABLE');
    expect(e1.conflicts).toEqual(['C7']);
    expect(e1.requestId).toBe('req-123');
    const e2 = (await api.post('/api/holds', {}).catch((e: unknown) => e)) as ApiError;
    expect(e2.message).toBe('Some details need fixing.');
    expect(e2.fieldErrors).toEqual({ 'seats[0]': 'unknown seat' });
    expect(e2.requestId).toBe('req-9');
  });

  it('explains rate limits with Retry-After and network failures plainly', async () => {
    const fn = vi
      .fn()
      .mockResolvedValueOnce(new Response('', { status: 429, headers: { 'Retry-After': '12' } }))
      .mockRejectedValueOnce(new TypeError('Failed to fetch')) as unknown as typeof fetch;
    const api = new ApiClient(fn);
    await expect(api.post('/api/holds', {})).rejects.toThrow('Too many requests. Try again in 12 seconds.');
    const net = (await api.get('/api/cities').catch((e: unknown) => e)) as ApiError;
    expect(net.status).toBe(0);
    expect(net.message).toMatch(/Could not reach the server/);
  });

  it('echoes the csrf cookie in X-CSRF-Token on unsafe methods only', async () => {
    document.cookie = 'csrf=tok%20en';
    const { fn, calls } = fake(json(200, {}), json(204, null));
    const api = new ApiClient(fn);
    await api.get('/api/cities');
    await api.del('/api/holds/1');
    expect(header(calls[0]!, 'X-CSRF-Token')).toBeUndefined();
    expect(header(calls[1]!, 'X-CSRF-Token')).toBe('tok en');
    document.cookie = 'csrf=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });
});
