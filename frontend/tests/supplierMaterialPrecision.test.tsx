import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { AuthContext, type AuthState } from '../src/auth/authContextValue';
import SupplierMaterialList from '../src/pages/Suppliers/SupplierMaterialList';
import type { AuthenticatedUser } from '../src/types/api';

function response(config: InternalAxiosRequestConfig, data: unknown): AxiosResponse {
  return { config, data, status: 200, statusText: 'OK', headers: {} };
}

function staffAuth(): AuthState {
  const user: AuthenticatedUser = { id: 1, username: 'procurement', displayName: 'Staff', role: 'PROCUREMENT_STAFF' };
  return { user, isAuthenticated: true, isLoading: false, verificationError: false, retryVerification: vi.fn(), login: vi.fn(), logout: vi.fn() };
}

beforeEach(() => { localStorage.clear(); });

describe('SupplierMaterial decimal rendering', () => {
  it('keeps a DECIMAL(19,4) token exact from API JSON through the list formatter', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config.url ?? '');
      if (config.url === '/supplier-materials') {
        return response(config, '{"content":[{"id":8,"supplierId":2,"materialId":42,"unitPrice":999999999999999.1234,"moq":0.0001,"packageMultiple":12.3400,"leadTimeDays":7,"currency":"TWD","status":"ACTIVE","createdAt":"2026-10-24T00:00:00Z","updatedAt":"2026-10-24T00:00:00Z"}],"page":0,"size":20,"totalElements":1,"totalPages":1}');
      }
      if (config.url === '/suppliers/2') {
        return response(config, { id: 2, code: 'SUP-002', name: 'Supplier' });
      }
      if (config.url === '/materials/42') {
        return response(config, { id: 42, code: 'MAT-042', name: 'Material', baseUnit: 'KG' });
      }
      if (config.url === '/suppliers' || config.url === '/materials') {
        return response(config, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
      }
      throw new Error(`Unexpected request: ${config.url}`);
    });

    render(
      <AuthContext.Provider value={staffAuth()}>
        <SupplierMaterialList isAdmin={false} />
      </AuthContext.Provider>,
    );

    expect(await screen.findByText('TWD 999,999,999,999,999.1234')).toBeTruthy();
    expect(screen.getByText('0.0001 KG')).toBeTruthy();
    expect(screen.getByText('12.3400 KG')).toBeTruthy();
    expect(requests).toEqual(expect.arrayContaining(['/supplier-materials', '/suppliers/2', '/materials/42']));
  });
});
