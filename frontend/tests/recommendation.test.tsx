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
    expect(await screen.findByText('Recommendation Workstation')).toBeTruthy();
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
    expect(await screen.findByText('Recommendation #91')).toBeTruthy();
    expect(requests).not.toContain('/recommendations');
  });
});

describe('SP-804 Recommendation rendering', () => {
  it.each([
    ['PURCHASE_NOT_REQUIRED', '不需要採購', '0.0000 KG'],
    ['SUPPLIER_RECOMMENDED', '已有推薦供應商', '108.4500 KG'],
    ['NO_QUALIFIED_SUPPLIER', '沒有合格供應商', '108.4500 KG'],
  ] as const)('renders %s using backend snapshot values', async (outcome, label, netRequirement) => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities.PROCUREMENT_MANAGER);
      if (config.url === '/recommendations/91') return response(config, recommendation(outcome));
      if (config.url === '/recommendations/91/suppliers') return response(config, outcome === 'SUPPLIER_RECOMMENDED' ? [qualified, disqualified] : []);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/91');
    expect(await screen.findByText(label)).toBeTruthy();
    expect(screen.getByText(netRequirement)).toBeTruthy();
    expect(screen.getByText('MAT-007')).toBeTruthy();
    expect(screen.getByText('Copper snapshot')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Create PO Draft' })).toBeNull();
    if (outcome !== 'SUPPLIER_RECOMMENDED') expect(screen.getAllByText('N/A').length).toBeGreaterThan(0);
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
    const table = await screen.findByRole('table', { name: 'Supplier ranking' });
    const rows = within(table).getAllByRole('row');
    expect(rows[1].textContent).toContain('SUP-012');
    expect(rows[2].textContent).toContain('SUP-013');
    expect(screen.getByText('DELIVERY_DATE_NOT_FEASIBLE')).toBeTruthy();
    expect(screen.getByText('交期無法滿足需求日期')).toBeTruthy();
    const expandButtons = within(table).getAllByRole('button', { name: 'Expand row' });
    await userEvent.setup().click(expandButtons[0]);
    expect(await screen.findByText('Price Score')).toBeTruthy();
    expect(screen.getAllByText('88.5000').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Review PO Draft' })).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(0);
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
    expect(await screen.findByText('Recommendation #91')).toBeTruthy();
    expect(await screen.findByText('Supplier ranking query failed')).toBeTruthy();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry supplier ranking' }));
    expect(await screen.findByText('SUP-012')).toBeTruthy();
    expect(rankingCalls).toBe(2);
  }, 30_000);

  it('renders a specific not-found state for an unknown Recommendation ID', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities.WAREHOUSE_STAFF);
      if (config.url === '/recommendations/404' || config.url === '/recommendations/404/suppliers') {
        throw new AxiosError('Not found', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'RECOMMENDATION_NOT_FOUND', message: 'Recommendation not found', traceId: 'not-found-trace', timestamp: '', path: config.url }, 404));
      }
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/404');
    expect((await screen.findAllByText('Recommendation not found')).length).toBeGreaterThan(0);
    expect(screen.getByText('RECOMMENDATION_NOT_FOUND')).toBeTruthy();
    expect(screen.queryByText('Recommendation #404')).toBeNull();
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
    await user.click(await screen.findByRole('button', { name: 'Calculate Recommendation' }));
    expect(await screen.findByText('Material is required')).toBeTruthy();
    expect(screen.getByText('Period start is required')).toBeTruthy();
    expect(screen.getByText('Period end is required')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Recommendation period start'), { target: { value: '2026-10-31' } });
    fireEvent.change(screen.getByLabelText('Recommendation period end'), { target: { value: '2026-10-01' } });
    await user.click(screen.getByRole('button', { name: 'Calculate Recommendation' }));
    expect(await screen.findByText('Period start must not be later than period end')).toBeTruthy();
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
    await user.click(await screen.findByLabelText('Select material'));
    await user.click(await screen.findByText('MAT-007 — Copper'));
    fireEvent.change(screen.getByLabelText('Recommendation period start'), { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByLabelText('Recommendation period end'), { target: { value: '2026-10-31' } });
    const submit = screen.getByRole('button', { name: 'Calculate Recommendation' });
    await user.click(submit);
    fireEvent.click(submit);
    await waitFor(() => expect(requests.filter(({ method, url }) => method === 'post' && url === '/recommendations')).toHaveLength(1));
    finish();
    expect(await screen.findByText('Recommendation #91')).toBeTruthy();
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
    await user.click(await screen.findByLabelText('Select material'));
    await user.click(await screen.findByText('MAT-007 — Copper'));
    fireEvent.change(screen.getByLabelText('Recommendation period start'), { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByLabelText('Recommendation period end'), { target: { value: '2026-10-31' } });
    await user.click(screen.getByRole('button', { name: 'Calculate Recommendation' }));
    expect(await screen.findByText('Recommendation create failed')).toBeTruthy();
    expect(screen.getByText('VALIDATION_FAILED')).toBeTruthy();
    expect((screen.getByLabelText('Recommendation period start') as HTMLInputElement).value).toBe('2026-10-01');
    expect((screen.getByLabelText('Recommendation period end') as HTMLInputElement).value).toBe('2026-10-31');
    expect(screen.queryByText('Recommendation #91')).toBeNull();
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
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Review PO Draft' }, { timeout: 10_000 }));
    expect(await screen.findByText('Confirm Create PO DRAFT')).toBeTruthy();
    expect(screen.getAllByText('101').length).toBeGreaterThan(0);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Confirm Create DRAFT' }));
    expect(await screen.findByText('PO DRAFT #501 已建立')).toBeTruthy();

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
    expect(await screen.findByText('Recommendation #91')).toBeTruthy();
    await screen.findByText('SUP-012');
    expect(screen.queryByRole('button', { name: 'Review PO Draft' })).toBeNull();
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
    await user.click(await screen.findByLabelText('Qualified supplier result'));
    await user.click(await screen.findByText('SUP-014 — Alternate Supplier (Result #103)'));
    expect(await screen.findByLabelText('Supplier override reason')).toBeTruthy();
    expect(screen.getByLabelText('Quantity override reason')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Review PO Draft' }));
    expect(await screen.findByText('Supplier override reason is required')).toBeTruthy();
    expect(screen.getByText('Quantity override reason is required')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(0);

    await user.type(screen.getByLabelText('Supplier override reason'), 'Capacity confirmed');
    await user.type(screen.getByLabelText('Quantity override reason'), 'Supplier package quantity');
    fireEvent.change(screen.getByLabelText('Expected delivery date'), { target: { value: '2026-10-31' } });
    await user.click(screen.getByRole('button', { name: 'Review PO Draft' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm Create DRAFT' }, { timeout: 10_000 }));
    expect(await screen.findByText('PO DRAFT #502 已建立')).toBeTruthy();
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
    expect(screen.queryByRole('button', { name: 'Review PO Draft' })).toBeNull();
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
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Review PO Draft' }, { timeout: 10_000 }));
    const confirm = await screen.findByRole('button', { name: 'Confirm Create DRAFT' }, { timeout: 10_000 });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    await waitFor(() => expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(1));
    finish();
    expect(await screen.findByText('PO DRAFT #503 已建立')).toBeTruthy();
  }, 30_000);

  it.each([
    ['409 conflict', new AxiosError('Conflict', 'ERR_BAD_REQUEST', undefined, undefined, undefined), false],
    ['network uncertainty', new AxiosError('Network Error', 'ERR_NETWORK'), true],
  ] as const)('does not show fake success or automatically retry after %s', async (_label, failure, unknown) => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.ADMIN);
      if (config.url === '/recommendations/91') return response(config, recommendation('SUPPLIER_RECOMMENDED'));
      if (config.url === '/recommendations/91/suppliers') return response(config, [qualified]);
      if (config.url === '/purchase-orders') {
        if (!unknown) throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'DATA_INTEGRITY_VIOLATION', message: 'Conflict', traceId: 'conflict-trace', timestamp: '', path: config.url }, 409));
        throw failure;
      }
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('/recommendations/91');
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Review PO Draft' }, { timeout: 10_000 }));
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Confirm Create DRAFT' }, { timeout: 10_000 }));
    expect(await screen.findByText(unknown ? '建立結果不明，請先人工查核' : 'PO Draft 建立失敗')).toBeTruthy();
    await new Promise((resolve) => window.setTimeout(resolve, 20));
    expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(1);
    expect(screen.queryByText(/PO DRAFT #\d+ 已建立/)).toBeNull();
  }, 30_000);
});
