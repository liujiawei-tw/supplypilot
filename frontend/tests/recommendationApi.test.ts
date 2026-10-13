import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { createRecommendation, getRecommendation, getSupplierRanking, parseRecommendationResponse } from '../src/api/recommendation';

function response(config: InternalAxiosRequestConfig, data: unknown = {}): AxiosResponse {
  return { config, data, status: 200, statusText: 'OK', headers: {} };
}

beforeEach(() => { localStorage.clear(); });

describe('SP-804 Recommendation API contracts', () => {
  it('uses only the supported create, detail and supplier ranking endpoints', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      return response(config, config.url?.endsWith('/suppliers') ? [] : {});
    });

    await createRecommendation({ materialId: 7, periodStart: '2026-10-01', periodEnd: '2026-10-31' });
    await getRecommendation(91);
    await getSupplierRanking(91);

    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
      { method: 'post', url: '/recommendations' },
      { method: 'get', url: '/recommendations/91' },
      { method: 'get', url: '/recommendations/91/suppliers' },
    ]);
    expect(JSON.parse(requests[0].data as string)).toEqual({ materialId: 7, periodStart: '2026-10-01', periodEnd: '2026-10-31' });
    expect(requests[2].params).toBeUndefined();
    expect(requests.some(({ url }) => url === '/recommendations')).toBe(true);
    expect(requests.filter(({ method, url }) => method === 'get' && url === '/recommendations')).toHaveLength(0);
  });

  it('preserves documented BigDecimal tokens before native JSON parsing', () => {
    const parsed = parseRecommendationResponse('{"id":91,"calculation":{"expectedDemand":999999999999999.1234,"netRequirement":0.0001},"recommendation":{"quantity":100.0000,"score":88.5000}}') as Record<string, unknown>;
    const calculation = parsed.calculation as Record<string, unknown>;
    const recommendation = parsed.recommendation as Record<string, unknown>;
    expect(parsed.id).toBe(91);
    expect(calculation.expectedDemand).toBe('999999999999999.1234');
    expect(calculation.netRequirement).toBe('0.0001');
    expect(recommendation.quantity).toBe('100.0000');
    expect(recommendation.score).toBe('88.5000');
  });

  it('maps the persisted supplier recommendation result ID separately from supplier ID', () => {
    const parsed = parseRecommendationResponse('[{"id":101,"supplierId":12,"qualified":true,"recommendedQuantity":110.0000,"unitPrice":25.5000}]') as Array<Record<string, unknown>>;
    expect(parsed[0].id).toBe(101);
    expect(parsed[0].supplierId).toBe(12);
    expect(parsed[0].recommendedQuantity).toBe('110.0000');
    expect(parsed[0].unitPrice).toBe('25.5000');
  });
});
