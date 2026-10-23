import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../src/App';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import { getAdjustmentQuantityError, getAdjustmentReasonError } from '../src/pages/Inventory/inventoryValidation';
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
    await waitFor(() => expect(requests).toContain('/demands'));
    await screen.findByRole('row', { name: /物料 #1/ });
    await waitFor(() => expect(requests).toContain('/materials/1'));
    expect(await screen.findByRole('row', { name: /MAT-001 — Steel/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '新增需求' })).toBeNull();
    expect(screen.queryByRole('button', { name: '編輯' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Cancel' })).toBeNull();
    expect(requests).toContain('/demands');
  }, 15_000);

  it('allows a failed Material detail lookup to be retried explicitly', async () => {
    let materialDetailCalls = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities.PRODUCTION_PLANNER);
      if (config.url === '/demands') return response(config, { content: [demand], page: 0, size: 20, totalElements: 1, totalPages: 1 });
      if (config.url === '/materials') return response(config, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
      if (config.url === '/materials/1') {
        materialDetailCalls += 1;
        if (materialDetailCalls === 1) throw new AxiosError('Temporary failure', 'ERR_BAD_RESPONSE', config, undefined, response(config, { code: 'INTERNAL_ERROR', message: 'Try again', traceId: 'trace', timestamp: '', path: '' }, 500));
        return response(config, material);
      }
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/demands');
    expect(await screen.findByRole('row', { name: /物料 #1/ })).toBeTruthy();
    const retry = await screen.findByRole('button', { name: '重新載入物料資訊' });
    expect(materialDetailCalls).toBe(1);
    await userEvent.setup().click(retry);
    expect(await screen.findByRole('row', { name: /MAT-001 — Steel/ })).toBeTruthy();
    expect(materialDetailCalls).toBe(2);
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
    await waitFor(() => expect(requests.some(({ url }) => url === '/demands')).toBe(true));
    await screen.findByRole('row', { name: /物料 #1/ });
    await waitFor(() => expect(requests.some(({ url }) => url === '/materials/1')).toBe(true));
    expect(await screen.findByRole('row', { name: /MAT-001 — Steel/ })).toBeTruthy();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: /新增需求/ }));
    const createDialog = screen.getByRole('dialog');
    expect(within(createDialog).getByLabelText('選取物料')).toBeTruthy();
    expect(within(createDialog).getByRole('spinbutton', { name: '需求數量' })).toBeTruthy();
    expect(within(createDialog).getByLabelText('需求日期')).toBeTruthy();
    await user.click(within(createDialog).getByRole('button', { name: /取\s*消/ }));

    await user.click(screen.getByRole('button', { name: /編\s*輯/ }));
    await user.click(screen.getByRole('checkbox'));
    fireEvent.change(screen.getByLabelText('需求備註'), { target: { value: '' } });
    await user.click(screen.getByRole('button', { name: /儲存變更/ }));
    expect(await screen.findByText('需求送出失敗')).toBeTruthy();
    expect((screen.getByLabelText('需求備註') as HTMLTextAreaElement).value).toBe('');
    await user.click(screen.getByRole('button', { name: /儲存變更/ }));
    await waitFor(() => expect(requests.filter(({ method, url }) => method === 'patch' && url === '/demands/9')).toHaveLength(2));
    const patchRequest = requests.find(({ method, url }) => method === 'patch' && url === '/demands/9')!;
    expect(JSON.parse(patchRequest.data as string)).toEqual({ quantity: 100, requiredDate: '2026-10-20', note: null });

    await user.click(screen.getByRole('button', { name: /取\s*消/ }));
    expect(await screen.findByText('確定取消需求 #9？')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '確認取消' }));
    await waitFor(() => expect(requests.filter(({ url }) => url === '/demands/9/cancel')).toHaveLength(1));
    expect(requests.filter(({ method, url }) => method === 'get' && url === '/demands').length).toBeGreaterThanOrEqual(3);
  }, 60_000);
});

describe('SP-802 Inventory UI', () => {
  it('validates adjustment decimal boundaries without Number conversion', () => {
    for (const value of ['5', '-5.25', '0.0001', '-0.0001', '999999999999999.1234']) {
      expect(getAdjustmentQuantityError(value)).toBeUndefined();
    }
    expect(getAdjustmentQuantityError('0')).toBe('數量不可為 0');
    expect(getAdjustmentQuantityError('1000000000000000')).toBe('最多 15 位整數與 4 位小數');
    expect(getAdjustmentQuantityError('1.00001')).toBe('最多 15 位整數與 4 位小數');
    expect(getAdjustmentReasonError('   ')).toBe('請輸入調整原因');
    expect(getAdjustmentReasonError('x'.repeat(501))).toBe('調整原因不可超過 500 個字元');
    expect(getAdjustmentReasonError('Cycle count')).toBeUndefined();
  });

  it.each([
    ['PRODUCTION_PLANNER', ['庫存水位'], 'stock-levels', true],
    ['PROCUREMENT_STAFF', ['庫存水位'], 'stock-levels', true],
    ['PROCUREMENT_MANAGER', ['庫存異動'], 'stock-transactions', false],
    ['WAREHOUSE_STAFF', ['庫存水位', '庫存異動'], 'stock-levels', true],
    ['ADMIN', ['庫存水位', '庫存異動'], 'stock-levels', true],
  ] as const)('enforces the tab matrix for %s', async (role, visibleTabs, defaultTab, expectsInventory) => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config.url ?? '');
      if (config.url === '/auth/me') return response(config, identities[role]);
      if (config.url === '/inventory') return response(config, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
      if (config.url === '/stock-transactions') return response(config, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
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
    await user.click(screen.getByRole('button', { name: /檢\s*視/ }));
    expect(await screen.findByText('MAT-001 庫存明細')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '關閉' }));
    await user.click(screen.getByRole('button', { name: '調整庫存' }));
    fireEvent.change(screen.getByRole('spinbutton', { name: '異動數量' }), { target: { value: '-5.25' } });
    await user.type(screen.getByLabelText('調整原因'), 'Damaged stock');
    const confirm = screen.getByRole('button', { name: '確認調整' });
    await user.click(confirm);
    fireEvent.click(confirm);
    await waitFor(() => expect(requests.filter(({ url }) => url === '/inventory/1/adjustments')).toHaveLength(1));
    finishAdjustment();
    await waitFor(() => expect(requests.filter(({ method, url }) => method === 'get' && url === '/inventory').length).toBeGreaterThanOrEqual(2));
    const post = requests.find(({ url }) => url === '/inventory/1/adjustments')!;
    expect(JSON.parse(post.data as string)).toEqual({ quantity: '-5.25', reason: 'Damaged stock' });
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
    await user.click(await screen.findByRole('button', { name: '調整庫存' }, { timeout: 5_000 }));
    fireEvent.change(screen.getByRole('spinbutton', { name: '異動數量' }), { target: { value: '0' } });
    await user.click(screen.getByRole('button', { name: '確認調整' }));
    expect(await screen.findByText('數量不可為 0')).toBeTruthy();
    expect(screen.getByText('請輸入調整原因')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/inventory/1/adjustments')).toHaveLength(0);
    fireEvent.change(screen.getByRole('spinbutton', { name: '異動數量' }), { target: { value: '5' } });
    await user.type(screen.getByLabelText('調整原因'), 'Cycle count');
    await user.click(screen.getByRole('button', { name: '確認調整' }));
    expect(await screen.findByText('操作結果待確認')).toBeTruthy();
    expect((screen.getByLabelText('調整原因') as HTMLTextAreaElement).value).toBe('Cycle count');
    expect((screen.getByRole('spinbutton', { name: '異動數量' }) as HTMLInputElement).value).toBe('5');
    expect(requests.filter(({ url }) => url === '/inventory/1/adjustments')).toHaveLength(1);
  }, 30_000);
});
