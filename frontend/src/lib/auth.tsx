import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, setUnauthorizedHandler, tokenStore } from '../api/client';
import type { TokenResponse, User } from '../api/types';

interface AuthState {
  user: User | null;
  /** true while a stored token is being verified on first load */
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const Ctx = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const [token, setToken] = useState<string | null>(() => tokenStore.get());

  const logout = useCallback(() => {
    tokenStore.set(null);
    setToken(null);
    qc.clear(); // never show one user's cached data to the next
  }, [qc]);

  // An expired/invalid token anywhere sends the user back to the login page.
  useEffect(() => setUnauthorizedHandler(logout), [logout]);

  const me = useQuery({
    queryKey: ['me'],
    queryFn: () => api<User>('/auth/me'),
    enabled: !!token,
    retry: false,
    staleTime: 5 * 60_000,
  });

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await api<TokenResponse>('/auth/login', { method: 'POST', body: { email, password } });
      tokenStore.set(res.accessToken);
      qc.setQueryData(['me'], res.user);
      setToken(res.accessToken);
    },
    [qc],
  );

  const value = useMemo<AuthState>(
    () => ({ user: token ? (me.data ?? null) : null, loading: !!token && me.isPending, login, logout }),
    [token, me.data, me.isPending, login, logout],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAuth outside AuthProvider');
  return v;
}
