import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import { confirmInventoryImport, validateInventoryImport } from '../src/api/inventoryImport';

function response(config: InternalAxiosRequestConfig, data: unknown = undefined): AxiosResponse {
  return { config, data, status: 200, statusText: 'OK', headers: {} };
}

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem(TOKEN_STORAGE_KEY, 'inventory-token');
});

describe('SP-803 Inventory Import API contracts', () => {
  it('sends the selected CSV as multipart part file without retaining the JSON content type', async () => {
    let request: InternalAxiosRequestConfig | undefined;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      request = config;
      return response(config, { batchId: 17, status: 'VALIDATED', totalRows: 1, validRows: 1, errorRows: 0, errors: [] });
    });
    const file = new File(['material_code,quantity,unit\nMAT-001,1,KG\n'], 'inventory.csv', { type: 'text/csv' });

    await validateInventoryImport(file);

    expect(request).toBeDefined();
    const captured = request!;
    expect(captured.url).toBe('/inventory-imports/validate');
    expect(captured.method).toBe('post');
    expect(captured.headers.Authorization).toBe('Bearer inventory-token');
    expect(captured.headers.get('Content-Type')).not.toBe('application/json');
    expect(captured.data).toBeInstanceOf(FormData);
    expect((captured.data as FormData).get('file')).toBe(file);
  });

  it('confirms the exact batch with no request body and accepts an empty response', async () => {
    let request: InternalAxiosRequestConfig | undefined;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      request = config;
      return response(config);
    });

    await expect(confirmInventoryImport(42)).resolves.toBeUndefined();

    expect(request?.url).toBe('/inventory-imports/42/confirm');
    expect(request?.method).toBe('post');
    expect(request?.data).toBeUndefined();
    expect(request?.headers.Authorization).toBe('Bearer inventory-token');
  });
});
