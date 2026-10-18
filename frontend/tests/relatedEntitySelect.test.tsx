import { useState } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { axiosClient } from '../src/api/axiosClient';
import { AuthContext, type AuthState } from '../src/auth/authContextValue';
import RelatedEntitySelect from '../src/components/RelatedEntitySelect';
import type { AuthenticatedUser, UserRole } from '../src/types/api';

const selectedMaterial = { id: 99, code: 'MAT-099', name: 'Selected milk', baseUnit: 'L', safetyStock: 0, status: 'ACTIVE', createdAt: '', updatedAt: '' };
const firstMaterial = { ...selectedMaterial, id: 1, code: 'MAT-001', name: 'First material' };
const nextMaterial = { ...selectedMaterial, id: 21, code: 'MAT-021', name: 'Next material' };

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { config, data, status, statusText: String(status), headers: {} };
}

function page(content: unknown[], currentPage = 0, totalPages = 1) {
  return { content, page: currentPage, size: 20, totalElements: content.length, totalPages };
}

function auth(role: UserRole): AuthState {
  const user: AuthenticatedUser = { id: 1, username: role.toLowerCase(), displayName: role, role };
  return { user, isAuthenticated: true, isLoading: false, verificationError: false, retryVerification: vi.fn(), login: vi.fn(), logout: vi.fn() };
}

function renderSelect(role: UserRole, initialValue?: number, onError = vi.fn()) {
  function Harness() {
    const [value, setValue] = useState(initialValue);
    return <RelatedEntitySelect entity="material" value={value} onChange={setValue} onError={onError} />;
  }
  return render(<AuthContext.Provider value={auth(role)}><Harness /></AuthContext.Provider>);
}

beforeEach(() => { localStorage.clear(); });

