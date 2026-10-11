import { createContext } from 'react';
import type { AuthenticatedUser } from '../types/api';

export interface AuthState {
  user: AuthenticatedUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  verificationError: boolean;
  retryVerification: () => void;
  login: (username: string, password: string) => Promise<AuthenticatedUser>;
  logout: () => void;
}

export const AuthContext = createContext<AuthState | null>(null);
