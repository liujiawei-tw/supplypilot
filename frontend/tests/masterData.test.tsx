import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { AuthContext, type AuthState } from '../src/auth/authContextValue';
import MaterialsPage from '../src/pages/Materials/MaterialsPage';
import SuppliersPage from '../src/pages/Suppliers/SuppliersPage';
import type { AuthenticatedUser, UserRole } from '../src/types/api';

const material = { id: 1, code: 'MAT-001', name: 'Milk', baseUnit: 'L', safetyStock: 10, status: 'ACTIVE', createdAt: '', updatedAt: '' };
const supplier = { id: 2, code: 'SUP-001', name: 'Taiwan Foods', country: 'TW', reliabilityScore: 92, qualityScore: 95, status: 'ACTIVE', createdAt: '', updatedAt: '' };

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { config, data, status, statusText: String(status), headers: {} };
}
function page(content: unknown[]) { return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 }; }
function auth(role: UserRole): AuthState {
  const user: AuthenticatedUser = { id: 1, username: role.toLowerCase(), displayName: role, role };
  return { user, isAuthenticated: true, isLoading: false, verificationError: false, retryVerification: vi.fn(), login: vi.fn(), logout: vi.fn() };
}
function renderWithRole(node: React.ReactNode, role: UserRole) {
  return render(<AuthContext.Provider value={auth(role)}>{node}</AuthContext.Provider>);
}

beforeEach(() => { localStorage.clear(); });

describe('master data pages', () => {
  it('shows loading, data and ADMIN actions on Materials', async () => {
    let finish!: () => void;
    axiosClient.defaults.adapter = vi.fn((config) => new Promise((resolve) => { finish = () => resolve(response(config, page([material]))); }));
    renderWithRole(<MaterialsPage />, 'ADMIN');
    expect(await screen.findByText('Loading materials...')).toBeTruthy();
    await act(async () => finish());
    expect(await screen.findByText('MAT-001')).toBeTruthy();
    expect(screen.getByRole('button', { name: /Create Material/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Deactivate' })).toBeTruthy();
  });

  it('keeps Materials read-only for an authorized non-ADMIN role', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config.url ?? ''); return response(config, page([material])); });
    renderWithRole(<MaterialsPage />, 'PRODUCTION_PLANNER');
    expect(await screen.findByText('MAT-001')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Create Material/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(requests).toEqual(['/materials']);
  });

  it('validates Material fields, creates once, then refetches', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let finishCreate!: () => void;
    axiosClient.defaults.adapter = vi.fn((config) => {
      requests.push(config);
      if (config.method === 'post') return new Promise((resolve) => { finishCreate = () => resolve(response(config, material, 201)); });
      return Promise.resolve(response(config, page([])));
    });
    renderWithRole(<MaterialsPage />, 'ADMIN');
    await screen.findByText('尚無 Material 資料');
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: /Create Material/ })[0]);
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findAllByText(/required/i)).toHaveLength(2);
    fireEvent.change(screen.getByRole('textbox', { name: 'Code' }), { target: { value: 'X'.repeat(51) } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'New material' } });
    const safetyStock = screen.getByRole('spinbutton', { name: 'Safety Stock' });
    await user.clear(safetyStock);
    await user.type(safetyStock, '-1');
    fireEvent.blur(safetyStock);
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText(/cannot be longer than 50/i)).toBeTruthy();
    expect(await screen.findByText(/cannot be less than 0/i)).toBeTruthy();
    expect(requests.filter(({ method }) => method === 'post')).toHaveLength(0);
    fireEvent.change(screen.getByRole('textbox', { name: 'Code' }), { target: { value: 'MAT-NEW' } });
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Safety Stock' }), { target: { value: '0' } });
    await user.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(finishCreate).toBeTypeOf('function'));
    expect(screen.getByRole('button', { name: /OK/ }).hasAttribute('disabled')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: /OK/ }));
    expect(requests.filter(({ method }) => method === 'post')).toHaveLength(1);
    await act(async () => finishCreate());
    await waitFor(() => expect(requests.filter(({ method }) => method === 'get')).toHaveLength(2));
    expect(JSON.parse(requests.find(({ method }) => method === 'post')?.data as string)).toEqual({ code: 'MAT-NEW', name: 'New material', baseUnit: 'PCS', safetyStock: 0 });
  }, 30_000);

  it('shows backend validation details and does not report a failed status update as success', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.method === 'patch') throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'DUPLICATE_CODE', message: 'Material conflict', traceId: 'trace-801', timestamp: '', path: '/materials/1', fieldErrors: [{ field: 'status', message: 'Status rejected' }] }, 409));
      return response(config, page([material]));
    });
    renderWithRole(<MaterialsPage />, 'ADMIN');
    await screen.findByText('MAT-001');
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }));
    await screen.findByText(/確定要將此 Material/);
    fireEvent.click(screen.getAllByRole('button', { name: 'OK' }).at(-1)!);
    expect(await screen.findByText('Material conflict')).toBeTruthy();
    expect(screen.getByText('DUPLICATE_CODE')).toBeTruthy();
    expect(screen.getByText('trace-801')).toBeTruthy();
    expect(screen.getByText('Status rejected')).toBeTruthy();
    expect(screen.queryByText('狀態已更新')).toBeNull();
  }, 15_000);

  it('shows Suppliers and SupplierMaterials as read-only tabs for procurement roles', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config.url ?? '');
      if (config.url === '/suppliers') return response(config, page([supplier]));
      if (config.url === '/supplier-materials') return response(config, page([]));
      if (config.url === '/materials') return response(config, page([]));
      throw new Error(`Unexpected request: ${config.url}`);
    });
    renderWithRole(<SuppliersPage />, 'PROCUREMENT_STAFF');
    expect(await screen.findByText('SUP-001')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Create Supplier' })).toBeNull();
    fireEvent.click(screen.getByRole('tab', { name: 'Supplier Materials' }));
    expect(await screen.findByText('尚無 SupplierMaterial 資料')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Create SupplierMaterial' })).toBeNull();
    expect(requests).toContain('/supplier-materials');
  });

  it('edits a Supplier without sending its immutable code and refetches', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/suppliers' && config.method === 'get') return response(config, page([supplier]));
      if (config.url === '/suppliers/2' && config.method === 'patch') return response(config, { ...supplier, name: 'Updated Foods' });
      throw new Error(`Unexpected request: ${config.method} ${config.url}`);
    });
    renderWithRole(<SuppliersPage />, 'ADMIN');
    await screen.findByText('SUP-001');
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    const name = screen.getByRole('textbox', { name: 'Name' });
    fireEvent.change(name, { target: { value: 'Updated Foods' } });
    fireEvent.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(requests.filter(({ method }) => method === 'patch')).toHaveLength(1));
    const payload = JSON.parse(requests.find(({ method }) => method === 'patch')?.data as string) as Record<string, unknown>;
    expect(payload).toEqual({ name: 'Updated Foods', country: 'TW', reliabilityScore: 92, qualityScore: 95, status: 'ACTIVE' });
    expect(payload).not.toHaveProperty('code');
    await waitFor(() => expect(requests.filter(({ method }) => method === 'get')).toHaveLength(2));
  }, 20_000);
});
