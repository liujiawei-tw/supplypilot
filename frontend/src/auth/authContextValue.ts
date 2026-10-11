import { createContext } from 'react';
import type { AuthenticatedUser } from '../types/api';

export interface AuthState {
  user: AuthenticatedUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<AuthenticatedUser>;
  logout: () => void;
}

export const AuthContext = createContext<AuthState | null>(null);
