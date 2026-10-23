// Development-phase coverage for the provisional #142 contract used by Issue #143.
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { InternalAxiosRequestConfig } from 'axios';
import axiosClient from '../src/api/axiosClient';
import { purchaseOrderStageGateway, type PurchaseOrderStageTransition } from '../src/api/purchaseOrderStage';

const originalAdapter = axiosClient.defaults.adapter;

afterEach(() => {
  axiosClient.defaults.adapter = originalAdapter;
  vi.restoreAllMocks();
});

describe('Issue #143 provisional Stage API contract', () => {
  it.each<[PurchaseOrderStageTransition, string]>([
    ['BEGIN_SOURCING', '/purchase-orders/42/begin-sourcing'],
    ['SUBMIT_FOR_APPROVAL', '/purchase-orders/42/submit-for-approval'],
    ['APPROVE', '/purchase-orders/42/approve'],
    ['ORDER', '/purchase-orders/42/order'],
    ['BEGIN_RECEIVING_INSPECTION', '/purchase-orders/42/begin-receiving-inspection'],
    ['CONFIRM_PAYMENT', '/purchase-orders/42/confirm-payment'],
    ['REVOKE_PAYMENT_CONFIRMATION', '/purchase-orders/42/revoke-payment-confirmation'],
  ])('calls the real %s command without a request body, then reads the authoritative PO', async (transition, url) => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({
      data: { id: 42 },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }));
    axiosClient.defaults.adapter = adapter;

    await purchaseOrderStageGateway.executeTransition(42, transition);

    expect(adapter).toHaveBeenCalledTimes(2);
    expect(adapter.mock.calls[0][0]).toMatchObject({ method: 'post', url, data: undefined });
    expect(adapter.mock.calls[1][0]).toMatchObject({ method: 'get', url: '/purchase-orders/42' });
  });

  it('cannot invoke the receiving mutation before the detail inspection form', async () => {
    const adapter = vi.fn();
    axiosClient.defaults.adapter = adapter;

    await expect(purchaseOrderStageGateway.executeTransition(42, 'RECEIVE'))
      .rejects.toThrow('請先在驗收詳情完成人工驗收表單。');
    expect(adapter).not.toHaveBeenCalled();
  });

  it('withholds the board when the first page does not contain its declared rows', async () => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({
      data: {
        content: [{ id: 42 }],
        page: 0,
        size: 100,
        totalElements: 101,
        totalPages: 2,
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }));
    axiosClient.defaults.adapter = adapter;

    const result = await purchaseOrderStageGateway.readCompleteBoard({ status: 'ORDERED', overdue: true });

    expect(result.kind).toBe('incomplete');
    expect(adapter).toHaveBeenCalledTimes(1);
    expect(adapter.mock.calls[0][0].params).toMatchObject({
      status: 'ORDERED',
      overdue: true,
      page: 0,
      size: 100,
      sort: 'id,asc',
    });
  });

  it('reads every filtered page before declaring a complete board snapshot', async () => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      const params = config.params as Record<string, unknown>;
      const page = Number(params.page);
      const content = page === 0
        ? Array.from({ length: 100 }, (_, index) => ({ id: index + 1 }))
        : [{ id: 101 }];
      return {
        data: { content, page, size: 100, totalElements: 101, totalPages: 2 },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    });
    axiosClient.defaults.adapter = adapter;

    const result = await purchaseOrderStageGateway.readCompleteBoard({
      status: 'ORDERED',
      supplierId: 7,
      overdue: false,
      page: 8,
      size: 20,
      sort: 'createdAt,desc',
    });

    expect(result.kind).toBe('ready');
    if (result.kind !== 'ready') throw new Error('Expected complete board snapshot');
    expect(result.snapshot.complete).toBe(true);
    expect(result.snapshot.purchaseOrders).toHaveLength(101);
    expect(adapter).toHaveBeenCalledTimes(2);
    expect(adapter.mock.calls.map(([config]) => config.params)).toEqual([
      { status: 'ORDERED', supplierId: 7, overdue: false, page: 0, size: 100, sort: 'id,asc' },
      { status: 'ORDERED', supplierId: 7, overdue: false, page: 1, size: 100, sort: 'id,asc' },
    ]);
  });

  it('hides the board if list metadata changes while pages are loading', async () => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      const page = Number((config.params as Record<string, unknown>).page);
      return {
        data: {
          content: page === 0 ? Array.from({ length: 100 }, (_, index) => ({ id: index + 1 })) : [{ id: 101 }],
          page,
          size: 100,
          totalElements: page === 0 ? 101 : 102,
          totalPages: 2,
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    });
    axiosClient.defaults.adapter = adapter;

    await expect(purchaseOrderStageGateway.readCompleteBoard()).resolves.toMatchObject({ kind: 'incomplete' });
    expect(adapter).toHaveBeenCalledTimes(2);
  });
});
