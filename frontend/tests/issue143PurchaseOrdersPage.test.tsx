import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import zhTW from 'antd/locale/zh_TW';
import { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { MemoryRouter, useLocation } from 'react-router-dom';
import axiosClient from '../src/api/axiosClient';
import type { PurchaseOrderResponse, PurchaseOrderQuery } from '../src/api/purchaseOrder';
import PurchaseOrdersPage from '../src/pages/PurchaseOrders/PurchaseOrdersPage';
import { AuthContext } from '../src/auth/authContextValue';

const originalAdapter = axiosClient.defaults.adapter;
const purchaseOrder: PurchaseOrderResponse = {
  id: 42,
  poNumber: 'PO-20261022-000042',
  supplierId: 7,
  supplierCode: 'SUP-007',
  supplierName: '台灣測試供應商',
  status: 'DRAFT',
  stage: 'NEW_REQUEST',
  orderedProgress: null,
  expectedDeliveryDate: '2026-10-30',
  supplierPromisedDeliveryDate: null,
  receivingInspection: {
    allItemsReceived: false,
    allQuantitiesConfirmed: false,
    allQualityAccepted: false,
    note: null,
    inspectedBy: null,
    inspectedByDisplayName: null,
    inspectedByUsername: null,
    inspectedAt: null,
  },
  paymentConfirmed: false,
  paymentConfirmedBy: null,
  paymentConfirmedByDisplayName: null,
  paymentConfirmedByUsername: null,
  paymentConfirmedAt: null,
  items: [{
    id: 420, materialId: 10, materialCode: 'MAT-010', materialName: '測試物料', quantity: '2', unit: 'KG',
    unitPrice: '100', total: '200', recommendationId: null, supplierRecommendationResultId: null,
    supplierOverride: false, quantityOverride: false, supplierOverrideReason: null, quantityOverrideReason: null,
  }],
  createdBy: null,
  createdByDisplayName: '採購人員',
  createdByUsername: null,
  assignedTo: null,
  assignedToDisplayName: null,
  assignedToUsername: null,
  createdAt: '2026-10-22T09:00:00Z',
  approvedBy: null,
  approvedByDisplayName: null,
  approvedAt: null,
  approvedByUsername: null,
  rejectReason: null,
  rejectedBy: null,
  rejectedByDisplayName: null,
  rejectedByUsername: null,
  rejectedAt: null,
  cancelReason: null,
  cancelledBy: null,
  cancelledByDisplayName: null,
  cancelledByUsername: null,
  cancelledAt: null,
  orderedBy: null,
  orderedByDisplayName: null,
  orderedByUsername: null,
  orderedAt: null,
  receivedBy: null,
  receivedByDisplayName: null,
  receivedByUsername: null,
  receivedAt: null,
  isOverdue: false,
  updatedAt: '2026-10-22T09:00:00Z',
};
const otherSupplierPurchaseOrder: PurchaseOrderResponse = {
  ...purchaseOrder,
  id: 43,
  poNumber: 'PO-20261022-000043',
  supplierId: 8,
  supplierCode: 'SUP-008',
  supplierName: '另一家測試供應商',
};

const authValue = {
  user: null,
  isAuthenticated: false,
  isLoading: false,
  verificationError: false,
  retryVerification: () => undefined,
  login: async () => { throw new Error('not used'); },
  logout: () => undefined,
};
const purchaserAuthValue = {
  ...authValue,
  user: { id: 7, username: 'buyer', displayName: '採購人員', role: 'PROCUREMENT_STAFF' as const, canManageUsers: false },
  isAuthenticated: true,
};

let requestedQueries: PurchaseOrderQuery[];
let backendPurchaseOrders: PurchaseOrderResponse[];
let purchaseOrderCreateFailure: 'forbidden' | 'network' | null;

function CurrentRoute() {
  const location = useLocation();
  return <output data-testid="current-route">{location.pathname}{location.search}</output>;
}

function renderPage(contextValue = authValue, initialEntries: string[] = ['/purchase-orders']) {
  return render(<ConfigProvider locale={zhTW}><AuthContext.Provider value={contextValue}>
    <MemoryRouter initialEntries={initialEntries}><CurrentRoute /><PurchaseOrdersPage /></MemoryRouter>
  </AuthContext.Provider></ConfigProvider>);
}

async function submitManualDraft() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '建立手動草稿' }));
  await user.click(screen.getByRole('combobox', { name: '選取供應商' }));
  await user.click(await screen.findByText('SUP-007 — 台灣測試供應商'));
  await user.click(screen.getByRole('combobox', { name: '選取物料' }));
  await user.click(await screen.findByText('MAT-010 — 測試物料 (PCS)'));
  await user.type(screen.getByRole('textbox', { name: '採購單品項 1 數量' }), '2');
  await user.type(screen.getByRole('textbox', { name: '採購單品項 1 單價' }), '100');
  await user.click(screen.getByRole('button', { name: '檢視新草稿' }));
  await user.click(await screen.findByRole('button', { name: '確認建立草稿' }));
}

