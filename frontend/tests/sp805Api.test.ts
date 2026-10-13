import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { approvePurchaseOrder, listPurchaseOrders, markPurchaseOrderOrdered, rejectPurchaseOrder, updatePurchaseOrderDraft } from '../src/api/purchaseOrder';
import { getReceivingPurchaseOrder, listReceivingPurchaseOrders, receivePurchaseOrder } from '../src/api/receiving';
import { getStockTransaction, listStockTransactions } from '../src/api/stockTransaction';
import { parseDecimalJson } from '../src/api/decimalJson';
import { can } from '../src/auth/permissions';

function response(config: InternalAxiosRequestConfig, data: unknown = {}): AxiosResponse { return { config, data, status: 200, statusText: 'OK', headers: {} }; }
beforeEach(() => { localStorage.clear(); });

describe('SP-805 API contracts', () => {
  it('keeps PO actions as separate fine-grained permissions', () => {
    expect(can('PROCUREMENT_STAFF', 'purchaseOrderRead')).toBe(true);
    expect(can('PROCUREMENT_STAFF', 'purchaseOrderCreate')).toBe(true);
    expect(can('PROCUREMENT_STAFF', 'purchaseOrderApproveReject')).toBe(false);
    expect(can('PROCUREMENT_MANAGER', 'purchaseOrderRead')).toBe(true);
    expect(can('PROCUREMENT_MANAGER', 'purchaseOrderCreate')).toBe(false);
    expect(can('PROCUREMENT_MANAGER', 'purchaseOrderApproveReject')).toBe(true);
    expect(can('WAREHOUSE_STAFF', 'purchaseOrderRead')).toBe(false);
    expect(can('WAREHOUSE_STAFF', 'receiving')).toBe(true);
    expect(can('ADMIN', 'purchaseOrderEdit')).toBe(true);
    expect(can('ADMIN', 'purchaseOrderOrder')).toBe(true);
  });
  it('parses BigDecimal tokens as exact strings while retaining integer IDs and pagination numbers', () => {
    expect(parseDecimalJson('{"id":71,"quantity":999999999999999.1234000,"unitPrice":1.2300,"page":0}')).toEqual({
      id: 71, quantity: '999999999999999.1234000', unitPrice: '1.2300', page: 0,
    });
  });
  it('uses backend PO pagination filters and preserves PATCH omission versus explicit null', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config); });
    await listPurchaseOrders({ status: 'ORDERED', supplierId: 2, materialId: 3, createdFrom: '2026-10-01', createdTo: '2026-10-02', expectedDeliveryFrom: '2026-10-03', expectedDeliveryTo: '2026-10-04', overdue: true, page: 2, size: 50, sort: 'orderedAt,desc' });
    await updatePurchaseOrderDraft(9, { supplierId: 2 });
    await updatePurchaseOrderDraft(9, { expectedDeliveryDate: null });
    expect(requests[0].params).toEqual({ status: 'ORDERED', supplierId: 2, materialId: 3, createdFrom: '2026-10-01', createdTo: '2026-10-02', expectedDeliveryFrom: '2026-10-03', expectedDeliveryTo: '2026-10-04', overdue: true, page: 2, size: 50, sort: 'orderedAt,desc' });
    expect(JSON.parse(requests[1].data as string)).toEqual({ supplierId: 2 });
    expect(JSON.parse(requests[2].data as string)).toEqual({ expectedDeliveryDate: null });
  });

  it('preserves BigDecimal strings and recommendation evidence when replacing a DRAFT item list', async () => {
    let request!: InternalAxiosRequestConfig;
    axiosClient.defaults.adapter = vi.fn(async (config) => { request = config; return response(config); });
    await updatePurchaseOrderDraft(9, { items: [{ materialId: 3, quantity: '999999999999999.1234000', unitPrice: '0.0000001234500', recommendationId: 91, supplierRecommendationResultId: 101, supplierOverrideReason: 'Capacity', quantityOverrideReason: 'Demand changed' }] });
    expect(JSON.parse(request.data as string)).toEqual({ items: [{ materialId: 3, quantity: '999999999999999.1234000', unitPrice: '0.0000001234500', recommendationId: 91, supplierRecommendationResultId: 101, supplierOverrideReason: 'Capacity', quantityOverrideReason: 'Demand changed' }] });
  });

  it('sends lifecycle action payloads exactly as defined', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config); });
    await approvePurchaseOrder(9); await rejectPurchaseOrder(9, 'Not in plan'); await markPurchaseOrderOrdered(9);
    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
      { method: 'post', url: '/purchase-orders/9/approve' }, { method: 'post', url: '/purchase-orders/9/reject' }, { method: 'post', url: '/purchase-orders/9/order' },
    ]);
    expect(requests[0].data).toBeUndefined();
    expect(JSON.parse(requests[1].data as string)).toEqual({ reason: 'Not in plan' });
    expect(requests[2].data).toBeUndefined();
  });

  it('keeps warehouse reads on receiving endpoints while POST receive stays on purchase-orders', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config); });
    await listReceivingPurchaseOrders({ status: 'ORDERED', materialId: 7, page: 0, size: 20, sort: 'id,asc' });
    await getReceivingPurchaseOrder(11); await receivePurchaseOrder(11);
    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
      { method: 'get', url: '/receiving/purchase-orders' }, { method: 'get', url: '/receiving/purchase-orders/11' }, { method: 'post', url: '/purchase-orders/11/receive' },
    ]);
    expect(requests[0].params.status).toBe('ORDERED');
    expect(requests[2].data).toBeUndefined();
  });

  it('supports the read-only stock transaction list and detail contract', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config); });
    await listStockTransactions({ materialId: 1, type: 'PURCHASE_RECEIVE', purchaseOrderId: 9, from: '2026-10-01T00:00:00Z', to: '2026-10-02T00:00:00Z', page: 1, size: 50, sort: 'quantity,desc' });
    await getStockTransaction(71);
    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([{ method: 'get', url: '/stock-transactions' }, { method: 'get', url: '/stock-transactions/71' }]);
    expect(requests[0].params.purchaseOrderId).toBe(9);
  });
});
