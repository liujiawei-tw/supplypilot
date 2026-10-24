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

  it('parses SupplierMaterial BigDecimal fields losslessly across response endpoints', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    const item = '{"id":8,"supplierId":2,"materialId":42,"unitPrice":999999999999999.1234,"moq":0.0001,"packageMultiple":1E-4,"leadTimeDays":7,"currency":"TWD","status":"ACTIVE","createdAt":"2026-10-24T00:00:00Z","updatedAt":"2026-10-24T00:00:00Z"}';
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      const data = config.method === 'get'
        ? '{"content":[' + item + '],"page":0,"size":20,"totalElements":1,"totalPages":1}'
        : item;
      return response(config, data);
    });

    const listed = await listSupplierMaterials({ page: 0, size: 20 });
    const created = await createSupplierMaterial({ supplierId: 2, materialId: 42, unitPrice: '999999999999999.1234', moq: '0.0001', packageMultiple: '0.0001', leadTimeDays: 7, currency: 'TWD' });
    const updated = await updateSupplierMaterial(8, { status: 'ACTIVE' });
    for (const value of [listed.content[0], created, updated]) {
      expect(value).toMatchObject({
        id: 8,
        supplierId: 2,
        materialId: 42,
        unitPrice: '999999999999999.1234',
        moq: '0.0001',
        packageMultiple: '1E-4',
        leadTimeDays: 7,
      });
      expect(typeof value.id).toBe('number');
      expect(typeof value.supplierId).toBe('number');
      expect(typeof value.materialId).toBe('number');
      expect(typeof value.leadTimeDays).toBe('number');
    }
    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
      { method: 'get', url: '/supplier-materials' },
      { method: 'post', url: '/supplier-materials' },
      { method: 'patch', url: '/supplier-materials/8' },
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