beforeEach(() => {
  requestedQueries = [];
  backendPurchaseOrders = [purchaseOrder, otherSupplierPurchaseOrder];
  purchaseOrderCreateFailure = null;
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
  axiosClient.defaults.adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
    if (config.url === '/users/assignable') {
      return { data: [], status: 200, statusText: 'OK', headers: {}, config };
    }
    if (config.url === '/suppliers') {
      return {
        data: {
          content: [{ id: 7, code: 'SUP-007', name: '台灣測試供應商', country: 'TW', reliabilityScore: 80, qualityScore: 85, status: 'ACTIVE', createdAt: '2026-10-22T09:00:00Z', updatedAt: '2026-10-22T09:00:00Z' }],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    }
    if (config.url === '/materials') {
      return {
        data: {
          content: [{ id: 10, code: 'MAT-010', name: '測試物料', baseUnit: 'PCS', safetyStock: 0, status: 'ACTIVE', createdAt: '2026-10-22T09:00:00Z', updatedAt: '2026-10-22T09:00:00Z' }],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    }
    if (config.method === 'get' && (config.url ?? '').startsWith('/purchase-orders/')) {
      const segments = (config.url ?? '').split('/');
      const purchaseOrderId = Number(segments[segments.length - 1]);
      const found = backendPurchaseOrders.find((item) => item.id === purchaseOrderId);
      if (!found) throw new Error('Purchase order not found');
      return { data: found, status: 200, statusText: 'OK', headers: {}, config };
    }
    if (config.url === '/purchase-orders') {
      if (config.method === 'post') {
        if (purchaseOrderCreateFailure === 'forbidden') {
          throw new AxiosError('Forbidden', 'ERR_BAD_REQUEST', config, undefined, {
            data: { code: 'ACCESS_DENIED', message: '測試帳號沒有建立權限' },
            status: 403,
            statusText: 'Forbidden',
            headers: {},
            config,
          });
        }
        if (purchaseOrderCreateFailure === 'network') throw new AxiosError('Network Error', 'ERR_NETWORK', config);
        const created = { ...purchaseOrder, id: 44, poNumber: 'PO-20261022-000044' };
        backendPurchaseOrders = [created, ...backendPurchaseOrders];
        return { data: created, status: 201, statusText: 'Created', headers: {}, config };
      }
      const query = (config.params ?? {}) as PurchaseOrderQuery;
      requestedQueries.push({ ...query });
      const filteredOrders = backendPurchaseOrders
        .filter((item) => query.supplierId === undefined || item.supplierId === query.supplierId)
        .filter((item) => !query.poNumber || item.poNumber.toLowerCase().includes(query.poNumber.toLowerCase()))
        .filter((item) => !query.supplierCode || item.supplierCode.toLowerCase().includes(query.supplierCode.toLowerCase()))
        .filter((item) => !query.supplierName || item.supplierName.toLowerCase().includes(query.supplierName.toLowerCase()))
        .filter((item) => !query.materialCode || item.items.some((line) => line.materialCode.toLowerCase().includes(query.materialCode!.toLowerCase())));
      const page = query.page ?? 0;
      const size = query.size ?? 20;
      return {
        data: {
          content: filteredOrders.slice(page * size, (page + 1) * size),
          page,
          size,
          totalElements: filteredOrders.length,
          totalPages: Math.ceil(filteredOrders.length / size),
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    }
    throw new Error(`Unexpected API request: ${config.method} ${config.url}`);
  });
});

afterEach(() => {
  axiosClient.defaults.adapter = originalAdapter;
  vi.restoreAllMocks();
});

describe('Issue #143 purchase-order page views', () => {
  it('defaults to desktop Kanban and preserves the active filter across both views', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByRole('region', { name: '採購單八階段看板' })).toBeTruthy();
    expect(await screen.findByText('PO-20261022-000043')).toBeTruthy();
    const poNumberSearch = screen.getByRole('textbox', { name: '採購單號搜尋' }) as HTMLInputElement;
    const supplierCodeSearch = screen.getByRole('textbox', { name: '供應商代碼搜尋' }) as HTMLInputElement;
    const supplierNameSearch = screen.getByRole('textbox', { name: '供應商名稱搜尋' }) as HTMLInputElement;
    const materialCodeSearch = screen.getByRole('textbox', { name: '物料代碼搜尋' }) as HTMLInputElement;
    fireEvent.change(poNumberSearch, { target: { value: '000042' } });
    fireEvent.change(supplierCodeSearch, { target: { value: 'SUP-007' } });
    fireEvent.change(supplierNameSearch, { target: { value: '台灣' } });
    fireEvent.change(materialCodeSearch, { target: { value: 'MAT-010' } });
    await waitFor(() => expect(requestedQueries.some((query) => query.poNumber === '000042'
      && query.supplierCode === 'SUP-007' && query.supplierName === '台灣'
      && query.materialCode === 'MAT-010' && query.size === 100)).toBe(true));
    const supplierFilter = screen.getByRole('textbox', { name: '供應商編號篩選' }) as HTMLInputElement;
    fireEvent.change(supplierFilter, { target: { value: '7' } });
    await waitFor(() => expect(requestedQueries.some((query) => query.supplierId === 7 && query.size === 100)).toBe(true));
    await waitFor(() => expect(screen.queryByText('PO-20261022-000043')).toBeNull());

    await user.click(screen.getByText('列表'));
    expect(await screen.findByRole('table')).toBeTruthy();
    expect(await screen.findByText('PO-20261022-000042')).toBeTruthy();
    expect(screen.queryByText('PO-20261022-000043')).toBeNull();
    expect(poNumberSearch.value).toBe('000042');
    expect(supplierCodeSearch.value).toBe('SUP-007');
    expect(supplierNameSearch.value).toBe('台灣');
    expect(materialCodeSearch.value).toBe('MAT-010');
    expect(supplierFilter.value).toBe('7');
    await waitFor(() => expect(requestedQueries.some((query) => query.supplierId === 7 && query.poNumber === '000042'
      && query.supplierCode === 'SUP-007' && query.supplierName === '台灣'
      && query.materialCode === 'MAT-010' && query.size === 20)).toBe(true));

    await user.click(screen.getByText('看板'));
    expect(await screen.findByRole('region', { name: '採購單八階段看板' })).toBeTruthy();
    expect(screen.queryByText('PO-20261022-000043')).toBeNull();
    expect(poNumberSearch.value).toBe('000042');
    expect(supplierCodeSearch.value).toBe('SUP-007');
    expect(supplierNameSearch.value).toBe('台灣');
    expect(materialCodeSearch.value).toBe('MAT-010');
    expect(supplierFilter.value).toBe('7');
    expect(requestedQueries.some((query) => query.supplierId === 7 && query.poNumber === '000042'
      && query.supplierCode === 'SUP-007' && query.supplierName === '台灣'
      && query.materialCode === 'MAT-010' && query.size === 100)).toBe(true);
  });

  it('opens a query-addressable read-only inspector from Kanban and links to full detail', async () => {
    const user = userEvent.setup();
    renderPage(purchaserAuthValue);

    expect(await screen.findByRole('region', { name: '採購單八階段看板' })).toBeTruthy();
    await user.click(await screen.findByRole('button', { name: '快速查看採購單 PO-20261022-000042' }));

    const inspector = await screen.findByRole('dialog');
    expect(within(inspector).getByText('SUPPLYPILOT / 採購交接單')).toBeTruthy();
    expect(within(inspector).getByText('公司期望到貨日')).toBeTruthy();
    expect(within(inspector).getByText('2026/10/30')).toBeTruthy();
    expect(within(inspector).getByText('供應商承諾交期')).toBeTruthy();
    expect(within(inspector).getByText('尚未提供')).toBeTruthy();
    expect(within(inspector).getByRole('link', { name: '開啟完整採購單明細' }).getAttribute('href')).toBe('/purchase-orders/42');
    expect(screen.getByTestId('current-route').textContent).toBe('/purchase-orders?inspect=42');
  });

  it('opens a bookmarked inspector selection after loading the Backend PO', async () => {
    renderPage(purchaserAuthValue, ['/purchase-orders?inspect=42']);

    const inspector = await screen.findByRole('dialog');
    expect(await within(inspector).findByText('PO-20261022-000042')).toBeTruthy();
    expect(within(inspector).getByRole('link', { name: '開啟完整採購單明細' }).getAttribute('href')).toBe('/purchase-orders/42');
    expect(screen.getByTestId('current-route').textContent).toBe('/purchase-orders?inspect=42');
  });

  it('opens the mobile page in List mode while retaining backend purchase-order data', async () => {
    vi.spyOn(window, 'matchMedia').mockImplementation((query: string) => ({
      matches: query === '(max-width: 767px)',
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));

    renderPage();

    expect(await screen.findByRole('table')).toBeTruthy();
    expect(await screen.findByText('PO-20261022-000042')).toBeTruthy();
    expect(await screen.findByText('PO-20261022-000043')).toBeTruthy();
    expect(screen.queryByRole('region', { name: '採購單八階段看板' })).toBeNull();
    expect(screen.queryByRole('button', { name: '快速查看採購單 PO-20261022-000042' })).toBeNull();
    expect(requestedQueries.some((query) => query.page === 0 && query.size === 20)).toBe(true);
  });

  it('refreshes the current Kanban from backend after a successful draft create', async () => {
    backendPurchaseOrders = [otherSupplierPurchaseOrder];
    renderPage(purchaserAuthValue);

    expect(await screen.findByRole('region', { name: '採購單八階段看板' })).toBeTruthy();
    const supplierFilter = screen.getByRole('textbox', { name: '供應商編號篩選' }) as HTMLInputElement;
    fireEvent.change(supplierFilter, { target: { value: '7' } });
    await waitFor(() => expect(requestedQueries.some((query) => query.supplierId === 7 && query.size === 100)).toBe(true));
    await waitFor(() => expect(screen.queryByText('PO-20261022-000043')).toBeNull());

    await submitManualDraft();

    expect(await screen.findByText('採購單草稿 PO-20261022-000044 已建立')).toBeTruthy();
    expect(backendPurchaseOrders.some((item) => item.poNumber === 'PO-20261022-000044')).toBe(true);
    expect(await screen.findByRole('region', { name: '採購單八階段看板' })).toBeTruthy();
    expect(await screen.findByText('PO-20261022-000044')).toBeTruthy();
    expect(supplierFilter.value).toBe('7');
    await waitFor(() => expect(requestedQueries.filter((query) => query.supplierId === 7 && query.size === 100)).toHaveLength(2));
    expect(requestedQueries.some((query) => query.size === 20)).toBe(false);
  });

  it.each([
    ['403', 'forbidden', '採購單草稿建立失敗'],
    ['network error', 'network', '建立結果不明，已鎖定再次送出'],
  ] as const)('does not refresh the Kanban or report success after a %s draft-create failure', async (_label, failure, expectedMessage) => {
    purchaseOrderCreateFailure = failure;
    backendPurchaseOrders = [otherSupplierPurchaseOrder];
    renderPage(purchaserAuthValue);

    expect(await screen.findByRole('region', { name: '採購單八階段看板' })).toBeTruthy();
    const supplierFilter = screen.getByRole('textbox', { name: '供應商編號篩選' }) as HTMLInputElement;
    fireEvent.change(supplierFilter, { target: { value: '7' } });
    await waitFor(() => expect(requestedQueries.filter((query) => query.supplierId === 7 && query.size === 100)).toHaveLength(1));
    const boardQueryCount = requestedQueries.filter((query) => query.supplierId === 7 && query.size === 100).length;

    await submitManualDraft();

    expect(await screen.findByText(expectedMessage)).toBeTruthy();
    expect(screen.queryByText('採購單草稿 PO-20261022-000044 已建立')).toBeNull();
    expect(backendPurchaseOrders.some((item) => item.poNumber === 'PO-20261022-000044')).toBe(false);
    expect(supplierFilter.value).toBe('7');
    expect(screen.getByRole('region', { name: '採購單八階段看板' })).toBeTruthy();
    await new Promise((resolve) => window.setTimeout(resolve, 20));
    expect(requestedQueries.filter((query) => query.supplierId === 7 && query.size === 100)).toHaveLength(boardQueryCount);
    expect(requestedQueries.some((query) => query.size === 20)).toBe(false);
  });
});
