import { useEffect, useRef, useState, type ReactNode } from 'react';
import { currentUserRequest, loginRequest } from '../api/auth';
import { onUnauthorized, TOKEN_STORAGE_KEY } from '../api/axiosClient';
import type { AuthenticatedUser } from '../types/api';
import { AuthContext } from './authContextValue';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthenticatedUser | null>(null);
  const [isLoading, setIsLoading] = useState(() => !!localStorage.getItem(TOKEN_STORAGE_KEY));
  const generation = useRef(0);

  useEffect(() => {
    const unsubscribe = onUnauthorized(() => {
      generation.current += 1;
      setUser(null);
      setIsLoading(false);
    });
    const current = ++generation.current;
    if (localStorage.getItem(TOKEN_STORAGE_KEY)) {
      currentUserRequest()
        .then((authenticatedUser) => {
          if (generation.current === current) setUser(authenticatedUser);
        })
        .catch(() => {
          if (generation.current === current) {
            localStorage.removeItem(TOKEN_STORAGE_KEY);
            setUser(null);
          }
        })
        .finally(() => {
          if (generation.current === current) setIsLoading(false);
        });
    }
    return () => {
      generation.current += 1;
      unsubscribe();
    };
  }, []);

  const logout = () => {
    generation.current += 1;
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    setUser(null);
    setIsLoading(false);
  };

  const login = async (username: string, password: string) => {
    const current = ++generation.current;
    setUser(null);
    setIsLoading(false);
    const response = await loginRequest(username, password);
    if (generation.current !== current) throw new Error('Login cancelled');
    if (!response.accessToken || response.tokenType !== 'Bearer') {
      throw new Error('Invalid login response');
    }
    localStorage.setItem(TOKEN_STORAGE_KEY, response.accessToken);
    try {
      const authenticatedUser = await currentUserRequest();
      if (generation.current !== current) throw new Error('Login cancelled');
      setUser(authenticatedUser);
      return authenticatedUser;
    } catch (error) {
      if (generation.current === current) {
        localStorage.removeItem(TOKEN_STORAGE_KEY);
        setUser(null);
      }
      throw error;
    }
  };

  return (
    <AuthContext.Provider value={{ user, isAuthenticated: user !== null, isLoading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
