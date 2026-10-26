import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../src/App';
import { toApiError } from '../src/api/apiError';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import ErrorState from '../src/components/ErrorState';
import { router } from '../src/router/routes';
import type { AuthenticatedUser } from '../src/types/api';

const admin: AuthenticatedUser = { id: 1, username: 'admin', displayName: 'Admin', role: 'ADMIN' };
const warehouse: AuthenticatedUser = { id: 4, username: 'warehouse', displayName: 'Warehouse', role: 'WAREHOUSE_STAFF' };

const draftPo = {
  id: 42, poNumber: 'PO-000042', supplierId: 7, supplierCode: 'SUP-007', supplierName: 'Supplier', status: 'DRAFT',
  orderedProgress: null,
  expectedDeliveryDate: '2026-10-20', supplierPromisedDeliveryDate: null, items: [{ id: 1, materialId: 8, materialCode: 'MAT-008', materialName: 'Steel', quantity: '50', unit: 'KG', unitPrice: '10', total: '500', recommendationId: null, supplierRecommendationResultId: null, supplierOverride: false, quantityOverride: false, supplierOverrideReason: null, quantityOverrideReason: null }],
  receivingInspection: { allItemsReceived: false, allQuantitiesConfirmed: false, allQualityAccepted: false, note: null, inspectedBy: null, inspectedByDisplayName: null, inspectedByUsername: null, inspectedAt: null },
  createdBy: 2, createdByDisplayName: 'Buyer', createdAt: '2026-10-01T00:00:00Z', approvedBy: null, approvedByDisplayName: null, approvedAt: null,
  rejectReason: null, rejectedBy: null, rejectedByDisplayName: null, rejectedAt: null, orderedBy: null, orderedByDisplayName: null, orderedAt: null,
  receivedBy: null, receivedByDisplayName: null, receivedAt: null, paymentConfirmed: false, paymentConfirmedBy: null,
  paymentConfirmedByDisplayName: null, paymentConfirmedByUsername: null, paymentConfirmedAt: null,
  isOverdue: false, updatedAt: '2026-10-01T00:00:00Z',
};
const approvedPo = { ...draftPo, status: 'APPROVED', approvedBy: 1, approvedByDisplayName: 'Admin', approvedAt: '2026-10-02T00:00:00Z' };
const receivingPo = {
  id: 42, poNumber: 'PO-000042', supplierId: 7, supplierCode: 'SUP-007', supplierName: 'Supplier', status: 'ORDERED', orderedProgress: 'RECEIVING_INSPECTION', expectedDeliveryDate: '2026-10-20', isOverdue: false,
  receivingInspection: { allItemsReceived: true, allQuantitiesConfirmed: true, allQualityAccepted: true, note: null, inspectedBy: 1, inspectedByDisplayName: 'Admin', inspectedByUsername: 'admin', inspectedAt: '2026-10-01T00:00:00Z' },
  items: [{ id: 1, materialId: 8, materialCode: 'MAT-008', materialName: 'Steel', quantity: '50', unit: 'KG' }], orderedAt: '2026-10-02T00:00:00Z',
  receivedBy: null, receivedByDisplayName: null, receivedAt: null,
};
function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { config, data, status, statusText: String(status), headers: {} };
}

function failure(status: number, code: string, message: string, traceId = ''): AxiosError {
  const config = { headers: {} } as InternalAxiosRequestConfig;
  return new AxiosError(message, 'ERR_BAD_RESPONSE', config, undefined,
    response(config, { code, message, traceId, timestamp: '', path: '/test' }, status));
}

async function open(path: string) {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
  await router.navigate(path);
  render(<App />);
}

beforeEach(() => localStorage.clear());

