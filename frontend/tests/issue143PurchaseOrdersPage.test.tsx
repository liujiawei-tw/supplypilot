import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import zhTW from 'antd/locale/zh_TW';
import type { InternalAxiosRequestConfig } from 'axios';
import { MemoryRouter } from 'react-router-dom';
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
  items: [],
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

let requestedQueries: PurchaseOrderQuery[];

function renderPage() {
  return render(<ConfigProvider locale={zhTW}><AuthContext.Provider value={authValue}>
    <MemoryRouter><PurchaseOrdersPage /></MemoryRouter>
  </AuthContext.Provider></ConfigProvider>);
}

beforeEach(() => {
  requestedQueries = [];
  axiosClient.defaults.adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
    if (config.url === '/users/assignable') {
      return { data: [], status: 200, statusText: 'OK', headers: {}, config };
    }
    if (config.url === '/purchase-orders') {
      const query = (config.params ?? {}) as PurchaseOrderQuery;
      requestedQueries.push({ ...query });
      const filteredOrders = [purchaseOrder, otherSupplierPurchaseOrder]
        .filter((item) => query.supplierId === undefined || item.supplierId === query.supplierId);
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
    const supplierFilter = screen.getByRole('textbox', { name: '供應商編號篩選' }) as HTMLInputElement;
    fireEvent.change(supplierFilter, { target: { value: '7' } });
    await waitFor(() => expect(requestedQueries.some((query) => query.supplierId === 7 && query.size === 100)).toBe(true));
    await waitFor(() => expect(screen.queryByText('PO-20261022-000043')).toBeNull());

    await user.click(screen.getByText('列表'));
    expect(await screen.findByRole('table')).toBeTruthy();
    expect(await screen.findByText('PO-20261022-000042')).toBeTruthy();
    expect(screen.queryByText('PO-20261022-000043')).toBeNull();
    expect(supplierFilter.value).toBe('7');
    await waitFor(() => expect(requestedQueries.some((query) => query.supplierId === 7 && query.size === 20)).toBe(true));

    await user.click(screen.getByText('看板'));
    expect(await screen.findByRole('region', { name: '採購單八階段看板' })).toBeTruthy();
    expect(screen.queryByText('PO-20261022-000043')).toBeNull();
    expect(supplierFilter.value).toBe('7');
    expect(requestedQueries.some((query) => query.supplierId === 7 && query.size === 100)).toBe(true);
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
    expect(requestedQueries.some((query) => query.page === 0 && query.size === 20)).toBe(true);
  });
});
