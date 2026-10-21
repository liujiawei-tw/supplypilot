import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import App from '../src/App';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import { AuthContext, type AuthState } from '../src/auth/authContextValue';
import DashboardPageForTest from '../src/pages/Dashboard/DashboardPage';
import { router } from '../src/router/routes';
import type { AuthenticatedUser, UserRole } from '../src/types/api';

const identities: Record<UserRole, AuthenticatedUser> = {
  PRODUCTION_PLANNER: { id: 1, username: 'planner', displayName: '規劃員', role: 'PRODUCTION_PLANNER' },
  PROCUREMENT_STAFF: { id: 2, username: 'buyer', displayName: '採購員', role: 'PROCUREMENT_STAFF' },
  PROCUREMENT_MANAGER: { id: 3, username: 'manager', displayName: '採購經理', role: 'PROCUREMENT_MANAGER' },
  WAREHOUSE_STAFF: { id: 4, username: 'warehouse', displayName: '倉管', role: 'WAREHOUSE_STAFF' },
  ADMIN: { id: 5, username: 'admin', displayName: '管理員', role: 'ADMIN' },
};

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { config, data, status, statusText: String(status), headers: {} };
}

function page<T>(content: T[], totalElements = content.length) {
  return { content, page: 0, size: 5, totalElements, totalPages: totalElements === 0 ? 0 : 1 };
}

function purchaseOrder(status: 'DRAFT' | 'APPROVED' | 'ORDERED', poNumber = `PO-${status}`) {
  return {
    id: 40 + status.length,
    poNumber,
    supplierId: 7,
    supplierCode: 'SUP-007',
    supplierName: '供應商 A',
    status,
    expectedDeliveryDate: '2026-10-20',
    items: [{ id: 1, materialId: 8, materialCode: 'MAT-008', materialName: '鋼材', quantity: '250.0000', unit: 'KG', unitPrice: '99.0000', total: '24750.0000', recommendationId: null, supplierRecommendationResultId: null, supplierOverride: false, quantityOverride: false, supplierOverrideReason: null, quantityOverrideReason: null }],
    createdBy: 2,
    createdByDisplayName: '採購員',
    createdAt: '2026-10-01T00:00:00Z',
    approvedBy: null,
    approvedByDisplayName: null,
    approvedAt: null,
    rejectReason: null,
    rejectedBy: null,
    rejectedByDisplayName: null,
    rejectedAt: null,
    orderedBy: null,
    orderedByDisplayName: null,
    orderedAt: null,
    receivedBy: null,
    receivedByDisplayName: null,
    receivedAt: null,
    isOverdue: false,
    updatedAt: '2026-10-01T00:00:00Z',
  };
}

function receivingOrder(overdue = true) {
  return {
    id: 51,
    poNumber: 'PO-000051',
    supplierId: 7,
    supplierCode: 'SUP-007',
    supplierName: '倉管可見供應商',
    status: 'ORDERED',
    expectedDeliveryDate: '2026-10-10',
    isOverdue: overdue,
    items: [{ id: 1, materialId: 8, materialCode: 'MAT-008', materialName: '鋼材', quantity: '250.0000', unit: 'KG' }],
    orderedAt: '2026-10-01T00:00:00Z',
    receivedBy: null,
    receivedByDisplayName: null,
    receivedAt: null,
  };
}

function params(config: InternalAxiosRequestConfig) {
  return config.params as Record<string, unknown>;
}

async function openDashboard(_role: UserRole) {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
  await router.navigate('/dashboard');
  render(<App />);
  await screen.findByText('工作總覽');
}

function directAuth(role: UserRole): AuthState {
  return {
    user: identities[role],
    isAuthenticated: true,
    isLoading: false,
    verificationError: false,
    retryVerification: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
  };
}

beforeEach(() => { localStorage.clear(); });

