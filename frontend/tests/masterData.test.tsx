import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { MemoryRouter } from 'react-router-dom';
import { parseDecimalJson } from '../src/api/decimalJson';
import { AuthContext, type AuthState } from '../src/auth/authContextValue';
import MaterialsPage from '../src/pages/Materials/MaterialsPage';
import SuppliersPage from '../src/pages/Suppliers/SuppliersPage';
import type { AuthenticatedUser, UserRole } from '../src/types/api';

const material = { id: 1, code: 'MAT-001', name: 'Milk', baseUnit: 'L', safetyStock: 10, status: 'ACTIVE', createdAt: '', updatedAt: '' };
const supplier = { id: 2, code: 'SUP-001', name: 'Taiwan Foods', country: 'TW', reliabilityScore: 92, qualityScore: 95, status: 'ACTIVE', createdAt: '', updatedAt: '' };
const supplierMaterial = { id: 3, supplierId: 2, materialId: 1, unitPrice: '80', moq: '100', packageMultiple: '25', leadTimeDays: 14, currency: 'TWD', status: 'ACTIVE', createdAt: '', updatedAt: '' };

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { config, data, status, statusText: String(status), headers: {} };
}
function page(content: unknown[]) { return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 }; }
function auth(role: UserRole): AuthState {
  const user: AuthenticatedUser = { id: 1, username: role.toLowerCase(), displayName: role, role };
  return { user, isAuthenticated: true, isLoading: false, verificationError: false, retryVerification: vi.fn(), login: vi.fn(), logout: vi.fn() };
}
function renderWithRole(node: React.ReactNode, role: UserRole, initialEntry = '/') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AuthContext.Provider value={auth(role)}>{node}</AuthContext.Provider>
    </MemoryRouter>,
  );
}

beforeEach(() => { localStorage.clear(); });