describe('SP-807 reusable business error alert', () => {
  it.each([
    [400, 'VALIDATION_FAILED', '輸入資料無法通過驗證'],
    [403, 'ACCESS_DENIED', '無權限'],
    [404, 'PURCHASE_ORDER_NOT_FOUND', '找不到資料'],
    [409, 'CONCURRENT_MODIFICATION', '資料已被其他操作更新'],
    [503, 'AI_PROVIDER_UNAVAILABLE', 'AI 服務暫時無法使用'],
    [503, 'AI_QUERY_FAILED', 'AI 資料查詢失敗'],
  ] as const)('classifies HTTP %s %s for a reusable user-facing alert', (status, code, expectedTitle) => {
    render(<ErrorState error={toApiError(failure(status, code, 'Backend message', 'trace-safe'))} />);
    expect(screen.getByText(expectedTitle)).toBeTruthy();
    expect(screen.getByText(code)).toBeTruthy();
  });

  it('sanitizes an HTTP 500 message while preserving a real traceId and a safe formal code', () => {
    const raw = failure(500, 'SQL_GRAMMAR_EXCEPTION', 'SQLException at C:\\secret\\schema.sql password=hunter2', 'trace-500-real');
    const normalized = toApiError(raw);
    expect(normalized.code).toBe('INTERNAL_SERVER_ERROR');
    expect(normalized.message).not.toContain('SQLException');
    render(<ErrorState error={normalized} />);
    expect(screen.getAllByText(/Trace ID/).length).toBeGreaterThan(0);
    expect(screen.getByText('trace-500-real')).toBeTruthy();
    expect(screen.queryByText(/hunter2/)).toBeNull();
  });

  it('renders validation field guidance and keeps a non-HTTP mutation error distinct from HTTP 500', () => {
    const config = { headers: {} } as InternalAxiosRequestConfig;
    const validation = new AxiosError('Validation', 'ERR_BAD_REQUEST', config, undefined,
      response(config, { code: 'VALIDATION_FAILED', message: 'Request validation failed', traceId: 'trace-400', fieldErrors: [{ field: 'quantity', message: 'must be greater than 0' }] }, 400));
    const normalizedValidation = toApiError(validation);
    render(<ErrorState error={normalizedValidation} />);
    expect(screen.getByText('quantity:')).toBeTruthy();
    expect(screen.getByText('must be greater than 0')).toBeTruthy();

    const network = toApiError(new AxiosError('Network Error', 'ERR_NETWORK', config));
    expect(network.code).toBe('NETWORK_ERROR');
    expect(network.httpStatus).toBeUndefined();
    expect(network.message).not.toContain('伺服器發生暫時性問題');
  });

  it('does not invent a traceId and does not offer an unconditional retry for 403', () => {
    const retry = vi.fn();
    render(<ErrorState error={toApiError(failure(403, 'ACCESS_DENIED', 'No permission'))} onRetry={retry} />);
    expect(screen.queryByText(/Trace ID/)).toBeNull();
    expect(screen.queryByRole('button', { name: '重新載入' })).toBeNull();
  });

  it('uses GET retry for a list failure without triggering any write request', async () => {
    const reads: string[] = [];
    const writes: string[] = [];
    render(<ErrorState
      error={toApiError(failure(500, 'INTERNAL_SERVER_ERROR', 'private SQL detail', 'trace-list'))}
      onRetry={() => reads.push('GET /purchase-orders')}
    />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '重新載入' }));
    expect(reads).toEqual(['GET /purchase-orders']);
    expect(writes).toHaveLength(0);
  });
});

describe('SP-807 purchase order verification boundary', () => {
  it('locks Approve and Edit DRAFT after 409, stays locked after failed GET, then requires a fresh review of latest state', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let detailReads = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, admin);
      if (config.url === '/purchase-orders/42' && config.method === 'get') {
        detailReads += 1;
        if (detailReads === 2) throw new AxiosError('Network error', 'ERR_NETWORK', config);
        return response(config, draftPo);
      }
      if (config.url === '/purchase-orders/42/approve') {
        throw new AxiosError('Conflict', 'ERR_BAD_RESPONSE', config, undefined,
          response(config, { code: 'CONCURRENT_MODIFICATION', message: 'stale version', traceId: 'trace-po-409' }, 409));
      }
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/purchase-orders/42');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /核\s*准/ }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '確認核准' }));
    expect(await screen.findByText('狀態可能已變更，操作已鎖定')).toBeTruthy();
    expect(screen.getByRole('button', { name: '編輯草稿' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('button', { name: /核\s*准/ }).hasAttribute('disabled')).toBe(true);

    await user.click(screen.getByRole('button', { name: '重新查詢採購單' }));
    expect(await screen.findByText('結果尚未確認，操作維持鎖定')).toBeTruthy();
    expect(screen.getByRole('button', { name: '編輯草稿' }).hasAttribute('disabled')).toBe(true);
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/approve')).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: '重新查詢採購單' }));
    expect(await screen.findByText(/已取得後端最新資料：目前狀態為「草稿」/)).toBeTruthy();
    expect(screen.getByRole('button', { name: '編輯草稿' }).hasAttribute('disabled')).toBe(false);
    expect(screen.getByRole('button', { name: /核\s*准/ }).hasAttribute('disabled')).toBe(false);
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/approve')).toHaveLength(1);
  }, 30_000);

  it('does not resend Mark Ordered after 409 and makes the user reopen confirmation after a successful GET', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, admin);
      if (config.url === '/purchase-orders/42' && config.method === 'get') return response(config, approvedPo);
      if (config.url === '/purchase-orders/42/order') throw new AxiosError('Conflict', 'ERR_BAD_RESPONSE', config, undefined,
        response(config, { code: 'CONCURRENT_MODIFICATION', message: 'stale version', traceId: 'trace-order' }, 409));
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/purchase-orders/42');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '標記為已下單' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '確認下單' }));
    expect(await screen.findByText('狀態可能已變更，操作已鎖定')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/order')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: '重新查詢採購單' }));
    expect(await screen.findByText(/已取得後端最新資料：目前狀態為「已核准」/)).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/order')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: '標記為已下單' }));
    expect(within(screen.getByRole('dialog')).getByText(/核准的預計交期/)).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/order')).toHaveLength(1);
  }, 30_000);
});