describe('SP-907 role-adaptive dashboard API access', () => {
  it('shows procurement staff DRAFT, APPROVED and ORDERED data from three status-filtered requests', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.PROCUREMENT_STAFF);
      if (config.url === '/purchase-orders') return response(config, page([purchaseOrder(params(config).status as 'DRAFT' | 'APPROVED' | 'ORDERED')]));
      throw new Error(`Unexpected request ${config.method} ${config.url}`);
    });

    await openDashboard('PROCUREMENT_STAFF');
    expect(await screen.findByText('PO-DRAFT')).toBeTruthy();
    expect(await screen.findByText('PO-APPROVED')).toBeTruthy();
    expect(await screen.findByText('PO-ORDERED')).toBeTruthy();
    const poRequests = requests.filter((request) => request.url === '/purchase-orders');
    expect(poRequests.map((request) => params(request).status).sort()).toEqual(['APPROVED', 'DRAFT', 'ORDERED']);
    expect(poRequests.every((request) => params(request).page === 0 && params(request).size === 5 && params(request).sort === 'id,desc')).toBe(true);
    expect(requests.some((request) => request.url === '/inventory')).toBe(false);
    expect(requests.some((request) => request.url?.startsWith('/receiving/'))).toBe(false);
    expect(screen.queryByRole('link', { name: /採購建議/ })).toBeNull();
  });

  it('shows only DRAFT approval work for managers and distinguishes a successful empty result', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.PROCUREMENT_MANAGER);
      if (config.url === '/purchase-orders') return response(config, page([]));
      throw new Error(`Unexpected request ${config.method} ${config.url}`);
    });

    await openDashboard('PROCUREMENT_MANAGER');
    expect(await screen.findByText('目前沒有等待核准的採購草稿。')).toBeTruthy();
    expect(screen.getByText('目前 0 筆')).toBeTruthy();
    expect(requests.filter((request) => request.url === '/purchase-orders')).toHaveLength(1);
    expect(params(requests.find((request) => request.url === '/purchase-orders')!).status).toBe('DRAFT');
    expect(requests.some((request) => request.url === '/inventory')).toBe(false);
    expect(requests.some((request) => request.url?.startsWith('/receiving/'))).toBe(false);
    expect(screen.queryByText(/目前沒有待辦資料/)).toBeNull();
  });

  it('uses the restricted Receiving API for warehouse data and never renders price fields', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.WAREHOUSE_STAFF);
      if (config.url === '/receiving/purchase-orders') return response(config, page([receivingOrder()]));
      throw new Error(`Unexpected request ${config.method} ${config.url}`);
    });

    await openDashboard('WAREHOUSE_STAFF');
    expect(await screen.findByText('PO-000051')).toBeTruthy();
    expect(screen.getByText('已逾期')).toBeTruthy();
    expect(screen.getByText('預計交期：2026-10-10')).toBeTruthy();
    expect(requests.filter((request) => request.url === '/receiving/purchase-orders')).toHaveLength(1);
    const receivingRequest = requests.find((request) => request.url === '/receiving/purchase-orders')!;
    expect(params(receivingRequest)).toMatchObject({ status: 'ORDERED', page: 0, size: 5, sort: 'expectedDeliveryDate,asc' });
    expect(requests.some((request) => request.url === '/purchase-orders')).toBe(false);
    expect(screen.queryByText(/99\.0000/)).toBeNull();
    expect(screen.queryByText(/24750\.0000/)).toBeNull();
    expect(await screen.findByRole('link', { name: '查看已收貨紀錄' })).toBeTruthy();
  });

  it('shows admin purchasing and receiving work while using only bounded, authorized reads', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.ADMIN);
      if (config.url === '/purchase-orders') return response(config, page([purchaseOrder(params(config).status as 'DRAFT' | 'APPROVED')]));
      if (config.url === '/receiving/purchase-orders') return response(config, page([receivingOrder(false)]));
      throw new Error(`Unexpected request ${config.method} ${config.url}`);
    });

    await openDashboard('ADMIN');
    expect(await screen.findByText('待處理採購單')).toBeTruthy();
    expect(await screen.findByText('已核准待下單')).toBeTruthy();
    expect(await screen.findByText('待收貨採購單')).toBeTruthy();
    expect(screen.queryByText('進行中的需求')).toBeNull();
    expect(screen.queryByRole('link', { name: /採購建議|需求管理/ })).toBeNull();
    expect(requests.filter((request) => request.url === '/purchase-orders').map((request) => params(request).status).sort()).toEqual(['APPROVED', 'DRAFT']);
    expect(requests.filter((request) => request.url === '/receiving/purchase-orders')).toHaveLength(1);
    expect(requests.some((request) => request.url === '/inventory')).toBe(false);
  });

  it('does not load Demand or recommendation work queues in the V2 dashboard', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.PRODUCTION_PLANNER);
      if (config.url === '/demands') throw new Error('V2 dashboard must not query Demand');
      throw new Error(`Unexpected request ${config.method} ${config.url}`);
    });

    await openDashboard('PRODUCTION_PLANNER');
    await screen.findByText('查看庫存');
    expect(requests.some((request) => request.url === '/demands')).toBe(false);
    expect(requests.some((request) => request.url === '/inventory')).toBe(false);
    expect(requests.some((request) => request.url === '/recommendations')).toBe(false);
  });

  it('keeps successful staff panels visible after another query fails and retries only that query', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let draftCalls = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.PROCUREMENT_STAFF);
      if (config.url === '/purchase-orders') {
        const status = params(config).status;
        if (status === 'DRAFT' && ++draftCalls === 1) throw new AxiosError('Server error', 'ERR_BAD_RESPONSE', config, undefined, response(config, { code: 'INTERNAL_SERVER_ERROR', message: 'private backend detail', traceId: 'dash-trace', timestamp: '', path: config.url }, 500));
        return response(config, page([purchaseOrder(status as 'DRAFT' | 'APPROVED' | 'ORDERED')]));
      }
      throw new Error(`Unexpected request ${config.method} ${config.url}`);
    });

    await openDashboard('PROCUREMENT_STAFF');
    expect(await screen.findByText('採購草稿查詢失敗')).toBeTruthy();
    expect(await screen.findByText('PO-APPROVED')).toBeTruthy();
    expect(await screen.findByText('PO-ORDERED')).toBeTruthy();
    expect(screen.getByText('部分工作資料無法載入')).toBeTruthy();
    expect(screen.queryByText('private backend detail')).toBeNull();

    const draftPanel = screen.getByRole('region', { name: '採購草稿' });
    await userEvent.setup().click(within(draftPanel).getByRole('button', { name: '重新查詢' }));
    expect(await within(draftPanel).findByText('PO-DRAFT')).toBeTruthy();
    expect(requests.filter((request) => request.url === '/purchase-orders' && params(request).status === 'DRAFT')).toHaveLength(2);
    expect(requests.filter((request) => request.url === '/purchase-orders' && params(request).status === 'APPROVED')).toHaveLength(1);
    expect(requests.filter((request) => request.url === '/purchase-orders' && params(request).status === 'ORDERED')).toHaveLength(1);
  });

  it('shows a sanitized forbidden state for HTTP 403 without treating it as empty or retrying', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.PROCUREMENT_STAFF);
      if (config.url === '/purchase-orders' && params(config).status === 'DRAFT') {
        throw new AxiosError('Forbidden', 'ERR_BAD_REQUEST', config, undefined, response(config, {
          code: 'ACCESS_DENIED',
          message: 'Sensitive backend authorization details',
          traceId: 'dash-403-00001',
          timestamp: '2026-10-17T08:00:00Z',
          path: config.url,
        }, 403));
      }
      if (config.url === '/purchase-orders') {
        return response(config, page([purchaseOrder(params(config).status as 'APPROVED' | 'ORDERED')]));
      }
      throw new Error(`Unexpected request ${config.method} ${config.url}`);
    });

    await openDashboard('PROCUREMENT_STAFF');
    const forbiddenPanel = await screen.findByRole('region', { name: '採購草稿' });
    expect(await within(forbiddenPanel).findByText('此工作資料無權限顯示')).toBeTruthy();
    expect(within(forbiddenPanel).queryByText(/目前 0 筆/)).toBeNull();
    expect(within(forbiddenPanel).queryByText(/目前沒有 .*採購草稿/)).toBeNull();
    expect(within(forbiddenPanel).queryByText('Sensitive backend authorization details')).toBeNull();
    expect(await screen.findByText('部分工作資料無法載入')).toBeTruthy();
    expect(await screen.findByText('PO-APPROVED')).toBeTruthy();
    expect(await screen.findByText('PO-ORDERED')).toBeTruthy();

    await waitFor(() => expect(requests.filter((request) => request.url === '/purchase-orders')).toHaveLength(3));
    expect(requests.filter((request) => request.url === '/purchase-orders').map((request) => params(request).status).sort()).toEqual(['APPROVED', 'DRAFT', 'ORDERED']);
    expect(requests.some((request) => request.url === '/inventory')).toBe(false);
    expect(requests.some((request) => request.url?.startsWith('/receiving/'))).toBe(false);
    expect(requests.some((request) => request.url === '/demands')).toBe(false);
  });

  it('ignores an old role response after the authenticated role changes', async () => {
    let finishOldRequest!: () => void;
    let draftCalls = 0;
    axiosClient.defaults.adapter = vi.fn((config) => {
      if (config.url !== '/purchase-orders') return Promise.reject(new Error(`Unexpected request ${config.url}`));
      if (params(config).status === 'DRAFT' && ++draftCalls === 1) {
        return new Promise((resolve) => { finishOldRequest = () => resolve(response(config, page([purchaseOrder('DRAFT', 'STALE-ROLE-PO')]))); });
      }
      return Promise.resolve(response(config, page([purchaseOrder('DRAFT', 'CURRENT-MANAGER-PO')])));
    });

    const renderForRole = (role: UserRole) => <AuthContext.Provider value={directAuth(role)}><MemoryRouter><DashboardPageForTest /></MemoryRouter></AuthContext.Provider>;
    const { rerender } = render(renderForRole('PROCUREMENT_STAFF'));
    await screen.findByRole('heading', { name: '工作總覽' });
    rerender(renderForRole('PROCUREMENT_MANAGER'));
    expect(await screen.findByText('CURRENT-MANAGER-PO')).toBeTruthy();
    await act(async () => { finishOldRequest(); });
    await waitFor(() => expect(screen.queryByText('STALE-ROLE-PO')).toBeNull());
    expect(document.querySelector('.dashboard-header')?.textContent).toContain('採購經理');
  });

  it('opens the received history route with an explicit RECEIVED query', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, identities.WAREHOUSE_STAFF);
      if (config.url === '/receiving/purchase-orders') return response(config, page([]));
      throw new Error(`Unexpected request ${config.method} ${config.url}`);
    });

    await openDashboard('WAREHOUSE_STAFF');
    await userEvent.setup().click(screen.getByRole('link', { name: '查看已收貨紀錄' }));
    await screen.findByText('收貨作業');
    await waitFor(() => expect(requests.some((request) => request.url === '/receiving/purchase-orders' && params(request).status === 'RECEIVED')).toBe(true));
    expect(requests.some((request) => request.url === '/receiving/purchase-orders' && params(request).status === 'RECEIVED')).toBe(true);
  });
});
