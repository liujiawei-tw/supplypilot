import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import App from '../src/App';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import { router } from '../src/router/routes';
import type { AuthenticatedUser, UserRole } from '../src/types/api';

const identities: Record<UserRole, AuthenticatedUser> = {
  PRODUCTION_PLANNER: { id: 1, username: 'planner', displayName: 'Planner', role: 'PRODUCTION_PLANNER' },
  PROCUREMENT_STAFF: { id: 2, username: 'buyer', displayName: 'Buyer', role: 'PROCUREMENT_STAFF' },
  PROCUREMENT_MANAGER: { id: 3, username: 'manager', displayName: 'Manager', role: 'PROCUREMENT_MANAGER' },
  WAREHOUSE_STAFF: { id: 4, username: 'warehouse', displayName: 'Warehouse', role: 'WAREHOUSE_STAFF' },
  ADMIN: { id: 5, username: 'admin', displayName: 'Admin', role: 'ADMIN' },
};

function response(config: InternalAxiosRequestConfig, data: unknown = undefined, status = 200): AxiosResponse {
  return { config, data, status, statusText: String(status), headers: {} };
}

async function open(role: UserRole) {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
  await router.navigate('/inventory/import');
  render(<App />);
  return role;
}

function csv(name = 'inventory.csv', quantity = '10') {
  return new File([`material_code,quantity,unit\nMAT-001,${quantity},KG\n`], name, { type: 'text/csv' });
}

beforeEach(() => localStorage.clear());

describe('SP-803 role isolation', () => {
  it.each(['PRODUCTION_PLANNER', 'PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER'] as const)('renders 403 without mounting or requesting import APIs for %s', async (role) => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config.url ?? '');
      if (config.url === '/auth/me') return response(config, identities[role]);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open(role);
    expect(await screen.findByText('403')).toBeTruthy();
    expect(screen.queryByLabelText('庫存 CSV 檔案')).toBeNull();
    expect(screen.queryByRole('link', { name: '庫存匯入' })).toBeNull();
    expect(requests).toEqual(['/auth/me']);
  });

  it.each(['WAREHOUSE_STAFF', 'ADMIN'] as const)('allows %s to open the page and shows its sidebar entry', async (role) => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities[role]);
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open(role);
    expect(await screen.findByRole('heading', { name: '庫存匯入' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '庫存匯入' })).toBeTruthy();
  });
});

