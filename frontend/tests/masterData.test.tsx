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
const supplierMaterial = { id: 3, supplierId: 2, materialId: 1, unitPrice: 80, moq: 100, packageMultiple: 25, leadTimeDays: 14, currency: 'TWD', status: 'ACTIVE', createdAt: '', updatedAt: '' };

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

  it('keeps the Material create modal and values after a 409, then refetches only after retry succeeds', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let createAttempts = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.method === 'post') {
        createAttempts += 1;
        if (createAttempts === 1) throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined, response(config, {
          code: 'DUPLICATE_CODE', message: 'Material code already exists', traceId: 'trace-create', timestamp: '', path: '/materials',
          fieldErrors: [{ field: 'code', message: 'Code must be unique' }],
        }, 409));
        return response(config, material, 201);
      }
      return response(config, page([]));
    });
    renderWithRole(<MaterialsPage />, 'ADMIN');
    await screen.findByText('尚無 Material 資料');
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: /Create Material/ })[0]);
    await user.type(screen.getByRole('textbox', { name: 'Code' }), 'MAT-DUP');
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'Duplicate milk');
    await user.click(screen.getByRole('button', { name: 'OK' }));

    expect(await screen.findByText('Material submit failed')).toBeTruthy();
    expect(screen.getByText('Material code already exists')).toBeTruthy();
    expect(screen.getByText('DUPLICATE_CODE')).toBeTruthy();
    expect(screen.getByText('Code must be unique')).toBeTruthy();
    expect(screen.getByText('trace-create')).toBeTruthy();
    expect((screen.getByRole('textbox', { name: 'Code' }) as HTMLInputElement).value).toBe('MAT-DUP');
    expect((screen.getByRole('textbox', { name: 'Name' }) as HTMLInputElement).value).toBe('Duplicate milk');
    expect(screen.queryByText('Material 已建立')).toBeNull();
    expect(requests.filter(({ method }) => method === 'get')).toHaveLength(1);

    await user.clear(screen.getByRole('textbox', { name: 'Code' }));
    await user.type(screen.getByRole('textbox', { name: 'Code' }), 'MAT-NEW');
    await user.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(requests.filter(({ method }) => method === 'post')).toHaveLength(2));
    await waitFor(() => expect(requests.filter(({ method }) => method === 'get')).toHaveLength(2));
    expect(screen.queryByRole('dialog', { name: 'Create Material' })).toBeNull();
  }, 40_000);

  it('shows Material edit validation errors inside the modal and keeps edited values', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.method === 'patch') throw new AxiosError('Validation', 'ERR_BAD_REQUEST', config, undefined, response(config, {
        code: 'VALIDATION_FAILED', message: 'Request validation failed', traceId: 'trace-edit', timestamp: '', path: '/materials/1',
        fieldErrors: [{ field: 'name', message: 'Name is reserved' }],
      }, 400));
      return response(config, page([material]));
    });
    renderWithRole(<MaterialsPage />, 'ADMIN');
    await screen.findByText('MAT-001');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const name = screen.getByRole('textbox', { name: 'Name' });
    await user.clear(name);
    await user.type(name, 'Reserved name');
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('Material submit failed')).toBeTruthy();
    expect(screen.getByText('VALIDATION_FAILED')).toBeTruthy();
    expect(screen.getByText('Name is reserved')).toBeTruthy();
    expect((name as HTMLInputElement).value).toBe('Reserved name');
    expect(screen.getByText('Edit Material')).toBeTruthy();
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

  it('keeps Supplier create and edit values when backend submissions fail', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.method === 'post') throw new AxiosError('Forbidden', 'ERR_BAD_REQUEST', config, undefined, response(config, {
        code: 'FORBIDDEN', message: 'Admin permission required', traceId: 'trace-supplier-create', timestamp: '', path: '/suppliers',
      }, 403));
      if (config.method === 'patch') throw new AxiosError('Validation', 'ERR_BAD_REQUEST', config, undefined, response(config, {
        code: 'VALIDATION_FAILED', message: 'Supplier validation failed', traceId: 'trace-supplier-edit', timestamp: '', path: '/suppliers/2',
        fieldErrors: [{ field: 'name', message: 'Invalid supplier name' }],
      }, 400));
      return response(config, page([supplier]));
    });
    renderWithRole(<SuppliersPage />, 'ADMIN');
    await screen.findByText('SUP-001');
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: /Create Supplier/ }));
    await user.type(screen.getByRole('textbox', { name: 'Code' }), 'SUP-NEW');
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'New supplier');
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('Admin permission required')).toBeTruthy();
    expect(screen.getByText('FORBIDDEN')).toBeTruthy();
    expect((screen.getByRole('textbox', { name: 'Code' }) as HTMLInputElement).value).toBe('SUP-NEW');
    expect((screen.getByRole('textbox', { name: 'Name' }) as HTMLInputElement).value).toBe('New supplier');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const editName = screen.getByRole('textbox', { name: 'Name' });
    await user.clear(editName);
    await user.type(editName, 'Rejected supplier');
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('Invalid supplier name')).toBeTruthy();
    expect(screen.getByText('VALIDATION_FAILED')).toBeTruthy();
    expect((editName as HTMLInputElement).value).toBe('Rejected supplier');
    expect(requests.filter(({ method }) => method === 'post')).toHaveLength(1);
    expect(requests.filter(({ method }) => method === 'patch')).toHaveLength(1);
  }, 40_000);

  it('creates and edits SupplierMaterial through the UI without PATCHing immutable relationships or currency', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let created = false;
    let postAttempts = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/suppliers/2') return response(config, supplier);
      if (config.url === '/materials/1') return response(config, material);
      if (config.url === '/suppliers') return response(config, page([supplier]));
      if (config.url === '/materials') return response(config, page([material]));
      if (config.url === '/supplier-materials' && config.method === 'get') return response(config, page(created ? [supplierMaterial] : []));
      if (config.url === '/supplier-materials' && config.method === 'post') {
        postAttempts += 1;
        if (postAttempts === 1) throw new AxiosError('Validation', 'ERR_BAD_REQUEST', config, undefined, response(config, {
          code: 'VALIDATION_FAILED', message: 'Relationship validation failed', traceId: 'trace-sm-create', timestamp: '', path: '/supplier-materials',
          fieldErrors: [{ field: 'moq', message: 'MOQ violates supplier rule' }],
        }, 400));
        created = true;
        return response(config, supplierMaterial, 201);
      }
      if (config.url === '/supplier-materials/3' && config.method === 'patch') return response(config, { ...supplierMaterial, unitPrice: 85 });
      throw new Error(`Unexpected request: ${config.method} ${config.url}`);
    });
    renderWithRole(<SuppliersPage />, 'ADMIN');
    await screen.findByText('SUP-001');
    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: 'Supplier Materials' }));
    await screen.findByText('尚無 SupplierMaterial 資料');
    await user.click(screen.getAllByRole('button', { name: /Create SupplierMaterial/ })[0]);

    await waitFor(() => expect(requests.some(({ url, params }) => url === '/suppliers' && params?.status === 'ACTIVE')).toBe(true), { timeout: 5_000 });

    const supplierSelects = screen.getAllByRole('combobox', { name: 'Select supplier' });
    await user.click(supplierSelects.at(-1)!);
    await user.click((await screen.findAllByText('SUP-001 — Taiwan Foods', {}, { timeout: 5_000 })).at(-1)!);
    await waitFor(() => expect(requests.some(({ url, params }) => url === '/materials' && params?.status === 'ACTIVE')).toBe(true), { timeout: 5_000 });
    const materialSelects = screen.getAllByRole('combobox', { name: 'Select material' });
    await user.click(materialSelects.at(-1)!);
    await user.click((await screen.findAllByText('MAT-001 — Milk', {}, { timeout: 5_000 })).at(-1)!);
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Unit Price' }), { target: { value: '80' } });
    fireEvent.change(screen.getByRole('spinbutton', { name: 'MOQ' }), { target: { value: '100' } });
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Package Multiple' }), { target: { value: '25' } });
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Lead Time Days' }), { target: { value: '14' } });
    await user.click(screen.getByRole('button', { name: 'OK' }));

    expect(await screen.findByText('SupplierMaterial submit failed')).toBeTruthy();
    expect(screen.getByText('VALIDATION_FAILED')).toBeTruthy();
    expect(screen.getByText('MOQ violates supplier rule')).toBeTruthy();
    expect((screen.getByRole('spinbutton', { name: 'MOQ' }) as HTMLInputElement).value).toBe('100');
    expect(screen.queryByText('SupplierMaterial 已建立')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('80')).toBeTruthy();

    const postPayloads = requests.filter(({ method }) => method === 'post').map(({ data }) => JSON.parse(data as string));
    expect(postPayloads).toEqual([
      { supplierId: 2, materialId: 1, unitPrice: 80, moq: 100, packageMultiple: 25, leadTimeDays: 14, currency: 'TWD' },
      { supplierId: 2, materialId: 1, unitPrice: 80, moq: 100, packageMultiple: 25, leadTimeDays: 14, currency: 'TWD' },
    ]);

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Unit Price' }), { target: { value: '85' } });
    await user.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(requests.filter(({ method }) => method === 'patch')).toHaveLength(1));
    const patchPayload = JSON.parse(requests.find(({ method }) => method === 'patch')?.data as string) as Record<string, unknown>;
    expect(patchPayload).toEqual({ unitPrice: 85, moq: 100, packageMultiple: 25, leadTimeDays: 14, status: 'ACTIVE' });
    expect(patchPayload).not.toHaveProperty('supplierId');
    expect(patchPayload).not.toHaveProperty('materialId');
    expect(patchPayload).not.toHaveProperty('currency');
    expect(requests.filter(({ url, method }) => url === '/supplier-materials' && method === 'get').length).toBeGreaterThanOrEqual(3);
  }, 60_000);
});
