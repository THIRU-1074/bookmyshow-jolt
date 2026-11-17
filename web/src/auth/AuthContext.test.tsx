import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from '../api/client';
import { AuthProvider, useAuth } from './AuthContext';

function Who() {
  const { user, ready } = useAuth();
  return <p>{ready ? (user ? `signed in as ${user.name}` : 'anonymous') : 'loading'}</p>;
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('AuthProvider', () => {
  it('restores the session on load through the refresh cookie', async () => {
    window.localStorage.setItem('bms.signedIn', '1');
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(200, { accessToken: 'a', expiresIn: 900 }))
      .mockResolvedValueOnce(json(200, { id: 1, email: 'a@b.c', name: 'Asha', roles: ['AUDIENCE'] }));
    render(
      <AuthProvider client={new ApiClient(fetchMock as unknown as typeof fetch)}>
        <Who />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText('signed in as Asha')).toBeInTheDocument());
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/auth/refresh');
  });

  it('does not call refresh for a browser that never signed in', async () => {
    window.localStorage.clear();
    const fetchMock = vi.fn();
    render(
      <AuthProvider client={new ApiClient(fetchMock as unknown as typeof fetch)}>
        <Who />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText('anonymous')).toBeInTheDocument());
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('ends anonymous when the refresh cookie is rejected', async () => {
    window.localStorage.setItem('bms.signedIn', '1');
    const fetchMock = vi.fn().mockResolvedValue(json(401, { status: 401 }));
    render(
      <AuthProvider client={new ApiClient(fetchMock as unknown as typeof fetch)}>
        <Who />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText('anonymous')).toBeInTheDocument());
    expect(window.localStorage.getItem('bms.signedIn')).toBeNull();
  });
});
