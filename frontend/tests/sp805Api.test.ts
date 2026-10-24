import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { approvePurchaseOrder, beginReceivingInspection, confirmPurchaseOrderPayment, listPurchaseOrders, markPurchaseOrderOrdered, recordReceivingInspection, rejectPurchaseOrder, revokePurchaseOrderPaymentConfirmation, updatePurchaseOrderDraft, updateSupplierPromisedDeliveryDate } from '../src/api/purchaseOrder';
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
  it('parses formal BigDecimal fields by DTO type while retaining safe integer IDs and pagination numbers', () => {
    expect(parseDecimalJson('{"id":42,"items":[{"quantity":999999999999999999999,"unitPrice":100,"total":123.4500},{"quantity":1.23E+8}],"page":0,"size":20,"totalElements":1,"totalPages":1}')).toEqual({
      id: 42,
      items: [
        { quantity: '999999999999999999999', unitPrice: '100', total: '123.4500' },
        { quantity: '1.23E+8' },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
  });

  it('covers PO, Receiving, and StockTransaction BigDecimal response fields', () => {
    const po = parseDecimalJson('{"id":42,"items":[{"id":1,"quantity":100,"unitPrice":123.4500,"total":1.23E+8}]}') as { items: Array<Record<string, unknown>> };
    const receiving = parseDecimalJson('{"id":42,"items":[{"id":1,"quantity":999999999999999999999}]}') as { items: Array<Record<string, unknown>> };
    const transaction = parseDecimalJson('{"id":42,"quantity":1.23E+8}') as Record<string, unknown>;
    expect(po.items[0]).toMatchObject({ quantity: '100', unitPrice: '123.4500', total: '1.23E+8' });
    expect(receiving.items[0].quantity).toBe('999999999999999999999');
    expect(transaction.quantity).toBe('1.23E+8');
  });

  it('preserves Inventory and SupplierMaterial BigDecimal fields while keeping IDs and integer DTO fields numeric', () => {
    const inventory = parseDecimalJson('{"materialId":42,"quantity":999999999999999.1234}') as Record<string, unknown>;
    const supplierMaterial = parseDecimalJson('{"id":8,"supplierId":2,"materialId":42,"unitPrice":999999999999999.1234,"moq":0.0001,"packageMultiple":1E-4,"leadTimeDays":7}') as Record<string, unknown>;

    expect(inventory).toEqual({ materialId: 42, quantity: '999999999999999.1234' });
    expect(supplierMaterial).toEqual({
      id: 8,
      supplierId: 2,
      materialId: 42,
      unitPrice: '999999999999999.1234',
      moq: '0.0001',
      packageMultiple: '1E-4',
      leadTimeDays: 7,
    });
    expect(typeof inventory.materialId).toBe('number');
    expect(typeof supplierMaterial.id).toBe('number');
    expect(typeof supplierMaterial.supplierId).toBe('number');
    expect(typeof supplierMaterial.materialId).toBe('number');
    expect(typeof supplierMaterial.leadTimeDays).toBe('number');
  });
  it('rejects unsafe ordinary integer fields instead of silently rounding them', () => {
    expect(() => parseDecimalJson('{"id":999999999999999999999,"quantity":100,"page":0,"size":20}'))
      .toThrow('Unsafe integer token for field "id": 999999999999999999999');
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

  it('updates or explicitly clears the supplier promise through its dedicated endpoint', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config); });
    await updateSupplierPromisedDeliveryDate(9, '2026-11-05');
    await updateSupplierPromisedDeliveryDate(9, null);
    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
      { method: 'put', url: '/purchase-orders/9/supplier-promised-delivery-date' },
      { method: 'put', url: '/purchase-orders/9/supplier-promised-delivery-date' },
    ]);
    expect(JSON.parse(requests[0].data as string)).toEqual({ supplierPromisedDeliveryDate: '2026-11-05' });
    expect(JSON.parse(requests[1].data as string)).toEqual({ supplierPromisedDeliveryDate: null });
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

  it('uses separate action endpoints for inspection, full receive, and manual payment confirmation', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config); });
    await beginReceivingInspection(11);
    await recordReceivingInspection(11, { allItemsReceived: false, allQuantitiesConfirmed: false,
      allQualityAccepted: false, note: '短收' });
    await receivePurchaseOrder(11);
    await confirmPurchaseOrderPayment(11);
    await revokePurchaseOrderPaymentConfirmation(11);
    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
      { method: 'post', url: '/purchase-orders/11/begin-receiving-inspection' },
      { method: 'put', url: '/purchase-orders/11/receiving-inspection' },
      { method: 'post', url: '/purchase-orders/11/receive' },
      { method: 'post', url: '/purchase-orders/11/confirm-payment' },
      { method: 'post', url: '/purchase-orders/11/revoke-payment-confirmation' },
    ]);
    expect(JSON.parse(requests[1].data as string)).toEqual({ allItemsReceived: false,
      allQuantitiesConfirmed: false, allQualityAccepted: false, note: '短收' });
    expect(requests[0].data).toBeUndefined();
    expect(requests[2].data).toBeUndefined();
    expect(requests[3].data).toBeUndefined();
    expect(requests[4].data).toBeUndefined();
  });


  it('preserves PO and StockTransaction decimal tokens through API response transforms', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      const data = config.url === '/purchase-orders'
        ? '{"content":[{"id":42,"items":[{"id":1,"materialId":7,"quantity":999999999999999.1234000,"unitPrice":0.0000001234500,"total":1.234500E+30}]}],"page":0,"size":20,"totalElements":1,"totalPages":1}'
        : config.url === '/stock-transactions'
          ? '{"content":[{"id":71,"materialId":7,"createdBy":3,"quantity":123456789012345.123456789}],"page":0,"size":20,"totalElements":1,"totalPages":1}'
          : '{"id":72,"materialId":7,"createdBy":3,"quantity":1.23E+8}';
      return response(config, data);
    });

    const purchaseOrders = await listPurchaseOrders({ page: 0, size: 20 });
    const stockTransactions = await listStockTransactions({ page: 0, size: 20 });
    const stockTransaction = await getStockTransaction(72);

    expect(purchaseOrders.content[0].items[0]).toMatchObject({
      quantity: '999999999999999.1234000',
      unitPrice: '0.0000001234500',
      total: '1.234500E+30',
    });
    expect(typeof purchaseOrders.content[0].id).toBe('number');
    expect(typeof purchaseOrders.page).toBe('number');
    expect(stockTransactions.content[0].quantity).toBe('123456789012345.123456789');
    expect(typeof stockTransactions.content[0].id).toBe('number');
    expect(stockTransaction.quantity).toBe('1.23E+8');
    expect(typeof stockTransaction.materialId).toBe('number');
    expect(requests.map(({ url }) => url)).toEqual([
      '/purchase-orders',
      '/stock-transactions',
      '/stock-transactions/72',
    ]);
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
