import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrictMode } from 'react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import App from '../src/App';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import { router } from '../src/router/routes';
import type { AuthenticatedUser } from '../src/types/api';
import { AuthProvider } from '../src/auth/AuthContext';
import { useAuth } from '../src/auth/useAuth';

const identities: Record<'business' | 'manager', AuthenticatedUser> = {
  business: { id: 1, username: 'business', displayName: 'Business User', role: 'ADMIN', canManageUsers: false },
  manager: { id: 2, username: 'manager', displayName: 'User Manager', role: 'ADMIN', canManageUsers: true },
};

const purchaseOrder = {
  id: 42, poNumber: 'PO-000042', supplierId: 7, supplierCode: 'SUP-007', supplierName: 'Supplier', status: 'DRAFT',
  orderedProgress: null,
  expectedDeliveryDate: null, supplierPromisedDeliveryDate: null, items: [], createdBy: 2, createdByDisplayName: 'Buyer', createdAt: '2026-10-01T00:00:00Z',
  receivingInspection: { allItemsReceived: false, allQuantitiesConfirmed: false, allQualityAccepted: false, note: null, inspectedBy: null, inspectedByDisplayName: null, inspectedByUsername: null, inspectedAt: null },
  paymentConfirmed: false, paymentConfirmedBy: null, paymentConfirmedByDisplayName: null, paymentConfirmedByUsername: null, paymentConfirmedAt: null,
  approvedBy: null, approvedByDisplayName: null, approvedAt: null, rejectReason: null, rejectedBy: null,
  rejectedByDisplayName: null, rejectedAt: null, orderedBy: null, orderedByDisplayName: null, orderedAt: null,
  receivedBy: null, receivedByDisplayName: null, receivedAt: null, isOverdue: false, updatedAt: '2026-10-01T00:00:00Z',
};

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { config, data, status, statusText: String(status), headers: {} };
}

