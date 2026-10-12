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
    const combobox = screen.getByRole('combobox', { name: 'Select material' });
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
    const combobox = screen.getByRole('combobox', { name: 'Select material' });
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
    const combobox = screen.getByRole('combobox', { name: 'Select material' });
    fireEvent.change(combobox, { target: { value: 'FAIL' } });
    await waitFor(() => expect(onError).toHaveBeenCalledOnce());
    expect(screen.getAllByText('MAT-099 — Selected milk').length).toBeGreaterThan(0);
  });

  it('does not issue Supplier requests for roles without Supplier API permission', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config, page([])); });
    render(<AuthContext.Provider value={auth('PRODUCTION_PLANNER')}><RelatedEntitySelect entity="supplier" /></AuthContext.Provider>);
    await new Promise((resolve) => window.setTimeout(resolve, 10));
    expect(requests).toHaveLength(0);
    expect((screen.getByRole('combobox', { name: 'Select supplier' }) as HTMLInputElement).disabled).toBe(true);
  });
});
