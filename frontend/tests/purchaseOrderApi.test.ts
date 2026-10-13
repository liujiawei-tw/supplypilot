import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { createPurchaseOrderDraft } from '../src/api/purchaseOrder';

function response(config: InternalAxiosRequestConfig, data: unknown = {}): AxiosResponse {
  return { config, data, status: 201, statusText: 'Created', headers: {} };
}

beforeEach(() => { localStorage.clear(); });

describe('SP-804 linked PO Draft API contract', () => {
  it('posts only the formal create payload and preserves BigDecimal strings exactly', async () => {
    let request!: InternalAxiosRequestConfig;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      request = config;
      return response(config, { id: 501, poNumber: 'PO-20261012-000501', status: 'DRAFT' });
    });

    const created = await createPurchaseOrderDraft({
      supplierId: 12,
      expectedDeliveryDate: '2026-10-31',
      items: [{
        materialId: 7,
        quantity: '999999999999999.1234000',
        unitPrice: '0.0000001234500',
        recommendationId: 91,
        supplierRecommendationResultId: 101,
      }],
    });

    expect({ method: request.method, url: request.url }).toEqual({ method: 'post', url: '/purchase-orders' });
    expect(JSON.parse(request.data as string)).toEqual({
      supplierId: 12,
      expectedDeliveryDate: '2026-10-31',
      items: [{
        materialId: 7,
        quantity: '999999999999999.1234000',
        unitPrice: '0.0000001234500',
        recommendationId: 91,
        supplierRecommendationResultId: 101,
      }],
    });
    expect(created).toEqual({ id: 501, poNumber: 'PO-20261012-000501', status: 'DRAFT' });
  });
});
