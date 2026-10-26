import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { AuthContext, type AuthState } from '../src/auth/authContextValue';
import { MemoryRouter } from 'react-router-dom';
import MaterialsPage from '../src/pages/Materials/MaterialsPage';
import SupplierMaterialList from '../src/pages/Suppliers/SupplierMaterialList';
import type { AuthenticatedUser, UserRole } from '../src/types/api';

const unitPrice = '999999999999999.1234';
const moq = '0.0001';
const packageMultiple = '12.3400';
const safetyStock = '999999999999999.1234';

function response(config: InternalAxiosRequestConfig, data: unknown): AxiosResponse {
  return { config, data, status: 200, statusText: 'OK', headers: {} };
}
function auth(role: UserRole): AuthState {
  const user: AuthenticatedUser = { id: 1, username: role.toLowerCase(), displayName: role, role };
  return { user, isAuthenticated: true, isLoading: false, verificationError: false, retryVerification: vi.fn(), login: vi.fn(), logout: vi.fn() };
}

beforeEach(() => { localStorage.clear(); });

describe('BigDecimal edit round trips', () => {
  it('keeps SupplierMaterial decimal strings through edit, unrelated field change, and PATCH', async () => {
    let patchBody = '';
    const observedRequests: string[] = [];
    const listJson = '{"content":[{"id":8,"supplierId":2,"materialId":42,"unitPrice":' + unitPrice + ',"moq":' + moq + ',"packageMultiple":' + packageMultiple + ',"leadTimeDays":7,"currency":"TWD","status":"ACTIVE","createdAt":"2026-10-24T00:00:00Z","updatedAt":"2026-10-24T00:00:00Z"}],"page":0,"size":20,"totalElements":1,"totalPages":1}';
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      observedRequests.push(`${config.method} ${config.url}`);
      if (config.url === '/supplier-materials/8') {
        patchBody = config.data as string;
        return response(config, listJson);
      }
      if (config.url === '/supplier-materials') return response(config, listJson);
      if (config.url === '/suppliers/2') return response(config, { id: 2, code: 'SUP-002', name: 'Supplier' });
      if (config.url === '/materials/42') return response(config, { id: 42, code: 'MAT-042', name: 'Material', baseUnit: 'KG' });
      if (config.url === '/suppliers' || config.url === '/materials') return response(config, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
      throw new Error('Unexpected request: ' + config.method + ' ' + config.url);
    });

    render(
      <AuthContext.Provider value={auth('ADMIN')}>
        <SupplierMaterialList isAdmin />
      </AuthContext.Provider>,
    );

    expect(await screen.findByText('TWD 999,999,999,999,999.1234')).toBeTruthy();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /編\s*輯/ }));
    expect(await screen.findByText('編輯供應商物料關聯')).toBeTruthy();

    const priceInput = screen.getByRole('textbox', { name: '單價' }) as HTMLInputElement;
    const moqInput = screen.getByRole('textbox', { name: '最低訂購量（MOQ）' }) as HTMLInputElement;
    const multipleInput = screen.getByRole('textbox', { name: '包裝倍數' }) as HTMLInputElement;

    expect(priceInput.value).toBe(unitPrice);
    expect(moqInput.value).toBe(moq);
    expect(multipleInput.value).toBe(packageMultiple);


    const leadTimeInput = screen.getByRole('spinbutton', { name: '交期（天）' });
    await user.clear(leadTimeInput);
    await user.type(leadTimeInput, '9');
    await user.click(screen.getByRole('button', { name: 'OK' }));

    await waitFor(() => {
      const patchRequest = observedRequests.find((request) => request.toLowerCase() === 'patch /supplier-materials/8');
      if (!patchRequest) {
        const validationErrors = [...document.querySelectorAll('.ant-form-item-explain-error')].map((node) => node.textContent).filter(Boolean).join(' | ');
        throw new Error(`SupplierMaterial PATCH was not sent; requests=${observedRequests.join(', ')}; validation=${validationErrors}`);
      }
    });
    expect(patchBody).not.toBe('');
    expect(patchBody).toContain('"unitPrice":"999999999999999.1234"');
    expect(patchBody).toContain('"moq":"0.0001"');
    expect(patchBody).toContain('"packageMultiple":"12.3400"');
    expect(JSON.parse(patchBody)).toEqual({
      unitPrice,
      moq,
      packageMultiple,
      leadTimeDays: 9,
      status: 'ACTIVE',
    });
  });

  it('keeps Material safetyStock exact through read, edit, unrelated field change, and PATCH', async () => {
    let patchBody = '';
    const listJson = '{"content":[{"id":42,"code":"MAT-042","name":"Copper","baseUnit":"KG","safetyStock":' + safetyStock + ',"status":"ACTIVE","createdAt":"2026-10-24T00:00:00Z","updatedAt":"2026-10-24T00:00:00Z"}],"page":0,"size":20,"totalElements":1,"totalPages":1}';
    const material = { id: 42, code: 'MAT-042', name: 'Changed copper', baseUnit: 'KG', safetyStock, status: 'ACTIVE', createdAt: '2026-10-24T00:00:00Z', updatedAt: '2026-10-24T00:00:00Z' };
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/materials/42' && config.method === 'patch') {
        patchBody = config.data as string;
        return response(config, material);
      }
      if (config.url === '/materials') return response(config, listJson);
      throw new Error('Unexpected request: ' + config.method + ' ' + config.url);
    });

    render(
      <MemoryRouter>
        <AuthContext.Provider value={auth('ADMIN')}>
          <MaterialsPage />
        </AuthContext.Provider>
      </MemoryRouter>,
    );

    expect(await screen.findByText('999,999,999,999,999.1234 KG')).toBeTruthy();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /編\s*輯/ }));
    expect(await screen.findByText('編輯物料')).toBeTruthy();

    const stockInput = screen.getByRole('textbox', { name: '安全庫存' }) as HTMLInputElement;
    expect(stockInput.value).toBe(safetyStock);
    const nameInput = screen.getByRole('textbox', { name: '名稱' });
    await user.clear(nameInput);
    await user.type(nameInput, 'Changed copper');
    await user.click(screen.getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(patchBody).not.toBe(''));
    expect(patchBody).toContain('"safetyStock":"999999999999999.1234"');
    expect(JSON.parse(patchBody)).toEqual({
      name: 'Changed copper',
      safetyStock,
      status: 'ACTIVE',
    });
  });
});
