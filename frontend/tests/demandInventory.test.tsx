import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../src/App';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import { router } from '../src/router/routes';
import type { AuthenticatedUser, UserRole } from '../src/types/api';

const identities: Record<UserRole, AuthenticatedUser> = {
  PRODUCTION_PLANNER: { id: 1, username: 'planner', displayName: 'Planner', role: 'PRODUCTION_PLANNER' },
  PROCUREMENT_STAFF: { id: 2, username: 'buyer', displayName: 'Buyer', role: 'PROCUREMENT_STAFF' },
  PROCUREMENT_MANAGER: { id: 3, username: 'manager', displayName: 'Manager', role: 'PROCUREMENT_MANAGER' },
  WAREHOUSE_STAFF: { id: 4, username: 'warehouse', displayName: 'Warehouse', role: 'WAREHOUSE_STAFF' },
  ADMIN: { id: 5, username: 'admin', displayName: 'Admin', role: 'ADMIN' },
};

const material = { id: 1, code: 'MAT-001', name: 'Steel', baseUnit: 'KG', safetyStock: 10, status: 'ACTIVE', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' };
const demand = { id: 9, materialId: 1, quantity: 100, requiredDate: '2026-10-20', status: 'ACTIVE', note: 'Original note', createdBy: 1, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' };
const inventory = { materialId: 1, materialCode: 'MAT-001', materialName: 'Steel', quantity: 100, unit: 'KG', updatedAt: '2026-10-01T00:00:00Z' };

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { config, data, status, statusText: String(status), headers: {} };
}

async function open(path: string) {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
  await router.navigate(path);
  render(<App />);
}

beforeEach(() => { localStorage.clear(); });

describe('SP-802 Demand UI', () => {
  it('does not mount Demand queries for an unauthorized role', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config.url ?? '');
      if (config.url === '/auth/me') return response(config, identities.PROCUREMENT_STAFF);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/demands');
    expect(await screen.findByText('403')).toBeTruthy();
    expect(requests).toEqual(['/auth/me']);
  });

  it('keeps Procurement Manager read-only while rendering Demand data', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config.url ?? '');
      if (config.url === '/auth/me') return response(config, identities.PROCUREMENT_MANAGER);
      if (config.url === '/demands') return response(config, { content: [demand], page: 0, size: 20, totalElements: 1, totalPages: 1 });
      if (config.url === '/materials') return response(config, { content: [material], page: 0, size: 20, totalElements: 1, totalPages: 1 });
      if (config.url === '/materials/1') return response(config, material);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/demands');
    expect(await screen.findByRole('row', { name: /MAT-001 — Steel/ }, { timeout: 5_000 })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Create Demand' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Cancel' })).toBeNull();
    expect(requests).toContain('/demands');
  }, 15_000);

  it('shows create fields, edits with explicit note clearing, confirms cancel and refetches', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let patchCalls = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.PRODUCTION_PLANNER);
      if (config.url === '/demands' && config.method === 'get') return response(config, { content: [demand], page: 0, size: 20, totalElements: 1, totalPages: 1 });
      if (config.url === '/materials') return response(config, { content: [material], page: 0, size: 20, totalElements: 1, totalPages: 1 });
      if (config.url === '/materials/1') return response(config, material);
      if (config.url === '/demands' && config.method === 'post') return response(config, demand, 201);
      if (config.url === '/demands/9' && config.method === 'patch') {
        patchCalls += 1;
        if (patchCalls === 1) throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'CONCURRENT_MODIFICATION', message: 'Demand changed', traceId: 'trace', timestamp: '', path: '' }, 409));
        return response(config, { ...demand, note: null });
      }
      if (config.url === '/demands/9/cancel') return response(config, { ...demand, status: 'CANCELLED' });
      throw new Error(`Unexpected request ${config.method} ${config.url}`);
    });
    await open('/demands');
    expect(await screen.findByRole('row', { name: /MAT-001 — Steel/ }, { timeout: 5_000 })).toBeTruthy();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: /Create Demand/ }));
    const createDialog = screen.getByRole('dialog');
    expect(within(createDialog).getByLabelText('Select material')).toBeTruthy();
    expect(within(createDialog).getByRole('spinbutton', { name: 'Demand quantity' })).toBeTruthy();
    expect(within(createDialog).getByLabelText('Demand required date')).toBeTruthy();
    await user.click(within(createDialog).getByRole('button', { name: 'Cancel' }));

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    await user.click(screen.getByRole('checkbox'));
    fireEvent.change(screen.getByLabelText('Demand note'), { target: { value: '' } });
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('Demand submit failed')).toBeTruthy();
    expect((screen.getByLabelText('Demand note') as HTMLTextAreaElement).value).toBe('');
    await user.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(requests.filter(({ method, url }) => method === 'patch' && url === '/demands/9')).toHaveLength(2));
    const patchRequest = requests.find(({ method, url }) => method === 'patch' && url === '/demands/9')!;
    expect(JSON.parse(patchRequest.data as string)).toEqual({ quantity: 100, requiredDate: '2026-10-20', note: null });

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(await screen.findByText('取消 Demand #9？')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Confirm Cancel' }));
    await waitFor(() => expect(requests.filter(({ url }) => url === '/demands/9/cancel')).toHaveLength(1));
    expect(requests.filter(({ method, url }) => method === 'get' && url === '/demands').length).toBeGreaterThanOrEqual(3);
  }, 60_000);
});

