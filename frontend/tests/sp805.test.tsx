import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../src/App';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import { router } from '../src/router/routes';
import type { AuthenticatedUser } from '../src/types/api';

const manager: AuthenticatedUser = { id: 3, username: 'manager', displayName: 'Manager', role: 'PROCUREMENT_MANAGER' };
const staff: AuthenticatedUser = { id: 2, username: 'buyer', displayName: 'Buyer', role: 'PROCUREMENT_STAFF' };
const warehouse: AuthenticatedUser = { id: 4, username: 'warehouse', displayName: 'Warehouse', role: 'WAREHOUSE_STAFF' };
const admin: AuthenticatedUser = { id: 1, username: 'admin', displayName: 'Admin', role: 'ADMIN' };
const po = {
  id: 42, poNumber: 'PO-000042', supplierId: 7, supplierCode: 'SUP-007', supplierName: 'Supplier', status: 'DRAFT',
  expectedDeliveryDate: null, items: [{ id: 1, materialId: 8, materialCode: 'MAT-008', materialName: 'Steel', quantity: '999999999999999.1234000', unit: 'KG', unitPrice: '0.0000001234500', total: '123.456789', recommendationId: 91, supplierRecommendationResultId: 101, supplierOverride: true, quantityOverride: true, supplierOverrideReason: 'Capacity', quantityOverrideReason: 'Demand changed' }],
  createdBy: 2, createdByDisplayName: 'Buyer', createdAt: '2026-10-01T00:00:00Z', approvedBy: null, approvedByDisplayName: null, approvedAt: null, rejectReason: null, rejectedBy: null, rejectedByDisplayName: null, rejectedAt: null, orderedBy: null, orderedByDisplayName: null, orderedAt: null, receivedBy: null, receivedByDisplayName: null, receivedAt: null, isOverdue: false, updatedAt: '2026-10-01T00:00:00Z',
};
const receiving = {
  id: 42, poNumber: 'PO-000042', supplierId: 7, supplierCode: 'SUP-007', supplierName: 'Supplier', status: 'ORDERED', expectedDeliveryDate: '2026-10-01', isOverdue: true,
  items: [{ id: 1, materialId: 8, materialCode: 'MAT-008', materialName: 'Steel', quantity: '50.0000', unit: 'KG' }], orderedAt: '2026-09-30T00:00:00Z', receivedBy: null, receivedByDisplayName: null, receivedAt: null,
};
const transaction = { id: 71, materialId: 8, materialCode: 'MAT-008', materialName: 'Steel', unit: 'KG', type: 'PURCHASE_RECEIVE', quantity: '50.0000', purchaseOrderId: 42, purchaseOrderNumber: 'PO-000042', reason: null, createdBy: 4, createdByDisplayName: 'Warehouse', createdAt: '2026-10-01T00:00:00Z' };
const supplier = { id: 7, code: 'SUP-007', name: 'Supplier', country: 'TW', reliabilityScore: 90, qualityScore: 95, status: 'ACTIVE', createdAt: '', updatedAt: '' };
const material = { id: 8, code: 'MAT-008', name: 'Steel', baseUnit: 'KG', safetyStock: 0, status: 'ACTIVE', createdAt: '', updatedAt: '' };
const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
const optionPage = (content: unknown[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 });

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse { return { config, data, status, statusText: String(status), headers: {} }; }
async function open(path: string) { localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token'); await router.navigate(path); render(<App />); }
beforeEach(() => { localStorage.clear(); });

describe('SP-805 PO lifecycle UI', () => {
  it('preserves integer, decimal, and scientific BigDecimal tokens from an API response through UI rendering', async () => {
    const rawPo = JSON.stringify({ ...po, items: [] }).replace('"items":[]', '"items":[{"id":1,"materialId":8,"materialCode":"MAT-008","materialName":"Steel","quantity":999999999999999999999,"unit":"KG","unitPrice":123.4500,"total":1.23E+8,"recommendationId":null,"supplierRecommendationResultId":null,"supplierOverride":false,"quantityOverride":false,"supplierOverrideReason":null,"quantityOverrideReason":null}]');
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, manager);
      if (config.url === '/purchase-orders/42') return response(config, rawPo);
      throw new Error(`Unexpected ${config.url}`);
    });
    await open('/purchase-orders/42');
    expect(await screen.findByText('999999999999999999999 KG')).toBeTruthy();
    expect(screen.getByText('123.4500')).toBeTruthy();
    expect(screen.getByText('1.23E+8')).toBeTruthy();
  });

  it('creates a Manual DRAFT with exact decimal strings after explicit confirmation', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, staff);
      if (config.url === '/purchase-orders' && config.method === 'get') return response(config, emptyPage);
      if (config.url === '/suppliers') return response(config, optionPage([supplier]));
      if (config.url === '/materials') return response(config, optionPage([material]));
      if (config.url === '/purchase-orders' && config.method === 'post') return response(config, { ...po, recommendationId: null });
      throw new Error(`Unexpected ${config.url}`);
    });
    await open('/purchase-orders'); const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Create Manual DRAFT' }));
    await user.click(await screen.findByLabelText('PO supplier')); await user.click(await screen.findByText('SUP-007 — Supplier'));
    await user.click(screen.getByLabelText('PO item 1 material')); await user.click(await screen.findByText('MAT-008 — Steel (KG)'));
    await user.type(screen.getByLabelText('PO item 1 quantity'), '100');
    await user.type(screen.getByLabelText('PO item 1 unit price'), '123.4500');
    await user.click(screen.getByRole('button', { name: 'Review New Draft' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm Create DRAFT' }));
    await waitFor(() => expect(requests.filter(({ url, method }) => url === '/purchase-orders' && method === 'post')).toHaveLength(1));
    expect(JSON.parse(requests.find(({ url, method }) => url === '/purchase-orders' && method === 'post')!.data as string)).toEqual({ supplierId: 7, items: [{ materialId: 8, quantity: '100', unitPrice: '123.4500' }] });
  }, 30_000);

  it('keeps linked recommendation evidence while filling delivery date, approving, and marking ordered', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, admin);
      if (config.url === '/purchase-orders/42' && config.method === 'get') return response(config, po);
      if (config.url === '/suppliers') return response(config, optionPage([supplier]));
      if (config.url === '/materials') return response(config, optionPage([material]));
      if (config.url === '/purchase-orders/42' && config.method === 'patch') return response(config, { ...po, expectedDeliveryDate: '2026-10-10' });
      if (config.url === '/purchase-orders/42/approve') return response(config, { ...po, expectedDeliveryDate: '2026-10-10', status: 'APPROVED', approvedBy: 1, approvedByDisplayName: 'Admin', approvedAt: '2026-10-02T00:00:00Z' });
      if (config.url === '/purchase-orders/42/order') return response(config, { ...po, expectedDeliveryDate: '2026-10-10', status: 'ORDERED', orderedBy: 1, orderedByDisplayName: 'Admin', orderedAt: '2026-10-03T00:00:00Z' });
      throw new Error(`Unexpected ${config.url}`);
    });
    await open('/purchase-orders/42'); const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Edit DRAFT' }));
    fireEvent.change(await screen.findByLabelText('PO expected delivery date'), { target: { value: '2026-10-10' } });
    await user.click(screen.getByRole('button', { name: 'Review Draft Changes' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm Update DRAFT' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Approve' }).hasAttribute('disabled')).toBe(false));
    const patchRequest = requests.find(({ url, method }) => url === '/purchase-orders/42' && method === 'patch')!;
    expect(JSON.parse(patchRequest.data as string)).toMatchObject({ expectedDeliveryDate: '2026-10-10', items: [{ recommendationId: 91, supplierRecommendationResultId: 101, supplierOverrideReason: 'Capacity', quantityOverrideReason: 'Demand changed' }] });
    await user.click(screen.getByRole('button', { name: 'Approve' })); await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm Approve' }));
    await user.click(await screen.findByRole('button', { name: 'Mark Ordered' })); await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm Mark Ordered' }));
    expect(await screen.findByText('ORDERED')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/approve')).toHaveLength(1);
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/order')).toHaveLength(1);
  }, 30_000);

  it('uses role plus domain state guards and blocks approval when a DRAFT has no delivery date', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config.url ?? ''); if (config.url === '/auth/me') return response(config, manager); if (config.url === '/purchase-orders/42') return response(config, po); throw new Error(`Unexpected ${config.url}`); });
    await open('/purchase-orders/42');
    expect(await screen.findByText('PO-000042')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Approve' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByText(/尚未設定交期/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Edit DRAFT' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Mark Ordered' })).toBeNull();
    expect(screen.getByText('999999999999999.1234000 KG')).toBeTruthy();
    expect(requests).toEqual(['/auth/me', '/purchase-orders/42']);
  });

  it('requires a non-blank reject reason before enabling the confirmed request', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); if (config.url === '/auth/me') return response(config, manager); if (config.url === '/purchase-orders/42') return response(config, po); if (config.url === '/purchase-orders/42/reject') return response(config, { ...po, status: 'REJECTED', rejectReason: 'Budget', rejectedBy: 3, rejectedByDisplayName: 'Manager', rejectedAt: '2026-10-02T00:00:00Z' }); throw new Error(`Unexpected ${config.url}`); });
    await open('/purchase-orders/42'); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: 'Reject' }));
    const dialog = screen.getByRole('dialog'); const confirm = within(dialog).getByRole('button', { name: 'Confirm Reject' });
    expect(confirm.hasAttribute('disabled')).toBe(true); fireEvent.change(within(dialog).getByLabelText('PO reject reason'), { target: { value: 'Budget' } }); expect(confirm.hasAttribute('disabled')).toBe(false);
    await user.click(confirm); await waitFor(() => expect(requests.filter(({ url }) => url === '/purchase-orders/42/reject')).toHaveLength(1));
    expect(JSON.parse(requests.find(({ url }) => url === '/purchase-orders/42/reject')!.data as string)).toEqual({ reason: 'Budget' });
  });
});