function mockServer(identity: 'business' | 'manager', loginStatus = 200) {
  const requests: string[] = [];
  axiosClient.defaults.adapter = vi.fn(async (config) => {
    requests.push(config.url ?? '');
    if (config.url === '/auth/login') {
      if (loginStatus !== 200) throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'UNAUTHENTICATED', message: 'Sensitive server detail' }, loginStatus));
      return response(config, { accessToken: 'valid-token', tokenType: 'Bearer', user: identities[identity] });
    }
    if (config.url === '/auth/me') return response(config, identities[identity]);
    if (config.url === '/purchase-orders') return response(config, { content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
    if (config.url === '/receiving/purchase-orders') return response(config, { content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
    if (config.url === '/demands') return response(config, { content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
    if (config.url === '/inventory') return response(config, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    if (config.url === '/purchase-orders/42') return response(config, purchaseOrder);
    if (config.url === '/stock-transactions') return response(config, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    if (config.url === '/users') return response(config, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    if (config.url === '/users/assignable') return response(config, []);
    throw new Error(`Unexpected API request: ${config.url}`);
  });
  return requests;
}

async function open(path: string) {
  await router.navigate(path);
  render(<App />);
}

beforeEach(() => {
  localStorage.clear();
  axiosClient.defaults.headers.common = new AxiosHeaders();
});

describe('authentication and routing', () => {
  it('redirects an unauthenticated deep link, logs in, checks /auth/me and returns to it', async () => {
    const requests = mockServer('business');
    await open('/purchase-orders/42');
    expect(await screen.findByText('登入採購與供應鏈決策平台')).toBeTruthy();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('帳號'), 'buyer');
    await user.type(screen.getByLabelText('密碼'), 'password');
    await user.click(screen.getByRole('button', { name: '登 入' }));
    expect(await screen.findByText('PO-000042')).toBeTruthy();
    expect(router.state.location.pathname).toBe('/purchase-orders/42');
    expect(requests).toEqual(['/auth/login', '/auth/me', '/users/assignable', '/purchase-orders/42']);
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('valid-token');
  });

  it('keeps the form available after bad credentials without exposing server detail', async () => {
    mockServer('business', 401);
    await open('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('帳號'), 'buyer');
    await user.type(screen.getByLabelText('密碼'), 'wrong');
    await user.click(screen.getByRole('button', { name: '登 入' }));
    expect(await screen.findByText('帳號或密碼錯誤。')).toBeTruthy();
    expect(screen.queryByText('Sensitive server detail')).toBeNull();
    expect(screen.getByRole('button', { name: '登 入' }).hasAttribute('disabled')).toBe(false);
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it.each([
    { status: 429, message: '登入請求過於頻繁，請稍後再試。' },
    { status: 503, message: '無法連線或登入服務暫時異常，請稍後再試。' },
  ])('shows a safe message for login HTTP $status', async ({ status, message }) => {
    mockServer('manager', status);
    await open('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('帳號'), 'admin');
    await user.type(screen.getByLabelText('密碼'), 'password');
    await user.click(screen.getByRole('button', { name: '登 入' }));
    expect(await screen.findByText(message)).toBeTruthy();
    expect(screen.queryByText('Sensitive server detail')).toBeNull();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('shows a connection message for a login network error', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      throw new AxiosError('Network Error', 'ERR_NETWORK', config);
    });
    await open('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('帳號'), 'admin');
    await user.type(screen.getByLabelText('密碼'), 'password');
    await user.click(screen.getByRole('button', { name: '登 入' }));
    expect(await screen.findByText('無法連線或登入服務暫時異常，請稍後再試。')).toBeTruthy();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('routes a first-login account to the password form after login and /auth/me succeed', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config.url ?? '');
      if (config.url === '/auth/login') return response(config, { accessToken: 'restricted-token', tokenType: 'Bearer', user: identities.manager });
      if (config.url === '/auth/me') return response(config, { ...identities.manager, mustChangePassword: true });
      throw new Error(`Unexpected API request: ${config.url}`);
    });
    await open('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('帳號'), 'admin');
    await user.type(screen.getByLabelText('密碼'), 'password');
    await user.click(screen.getByRole('button', { name: '登 入' }));
    expect(await screen.findByText('設定新密碼')).toBeTruthy();
    expect(router.state.location.pathname).toBe('/change-password');
    expect(screen.getByLabelText('目前密碼')).toBeTruthy();
    expect(screen.queryByText(/登入失敗/)).toBeNull();
    expect(requests).toEqual(['/auth/login', '/auth/me']);
  });

  it('does not send duplicate login requests while the first request is pending', async () => {
    const requests: string[] = [];
    let finishLogin!: () => void;
    axiosClient.defaults.adapter = vi.fn((config) => {
      requests.push(config.url ?? '');
      if (config.url === '/auth/login') return new Promise((resolve) => {
        finishLogin = () => resolve(response(config, { accessToken: 'valid-token', tokenType: 'Bearer', user: identities.manager }));
      });
      return Promise.resolve(response(config, identities.manager));
    });
    await open('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('帳號'), 'admin');
    await user.type(screen.getByLabelText('密碼'), 'password');
    const button = screen.getByRole('button', { name: '登 入' });
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(finishLogin).toBeTypeOf('function'));
    expect(requests.filter((url) => url === '/auth/login')).toHaveLength(1);
    await act(async () => finishLogin());
    expect(await screen.findByText('工作總覽')).toBeTruthy();
  });

  it('restores a session after reload and clears it on logout', async () => {
    const requests = mockServer('manager');
    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
    await open('/dashboard');
    expect(await screen.findByText('工作總覽')).toBeTruthy();
    expect(requests.filter((url) => url === '/auth/me')).toEqual(['/auth/me']);
    fireEvent.click(screen.getByRole('button', { name: /登出/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
    expect(screen.queryByText('工作總覽')).toBeNull();
  });

  it('clears an expired token and does not show protected content during initialization', async () => {
    let rejectMe!: () => void;
    axiosClient.defaults.adapter = vi.fn((config) => new Promise((_, reject) => {
      rejectMe = () => reject(new AxiosError('Expired', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'UNAUTHENTICATED' }, 401)));
    }));
    localStorage.setItem(TOKEN_STORAGE_KEY, 'expired');
    await open('/dashboard');
    expect(screen.queryByText('工作總覽')).toBeNull();
    await waitFor(() => expect(rejectMe).toBeTypeOf('function'));
    rejectMe();
    expect(await screen.findByText('登入採購與供應鏈決策平台')).toBeTruthy();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it.each([500, 503, 'network'] as const)('keeps the token after %s during startup and retries /auth/me', async (failure) => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config.url ?? '');
      if (requests.length === 1) {
        if (failure === 'network') throw new AxiosError('Network Error', 'ERR_NETWORK', config);
        throw new AxiosError('Temporary failure', 'ERR_BAD_RESPONSE', config, undefined, response(config, { code: 'SYSTEM_ERROR' }, failure));
      }
      return response(config, identities.manager);
    });
    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
    await open('/dashboard');
    expect(screen.queryByText('工作總覽')).toBeNull();
    expect(await screen.findByText('暫時無法驗證登入狀態')).toBeTruthy();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('valid-token');
    expect(router.state.location.pathname).toBe('/dashboard');
    fireEvent.click(screen.getByRole('button', { name: '重試驗證' }));
    expect(await screen.findByText('工作總覽')).toBeTruthy();
    expect(requests.filter((url) => url === '/auth/me')).toEqual(['/auth/me', '/auth/me']);
  });

  it('keeps a just-issued token when post-login /auth/me fails, then returns to the requested route after retry', async () => {
    let meCalls = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/login') return response(config, { accessToken: 'valid-token', tokenType: 'Bearer', user: identities.business });
      if (config.url === '/auth/me') {
        meCalls += 1;
        if (meCalls === 1) throw new AxiosError('Temporary failure', 'ERR_BAD_RESPONSE', config, undefined, response(config, { code: 'SYSTEM_ERROR' }, 503));
        return response(config, identities.business);
      }
      if (config.url === '/purchase-orders/42') return response(config, purchaseOrder);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/purchase-orders/42');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('帳號'), 'buyer');
    await user.type(screen.getByLabelText('密碼'), 'password');
    await user.click(screen.getByRole('button', { name: '登 入' }));
    expect(await screen.findByText('暫時無法驗證登入狀態')).toBeTruthy();
    expect(screen.queryByText('PO-000042')).toBeNull();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('valid-token');
    fireEvent.click(screen.getByRole('button', { name: '重試驗證' }));
    expect(await screen.findByText('PO-000042')).toBeTruthy();
    expect(router.state.location.pathname).toBe('/purchase-orders/42');
    expect(meCalls).toBe(2);
  });

  it('keeps protected content hidden while a retry is still pending', async () => {
    let finishRetry!: () => void;
    let calls = 0;
    axiosClient.defaults.adapter = vi.fn((config) => {
      calls += 1;
      if (calls === 1) return Promise.reject(new AxiosError('Network Error', 'ERR_NETWORK', config));
      return new Promise((resolve) => {
        finishRetry = () => resolve(response(config, identities.manager));
      });
    });
    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
    await open('/dashboard');
    expect(await screen.findByText('暫時無法驗證登入狀態')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '重試驗證' }));
    await waitFor(() => expect(finishRetry).toBeTypeOf('function'));
    expect(screen.queryByText('工作總覽')).toBeNull();
    await act(async () => finishRetry());
    expect(await screen.findByText('工作總覽')).toBeTruthy();
  });

  it('clears a just-issued token when post-login /auth/me returns 401', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/login') return response(config, { accessToken: 'invalid-token', tokenType: 'Bearer', user: identities.manager });
      throw new AxiosError('Expired', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'UNAUTHENTICATED' }, 401));
    });
    await open('/dashboard');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('帳號'), 'admin');
    await user.type(screen.getByLabelText('密碼'), 'password');
    await user.click(screen.getByRole('button', { name: '登 入' }));
    expect(await screen.findByText('登入成功，但暫時無法驗證登入狀態，請重新登入或稍後再試。')).toBeTruthy();
    expect(screen.queryByText('工作總覽')).toBeNull();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('keeps ADMIN business accounts out of user management menu, redirect, and direct route', async () => {
    const requests = mockServer('business');
    await open('/users');
    expect(await screen.findByText('登入採購與供應鏈決策平台')).toBeTruthy();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('帳號'), 'business');
    await user.type(screen.getByLabelText('密碼'), 'password');
    await user.click(screen.getByRole('button', { name: '登 入' }));
    expect(await screen.findByText('工作總覽')).toBeTruthy();
    expect(router.state.location.pathname).toBe('/dashboard');
    expect(document.querySelector('.ant-layout-header')?.textContent).toContain('Business User');
    const sidebar = document.querySelector('.ant-layout-sider')!;
    expect(sidebar.textContent).not.toContain('人員管理');
    await router.navigate('/users');
    expect(await screen.findByText('403')).toBeTruthy();
    expect(requests).not.toContain('/users');
  });

  it('allows an explicitly authorized ADMIN account to follow a user-management deep link', async () => {
    const requests = mockServer('manager');
    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
    await open('/users');
    expect(await screen.findByRole('heading', { name: '人員管理' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/users');
    expect(requests).toContain('/users');
  });

  it('allows an ADMIN business account into inventory tabs and shows 404 for unknown routes', async () => {
    const requests = mockServer('business');
    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
    await open('/inventory?tab=stock-levels&purchaseOrderId=42');
    expect((await screen.findByRole('tab', { name: '庫存水位' })).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: '庫存異動' }).getAttribute('aria-selected')).toBe('false');
    await waitFor(() => expect(router.state.location.search).toBe('?tab=stock-levels&purchaseOrderId=42'));
    expect(requests).toEqual(['/auth/me', '/inventory']);
    await router.navigate('/unknown');
    expect(await screen.findByText('404')).toBeTruthy();
  });

  it('allows an ADMIN business account to open the inventory transactions deep link', async () => {
    const requests = mockServer('business');
    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
    await open('/inventory?tab=stock-transactions&materialId=7');
    expect((await screen.findByRole('tab', { name: '庫存異動' })).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: '庫存水位' }).getAttribute('aria-selected')).toBe('false');
    await waitFor(() => expect(router.state.location.search).toBe('?tab=stock-transactions&materialId=7'));
    expect(requests).toContain('/stock-transactions');
  });

  it('retains business API authorization for ADMIN accounts without user-management capability', async () => {
    const requests = mockServer('business');
    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
    await open('/purchase-orders/42');
    expect(await screen.findByText('PO-000042')).toBeTruthy();
    expect(requests).toContain('/purchase-orders/42');
  });

  it('does not restore a logged-out session from late /auth/me responses under StrictMode', async () => {
    const complete: Array<() => void> = [];
    axiosClient.defaults.adapter = vi.fn((config) => new Promise((resolve) => {
      complete.push(() => resolve(response(config, identities.manager)));
    }));
    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
    function Harness() {
      const { user, logout } = useAuth();
      return <><button onClick={logout}>Log out</button><span>{user?.username ?? 'anonymous'}</span></>;
    }
    render(<StrictMode><AuthProvider><Harness /></AuthProvider></StrictMode>);
    await waitFor(() => expect(complete.length).toBeGreaterThan(0));
    fireEvent.click(screen.getByText('Log out'));
    await act(async () => complete.forEach((finish) => finish()));
    expect(screen.getByText('anonymous')).toBeTruthy();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });
});
