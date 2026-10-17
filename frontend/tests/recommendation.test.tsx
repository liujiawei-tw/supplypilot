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

const material = { id: 7, code: 'MAT-007', name: 'Copper', baseUnit: 'KG', safetyStock: 10, status: 'ACTIVE', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' };

function recommendation(outcome: 'PURCHASE_NOT_REQUIRED' | 'SUPPLIER_RECOMMENDED' | 'NO_QUALIFIED_SUPPLIER') {
  const recommended = outcome === 'SUPPLIER_RECOMMENDED';
  return {
    id: 91,
    material: { id: 7, code: 'MAT-007', name: 'Copper snapshot', unit: 'KG' },
    period: { start: '2026-10-01', end: '2026-10-31' },
    calculation: { expectedDemand: '123.4500', safetyStock: '10.0000', currentInventory: '20.0000', incomingInventory: '5.0000', netRequirement: outcome === 'PURCHASE_NOT_REQUIRED' ? '0.0000' : '108.4500' },
    outcome,
    recommendation: { purchaseRequired: outcome !== 'PURCHASE_NOT_REQUIRED', supplierId: recommended ? 12 : null, supplierName: recommended ? 'Supplier snapshot' : null, quantity: recommended ? '110.0000' : null, score: recommended ? '88.5000' : null },
    createdAt: '2026-10-12T01:02:03Z',
  };
}

const qualified = {
  id: 101,
  supplierId: 12, supplierCode: 'SUP-012', supplierName: 'Supplier snapshot', qualified: true, disqualifiedReason: null,
  recommendedQuantity: '110.0000', unitPrice: '25.5000', totalCost: '2805.0000', leadTimeDays: 5,
  reliabilityScoreSnapshot: '92.0000', qualityScoreSnapshot: '91.0000', priceScore: '95.0000', leadTimeScore: '90.0000', reliabilityScore: '92.0000', qualityScore: '91.0000', totalScore: '88.5000', rank: 1,
};
const disqualified = {
  id: 102,
  supplierId: 13, supplierCode: 'SUP-013', supplierName: 'Late Supplier', qualified: false, disqualifiedReason: 'DELIVERY_DATE_NOT_FEASIBLE',
  recommendedQuantity: null, unitPrice: null, totalCost: null, leadTimeDays: null,
  reliabilityScoreSnapshot: null, qualityScoreSnapshot: null, priceScore: null, leadTimeScore: null, reliabilityScore: null, qualityScore: null, totalScore: null, rank: null,
};
const alternateQualified = {
  id: 103,
  supplierId: 14, supplierCode: 'SUP-014', supplierName: 'Alternate Supplier', qualified: true, disqualifiedReason: null,
  recommendedQuantity: '120.0000', unitPrice: '24.2500', totalCost: '2910.0000', leadTimeDays: 4,
  reliabilityScoreSnapshot: '90.0000', qualityScoreSnapshot: '90.0000', priceScore: '96.0000', leadTimeScore: '92.0000', reliabilityScore: '90.0000', qualityScore: '90.0000', totalScore: '87.0000', rank: 2,
};

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { config, data, status, statusText: String(status), headers: {} };
}

async function open(path: string) {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
  await router.navigate(path);
  render(<App />);
}

beforeEach(() => { localStorage.clear(); });

describe('SP-804 role-aware routes', () => {
  it.each(['PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN'] as const)('allows %s to open the create workstation', async (role) => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities[role]);
      if (config.url === '/materials') return response(config, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations');
    expect(await screen.findByText('採購建議工作區')).toBeTruthy();
  });

  it.each(['PRODUCTION_PLANNER', 'WAREHOUSE_STAFF'] as const)('blocks %s from create but allows known-ID detail without a create API call', async (role) => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config.url ?? '');
      if (config.url === '/auth/me') return response(config, identities[role]);
      if (config.url === '/recommendations/91') return response(config, recommendation('PURCHASE_NOT_REQUIRED'));
      if (config.url === '/recommendations/91/suppliers') return response(config, []);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations');
    expect(await screen.findByText('403')).toBeTruthy();
    expect(requests).toEqual(['/auth/me']);
    await router.navigate('/recommendations/91');
    expect(await screen.findByText('採購建議 #91')).toBeTruthy();
    expect(requests).not.toContain('/recommendations');
  });
});