describe('master data pages', () => {
  it.each([
    { resource: 'supplier', field: 'code', value: 'SUP-001', path: '/suppliers?tab=suppliers&finder=SUP-001&finderField=code' },
    { resource: 'supplier', field: 'name', value: 'Taiwan Foods', path: '/suppliers?tab=suppliers&finder=Taiwan%20Foods&finderField=name' },
    { resource: 'material', field: 'code', value: 'MAT-001', path: '/materials?finder=MAT-001&finderField=code' },
    { resource: 'material', field: 'name', value: 'Milk', path: '/materials?finder=Milk&finderField=name' },
  ] as const)('maps a $resource finder to the server $field filter only', async ({ resource, field, value, path }) => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      return response(config, page([]));
    });
    renderWithRole(resource === 'supplier' ? <SuppliersPage /> : <MaterialsPage />, 'ADMIN', path);

    expect(await screen.findByText(resource === 'supplier' ? '共 0 筆供應商' : '共 0 筆物料')).toBeTruthy();
    const request = requests.find((item) => item.url === (resource === 'supplier' ? '/suppliers' : '/materials'));
    expect(request?.params?.[field]).toBe(value);
    expect(request?.params?.[field === 'code' ? 'name' : 'code']).toBeUndefined();
  });

  it('shows loading, data and ADMIN actions on Materials', async () => {
    let finish!: () => void;
    axiosClient.defaults.adapter = vi.fn((config) => new Promise((resolve) => { finish = () => resolve(response(config, page([material]))); }));
    renderWithRole(<MaterialsPage />, 'ADMIN');
    expect(await screen.findByText('正在載入物料…')).toBeTruthy();
    await act(async () => finish());
    expect(await screen.findByText('MAT-001')).toBeTruthy();
    expect(screen.getByRole('button', { name: /新增物料/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /編\s*輯/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /停\s*用/ })).toBeTruthy();
  });

  it('keeps Materials read-only for an authorized non-ADMIN role', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config.url ?? ''); return response(config, page([material])); });
    renderWithRole(<MaterialsPage />, 'PRODUCTION_PLANNER');
    expect(await screen.findByText('MAT-001')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /新增物料/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /編\s*輯/ })).toBeNull();
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
    await screen.findByText('目前沒有物料資料');
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: /新增物料/ })[0]);
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('請輸入物料代碼')).toBeTruthy();
    expect(screen.getByText('請輸入物料名稱')).toBeTruthy();
    fireEvent.change(screen.getByRole('textbox', { name: '物料代碼' }), { target: { value: 'X'.repeat(51) } });
    fireEvent.change(screen.getByRole('textbox', { name: '名稱' }), { target: { value: 'New material' } });
    const safetyStock = screen.getByRole('textbox', { name: '安全庫存' });
    await user.clear(safetyStock);
    await user.type(safetyStock, '-1');
    fireEvent.blur(safetyStock);
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText(/cannot be longer than 50/i)).toBeTruthy();
    expect((screen.getByRole('textbox', { name: '安全庫存' }) as HTMLInputElement).value).toBe('-1');
    expect(requests.filter(({ method }) => method === 'post')).toHaveLength(0);
    fireEvent.change(screen.getByRole('textbox', { name: '物料代碼' }), { target: { value: 'MAT-NEW' } });
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('安全庫存不可小於 0')).toBeTruthy();
    expect(requests.filter(({ method }) => method === 'post')).toHaveLength(0);
    fireEvent.change(screen.getByRole('textbox', { name: '安全庫存' }), { target: { value: '999999999999999.1234' } });
    await user.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(finishCreate).toBeTypeOf('function'));
    expect(screen.getByRole('button', { name: /OK/ }).hasAttribute('disabled')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: /OK/ }));
    expect(requests.filter(({ method }) => method === 'post')).toHaveLength(1);
    await act(async () => finishCreate());
    await waitFor(() => expect(requests.filter(({ method }) => method === 'get')).toHaveLength(2));
    const materialPost = requests.find(({ method }) => method === 'post');
    expect(materialPost?.data).toContain('"safetyStock":999999999999999.1234');
    expect(parseDecimalJson(materialPost?.data)).toEqual({
      code: 'MAT-NEW',
      name: 'New material',
      baseUnit: 'PCS',
      safetyStock: '999999999999999.1234',
    });
  }, 30_000);

  it('shows backend validation details and does not report a failed status update as success', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.method === 'patch') throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined, response(config, { code: 'DUPLICATE_CODE', message: 'Material conflict', traceId: 'trace-801', timestamp: '', path: '/materials/1', fieldErrors: [{ field: 'status', message: 'Status rejected' }] }, 409));
      return response(config, page([material]));
    });
    renderWithRole(<MaterialsPage />, 'ADMIN');
    await screen.findByText('MAT-001');
    fireEvent.click(screen.getByRole('button', { name: /停\s*用/ }));
    await screen.findByText(/確定要將此物料/);
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
    await screen.findByText('目前沒有物料資料');
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: /新增物料/ })[0]);
    await user.type(screen.getByRole('textbox', { name: '物料代碼' }), 'MAT-DUP');
    await user.type(screen.getByRole('textbox', { name: '名稱' }), 'Duplicate milk');
    await user.click(screen.getByRole('button', { name: 'OK' }));

    expect(await screen.findByText('物料送出失敗')).toBeTruthy();
    expect(screen.getByText('Material code already exists')).toBeTruthy();
    expect(screen.getByText('DUPLICATE_CODE')).toBeTruthy();
    expect(screen.getByText('Code must be unique')).toBeTruthy();
    expect(screen.getByText('trace-create')).toBeTruthy();
    expect((screen.getByRole('textbox', { name: '物料代碼' }) as HTMLInputElement).value).toBe('MAT-DUP');
    expect((screen.getByRole('textbox', { name: '名稱' }) as HTMLInputElement).value).toBe('Duplicate milk');
    expect(screen.queryByText('Material 已建立')).toBeNull();
    expect(requests.filter(({ method }) => method === 'get')).toHaveLength(1);

    await user.clear(screen.getByRole('textbox', { name: '物料代碼' }));
    await user.type(screen.getByRole('textbox', { name: '物料代碼' }), 'MAT-NEW');
    await user.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(requests.filter(({ method }) => method === 'post')).toHaveLength(2));
    await waitFor(() => expect(requests.filter(({ method }) => method === 'get')).toHaveLength(2));
    expect(screen.queryByRole('dialog', { name: '新增物料' })).toBeNull();
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
    await user.click(screen.getByRole('button', { name: /編\s*輯/ }));
    const name = screen.getByRole('textbox', { name: '名稱' });
    await user.clear(name);
    await user.type(name, 'Reserved name');
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('物料送出失敗')).toBeTruthy();
    expect(screen.getByText('VALIDATION_FAILED')).toBeTruthy();
    expect(screen.getByText('Name is reserved')).toBeTruthy();
    expect((name as HTMLInputElement).value).toBe('Reserved name');
    expect(screen.getByText('編輯物料')).toBeTruthy();
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
    expect(screen.queryByRole('button', { name: '新增供應商' })).toBeNull();
    fireEvent.click(screen.getByRole('tab', { name: '供應商物料' }));
    expect(await screen.findByText('目前沒有供應商物料關聯')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '新增供應商物料關聯' })).toBeNull();
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
    fireEvent.click(screen.getByRole('button', { name: /編\s*輯/ }));
    const name = screen.getByRole('textbox', { name: '名稱' });
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

    await user.click(screen.getByRole('button', { name: /新增供應商/ }));
    await user.type(screen.getByRole('textbox', { name: '供應商代碼' }), 'SUP-NEW');
    await user.type(screen.getByRole('textbox', { name: '名稱' }), 'New supplier');
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('Admin permission required')).toBeTruthy();
    expect(screen.getByText('FORBIDDEN')).toBeTruthy();
    expect((screen.getByRole('textbox', { name: '供應商代碼' }) as HTMLInputElement).value).toBe('SUP-NEW');
    expect((screen.getByRole('textbox', { name: '名稱' }) as HTMLInputElement).value).toBe('New supplier');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    await user.click(screen.getByRole('button', { name: /編\s*輯/ }));
    const editName = screen.getByRole('textbox', { name: '名稱' });
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
    const decimalPayload = { unitPrice: '999999999999999.1234', moq: '0.0001', packageMultiple: '12.3400' };
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
      if (config.url === '/supplier-materials/3' && config.method === 'patch') return response(config, { ...supplierMaterial, unitPrice: '85' });
      throw new Error(`Unexpected request: ${config.method} ${config.url}`);
    });
    renderWithRole(<SuppliersPage />, 'ADMIN');
    await screen.findByText('SUP-001');
    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: '供應商物料' }));
    await screen.findByText('目前沒有供應商物料關聯');
    await user.click(screen.getAllByRole('button', { name: /新增供應商物料關聯/ })[0]);

    await waitFor(() => expect(requests.some(({ url, params }) => url === '/suppliers' && params?.status === 'ACTIVE')).toBe(true), { timeout: 5_000 });

    const supplierSelects = screen.getAllByRole('combobox', { name: '選取供應商' });
    await user.click(supplierSelects.at(-1)!);
    await user.click((await screen.findAllByText('SUP-001 — Taiwan Foods', {}, { timeout: 5_000 })).at(-1)!);
    await waitFor(() => expect(requests.some(({ url, params }) => url === '/materials' && params?.status === 'ACTIVE')).toBe(true), { timeout: 5_000 });
    const materialSelects = screen.getAllByRole('combobox', { name: '選取物料' });
    await user.click(materialSelects.at(-1)!);
    await user.click((await screen.findAllByText('MAT-001 — Milk', {}, { timeout: 5_000 })).at(-1)!);
    const unitPriceInput = screen.getByRole('textbox', { name: '單價' });
    const moqInput = screen.getByRole('textbox', { name: /最低訂購量/ });
    const packageMultipleInput = screen.getByRole('textbox', { name: '包裝倍數' });
    fireEvent.change(unitPriceInput, { target: { value: '-1' } });
    fireEvent.change(moqInput, { target: { value: decimalPayload.moq } });
    fireEvent.change(packageMultipleInput, { target: { value: decimalPayload.packageMultiple } });
    fireEvent.change(screen.getByRole('spinbutton', { name: '交期（天）' }), { target: { value: '14' } });
    fireEvent.blur(unitPriceInput);
    expect((screen.getByRole('textbox', { name: '單價' }) as HTMLInputElement).value).toBe('-1');
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('單價不可小於 0')).toBeTruthy();
    expect(requests.filter(({ method }) => method === 'post')).toHaveLength(0);
    fireEvent.change(unitPriceInput, { target: { value: decimalPayload.unitPrice } });
    await user.click(screen.getByRole('button', { name: 'OK' }));

    expect(await screen.findByText('供應商物料資料送出失敗')).toBeTruthy();
    expect(screen.getByText('VALIDATION_FAILED')).toBeTruthy();
    expect(screen.getByText('MOQ violates supplier rule')).toBeTruthy();
    expect((screen.getByRole('textbox', { name: /最低訂購量/ }) as HTMLInputElement).value).toBe(decimalPayload.moq);
    expect(screen.queryByText('SupplierMaterial 已建立')).toBeNull();
    fireEvent.change(packageMultipleInput, { target: { value: '' } });
    expect((packageMultipleInput as HTMLInputElement).value).toBe('');
    await user.click(screen.getByRole('button', { name: 'OK' }));
    expect(await screen.findByText('TWD 80')).toBeTruthy();

    const postPayloads = requests.filter(({ method }) => method === 'post').map(({ data }) => data as string);
    expect(postPayloads).toHaveLength(2);
    expect(postPayloads[0]).toContain('"unitPrice":999999999999999.1234');
    expect(postPayloads[0]).toContain('"moq":0.0001');
    expect(postPayloads[0]).toContain('"packageMultiple":12.3400');
    expect(postPayloads[1]).toContain('"unitPrice":999999999999999.1234');
    expect(postPayloads[1]).toContain('"moq":0.0001');
    expect(postPayloads[1]).not.toContain('"packageMultiple"');
    expect(parseDecimalJson(postPayloads[0])).toEqual({
      supplierId: 2,
      materialId: 1,
      ...decimalPayload,
      leadTimeDays: 14,
      currency: 'TWD',
    });
    expect(parseDecimalJson(postPayloads[1])).toEqual({
      supplierId: 2,
      materialId: 1,
      unitPrice: decimalPayload.unitPrice,
      moq: decimalPayload.moq,
      leadTimeDays: 14,
      currency: 'TWD',
    });

    await user.click(screen.getByRole('button', { name: /編\s*輯/ }));
    fireEvent.change(screen.getByRole('textbox', { name: '單價' }), { target: { value: '85' } });
    await user.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(requests.filter(({ method }) => method === 'patch')).toHaveLength(1));
    const patchPayload = JSON.parse(requests.find(({ method }) => method === 'patch')?.data as string) as Record<string, unknown>;
    expect(patchPayload).toEqual({ unitPrice: '85', moq: '100', packageMultiple: '25', leadTimeDays: 14, status: 'ACTIVE' });
    expect(patchPayload).not.toHaveProperty('supplierId');
    expect(patchPayload).not.toHaveProperty('materialId');
    expect(patchPayload).not.toHaveProperty('currency');
    expect(requests.filter(({ url, method }) => url === '/supplier-materials' && method === 'get').length).toBeGreaterThanOrEqual(3);
  }, 60_000);
});