describe('SP-805 receiving and stock security boundaries', () => {
  it('loads warehouse detail only from the restricted endpoint and sends one Full Receive POST', async () => {
    const requests: InternalAxiosRequestConfig[] = []; let finish!: () => void;
    axiosClient.defaults.adapter = vi.fn((config) => { requests.push(config); if (config.url === '/auth/me') return Promise.resolve(response(config, warehouse)); if (config.url === '/receiving/purchase-orders/42') return Promise.resolve(response(config, receiving)); if (config.url === '/purchase-orders/42/receive') return new Promise((resolve) => { finish = () => resolve(response(config, { ...receiving, status: 'RECEIVED', isOverdue: false, receivedBy: 4, receivedByDisplayName: 'Warehouse', receivedAt: '2026-10-02T00:00:00Z' })); }); return Promise.reject(new Error(`Unexpected ${config.url}`)); });
    await open('/receiving/purchase-orders/42'); const user = userEvent.setup(); expect(await screen.findByText('PO-000042')).toBeTruthy();
    expect(requests.some(({ url }) => url === '/purchase-orders/42' && configMethod(requests, url) === 'get')).toBe(false);
    expect(screen.queryByText('Unit Price')).toBeNull(); expect(screen.queryByText('Recommendation Evidence')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Confirm Full Receive' })); const confirm = within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm Full Receive' }); await user.click(confirm); fireEvent.click(confirm);
    await waitFor(() => expect(requests.filter(({ url }) => url === '/purchase-orders/42/receive')).toHaveLength(1)); finish(); await screen.findByText('RECEIVED');
  });

  it('locks a 409 receive result until an explicit restricted refetch and never auto-retries', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); if (config.url === '/auth/me') return response(config, warehouse); if (config.url === '/receiving/purchase-orders/42') return response(config, receiving); if (config.url === '/purchase-orders/42/receive') throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'DUPLICATE_RECEIVING', message: 'Already received', traceId: '', timestamp: '', path: '' }, 409)); throw new Error(`Unexpected ${config.url}`); });
    await open('/receiving/purchase-orders/42'); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: 'Confirm Full Receive' })); await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm Full Receive' }));
    expect(await screen.findByText('收貨結果需要查核，已鎖定再次提交')).toBeTruthy(); expect(requests.filter(({ url }) => url === '/purchase-orders/42/receive')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Confirm Full Receive' }).hasAttribute('disabled')).toBe(true); await user.click(screen.getByRole('button', { name: '重新查詢 Receiving 狀態' }));
    await waitFor(() => expect(requests.filter(({ url }) => url === '/receiving/purchase-orders/42')).toHaveLength(2)); expect(requests.filter(({ url }) => url === '/purchase-orders/42/receive')).toHaveLength(1); expect(screen.getByText('ORDERED')).toBeTruthy();
  }, 30_000);

  it('handles a receiving timeout by refetching RECEIVED without automatically resending', async () => {
    const requests: InternalAxiosRequestConfig[] = []; let receivingReads = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, warehouse);
      if (config.url === '/receiving/purchase-orders/42') {
        receivingReads += 1;
        return response(config, receivingReads === 1 ? receiving : { ...receiving, status: 'RECEIVED', isOverdue: false, receivedBy: 4, receivedByDisplayName: 'Warehouse', receivedAt: '2026-10-02T00:00:00Z' });
      }
      if (config.url === '/purchase-orders/42/receive') throw new AxiosError('Timeout', 'ECONNABORTED', config);
      throw new Error(`Unexpected ${config.url}`);
    });
    await open('/receiving/purchase-orders/42'); const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Confirm Full Receive' })); await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm Full Receive' }));
    expect(await screen.findByText('收貨結果需要查核，已鎖定再次提交')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '重新查詢 Receiving 狀態' }));
    expect(await screen.findByText('RECEIVED')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/receive')).toHaveLength(1);
    expect(requests.filter(({ url }) => url === '/receiving/purchase-orders/42')).toHaveLength(2);
  }, 30_000);

  it('shows PO text but no procurement deep link to warehouse stock readers', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config.url ?? ''); if (config.url === '/auth/me') return response(config, warehouse); if (config.url === '/stock-transactions') return response(config, { content: [transaction], page: 0, size: 20, totalElements: 1, totalPages: 1 }); throw new Error(`Unexpected ${config.url}`); });
    await open('/inventory?tab=stock-transactions&purchaseOrderId=42'); expect(await screen.findByRole('row', { name: /PO-000042/ }, { timeout: 10_000 })).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'PO-000042' })).toBeNull();
    expect(requests).toEqual(['/auth/me', '/stock-transactions']);
  }, 30_000);
});

function configMethod(requests: InternalAxiosRequestConfig[], url: string | undefined): string | undefined { return requests.find((request) => request.url === url)?.method; }