describe('SP-804 Recommendation rendering', () => {
  it.each([
    ['PURCHASE_NOT_REQUIRED', '無須採購', '0.0000 KG'],
    ['SUPPLIER_RECOMMENDED', '建議供應商', '108.4500 KG'],
    ['NO_QUALIFIED_SUPPLIER', '沒有合格供應商', '108.4500 KG'],
  ] as const)('renders %s using backend snapshot values', async (outcome, label, netRequirement) => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities.PROCUREMENT_MANAGER);
      if (config.url === '/recommendations/91') return response(config, recommendation(outcome));
      if (config.url === '/recommendations/91/suppliers') return response(config, outcome === 'SUPPLIER_RECOMMENDED' ? [qualified, disqualified] : []);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/91');
    expect(await screen.findAllByText(label)).toHaveLength(1);
    expect(screen.getAllByText(netRequirement).length).toBeGreaterThan(0);
    expect(document.querySelector('.recommendation-material .recommendation-wrap-value')?.textContent).toBe('MAT-007 · Copper snapshot');
    expect(screen.queryByRole('button', { name: '檢視採購單草稿' })).toBeNull();
    if (outcome === 'PURCHASE_NOT_REQUIRED') {
      expect(screen.getByText('此結果不需要建立採購單草稿。')).toBeTruthy();
      expect(screen.getAllByText('不適用').length).toBeGreaterThan(0);
    }
    if (outcome === 'NO_QUALIFIED_SUPPLIER') {
      expect(screen.getByText('尚無合格供應商')).toBeTruthy();
      expect(screen.getByText('尚無建議數量')).toBeTruthy();
      expect(screen.getByRole('table', { name: '供應商排名' })).toBeTruthy();
    }
  });

  it('preserves backend ranking order, renders disqualification enum and score breakdown without a draft POST', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.PROCUREMENT_STAFF);
      if (config.url === '/recommendations/91') return response(config, recommendation('SUPPLIER_RECOMMENDED'));
      if (config.url === '/recommendations/91/suppliers') return response(config, [qualified, disqualified]);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/91');
    await userEvent.setup().click(await screen.findByText('供應商排名與排除原因'));
    const table = await screen.findByRole('table', { name: '供應商排名' });
    const rows = within(table).getAllByRole('row');
    expect(rows[1].textContent).toContain('SUP-012');
    expect(rows[2].textContent).toContain('SUP-013');
    expect(screen.getAllByText('交期無法滿足需求日期').length).toBeGreaterThan(0);
    const expandButtons = within(table).getAllByRole('button', { name: /展開|Expand row/ });
    await userEvent.setup().click(expandButtons[0]);
    expect(await screen.findByText('價格評分')).toBeTruthy();
    expect(screen.getAllByText('88.5000').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: '檢視採購單草稿' })).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(0);
  }, 30_000);

  it('keeps long material names and exact signed decimal strings without rounding or treating null as zero', async () => {
    const longName = '高精度物料名稱'.repeat(24);
    const largeValue = '123456789012345678901234567890.00000000000000000017';
    const tinyNegative = '-0.00000000000000000003';
    const snapshot = {
      ...recommendation('NO_QUALIFIED_SUPPLIER'),
      material: { id: 7, code: 'MAT-007', name: longName, unit: 'PCS' },
      calculation: {
        expectedDemand: largeValue,
        safetyStock: tinyNegative,
        currentInventory: '0.00000000000000000001',
        incomingInventory: null as unknown as string,
        netRequirement: largeValue,
      },
    };
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities.PROCUREMENT_MANAGER);
      if (config.url === '/recommendations/91') return response(config, snapshot);
      if (config.url === '/recommendations/91/suppliers') return response(config, [disqualified]);
      throw new Error(`Unexpected request ${config.url}`);
    });

    await open('/recommendations/91');
    await screen.findByText('採購建議 #91');
    expect(document.querySelector('.recommendation-net-need h3')?.textContent).toBe(`${largeValue} PCS`);
    expect(document.querySelector('.recommendation-material .recommendation-wrap-value')?.textContent).toBe(`MAT-007 · ${longName}`);
    expect(document.querySelector('.recommendation-calculation')?.textContent).toContain(largeValue);
    expect(screen.getByText(`${tinyNegative} PCS`)).toBeTruthy();
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
    expect(screen.getByText('尚無合格供應商')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '檢視草稿選項' })).toBeNull();
  }, 30_000);

  it('keeps valid detail visible when ranking fails and retries only ranking', async () => {
    let rankingCalls = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities.ADMIN);
      if (config.url === '/recommendations/91') return response(config, recommendation('SUPPLIER_RECOMMENDED'));
      if (config.url === '/recommendations/91/suppliers') {
        rankingCalls += 1;
        if (rankingCalls === 1) throw new AxiosError('Failed', 'ERR_BAD_RESPONSE', config, undefined, response(config, { code: 'INTERNAL_SERVER_ERROR', message: 'Ranking unavailable', traceId: 'rank-trace', timestamp: '', path: '' }, 500));
        return response(config, [qualified]);
      }
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/91');
    expect(await screen.findByText('採購建議 #91')).toBeTruthy();
    expect(await screen.findByText('供應商排名查詢失敗')).toBeTruthy();
    await userEvent.setup().click(screen.getByRole('button', { name: '重新查詢供應商排名' }));
    await userEvent.setup().click(await screen.findByText('供應商排名與排除原因'));
    expect(await screen.findByText('SUP-012')).toBeTruthy();
    expect(rankingCalls).toBe(2);
  }, 30_000);

  it('renders a specific not-found state for an unknown Recommendation ID', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities.WAREHOUSE_STAFF);
      if (config.url === '/recommendations/404' || config.url === '/recommendations/404/suppliers') {
        throw new AxiosError('Not found', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'RECOMMENDATION_NOT_FOUND', message: '找不到採購建議', traceId: 'not-found-trace', timestamp: '', path: config.url }, 404));
      }
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/404');
    expect((await screen.findAllByText('找不到採購建議')).length).toBeGreaterThan(0);
    expect(screen.getByText('RECOMMENDATION_NOT_FOUND')).toBeTruthy();
    expect(screen.queryByText('採購建議 #404')).toBeNull();
  });
});

