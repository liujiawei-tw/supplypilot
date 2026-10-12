import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { listMaterials, updateMaterial } from '../src/api/material';
import { listSuppliers, updateSupplier } from '../src/api/supplier';
import { createSupplierMaterial, listSupplierMaterials, updateSupplierMaterial } from '../src/api/supplierMaterial';

function response(config: InternalAxiosRequestConfig, data: unknown = {}): AxiosResponse {
  return { config, data, status: 200, statusText: 'OK', headers: {} };
}

beforeEach(() => { localStorage.clear(); });

describe('master data API contracts', () => {
  it('passes database pagination, filtering and sorting to list endpoints', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config, { content: [], page: 2, size: 50, totalElements: 0, totalPages: 0 }); });
    await listMaterials({ status: 'ACTIVE', code: 'MAT', name: 'Milk', page: 2, size: 50, sort: 'code,desc' });
    await listSuppliers({ status: 'INACTIVE', page: 1, size: 20, sort: 'name,asc' });
    await listSupplierMaterials({ supplierId: 7, materialId: 9, status: 'ACTIVE', page: 3, size: 10, sort: 'leadTimeDays,desc' });
    expect(requests.map(({ url, params }) => ({ url, params }))).toEqual([
      { url: '/materials', params: { status: 'ACTIVE', code: 'MAT', name: 'Milk', page: 2, size: 50, sort: 'code,desc' } },
      { url: '/suppliers', params: { status: 'INACTIVE', page: 1, size: 20, sort: 'name,asc' } },
      { url: '/supplier-materials', params: { supplierId: 7, materialId: 9, status: 'ACTIVE', page: 3, size: 10, sort: 'leadTimeDays,desc' } },
    ]);
  });

  it('sends only backend-supported PATCH fields', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config); });
    await updateMaterial(1, { name: 'New material', safetyStock: 4, status: 'ACTIVE' });
    await updateSupplier(2, { name: 'New supplier', country: 'TW', reliabilityScore: 90, qualityScore: 91, status: 'ACTIVE' });
    await updateSupplierMaterial(3, { unitPrice: 12, moq: 5, packageMultiple: 2, leadTimeDays: 7, status: 'INACTIVE' });
    const payloads = requests.map(({ data }) => JSON.parse(data as string) as Record<string, unknown>);
    expect(payloads[0]).toEqual({ name: 'New material', safetyStock: 4, status: 'ACTIVE' });
    expect(payloads[0]).not.toHaveProperty('code'); expect(payloads[0]).not.toHaveProperty('baseUnit');
    expect(payloads[1]).not.toHaveProperty('code');
    expect(payloads[2]).not.toHaveProperty('supplierId'); expect(payloads[2]).not.toHaveProperty('materialId'); expect(payloads[2]).not.toHaveProperty('currency');
  });

  it('uses the exact SupplierMaterial create contract', async () => {
    let request: InternalAxiosRequestConfig | undefined;
    axiosClient.defaults.adapter = vi.fn(async (config) => { request = config; return response(config); });
    await createSupplierMaterial({ supplierId: 1, materialId: 2, unitPrice: 80, moq: 100, packageMultiple: 25, leadTimeDays: 14, currency: 'TWD' });
    expect(request?.method).toBe('post');
    expect(request?.url).toBe('/supplier-materials');
    expect(JSON.parse(request?.data as string)).toEqual({ supplierId: 1, materialId: 2, unitPrice: 80, moq: 100, packageMultiple: 25, leadTimeDays: 14, currency: 'TWD' });
  });
});
