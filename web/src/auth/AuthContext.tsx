import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, type ApiClient } from '../api/client';
import type { User } from '../api/types';

interface AuthValue {
  user: User | null;
  /** False until the on-load refresh settles, so guarded pages do not bounce to /login early. */
  ready: boolean;
  client: ApiClient;
}

const AuthContext = createContext<AuthValue>({ user: null, ready: false, client: api });

export function AuthProvider({ children, client = api }: { children: ReactNode; client?: ApiClient }) {
  const [user, setUser] = useState<User | null>(client.currentUser);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const off = client.onSession(setUser);
    // SPEC section 8: the app calls /api/auth/refresh on load to restore the session, when this
    // browser has signed in before; a 401 later in the session still triggers a refresh.
    if (client.mayHaveSession) void client.refresh().finally(() => setReady(true));
    else setReady(true);
    return () => {
      off();
    };
  }, [client]);

  const value = useMemo(() => ({ user, ready, client }), [user, ready, client]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  return useContext(AuthContext);
}