describe('SP-804 Recommendation create', () => {
  it('validates required fields and date order before sending a request', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.ADMIN);
      if (config.url === '/materials') return response(config, { content: [material], page: 0, size: 20, totalElements: 1, totalPages: 1 });
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '計算採購建議' }));
    expect(await screen.findByText('請選擇物料')).toBeTruthy();
    expect(screen.getByText('請選擇期間起日')).toBeTruthy();
    expect(screen.getByText('請選擇期間迄日')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('建議期間起日'), { target: { value: '2026-10-31' } });
    fireEvent.change(screen.getByLabelText('建議期間迄日'), { target: { value: '2026-10-01' } });
    await user.click(screen.getByRole('button', { name: '計算採購建議' }));
    expect(await screen.findByText('期間起日不可晚於迄日')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/recommendations')).toHaveLength(0);
  }, 30_000);

  it('sends one exact POST while loading and displays the backend ID on success', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let finish!: () => void;
    axiosClient.defaults.adapter = vi.fn((config) => {
      requests.push(config);
      if (config.url === '/auth/me') return Promise.resolve(response(config, identities.PROCUREMENT_STAFF));
      if (config.url === '/materials') return Promise.resolve(response(config, { content: [material], page: 0, size: 20, totalElements: 1, totalPages: 1 }));
      if (config.url === '/recommendations') return new Promise((resolve) => { finish = () => resolve(response(config, recommendation('SUPPLIER_RECOMMENDED'), 201)); });
      if (config.url === '/recommendations/91/suppliers') return Promise.resolve(response(config, [qualified]));
      return Promise.reject(new Error(`Unexpected request ${config.url}`));
    });
    await open('/recommendations');
    const user = userEvent.setup();
    await user.click(await screen.findByLabelText('選取物料'));
    await user.click(await screen.findByText('MAT-007 — Copper'));
    fireEvent.change(screen.getByLabelText('建議期間起日'), { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByLabelText('建議期間迄日'), { target: { value: '2026-10-31' } });
    const submit = screen.getByRole('button', { name: '計算採購建議' });
    await user.click(submit);
    fireEvent.click(submit);
    await waitFor(() => expect(requests.filter(({ method, url }) => method === 'post' && url === '/recommendations')).toHaveLength(1));
    finish();
    expect(await screen.findByText('採購建議 #91')).toBeTruthy();
    const post = requests.find(({ method, url }) => method === 'post' && url === '/recommendations')!;
    expect(JSON.parse(post.data as string)).toEqual({ materialId: 7, periodStart: '2026-10-01', periodEnd: '2026-10-31' });
  }, 30_000);

  it('keeps create form values and does not render fake success after a POST error', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities.ADMIN);
      if (config.url === '/materials') return response(config, { content: [material], page: 0, size: 20, totalElements: 1, totalPages: 1 });
      if (config.url === '/recommendations') throw new AxiosError('Validation failed', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'VALIDATION_FAILED', message: 'Invalid period', traceId: 'create-trace', timestamp: '', path: config.url, fieldErrors: [{ field: 'periodEnd', message: 'Invalid end', rejectedValue: '2026-10-31' }] }, 400));
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations');
    const user = userEvent.setup();
    await user.click(await screen.findByLabelText('選取物料'));
    await user.click(await screen.findByText('MAT-007 — Copper'));
    fireEvent.change(screen.getByLabelText('建議期間起日'), { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByLabelText('建議期間迄日'), { target: { value: '2026-10-31' } });
    await user.click(screen.getByRole('button', { name: '計算採購建議' }));
    expect(await screen.findByText('建立採購建議失敗')).toBeTruthy();
    expect(screen.getByText('VALIDATION_FAILED')).toBeTruthy();
    expect((screen.getByLabelText('建議期間起日') as HTMLInputElement).value).toBe('2026-10-01');
    expect((screen.getByLabelText('建議期間迄日') as HTMLInputElement).value).toBe('2026-10-31');
    expect(screen.queryByText('採購建議 #91')).toBeNull();
  }, 30_000);
});

