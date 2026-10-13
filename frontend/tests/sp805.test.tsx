import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../src/App';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import { router } from '../src/router/routes';
import type { AuthenticatedUser } from '../src/types/api';

const manager: AuthenticatedUser = { id: 3, username: 'manager', displayName: 'Manager', role: 'PROCUREMENT_MANAGER' };
const warehouse: AuthenticatedUser = { id: 4, username: 'warehouse', displayName: 'Warehouse', role: 'WAREHOUSE_STAFF' };
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

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse { return { config, data, status, statusText: String(status), headers: {} }; }
async function open(path: string) { localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token'); await router.navigate(path); render(<App />); }
beforeEach(() => { localStorage.clear(); });

describe('SP-805 PO lifecycle UI', () => {
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
    await waitFor(() => expect(requests.filter(({ url }) => url === '/receiving/purchase-orders/42')).toHaveLength(2)); expect(requests.filter(({ url }) => url === '/purchase-orders/42/receive')).toHaveLength(1);
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
