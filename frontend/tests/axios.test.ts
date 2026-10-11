import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { axiosClient, clearAccessToken, onUnauthorized, saveAccessToken, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';

function errorResponse(config: InternalAxiosRequestConfig, status: number): AxiosResponse {
  return { config, status, statusText: String(status), headers: {}, data: { code: status === 403 ? 'ACCESS_DENIED' : 'UNAUTHENTICATED', message: 'Backend message', traceId: 'trace-1' } };
}

beforeEach(() => localStorage.clear());

describe('axios authentication boundary', () => {
  it('adds Bearer only when a token exists and omits it for login', async () => {
    const headers: unknown[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      headers.push(config.headers.Authorization);
      return { config, status: 200, statusText: 'OK', headers: {}, data: {} };
    });
    await axiosClient.get('/auth/me');
    localStorage.setItem(TOKEN_STORAGE_KEY, 'token');
    await axiosClient.get('/auth/me');
    await axiosClient.post('/auth/login', {});
    expect(headers).toEqual([undefined, 'Bearer token', undefined]);
  });

  it('clears a session on 401, preserves the rejection, and leaves a 403 session intact', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'token');
    const listener = vi.fn();
    const unsubscribe = onUnauthorized(listener);
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      const status = config.url === '/forbidden' ? 403 : 401;
      throw new AxiosError('Failure', 'ERR_BAD_REQUEST', config, undefined, errorResponse(config, status));
    });
    await expect(axiosClient.get('/forbidden')).rejects.toMatchObject({ response: { data: { code: 'ACCESS_DENIED', traceId: 'trace-1' } } });
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('token');
    await expect(axiosClient.get('/expired')).rejects.toMatchObject({ response: { data: { code: 'UNAUTHENTICATED' } } });
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
    expect(listener).toHaveBeenCalledTimes(1);
    localStorage.setItem(TOKEN_STORAGE_KEY, 'token');
    await expect(axiosClient.post('/auth/login', {})).rejects.toBeTruthy();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('token');
    expect(listener).toHaveBeenCalledTimes(1);
    unsubscribe();
  });

  it('ignores a late 401 from an older token', async () => {
    let rejectOld!: () => void;
    localStorage.setItem(TOKEN_STORAGE_KEY, 'old');
    axiosClient.defaults.adapter = vi.fn((config) => new Promise((_, reject) => {
      rejectOld = () => reject(new AxiosError('Expired', 'ERR_BAD_REQUEST', config, undefined, errorResponse(config, 401)));
    }));
    const pending = axiosClient.get('/auth/me');
    await vi.waitFor(() => expect(rejectOld).toBeTypeOf('function'));
    localStorage.setItem(TOKEN_STORAGE_KEY, 'new');
    rejectOld();
    await expect(pending).rejects.toMatchObject({ response: { status: 401 } });
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('new');
  });

  it('ignores a late 401 from a prior session even when a new session uses the same token text', async () => {
    let rejectOld!: () => void;
    saveAccessToken('same-token');
    axiosClient.defaults.adapter = vi.fn((config) => new Promise((_, reject) => {
      rejectOld = () => reject(new AxiosError('Expired', 'ERR_BAD_REQUEST', config, undefined, errorResponse(config, 401)));
    }));
    const pending = axiosClient.get('/auth/me');
    await vi.waitFor(() => expect(rejectOld).toBeTypeOf('function'));
    clearAccessToken();
    saveAccessToken('same-token');
    rejectOld();
    await expect(pending).rejects.toMatchObject({ response: { status: 401 } });
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('same-token');
  });
});