describe('SP-804 Recommendation to linked PO Draft', () => {
  it.each(['PROCUREMENT_STAFF', 'ADMIN'] as const)('allows %s to confirm a linked DRAFT with real references', async (role) => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities[role]);
      if (config.url === '/recommendations/91') return response(config, recommendation('SUPPLIER_RECOMMENDED'));
      if (config.url === '/recommendations/91/suppliers') return response(config, [qualified, disqualified]);
      if (config.url === '/purchase-orders') return response(config, { id: 501, poNumber: 'PO-20261012-000501', status: 'DRAFT' }, 201);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/91');
    await userEvent.setup().click(await screen.findByRole('button', { name: '檢視採購單草稿' }, { timeout: 10_000 }));
    expect(await screen.findByText('確認建立採購單草稿')).toBeTruthy();
    expect(screen.getAllByText('101').length).toBeGreaterThan(0);
    await userEvent.setup().click(screen.getByRole('button', { name: '確認建立草稿' }));
    expect(await screen.findByText('採購單草稿 #501 已建立')).toBeTruthy();

    const createRequests = requests.filter(({ method, url }) => method === 'post' && url === '/purchase-orders');
    expect(createRequests).toHaveLength(1);
    expect(JSON.parse(createRequests[0].data as string)).toEqual({
      supplierId: 12,
      items: [{
        materialId: 7,
        quantity: '110.0000',
        unitPrice: '25.5000',
        recommendationId: 91,
        supplierRecommendationResultId: 101,
      }],
    });
    expect(requests.some(({ url }) => /\/(approve|order|receive)$/.test(url ?? ''))).toBe(false);
  }, 30_000);

  it.each(['PROCUREMENT_MANAGER', 'PRODUCTION_PLANNER', 'WAREHOUSE_STAFF'] as const)('does not expose or call create Draft for %s', async (role) => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities[role]);
      if (config.url === '/recommendations/91') return response(config, recommendation('SUPPLIER_RECOMMENDED'));
      if (config.url === '/recommendations/91/suppliers') return response(config, [qualified]);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/91');
    expect(await screen.findByText('採購建議 #91')).toBeTruthy();
    await userEvent.setup().click(await screen.findByText('供應商排名與排除原因'));
    await screen.findByText('SUP-012');
    expect(screen.queryByRole('button', { name: '檢視採購單草稿' })).toBeNull();
    expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(0);
  }, 30_000);

  it('requires both override reasons and sends the selected result snapshot without decimal arithmetic', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.PROCUREMENT_STAFF);
      if (config.url === '/recommendations/91') return response(config, recommendation('SUPPLIER_RECOMMENDED'));
      if (config.url === '/recommendations/91/suppliers') return response(config, [qualified, alternateQualified]);
      if (config.url === '/purchase-orders') return response(config, { id: 502, poNumber: 'PO-20261012-000502', status: 'DRAFT' }, 201);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/91');
    const user = userEvent.setup();
    await user.click(await screen.findByLabelText('合格供應商結果'));
    await user.click(await screen.findByText('SUP-014 — Alternate Supplier (Result #103)'));
    expect(await screen.findByLabelText('供應商覆寫原因')).toBeTruthy();
    expect(screen.getByLabelText('數量覆寫原因')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '檢視採購單草稿' }));
    expect(await screen.findByText('請填寫供應商覆寫原因')).toBeTruthy();
    expect(screen.getByText('請填寫數量覆寫原因')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(0);

    await user.type(screen.getByLabelText('供應商覆寫原因'), 'Capacity confirmed');
    await user.type(screen.getByLabelText('數量覆寫原因'), 'Supplier package quantity');
    fireEvent.change(screen.getByLabelText('預計交期'), { target: { value: '2026-10-31' } });
    await user.click(screen.getByRole('button', { name: '檢視採購單草稿' }));
    await user.click(await screen.findByRole('button', { name: '確認建立草稿' }, { timeout: 10_000 }));
    expect(await screen.findByText('採購單草稿 #502 已建立')).toBeTruthy();
    const request = requests.find(({ url }) => url === '/purchase-orders')!;
    expect(JSON.parse(request.data as string)).toEqual({
      supplierId: 14,
      expectedDeliveryDate: '2026-10-31',
      items: [{
        materialId: 7,
        quantity: '120.0000',
        unitPrice: '24.2500',
        recommendationId: 91,
        supplierRecommendationResultId: 103,
        supplierOverrideReason: 'Capacity confirmed',
        quantityOverrideReason: 'Supplier package quantity',
      }],
    });
  }, 30_000);

  it('blocks disqualified or incomplete supplier results before POST', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.ADMIN);
      if (config.url === '/recommendations/91') return response(config, recommendation('SUPPLIER_RECOMMENDED'));
      if (config.url === '/recommendations/91/suppliers') return response(config, [disqualified, { ...qualified, id: undefined }]);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/91');
    expect(await screen.findByText('無法建立 Linked PO Draft')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '檢視採購單草稿' })).toBeNull();
    expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(0);
  }, 30_000);

  it('uses a synchronous guard so rapid confirmation produces one POST', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let finish!: () => void;
    axiosClient.defaults.adapter = vi.fn((config) => {
      requests.push(config);
      if (config.url === '/auth/me') return Promise.resolve(response(config, identities.PROCUREMENT_STAFF));
      if (config.url === '/recommendations/91') return Promise.resolve(response(config, recommendation('SUPPLIER_RECOMMENDED')));
      if (config.url === '/recommendations/91/suppliers') return Promise.resolve(response(config, [qualified]));
      if (config.url === '/purchase-orders') return new Promise((resolve) => { finish = () => resolve(response(config, { id: 503, poNumber: 'PO-20261012-000503', status: 'DRAFT' }, 201)); });
      return Promise.reject(new Error(`Unexpected request ${config.url}`));
    });
    await open('/recommendations/91');
    await userEvent.setup().click(await screen.findByRole('button', { name: '檢視採購單草稿' }, { timeout: 10_000 }));
    const confirm = await screen.findByRole('button', { name: '確認建立草稿' }, { timeout: 10_000 });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    await waitFor(() => expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(1));
    finish();
    expect(await screen.findByText('採購單草稿 #503 已建立')).toBeTruthy();
  }, 30_000);

  it.each([
    ['network error', new AxiosError('Network Error', 'ERR_NETWORK')],
    ['timeout', new AxiosError('Timeout', 'ECONNABORTED')],
  ] as const)('locks manual resubmission when the create result is unknown after %s', async (_label, failure) => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.ADMIN);
      if (config.url === '/recommendations/91') return response(config, recommendation('SUPPLIER_RECOMMENDED'));
      if (config.url === '/recommendations/91/suppliers') return response(config, [qualified]);
      if (config.url === '/purchase-orders') throw failure;
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/91');
    const review = await screen.findByRole('button', { name: '檢視採購單草稿' }, { timeout: 10_000 });
    await userEvent.setup().click(review);
    await userEvent.setup().click(await screen.findByRole('button', { name: '確認建立草稿' }, { timeout: 10_000 }));
    expect(await screen.findByText('建立結果不明，請先人工查核')).toBeTruthy();
    expect(screen.getByText(/後端可能已完成建立/)).toBeTruthy();
    expect((review as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(review);
    expect(screen.queryByRole('dialog', { name: '確認建立採購單草稿' })).toBeNull();
    await new Promise((resolve) => window.setTimeout(resolve, 20));
    expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(1);
    expect(screen.queryByText(/採購單草稿 #\d+ 已建立/)).toBeNull();
  }, 30_000);

  it('shows the backend error for 409 without treating the result as unknown', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.ADMIN);
      if (config.url === '/recommendations/91') return response(config, recommendation('SUPPLIER_RECOMMENDED'));
      if (config.url === '/recommendations/91/suppliers') return response(config, [qualified]);
      if (config.url === '/purchase-orders') {
        throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined,
          response(config, { code: 'DATA_INTEGRITY_VIOLATION', message: 'Conflict', traceId: 'conflict-trace', timestamp: '', path: config.url }, 409));
      }
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/91');
    const review = await screen.findByRole('button', { name: '檢視採購單草稿' }, { timeout: 10_000 });
    await userEvent.setup().click(review);
    await userEvent.setup().click(await screen.findByRole('button', { name: '確認建立草稿' }, { timeout: 10_000 }));
    expect(await screen.findByText('採購單草稿建立失敗')).toBeTruthy();
    expect(screen.getByText('Conflict')).toBeTruthy();
    expect((review as HTMLButtonElement).disabled).toBe(false);
    expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(1);
  }, 30_000);
});