describe('SP-802 Inventory UI', () => {
  it.each([
    ['PRODUCTION_PLANNER', ['Stock Levels'], 'stock-levels', true],
    ['PROCUREMENT_STAFF', ['Stock Levels'], 'stock-levels', true],
    ['PROCUREMENT_MANAGER', ['Stock Transactions'], 'stock-transactions', false],
    ['WAREHOUSE_STAFF', ['Stock Levels', 'Stock Transactions'], 'stock-levels', true],
    ['ADMIN', ['Stock Levels', 'Stock Transactions'], 'stock-levels', true],
  ] as const)('enforces the tab matrix for %s', async (role, visibleTabs, defaultTab, expectsInventory) => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config.url ?? '');
      if (config.url === '/auth/me') return response(config, identities[role]);
      if (config.url === '/inventory') return response(config, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/inventory?tab=invalid');
    for (const tab of visibleTabs) expect(await screen.findByText(tab)).toBeTruthy();
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('tab')).toBe(defaultTab));
    expect(requests.includes('/inventory')).toBe(expectsInventory);
  });

  it('shows detail and allows Warehouse to submit a signed adjustment exactly once before refetch', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let finishAdjustment!: () => void;
    axiosClient.defaults.adapter = vi.fn((config) => {
      requests.push(config);
      if (config.url === '/auth/me') return Promise.resolve(response(config, identities.WAREHOUSE_STAFF));
      if (config.url === '/inventory' && config.method === 'get') return Promise.resolve(response(config, { content: [inventory], page: 0, size: 20, totalElements: 1, totalPages: 1 }));
      if (config.url === '/inventory/1' && config.method === 'get') return Promise.resolve(response(config, inventory));
      if (config.url === '/inventory/1/adjustments') return new Promise((resolve) => { finishAdjustment = () => resolve(response(config, undefined)); });
      return Promise.reject(new Error(`Unexpected request ${config.url}`));
    });
    await open('/inventory?tab=stock-levels');
    expect(await screen.findByText('MAT-001')).toBeTruthy();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Detail' }));
    expect(await screen.findByText('MAT-001 Inventory')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Close' }));
    await user.click(screen.getByRole('button', { name: 'Adjust' }));
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Adjustment quantity' }), { target: { value: '-5.25' } });
    await user.type(screen.getByLabelText('Adjustment reason'), 'Damaged stock');
    const confirm = screen.getByRole('button', { name: 'Confirm Adjustment' });
    await user.click(confirm);
    fireEvent.click(confirm);
    await waitFor(() => expect(requests.filter(({ url }) => url === '/inventory/1/adjustments')).toHaveLength(1));
    finishAdjustment();
    await waitFor(() => expect(requests.filter(({ method, url }) => method === 'get' && url === '/inventory').length).toBeGreaterThanOrEqual(2));
    const post = requests.find(({ url }) => url === '/inventory/1/adjustments')!;
    expect(JSON.parse(post.data as string)).toEqual({ quantity: -5.25, reason: 'Damaged stock' });
  }, 30_000);

  it('keeps adjustment input and marks a 409 result unknown without retrying', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.ADMIN);
      if (config.url === '/inventory') return response(config, { content: [inventory], page: 0, size: 20, totalElements: 1, totalPages: 1 });
      if (config.url === '/inventory/1/adjustments') throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'CONCURRENT_MODIFICATION', message: 'Changed', traceId: 'trace', timestamp: '', path: '' }, 409));
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/inventory?tab=stock-levels');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Adjust' }, { timeout: 5_000 }));
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Adjustment quantity' }), { target: { value: '0' } });
    await user.click(screen.getByRole('button', { name: 'Confirm Adjustment' }));
    expect(await screen.findByText('Quantity must not be zero')).toBeTruthy();
    expect(screen.getByText('Reason is required')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/inventory/1/adjustments')).toHaveLength(0);
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Adjustment quantity' }), { target: { value: '5' } });
    await user.type(screen.getByLabelText('Adjustment reason'), 'Cycle count');
    await user.click(screen.getByRole('button', { name: 'Confirm Adjustment' }));
    expect(await screen.findByText('操作結果待確認')).toBeTruthy();
    expect((screen.getByLabelText('Adjustment reason') as HTMLTextAreaElement).value).toBe('Cycle count');
    expect((screen.getByRole('spinbutton', { name: 'Adjustment quantity' }) as HTMLInputElement).value).toBe('5');
    expect(requests.filter(({ url }) => url === '/inventory/1/adjustments')).toHaveLength(1);
  }, 30_000);
});