describe('RelatedEntitySelect', () => {
  it.each([
    { entity: 'material' as const, prefix: 'MAT', field: 'code' as const, role: 'PROCUREMENT_STAFF' as const, search: 'MAT-101' },
    { entity: 'supplier' as const, prefix: 'SUP', field: 'name' as const, role: 'PROCUREMENT_STAFF' as const, search: 'name:Long supplier 120' },
  ])('searches beyond the first 100 $entity records and selects its exact ID', async ({ entity, prefix, field, role, search }) => {
    const fixtures = Array.from({ length: 120 }, (_, index) => ({
      ...(entity === 'material' ? selectedMaterial : { id: 0, code: '', name: '', country: null, reliabilityScore: 0, qualityScore: 0, status: 'ACTIVE', createdAt: '', updatedAt: '' }),
      id: index + 1,
      code: `${prefix}-${String(index + 1).padStart(3, '0')}`,
      name: index === 119 ? `${entity === 'material' ? 'Long material' : 'Long supplier'} ${index + 1} ${'特殊名稱'.repeat(12)}` : index === 100 ? 'Duplicate label' : `Record ${index + 1}`,
      ...(entity === 'material' ? { baseUnit: index % 2 ? 'PCS' : 'KG' } : {}),
    }));
    const requests: InternalAxiosRequestConfig[] = [];
    const onChange = vi.fn();
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      const path = entity === 'material' ? '/materials' : '/suppliers';
      if (config.url !== path) throw new Error(`Unexpected request ${config.url}`);
      let results = fixtures;
      if (config.params?.code) results = fixtures.filter((item) => item.code.includes(String(config.params.code)));
      if (config.params?.name) results = fixtures.filter((item) => item.name.includes(String(config.params.name)));
      const pageIndex = Number(config.params?.page ?? 0);
      const size = Number(config.params?.size ?? 20);
      const content = results.slice(pageIndex * size, (pageIndex + 1) * size);
      return response(config, { content, page: pageIndex, size, totalElements: results.length, totalPages: Math.ceil(results.length / size) });
    });
    render(<AuthContext.Provider value={auth(role)}><RelatedEntitySelect entity={entity} onChange={onChange} /></AuthContext.Provider>);
    const combobox = screen.getByRole('combobox', { name: entity === 'material' ? '選取物料' : '選取供應商' });
    await userEvent.click(combobox);
    expect(await screen.findByText(`${prefix}-001 — Record 1`)).toBeTruthy();
    await userEvent.type(combobox, search);
    const expected = entity === 'material' ? fixtures[100] : fixtures[119];
    const selectableLabel = `${expected.code} — ${expected.name}`;
    expect(await screen.findByText(selectableLabel)).toBeTruthy();
    const queried = field === 'code' ? 'MAT-101' : 'Long supplier 120';
    expect(requests.some(({ params }) => params?.[field] === queried)).toBe(true);
    expect(requests.filter(({ params }) => params?.[field] === queried)).toHaveLength(1);
    await userEvent.click(screen.getByText(selectableLabel));
    expect(onChange).toHaveBeenCalledWith(expected.id, expect.objectContaining({ value: expected.id }));
    expect(fixtures).toHaveLength(120);
  }, 15_000);

  it('loads 20-item pages and keeps an off-page selected label while loading the next page', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/materials/99') return response(config, selectedMaterial);
      if (config.url === '/materials' && config.params.page === 1) return response(config, page([nextMaterial], 1, 2));
      if (config.url === '/materials') return response(config, page([firstMaterial], 0, 2));
      throw new Error(`Unexpected request ${config.url}`);
    });
    renderSelect('ADMIN', 99);
    expect(await screen.findByText('MAT-099 — Selected milk')).toBeTruthy();
    const combobox = screen.getByRole('combobox', { name: '選取物料' });
    await userEvent.click(combobox);
    expect(await screen.findByText('MAT-001 — First material')).toBeTruthy();
    const scrollHolder = document.querySelector('.ant-select-dropdown-list-holder');
    expect(scrollHolder).toBeTruthy();
    fireEvent.scroll(scrollHolder!);
    expect(await screen.findByText('MAT-021 — Next material')).toBeTruthy();
    expect(screen.getAllByText('MAT-099 — Selected milk').length).toBeGreaterThan(0);
    expect(requests.filter(({ url }) => url === '/materials').map(({ params }) => ({ page: params.page, size: params.size }))).toEqual([
      { page: 0, size: 20 }, { page: 1, size: 20 },
    ]);
  }, 15_000);

  it('uses explicit code/name searches and ignores a slower stale search response', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let resolveOld!: (value: AxiosResponse) => void;
    axiosClient.defaults.adapter = vi.fn((config) => {
      requests.push(config);
      if (config.params?.code === 'OLD') return new Promise((resolve) => { resolveOld = resolve; });
      if (config.params?.name === 'Fresh') return Promise.resolve(response(config, page([{ ...firstMaterial, id: 2, code: 'MAT-NEW', name: 'Fresh' }])));
      return Promise.resolve(response(config, page([firstMaterial])));
    });
    renderSelect('ADMIN');
    const combobox = screen.getByRole('combobox', { name: '選取物料' });
    await userEvent.click(combobox);
    await screen.findByText('MAT-001 — First material');

    fireEvent.change(combobox, { target: { value: 'OLD' } });
    await waitFor(() => expect(requests.some(({ params }) => params?.code === 'OLD')).toBe(true));
    fireEvent.change(combobox, { target: { value: 'name:Fresh' } });
    expect(await screen.findByText('MAT-NEW — Fresh')).toBeTruthy();
    resolveOld(response(requests.find(({ params }) => params?.code === 'OLD')!, page([{ ...firstMaterial, id: 3, code: 'MAT-OLD', name: 'Old' }])));
    await waitFor(() => expect(screen.queryByText('MAT-OLD — Old')).toBeNull());
    expect(requests.some(({ params }) => params?.name === 'Fresh' && params?.size === 20)).toBe(true);
  }, 15_000);

  it('preserves a valid selected label when a later API request fails', async () => {
    const onError = vi.fn();
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/materials/99') return response(config, selectedMaterial);
      if (config.params?.code === 'FAIL') throw new AxiosError('Network failed', 'ERR_NETWORK', config);
      return response(config, page([firstMaterial]));
    });
    renderSelect('ADMIN', 99, onError);
    expect(await screen.findByText('MAT-099 — Selected milk')).toBeTruthy();
    const combobox = screen.getByRole('combobox', { name: '選取物料' });
    fireEvent.change(combobox, { target: { value: 'FAIL' } });
    await waitFor(() => expect(onError).toHaveBeenCalledOnce());
    expect(screen.getAllByText('MAT-099 — Selected milk').length).toBeGreaterThan(0);
  });

  it('shows a retry for API errors but treats a successful empty search as no matches', async () => {
    let fail = true;
    const onError = vi.fn();
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.params?.code === 'FAIL' && fail) throw new AxiosError('Network failed', 'ERR_NETWORK', config);
      if (config.params?.code === 'NONE') return response(config, page([]));
      if (config.params?.code === 'FAIL') { fail = false; return response(config, page([firstMaterial])); }
      return response(config, page([firstMaterial]));
    });
    renderSelect('ADMIN', undefined, onError);
    const combobox = screen.getByRole('combobox', { name: '選取物料' });
    await userEvent.click(combobox);
    await screen.findByText('MAT-001 — First material');
    await userEvent.type(combobox, 'FAIL');
    expect(await screen.findByText('無法取得搜尋結果。已選取項目會保留，請重試或調整搜尋條件。')).toBeTruthy();
    expect(screen.getByRole('button', { name: '重試搜尋' })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: '重試搜尋' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '重試搜尋' })).toBeNull());
    await userEvent.click(combobox);
    expect(await screen.findByText('MAT-001 — First material')).toBeTruthy();
    expect(onError).toHaveBeenCalled();
    await userEvent.clear(combobox);
    await userEvent.type(combobox, 'NONE');
    expect(await screen.findByText('沒有符合的項目')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '重試搜尋' })).toBeNull();
  }, 15_000);

  it('keeps an existing entity ID and offers a retry when loading its off-page label fails', async () => {
    let selectedCalls = 0;
    const onError = vi.fn();
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/materials/99' && selectedCalls++ === 0) throw new AxiosError('Not found temporarily', 'ERR_NETWORK', config);
      if (config.url === '/materials/99') return response(config, selectedMaterial);
      if (config.url === '/materials') return response(config, page([firstMaterial]));
      throw new Error(`Unexpected ${config.url}`);
    });
    function Harness() {
      const [value, setValue] = useState(99);
      return <AuthContext.Provider value={auth('ADMIN')}>
        <span aria-label="Selected entity ID">{value}</span>
        <RelatedEntitySelect entity="material" value={value} onChange={setValue} onError={onError} />
      </AuthContext.Provider>;
    }
    render(<Harness />);
    expect(await screen.findByText('目前選取值仍保留；無法確認其完整代碼與名稱，請重新載入。')).toBeTruthy();
    expect(screen.getByLabelText('Selected entity ID').textContent).toBe('99');
    await userEvent.click(screen.getByRole('button', { name: '重新載入已選項目' }));
    expect(await screen.findByText('MAT-099 — Selected milk')).toBeTruthy();
    expect(screen.getByLabelText('Selected entity ID').textContent).toBe('99');
    expect(selectedCalls).toBe(2);
    expect(onError).toHaveBeenCalled();
  }, 15_000);

  it('clears the previous selected-record error after choosing a different search result', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/materials/99') throw new AxiosError('Not found temporarily', 'ERR_NETWORK', config);
      if (config.url === '/materials') return response(config, page([firstMaterial]));
      throw new Error(`Unexpected ${config.url}`);
    });
    function Harness() {
      const [value, setValue] = useState(99);
      return <AuthContext.Provider value={auth('ADMIN')}>
        <span aria-label="Selected entity ID">{value}</span>
        <RelatedEntitySelect entity="material" value={value} onChange={setValue} />
      </AuthContext.Provider>;
    }
    render(<Harness />);
    expect(await screen.findByText('目前選取值仍保留；無法確認其完整代碼與名稱，請重新載入。')).toBeTruthy();
    const combobox = screen.getByRole('combobox', { name: '選取物料' });
    await userEvent.click(combobox);
    await userEvent.type(combobox, 'MAT-001');
    await userEvent.click(await screen.findByText('MAT-001 — First material'));
    expect(screen.getByLabelText('Selected entity ID').textContent).toBe('1');
    expect(screen.queryByText('目前選取值仍保留；無法確認其完整代碼與名稱，請重新載入。')).toBeNull();
  }, 15_000);

  it('does not issue Supplier requests for roles without Supplier API permission', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config, page([])); });
    render(<AuthContext.Provider value={auth('PRODUCTION_PLANNER')}><RelatedEntitySelect entity="supplier" /></AuthContext.Provider>);
    await new Promise((resolve) => window.setTimeout(resolve, 10));
    expect(requests).toHaveLength(0);
    expect((screen.getByRole('combobox', { name: '選取供應商' }) as HTMLInputElement).disabled).toBe(true);
  });
});
