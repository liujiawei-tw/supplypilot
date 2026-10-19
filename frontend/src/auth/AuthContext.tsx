import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { isAxiosError } from 'axios';
import { currentUserRequest, loginRequest } from '../api/auth';
import { clearAccessToken, onUnauthorized, saveAccessToken, TOKEN_STORAGE_KEY } from '../api/axiosClient';
import type { AuthenticatedUser } from '../types/api';
import { AuthContext } from './authContextValue';
import { PostLoginVerificationError } from './authErrors';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthenticatedUser | null>(null);
  const [isLoading, setIsLoading] = useState(() => !!localStorage.getItem(TOKEN_STORAGE_KEY));
  const [verificationError, setVerificationError] = useState(false);
  const generation = useRef(0);

  const verifySession = useCallback(async (current: number) => {
    try {
      const authenticatedUser = await currentUserRequest();
      if (generation.current !== current) return undefined;
      setUser(authenticatedUser);
      setVerificationError(false);
      return authenticatedUser;
    } catch (error) {
      if (generation.current === current) {
        setUser(null);
        if (isAxiosError(error) && error.response?.status === 401) {
          clearAccessToken();
          setVerificationError(false);
        } else {
          // The token may still be valid. Keep it for an explicit retry.
          setVerificationError(true);
        }
      }
      throw error;
    } finally {
      if (generation.current === current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    const unsubscribe = onUnauthorized(() => {
      generation.current += 1;
      setUser(null);
      setVerificationError(false);
      setIsLoading(false);
    });
    const current = ++generation.current;
    if (localStorage.getItem(TOKEN_STORAGE_KEY)) {
      void verifySession(current).catch(() => {});
    }
    return () => {
      generation.current += 1;
      unsubscribe();
    };
  }, [verifySession]);

  const logout = () => {
    generation.current += 1;
    clearAccessToken();
    setUser(null);
    setVerificationError(false);
    setIsLoading(false);
  };

  const retryVerification = () => {
    if (!localStorage.getItem(TOKEN_STORAGE_KEY)) return;
    const current = ++generation.current;
    setUser(null);
    setVerificationError(false);
    setIsLoading(true);
    void verifySession(current).catch(() => {});
  };

  const login = async (username: string, password: string) => {
    const current = ++generation.current;
    setUser(null);
    setVerificationError(false);
    setIsLoading(false);
    const response = await loginRequest(username, password);
    if (generation.current !== current) throw new Error('Login cancelled');
    if (!response.accessToken || response.tokenType !== 'Bearer') {
      throw new Error('Invalid login response');
    }
    saveAccessToken(response.accessToken);
    setIsLoading(true);
    let authenticatedUser: AuthenticatedUser | undefined;
    try {
      authenticatedUser = await verifySession(current);
    } catch (error) {
      throw new PostLoginVerificationError(error);
    }
    if (!authenticatedUser) throw new Error('Login cancelled');
    return authenticatedUser;
  };

  return (
    <AuthContext.Provider value={{ user, isAuthenticated: user !== null, isLoading, verificationError, retryVerification, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
