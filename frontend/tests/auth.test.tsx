import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrictMode } from 'react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import App from '../src/App';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import { router } from '../src/router/routes';
import type { AuthenticatedUser, UserRole } from '../src/types/api';
import { AuthProvider } from '../src/auth/AuthContext';
import { useAuth } from '../src/auth/useAuth';

const identities: Record<UserRole, AuthenticatedUser> = {
  PRODUCTION_PLANNER: { id: 1, username: 'planner', displayName: 'Planner', role: 'PRODUCTION_PLANNER' },
  PROCUREMENT_STAFF: { id: 2, username: 'buyer', displayName: 'Buyer', role: 'PROCUREMENT_STAFF' },
  PROCUREMENT_MANAGER: { id: 3, username: 'manager', displayName: 'Manager', role: 'PROCUREMENT_MANAGER' },
  WAREHOUSE_STAFF: { id: 4, username: 'warehouse', displayName: 'Warehouse', role: 'WAREHOUSE_STAFF' },
  ADMIN: { id: 5, username: 'admin', displayName: 'Admin', role: 'ADMIN' },
};

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { config, data, status, statusText: String(status), headers: {} };
}

function mockServer(role: UserRole, loginStatus = 200) {
  const requests: string[] = [];
  axiosClient.defaults.adapter = vi.fn(async (config) => {
    requests.push(config.url ?? '');
    if (config.url === '/auth/login') {
      if (loginStatus !== 200) throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'UNAUTHENTICATED', message: 'Sensitive server detail' }, loginStatus));
      return response(config, { accessToken: 'valid-token', tokenType: 'Bearer', user: identities[role] });
    }
    if (config.url === '/auth/me') return response(config, identities[role]);
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
    const requests = mockServer('PROCUREMENT_STAFF');
    await open('/purchase-orders/42');
    expect(await screen.findByText('登入採購與供應鏈決策平台')).toBeTruthy();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('帳號'), 'buyer');
    await user.type(screen.getByLabelText('密碼'), 'password');
    await user.click(screen.getByRole('button', { name: '登 入' }));
    expect(await screen.findByText('Purchase Order Detail')).toBeTruthy();
    expect(router.state.location.pathname).toBe('/purchase-orders/42');
    expect(requests).toEqual(['/auth/login', '/auth/me']);
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('valid-token');
  });

  it('keeps the form available after bad credentials without exposing server detail', async () => {
    mockServer('PROCUREMENT_STAFF', 401);
    await open('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('帳號'), 'buyer');
    await user.type(screen.getByLabelText('密碼'), 'wrong');
    await user.click(screen.getByRole('button', { name: '登 入' }));
    expect(await screen.findByText(/登入失敗。/)).toBeTruthy();
    expect(screen.queryByText('Sensitive server detail')).toBeNull();
    expect(screen.getByRole('button', { name: '登 入' }).hasAttribute('disabled')).toBe(false);
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('restores a session after reload and clears it on logout', async () => {
    const requests = mockServer('ADMIN');
    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
    await open('/dashboard');
    expect(await screen.findByText('Operations Inbox')).toBeTruthy();
    expect(requests).toEqual(['/auth/me']);
    fireEvent.click(screen.getByRole('button', { name: /登出/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
    expect(screen.queryByText('Operations Inbox')).toBeNull();
  });

  it('clears an expired token and does not show protected content during initialization', async () => {
    let rejectMe!: () => void;
    axiosClient.defaults.adapter = vi.fn((config) => new Promise((_, reject) => {
      rejectMe = () => reject(new AxiosError('Expired', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'UNAUTHENTICATED' }, 401)));
    }));
    localStorage.setItem(TOKEN_STORAGE_KEY, 'expired');
    await open('/dashboard');
    expect(screen.queryByText('Operations Inbox')).toBeNull();
    await waitFor(() => expect(rejectMe).toBeTypeOf('function'));
    rejectMe();
    expect(await screen.findByText('登入採購與供應鏈決策平台')).toBeTruthy();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it.each(Object.keys(identities) as UserRole[])('shows role navigation and a readable 403 for %s', async (role) => {
    const requests = mockServer(role);
    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
    await open('/dashboard');
    expect(await screen.findByText('Operations Inbox')).toBeTruthy();
    expect(document.querySelector('.ant-layout-header')?.textContent).toContain(identities[role].displayName);
    const sidebar = document.querySelector('.ant-layout-sider')!;
    expect(sidebar.textContent).toContain('Demand / Inventory');
    fireEvent.click([...sidebar.querySelectorAll('.ant-menu-submenu-title')].find((item) => item.textContent === 'Demand / Inventory')!);
    await waitFor(() => expect([...sidebar.querySelectorAll('.ant-menu-item')].some((item) => item.textContent === 'Inventory')).toBe(true));
    const workLinks = [...document.querySelectorAll('.ant-card a')].map((link) => link.textContent);
    expect(workLinks.includes('Receiving')).toBe(role === 'WAREHOUSE_STAFF' || role === 'ADMIN');
    expect(workLinks.includes('Purchase Orders')).toBe(role === 'PROCUREMENT_STAFF' || role === 'PROCUREMENT_MANAGER' || role === 'ADMIN');
    expect(screen.queryByRole('button', { name: /AI Assistant/ }) !== null).toBe(role === 'PROCUREMENT_STAFF' || role === 'PROCUREMENT_MANAGER' || role === 'ADMIN');
    await router.navigate('/purchase-orders/42');
    if (role === 'WAREHOUSE_STAFF' || role === 'PRODUCTION_PLANNER') expect(await screen.findByText('403')).toBeTruthy();
    else expect(await screen.findByText('Purchase Order Detail')).toBeTruthy();
    expect(requests).toEqual(['/auth/me']);
  });

  it('allows manager into inventory without making an inventory GET request, and shows 404 for unknown routes', async () => {
    const requests = mockServer('PROCUREMENT_MANAGER');
    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
    await open('/inventory?tab=stock-levels');
    expect(await screen.findByText('Stock Transactions')).toBeTruthy();
    expect(screen.queryByText('Stock Levels')).toBeNull();
    expect(requests).toEqual(['/auth/me']);
    await router.navigate('/unknown');
    expect(await screen.findByText('404')).toBeTruthy();
  });

  it('does not restore a logged-out session from late /auth/me responses under StrictMode', async () => {
    const complete: Array<() => void> = [];
    axiosClient.defaults.adapter = vi.fn((config) => new Promise((resolve) => {
      complete.push(() => resolve(response(config, identities.ADMIN)));
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
