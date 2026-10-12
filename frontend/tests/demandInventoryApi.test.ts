import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { cancelDemand, createDemand, listDemands, updateDemand } from '../src/api/demand';
import { adjustInventory, getInventory, listInventory } from '../src/api/inventory';

function response(config: InternalAxiosRequestConfig, data: unknown = {}): AxiosResponse {
  return { config, data, status: 200, statusText: 'OK', headers: {} };
}

beforeEach(() => { localStorage.clear(); });

describe('SP-802 API contracts', () => {
  it('passes Demand database filters, pagination and an allowed sort', async () => {
    let request: InternalAxiosRequestConfig | undefined;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      request = config;
      return response(config, { content: [], page: 2, size: 50, totalElements: 0, totalPages: 0 });
    });
    await listDemands({ materialId: 7, status: 'ACTIVE', requiredFrom: '2026-10-01', requiredTo: '2026-10-31', page: 2, size: 50, sort: 'requiredDate,desc' });
    expect(request?.url).toBe('/demands');
    expect(request?.params).toEqual({ materialId: 7, status: 'ACTIVE', requiredFrom: '2026-10-01', requiredTo: '2026-10-31', page: 2, size: 50, sort: 'requiredDate,desc' });
  });

  it('uses exact Demand create, update and cancel contracts', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config); });
    await createDemand({ materialId: 1, quantity: 100, requiredDate: '2026-10-20', note: 'Production requirement' });
    await updateDemand(9, { quantity: 80, requiredDate: '2026-10-21', note: null });
    await cancelDemand(9);
    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
      { method: 'post', url: '/demands' },
      { method: 'patch', url: '/demands/9' },
      { method: 'post', url: '/demands/9/cancel' },
    ]);
    expect(JSON.parse(requests[0].data as string)).toEqual({ materialId: 1, quantity: 100, requiredDate: '2026-10-20', note: 'Production requirement' });
    const update = JSON.parse(requests[1].data as string) as Record<string, unknown>;
    expect(update).toEqual({ quantity: 80, requiredDate: '2026-10-21', note: null });
    expect(update).not.toHaveProperty('materialId');
    expect(update).not.toHaveProperty('status');
  });

  it('uses Inventory filters without an unsupported sort and sends signed adjustment quantity', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config, config.url === '/inventory/3' ? { materialId: 3 } : { content: [] }); });
    await listInventory({ materialId: 3, materialCode: 'MAT', page: 1, size: 20 });
    await getInventory(3);
    await adjustInventory(3, { quantity: -5.25, reason: 'Damaged stock' });
    expect(requests[0].params).toEqual({ materialId: 3, materialCode: 'MAT', page: 1, size: 20 });
    expect(requests[0].params).not.toHaveProperty('sort');
    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
      { method: 'get', url: '/inventory' },
      { method: 'get', url: '/inventory/3' },
      { method: 'post', url: '/inventory/3/adjustments' },
    ]);
    expect(JSON.parse(requests[2].data as string)).toEqual({ quantity: -5.25, reason: 'Damaged stock' });
  });
});
