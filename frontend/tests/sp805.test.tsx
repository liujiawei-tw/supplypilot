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
    await user.click(await screen.findByRole('button', { name: '建立手動草稿' }));
    await user.click(await screen.findByRole('combobox', { name: '選取供應商' })); await user.click(await screen.findByText('SUP-007 — Supplier'));
    await user.click(screen.getByRole('combobox', { name: '選取物料' })); await user.click(await screen.findByText('MAT-008 — Steel (KG)'));
    await user.type(screen.getByLabelText('採購單品項 1 數量'), '100');
    await user.type(screen.getByLabelText('採購單品項 1 單價'), '123.4500');
    await user.click(screen.getByRole('button', { name: '檢視新草稿' }));
    await user.click(await screen.findByRole('button', { name: '確認建立草稿' }));
    await waitFor(() => expect(requests.filter(({ url, method }) => url === '/purchase-orders' && method === 'post')).toHaveLength(1));
    expect(JSON.parse(requests.find(({ url, method }) => url === '/purchase-orders' && method === 'post')!.data as string)).toEqual({ supplierId: 7, items: [{ materialId: 8, quantity: '100', unitPrice: '123.4500' }] });
  }, 30_000);

  it('creates a DRAFT with the exact IDs of page-6 master records and does not confuse duplicate names', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    const materials = Array.from({ length: 120 }, (_, index) => ({ ...material, id: index + 1, code: `MAT-${String(index + 1).padStart(3, '0')}`, name: index === 100 || index === 101 ? 'Copper Wire' : `Material ${index + 1}`, baseUnit: index === 100 ? 'KG' : 'PCS' }));
    const suppliers = Array.from({ length: 120 }, (_, index) => ({ ...supplier, id: index + 1, code: `SUP-${String(index + 1).padStart(3, '0')}`, name: `Supplier ${index + 1}` }));
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, staff);
      if (config.url === '/purchase-orders' && config.method === 'get') return response(config, emptyPage);
      if (config.url === '/suppliers' || config.url === '/materials') {
        const records = config.url === '/suppliers' ? suppliers : materials;
        const filtered = config.params?.code ? records.filter((item) => item.code.includes(String(config.params?.code))) : records;
        const pageIndex = Number(config.params?.page ?? 0); const size = Number(config.params?.size ?? 20);
        const content = filtered.slice(pageIndex * size, (pageIndex + 1) * size);
        return response(config, { content, page: pageIndex, size, totalElements: filtered.length, totalPages: Math.ceil(filtered.length / size) });
      }
      if (config.url === '/purchase-orders' && config.method === 'post') return response(config, { ...po, supplierId: 120, items: [{ ...po.items[0], materialId: 101, recommendationId: null, supplierRecommendationResultId: null }] });
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/purchase-orders'); const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '建立手動草稿' }));
    const supplierInput = await screen.findByRole('combobox', { name: '選取供應商' });
    await user.click(supplierInput); await user.type(supplierInput, 'SUP-120');
    await user.click(await screen.findByText('SUP-120 — Supplier 120'));
    const materialInput = screen.getByRole('combobox', { name: '選取物料' });
    await user.click(materialInput); await user.type(materialInput, 'MAT-101');
    await user.click(await screen.findByText('MAT-101 — Copper Wire (KG)'));
    await user.type(screen.getByLabelText('採購單品項 1 數量'), '12');
    await user.type(screen.getByLabelText('採購單品項 1 單價'), '4.2500');
    await user.click(screen.getByRole('button', { name: '檢視新草稿' }));
    expect(await screen.findByText('供應商編號')).toBeTruthy();
    await user.click(await screen.findByRole('button', { name: '確認建立草稿' }));
    await waitFor(() => expect(requests.some(({ url, method }) => url === '/purchase-orders' && method === 'post')).toBe(true));
    const created = JSON.parse(requests.find(({ url, method }) => url === '/purchase-orders' && method === 'post')!.data as string);
    expect(created).toEqual({ supplierId: 120, items: [{ materialId: 101, quantity: '12', unitPrice: '4.2500' }] });
    expect(requests.some(({ url, params }) => url === '/suppliers' && params?.code === 'SUP-120')).toBe(true);
    expect(requests.some(({ url, params }) => url === '/materials' && params?.code === 'MAT-101')).toBe(true);
  }, 30_000);

  it('restores off-page master labels in edit and preserves recommendation evidence and price snapshot', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    const selectedSupplier = { ...supplier, id: 120, code: 'SUP-120', name: 'Original long supplier name'.repeat(5) };
    const selectedMaterial = { ...material, id: 101, code: 'MAT-101', name: 'Original long material name'.repeat(5), baseUnit: 'KG' };
    const existing = { ...po, supplierId: 120, supplierCode: selectedSupplier.code, supplierName: selectedSupplier.name, items: [{ ...po.items[0], materialId: 101, materialCode: selectedMaterial.code, materialName: selectedMaterial.name, unit: 'KG' }] };
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, admin);
      if (config.url === '/purchase-orders/42' && config.method === 'get') return response(config, existing);
      if (config.url === '/suppliers/120') return response(config, selectedSupplier);
      if (config.url === '/materials/101') return response(config, selectedMaterial);
      if (config.url === '/suppliers' || config.url === '/materials') return response(config, optionPage([]));
      if (config.url === '/purchase-orders/42' && config.method === 'patch') return response(config, existing);
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/purchase-orders/42'); const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '編輯草稿' }));
    expect(await screen.findByText(`SUP-120 — ${selectedSupplier.name}`)).toBeTruthy();
    expect(screen.getByText(`MAT-101 — ${selectedMaterial.name} (KG)`)).toBeTruthy();
    expect(screen.getByRole('combobox', { name: '選取供應商' }).hasAttribute('disabled')).toBe(false);
    await user.click(screen.getByRole('button', { name: '檢視草稿修改' }));
    await user.click(await screen.findByRole('button', { name: '確認更新草稿' }));
    const patchRequest = requests.find(({ url, method }) => url === '/purchase-orders/42' && method === 'patch');
    expect(patchRequest).toBeTruthy();
    const updatedRequest = JSON.parse(patchRequest!.data as string);
    expect(updatedRequest).toEqual({ items: [{
      materialId: 101,
      quantity: '999999999999999.1234000',
      unitPrice: '0.0000001234500',
    }] });
    expect(updatedRequest.items[0]).not.toHaveProperty('recommendationId');
    expect(updatedRequest.items[0]).not.toHaveProperty('supplierRecommendationResultId');
    expect(await screen.findByText('歷史建議 #91 / 結果 #101')).toBeTruthy();
    expect(screen.getByText('原供應商覆寫：Capacity')).toBeTruthy();
    expect(screen.getByText('原數量覆寫：Demand changed')).toBeTruthy();
    expect(requests.some(({ url }) => url === '/suppliers/120')).toBe(true);
    expect(requests.some(({ url }) => url === '/materials/101')).toBe(true);
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
    await user.click(await screen.findByRole('button', { name: '編輯草稿' }));
    fireEvent.change(await screen.findByLabelText('採購單預計交期'), { target: { value: '2026-10-10' } });
    await user.click(screen.getByRole('button', { name: '檢視草稿修改' }));
    await user.click(await screen.findByRole('button', { name: '確認更新草稿' }));
    await waitFor(() => expect(screen.getByRole('button', { name: /核\s*准/ }).hasAttribute('disabled')).toBe(false));
    const patchRequest = requests.find(({ url, method }) => url === '/purchase-orders/42' && method === 'patch')!;
    const updatedRequest = JSON.parse(patchRequest.data as string);
    expect(updatedRequest).toMatchObject({ expectedDeliveryDate: '2026-10-10', items: [{ materialId: 8, quantity: '999999999999999.1234000', unitPrice: '0.0000001234500' }] });
    expect(updatedRequest.items[0]).not.toHaveProperty('recommendationId');
    expect(updatedRequest.items[0]).not.toHaveProperty('supplierRecommendationResultId');
    expect(await screen.findByText('歷史建議 #91 / 結果 #101')).toBeTruthy();
    expect(screen.getByText('原供應商覆寫：Capacity')).toBeTruthy();
    expect(screen.getByText('原數量覆寫：Demand changed')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /核\s*准/ })); await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '確認核准' }));
    await user.click(await screen.findByRole('button', { name: '標記為已下單' })); await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '確認下單' }));
    expect(await screen.findByText('已下單')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/approve')).toHaveLength(1);
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/order')).toHaveLength(1);
  }, 30_000);

  it('uses role plus domain state guards and blocks approval when a DRAFT has no delivery date', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config.url ?? ''); if (config.url === '/auth/me') return response(config, manager); if (config.url === '/purchase-orders/42') return response(config, po); throw new Error(`Unexpected ${config.url}`); });
    await open('/purchase-orders/42');
    expect(await screen.findByText('PO-000042')).toBeTruthy();
    expect(screen.getByRole('button', { name: /核\s*准/ }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByText(/尚未設定交期/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: '編輯草稿' })).toBeNull();
    expect(screen.queryByRole('button', { name: '標記為已下單' })).toBeNull();
    expect(screen.getByText('999999999999999.1234000 KG')).toBeTruthy();
    expect(requests).toEqual(['/auth/me', '/users/assignable', '/purchase-orders/42']);
  });

  it('requires a non-blank reject reason before enabling the confirmed request', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); if (config.url === '/auth/me') return response(config, manager); if (config.url === '/purchase-orders/42') return response(config, po); if (config.url === '/purchase-orders/42/reject') return response(config, { ...po, status: 'REJECTED', rejectReason: 'Budget', rejectedBy: 3, rejectedByDisplayName: 'Manager', rejectedAt: '2026-10-02T00:00:00Z' }); throw new Error(`Unexpected ${config.url}`); });
    await open('/purchase-orders/42'); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: /駁\s*回/ }));
    const dialog = screen.getByRole('dialog'); const confirm = within(dialog).getByRole('button', { name: '確認駁回' });
    expect(confirm.hasAttribute('disabled')).toBe(true); fireEvent.change(within(dialog).getByLabelText('採購單駁回原因'), { target: { value: 'Budget' } }); expect(confirm.hasAttribute('disabled')).toBe(false);
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
    expect(screen.queryByText('單價')).toBeNull(); expect(screen.queryByText('採購建議依據')).toBeNull();
    await user.click(screen.getByRole('button', { name: '確認整筆收貨' })); const confirm = within(screen.getByRole('dialog')).getByRole('button', { name: '確認整筆收貨' }); await user.click(confirm); fireEvent.click(confirm);
    await waitFor(() => expect(requests.filter(({ url }) => url === '/purchase-orders/42/receive')).toHaveLength(1)); finish(); await screen.findByText('已收貨');
  });

  it('locks a 409 receive result until an explicit restricted refetch and never auto-retries', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); if (config.url === '/auth/me') return response(config, warehouse); if (config.url === '/receiving/purchase-orders/42') return response(config, receiving); if (config.url === '/purchase-orders/42/receive') throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'DUPLICATE_RECEIVING', message: 'Already received', traceId: '', timestamp: '', path: '' }, 409)); throw new Error(`Unexpected ${config.url}`); });
    await open('/receiving/purchase-orders/42'); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '確認整筆收貨' })); await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '確認整筆收貨' }));
    expect(await screen.findByText('收貨結果需要查核，已鎖定再次提交')).toBeTruthy(); expect(requests.filter(({ url }) => url === '/purchase-orders/42/receive')).toHaveLength(1);
    expect(screen.getByRole('button', { name: '確認整筆收貨' }).hasAttribute('disabled')).toBe(true); await user.click(screen.getByRole('button', { name: '重新查詢收貨狀態' }));
    await waitFor(() => expect(requests.filter(({ url }) => url === '/receiving/purchase-orders/42')).toHaveLength(2)); expect(requests.filter(({ url }) => url === '/purchase-orders/42/receive')).toHaveLength(1); expect(screen.getByText('已下單')).toBeTruthy();
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
    await user.click(await screen.findByRole('button', { name: '確認整筆收貨' })); await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '確認整筆收貨' }));
    expect(await screen.findByText('收貨結果需要查核，已鎖定再次提交')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '重新查詢收貨狀態' }));
    expect(await screen.findByText('已收貨')).toBeTruthy();
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