describe('SP-807 receiving and import verification boundaries', () => {
  it('presents the receiving route as a read-only status and inspection work queue', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, warehouse);
      if (config.url === '/receiving/purchase-orders') return response(config, {
        content: [receivingPo], page: 0, size: 20, totalElements: 1, totalPages: 1,
      });
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });

    await open('/receiving/purchase-orders');
    expect(await screen.findByRole('heading', { name: '收貨作業' })).toBeTruthy();
    const container = document.querySelector<HTMLElement>('.receiving-queue__table');
    expect(container).toBeTruthy();
    const table = await within(container!).findByRole('table');
    const row = await within(table).findByRole('row', { name: /PO-000042/ });
    expect(within(row).getByText('已下單')).toBeTruthy();
    expect(within(row).getByText('到貨驗收中')).toBeTruthy();
    expect(within(row).getByRole('link', { name: '繼續驗收' })).toBeTruthy();
    expect(requests.every(({ method }) => method === 'get')).toBe(true);
    expect(requests.filter(({ url }) => url === '/receiving/purchase-orders')).toHaveLength(1);
  });
  it.each([
    ['RECEIVED', '此採購單已完成收貨。收貨專用查詢已確認狀態，本頁不會再送出收貨。'],
    ['ORDERED', '目前仍為待收貨（已下單），先前操作結果需由使用者重新確認。系統沒有自動重送；如要再操作，必須重新開啟確認流程。'],
  ] as const)('verifies DUPLICATE_RECEIVING through restricted GET and reports %s without a resend', async (verifiedStatus, expectedMessage) => {
    const requests: InternalAxiosRequestConfig[] = [];
    let reads = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, warehouse);
      if (config.url === '/receiving/purchase-orders/42') {
        reads += 1;
        return response(config, reads === 1 ? receivingPo : {
          ...receivingPo, status: verifiedStatus, ...(verifiedStatus === 'RECEIVED' ? { receivedBy: 4, receivedByDisplayName: 'Warehouse', receivedAt: '2026-10-13T01:00:00Z' } : {}),
        });
      }
      if (config.url === '/purchase-orders/42/receive') throw new AxiosError('Conflict', 'ERR_BAD_RESPONSE', config, undefined,
        response(config, { code: 'DUPLICATE_RECEIVING', message: 'Already received', traceId: 'trace-receive' }, 409));
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/receiving/purchase-orders/42');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '確認整筆收貨' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '確認整筆收貨' }));
    expect(await screen.findByText('收貨結果需要查核，已鎖定再次提交')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '重新查詢收貨狀態' }));
    expect(await screen.findByText(expectedMessage)).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/purchase-orders/42/receive')).toHaveLength(1);
    expect(requests.filter(({ url }) => url === '/receiving/purchase-orders/42')).toHaveLength(2);
    expect(requests.some(({ url, method }) => url === '/purchase-orders/42' && method === 'get')).toBe(false);
    expect(screen.queryByText('單價')).toBeNull();
    expect(screen.queryByText('採購建議依據')).toBeNull();
  }, 30_000);

  it('keeps CONFIRM_UNKNOWN locked after a server-side unknown result and does not invent a batch status GET', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, warehouse);
      if (config.url === '/inventory-imports/validate') return response(config, { batchId: 807, status: 'VALIDATED', totalRows: 1, validRows: 1, errorRows: 0, errors: [] });
      if (config.url === '/inventory-imports/807/confirm') throw new AxiosError('Server error', 'ERR_BAD_RESPONSE', config, undefined,
        response(config, { code: 'INTERNAL_SERVER_ERROR', message: 'SQL path /secret', traceId: 'trace-import' }, 500));
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/inventory/import');
    const user = userEvent.setup();
    const file = new File(['material_code,quantity,unit\nMAT-001,10,KG\n'], 'inventory.csv', { type: 'text/csv' });
    await user.upload(await screen.findByLabelText('庫存 CSV 檔案'), file);
    await user.click(screen.getByRole('button', { name: '驗證並預覽' }));
    await screen.findByText('後端驗證通過，尚未匯入');
    await user.click(screen.getByRole('button', { name: '確認匯入' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '確認匯入' }));
    expect(await screen.findByText('確認結果未知')).toBeTruthy();
    expect(screen.getByRole('button', { name: '確認匯入' }).hasAttribute('disabled')).toBe(true);
    expect(screen.queryByText(/SQL path/)).toBeNull();
    expect(requests.filter(({ url }) => url === '/inventory-imports/807/confirm')).toHaveLength(1);
    expect(requests.some(({ url, method }) => method === 'get' && url?.includes('inventory-imports/807'))).toBe(false);
  }, 30_000);
});
