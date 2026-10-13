import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios';
import type { ApiErrorResponse } from '../types/api';

export const TOKEN_STORAGE_KEY = 'supplypilot.accessToken';

const unauthorizedListeners = new Set<() => void>();
const requestRevisions = new WeakMap<object, number>();
let sessionRevision = 0;

export function saveAccessToken(token: string): void {
  sessionRevision += 1;
  localStorage.setItem(TOKEN_STORAGE_KEY, token);
}

export function clearAccessToken(): void {
  sessionRevision += 1;
  localStorage.removeItem(TOKEN_STORAGE_KEY);
}

export function onUnauthorized(listener: () => void): () => void {
  unauthorizedListeners.add(listener);
  return () => { unauthorizedListeners.delete(listener); };
}

const baseURL = import.meta.env.VITE_API_BASE_URL;

if (!baseURL) {
  throw new Error('VITE_API_BASE_URL is required');
}

export const axiosClient = axios.create({
  baseURL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request Interceptor: attach token boundary if token exists in localStorage
axiosClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
      // Let the browser/Axios create the multipart boundary. The instance JSON
      // default must never leak into document uploads.
      config.headers.setContentType(false);
    }
    if (typeof window !== 'undefined') {
      const token = localStorage.getItem(TOKEN_STORAGE_KEY);
      if (config.url === '/auth/login') {
        delete config.headers.Authorization;
      } else if (token && config.headers) {
        config.headers.Authorization = `Bearer ${token}`;
        requestRevisions.set(config, sessionRevision);
      } else if (config.headers) {
        delete config.headers.Authorization;
      }
    }
    return config;
  },
  (error: unknown) => Promise.reject(error)
);

// Response Interceptor: error handling boundary without swallowing exceptions
axiosClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiErrorResponse>) => {
    if (error.response?.status === 401 && error.config && error.config.url !== '/auth/login') {
      const sentAuthorization = error.config?.headers?.Authorization;
      const currentToken = localStorage.getItem(TOKEN_STORAGE_KEY);
      // A delayed response from a previous session must not log out a newer one.
      if (currentToken && sentAuthorization === `Bearer ${currentToken}` && requestRevisions.get(error.config) === sessionRevision) {
        clearAccessToken();
        unauthorizedListeners.forEach((listener) => listener());
      }
    }
    return Promise.reject(error);
  }
);

export default axiosClient;