describe('SP-805 PO ownership mutation verification', () => {
  const ownerPo = { ...po, assignedTo: null, assignedToDisplayName: null, assignedToUsername: null };
  const latestAssignedPo = { ...ownerPo, assignedTo: 1, assignedToDisplayName: 'Admin', assignedToUsername: 'admin' };
  const assignable = [{ id: 1, username: 'admin', displayName: 'Admin' }];

  it('locks detail owner changes after a conflict until the PO is explicitly refetched', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let detailReads = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, admin);
      if (config.url === '/users/assignable') return response(config, assignable);
      if (config.url === '/purchase-orders/42' && config.method === 'get') {
        detailReads += 1;
        return response(config, detailReads === 1 ? ownerPo : latestAssignedPo);
      }
      if (config.url === '/purchase-orders/42/claim') throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'CONCURRENT_MODIFICATION' }, 409));
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/purchase-orders/42'); const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Claim PO-000042' }));
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getAllByRole('button').at(-1)!);
    await waitFor(() => expect(requests.filter(({ url }) => url === '/purchase-orders/42/claim')).toHaveLength(1));

    const verifyButton = await waitFor(() => {
      const button = screen.getAllByRole('alert').map((alert) => alert.querySelector('button')).find(Boolean);
      if (!button) throw new Error('Verification action is not available');
      return button;
    });
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/claim')).toHaveLength(1);
    await user.click(verifyButton);
    await waitFor(() => expect(screen.getAllByText('Admin').length).toBeGreaterThan(1));
    expect(detailReads).toBe(2);
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/claim')).toHaveLength(1);
  }, 30_000);
});