describe('SP-803 validate, preview and confirm flow', () => {
  it('requires a file, distinguishes local preview from backend status and confirms a validated batch exactly once', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let finishConfirm!: () => void;
    axiosClient.defaults.adapter = vi.fn((config) => {
      requests.push(config);
      if (config.url === '/auth/me') return Promise.resolve(response(config, identities.WAREHOUSE_STAFF));
      if (config.url === '/inventory-imports/validate') return Promise.resolve(response(config, { batchId: 123, status: 'VALIDATED', totalRows: 1, validRows: 1, errorRows: 0, errors: [] }));
      if (config.url === '/inventory-imports/123/confirm') return new Promise((resolve) => { finishConfirm = () => resolve(response(config)); });
      return Promise.reject(new Error(`Unexpected request ${config.url}`));
    });
    await open('WAREHOUSE_STAFF');
    const user = userEvent.setup();
    const validateButton = await screen.findByRole('button', { name: '驗證並預覽' });
    expect(validateButton.hasAttribute('disabled')).toBe(true);
    await user.upload(screen.getByLabelText('庫存 CSV 檔案'), csv());
    expect(await screen.findByText('瀏覽器讀取')).toBeTruthy();
    expect(screen.getByText(/本機預覽，不會決定批次狀態或確認資格/)).toBeTruthy();
    expect(screen.queryByText('後端驗證通過，尚未匯入')).toBeNull();
    await user.click(validateButton);
    expect(await screen.findByText('後端驗證通過，尚未匯入')).toBeTruthy();
    expect(screen.getByText('123')).toBeTruthy();
    expect(screen.queryByText('庫存匯入已完成')).toBeNull();

    await user.click(screen.getByRole('button', { name: '確認匯入' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/取代所列物料的現有庫存/)).toBeTruthy();
    const finalConfirm = within(dialog).getByRole('button', { name: '確認匯入' });
    await user.click(finalConfirm);
    fireEvent.click(finalConfirm);
    await waitFor(() => expect(requests.filter(({ url }) => url === '/inventory-imports/123/confirm')).toHaveLength(1));
    expect(await screen.findByText(/正在確認匯入/)).toBeTruthy();
    await act(async () => finishConfirm());
    expect(await screen.findByText('庫存匯入已完成')).toBeTruthy();
    expect(screen.getByRole('button', { name: '確認匯入' }).hasAttribute('disabled')).toBe(true);
  }, 30_000);

  it('shows every backend row error and never enables confirm for VALIDATION_FAILED', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities.ADMIN);
      if (config.url === '/inventory-imports/validate') return response(config, {
        batchId: 124, status: 'VALIDATION_FAILED', totalRows: 2, validRows: 0, errorRows: 2,
        errors: [
          { row: 2, materialCode: 'MAT-001', code: 'INVALID_QUANTITY', message: 'Invalid quantity' },
          { row: 3, materialCode: 'MAT-002', code: 'UNIT_MISMATCH', message: 'Expected KG' },
        ],
      });
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('ADMIN');
    const user = userEvent.setup();
    await user.upload(await screen.findByLabelText('庫存 CSV 檔案'), csv());
    await user.click(screen.getByRole('button', { name: '驗證並預覽' }));
    expect(await screen.findByText('驗證失敗，尚未匯入')).toBeTruthy();
    expect(screen.getByText('INVALID_QUANTITY')).toBeTruthy();
    expect(screen.getByText('UNIT_MISMATCH')).toBeTruthy();
    expect(screen.getByText('Expected KG')).toBeTruthy();
    expect(screen.getByRole('button', { name: '確認匯入' }).hasAttribute('disabled')).toBe(true);
  }, 30_000);

  it('invalidates an old batch when the file changes and ignores its late validation response', async () => {
    let finishFirst!: () => void;
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn((config) => {
      requests.push(config);
      if (config.url === '/auth/me') return Promise.resolve(response(config, identities.ADMIN));
      if (config.url === '/inventory-imports/validate') return new Promise((resolve) => {
        finishFirst = () => resolve(response(config, { batchId: 500, status: 'VALIDATED', totalRows: 1, validRows: 1, errorRows: 0, errors: [] }));
      });
      return Promise.reject(new Error(`Unexpected request ${config.url}`));
    });
    await open('ADMIN');
    const user = userEvent.setup();
    const input = await screen.findByLabelText('庫存 CSV 檔案');
    await user.upload(input, csv('first.csv'));
    const validateButton = screen.getByRole('button', { name: '驗證並預覽' });
    await user.click(validateButton);
    fireEvent.click(validateButton);
    await waitFor(() => expect(finishFirst).toBeTypeOf('function'));
    expect(requests.filter(({ url }) => url === '/inventory-imports/validate')).toHaveLength(1);
    await user.upload(input, csv('second.csv', '20'));
    await act(async () => finishFirst());
    expect(screen.getByText('second.csv')).toBeTruthy();
    expect(screen.queryByText('500')).toBeNull();
    expect(screen.queryByRole('button', { name: '確認匯入' })).toBeNull();
    expect(requests.filter(({ url }) => url === '/inventory-imports/validate')).toHaveLength(1);
  }, 30_000);

  it('keeps a network error as an unknown locked result without retrying', async () => {
    let confirmAttempt = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities.ADMIN);
      if (config.url === '/inventory-imports/validate') return response(config, { batchId: 700, status: 'VALIDATED', totalRows: 1, validRows: 1, errorRows: 0, errors: [] });
      if (config.url === '/inventory-imports/700/confirm') {
        confirmAttempt += 1;
        throw new AxiosError('Network Error', 'ERR_NETWORK', config);
      }
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('ADMIN');
    const user = userEvent.setup();
    await user.upload(await screen.findByLabelText('庫存 CSV 檔案'), csv());
    await user.click(screen.getByRole('button', { name: '驗證並預覽' }));
    await screen.findByText('後端驗證通過，尚未匯入');
    await user.click(screen.getByRole('button', { name: '確認匯入' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '確認匯入' }));
    expect(await screen.findByText('確認結果未知')).toBeTruthy();
    expect(confirmAttempt).toBe(1);
    const locked = screen.getByRole('button', { name: '確認匯入' });
    expect(locked.hasAttribute('disabled')).toBe(true);
    fireEvent.click(locked);
    expect(confirmAttempt).toBe(1);
  }, 30_000);

  it('shows a backend 409 error without claiming success or retrying', async () => {
    let confirmAttempt = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identities.WAREHOUSE_STAFF);
      if (config.url === '/inventory-imports/validate') return response(config, { batchId: 701, status: 'VALIDATED', totalRows: 1, validRows: 1, errorRows: 0, errors: [] });
      if (config.url === '/inventory-imports/701/confirm') {
        confirmAttempt += 1;
        throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined, response(config, {
          code: 'INVENTORY_IMPORT_ALREADY_CONFIRMED', message: 'Batch was already confirmed.', traceId: 'trace-409', timestamp: '', path: config.url,
        }, 409));
      }
      throw new Error(`Unexpected request ${config.url}`);
    });
    await open('WAREHOUSE_STAFF');
    const user = userEvent.setup();
    await user.upload(await screen.findByLabelText('庫存 CSV 檔案'), csv());
    await user.click(screen.getByRole('button', { name: '驗證並預覽' }));
    await screen.findByText('後端驗證通過，尚未匯入');
    await user.click(screen.getByRole('button', { name: '確認匯入' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '確認匯入' }));
    expect(await screen.findByText('INVENTORY_IMPORT_ALREADY_CONFIRMED')).toBeTruthy();
    expect(screen.getByText('確認匯入未成功')).toBeTruthy();
    expect(screen.queryByText('庫存匯入已完成')).toBeNull();
    expect(screen.getByRole('button', { name: '確認匯入' }).hasAttribute('disabled')).toBe(true);
    expect(confirmAttempt).toBe(1);
  }, 30_000);
});
