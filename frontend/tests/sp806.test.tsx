import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../src/App';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import { router } from '../src/router/routes';
import type { AuthenticatedUser } from '../src/types/api';
import { validateDocumentFile } from '../src/pages/Documents/documentValidation';
import { cleanup } from '@testing-library/react';

const staff: AuthenticatedUser = { id: 2, username: 'buyer', displayName: 'Buyer', role: 'PROCUREMENT_STAFF' };
const manager: AuthenticatedUser = { id: 3, username: 'manager', displayName: 'Manager', role: 'PROCUREMENT_MANAGER' };
const warehouse: AuthenticatedUser = { id: 4, username: 'warehouse', displayName: 'Warehouse', role: 'WAREHOUSE_STAFF' };
const documentProcessing = { id: 21, documentType: 'QUOTATION', supplierId: 7, title: 'Supplier quote', version: 'v1', storagePath: '/private/do-not-show', status: 'PROCESSING', uploadedBy: 2, uploadedAt: '2026-10-12T00:00:00Z', updatedAt: '2026-10-12T00:00:00Z' };
const page = (content: unknown[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 });
function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse { return { config, data, status, statusText: String(status), headers: {} }; }
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
async function open(path: string) { localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token'); await router.navigate(path); render(<App />); }
function latestDialog() { const dialogs = screen.getAllByRole('dialog'); return dialogs[dialogs.length - 1]; }
function documentDrawer() { return screen.getByText('文件明細').closest('.ant-drawer')!; }
function aiAssistantDrawer() { return screen.getByText('SupplyPilot AI 助理').closest('.ant-drawer')!; }
async function openDraftProposal(user: ReturnType<typeof userEvent.setup>) {
  await open('/dashboard');
  await user.click(await screen.findByRole('button', { name: /AI 助理/ }));
  await user.click(screen.getByRole('tab', { name: '採購草稿提案' }));
  fireEvent.change(screen.getByLabelText('AI 草稿供應商編號'), { target: { value: '12' } });
  fireEvent.change(screen.getByLabelText('AI 草稿品項 1 物料編號'), { target: { value: '7' } });
  await user.type(screen.getByLabelText('AI 草稿品項 1 數量'), '1');
  await user.type(screen.getByLabelText('AI 草稿品項 1 單價'), '2');
  await user.click(screen.getByRole('button', { name: '產生提案供審閱' }));
  await screen.findByText('後端提案快照');
}
beforeEach(() => { localStorage.clear(); });

describe('SP-806 Document Management UI', () => {
  it.each([
    [{ id: 1, username: 'admin', displayName: 'Admin', role: 'ADMIN' } as AuthenticatedUser, true],
    [manager, false], [staff, false], [warehouse, false],
  ])('exposes Semantic operations only to ADMIN on the existing Documents route', async (identity, isAdmin) => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, identity);
      if (config.url === '/documents') return response(config, page([]));
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/documents');
    if (isAdmin) expect(await screen.findByText('語意索引管理')).toBeTruthy();
    else {
      await screen.findByText('SupplyPilot');
      expect(screen.queryByText('語意索引管理')).toBeNull();
      expect(screen.queryByRole('button', { name: '執行並保存索引稽核' })).toBeNull();
    }
    cleanup();
  });

  it('confirms outbound approval and renders the actual 後端 Review result', async () => {
    const ready = { ...documentProcessing, status: 'READY' };
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, { id: 1, username: 'admin', displayName: 'Admin', role: 'ADMIN' });
      if (config.url === '/documents') return response(config, page([ready]));
      if (config.url === '/documents/21') return response(config, ready);
      if (config.url === '/semantic-index/documents/21/approve-outbound') return response(config, { documentId: 21, version: 'v1', sourceHash: 'abc123', approvedBy: 1, status: 'APPROVED', reason: null });
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/documents'); const user = userEvent.setup();
    await screen.findByText('語意索引管理');
    await screen.findByText('Supplier quote');
    await user.click(screen.getByRole('button', { name: '#21' }));
    await user.click(await screen.findByRole('button', { name: '核准出站' }));
    expect(await screen.findByText(/可能送至外部向量嵌入服務/)).toBeTruthy();
    await user.click(within(latestDialog()).getByRole('button', { name: '確認核准出站' }));
    expect(await screen.findByText('本次審核回應：已核准')).toBeTruthy();
    expect(screen.getByText('abc123')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/semantic-index/documents/21/approve-outbound')).toHaveLength(1);
  });

  it('keeps a deep-linked #21 from overwriting a fast #22 switch and reviews only the current document', async () => {
    const first = { ...documentProcessing, id: 21, title: 'Quote 21', version: 'v21', status: 'READY' };
    const second = { ...documentProcessing, id: 22, title: 'Quote 22', version: 'v22', status: 'READY' };
    const delayed21 = deferred<AxiosResponse>();
    const delayed22 = deferred<AxiosResponse>();
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, { id: 1, username: 'admin', displayName: 'Admin', role: 'ADMIN' });
      if (config.url === '/documents') return response(config, page([first, second]));
      if (config.url === '/documents/21') return delayed21.promise;
      if (config.url === '/documents/22') return delayed22.promise;
      if (config.url === '/semantic-index/documents/22/approve-outbound') return response(config, { documentId: 22, version: 'v22', sourceHash: 'hash22', approvedBy: 1, status: 'APPROVED', reason: null });
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/documents?documentId=21');
    await waitFor(() => expect(requests.some(({ url }) => url === '/documents/21')).toBe(true));
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '#22' }));
    await waitFor(() => expect(requests.some(({ url }) => url === '/documents/22')).toBe(true));
    delayed22.resolve(response(requests.find(({ url }) => url === '/documents/22')!, second));
    const drawer = documentDrawer();
    await within(drawer).findByText('v22');
    delayed21.resolve(response(requests.find(({ url }) => url === '/documents/21')!, first));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(within(drawer).getByText('v22')).toBeTruthy();
    await user.click(within(drawer).getByRole('button', { name: '核准出站' }));
    await user.click(within(latestDialog()).getByRole('button', { name: '確認核准出站' }));
    expect(await within(drawer).findByText('本次審核回應：已核准')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/semantic-index/documents/22/approve-outbound')).toHaveLength(1);
    expect(requests.some(({ url }) => url === '/semantic-index/documents/21/approve-outbound')).toBe(false);
  });

  it.each(['title', 'archive', 'ingestion'] as const)('ignores a delayed %s response after switching documents', async (operation) => {
    const first = { ...documentProcessing, title: 'Quote 21', status: operation === 'ingestion' ? 'PROCESSING' : 'READY' };
    const second = { ...documentProcessing, id: 22, title: 'Quote 22', version: 'v22', status: 'READY' };
    const delayedMutation = deferred<AxiosResponse>();
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, { id: 1, username: 'admin', displayName: 'Admin', role: 'ADMIN' });
      if (config.url === '/documents' && config.method === 'get') return response(config, page([first, second]));
      if (config.url === '/documents/21' && config.method === 'get') return response(config, first);
      if (config.url === '/documents/22' && config.method === 'get') return response(config, second);
      if (config.url === '/documents/21' && config.method === 'patch') return delayedMutation.promise;
      if (config.url === '/documents/21/ingestions') return delayedMutation.promise;
      if (config.url === '/semantic-index/documents/22/approve-outbound') return response(config, { documentId: 22, version: 'v22', sourceHash: 'hash22', approvedBy: 1, status: 'APPROVED', reason: null });
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/documents'); const user = userEvent.setup();
    await screen.findByText('語意索引管理');
    await user.click(await screen.findByRole('button', { name: '#21' }));
    const drawer = documentDrawer();
    await within(drawer).findByText('Quote 21');

    if (operation === 'title') {
      const title = within(drawer).getByRole('textbox', { name: '文件標題' });
      await user.clear(title); await user.type(title, 'Changed quote');
      await user.click(within(drawer).getByRole('button', { name: '儲存標題' }));
      await waitFor(() => expect(requests.some(({ url, method }) => url === '/documents/21' && method === 'patch')).toBe(true));
    } else if (operation === 'archive') {
      await user.click(within(drawer).getByRole('button', { name: '封存文件' }));
      await waitFor(() => expect(requests.some(({ url, method }) => url === '/documents/21' && method === 'patch')).toBe(true));
    } else {
      await user.click(within(drawer).getByRole('button', { name: '開始文件解析與索引' }));
      await user.click(within(latestDialog()).getByRole('button', { name: '確認開始' }));
      await waitFor(() => expect(requests.some(({ url }) => url === '/documents/21/ingestions')).toBe(true));
    }

    await router.navigate('/documents?documentId=22');
    await within(drawer).findByText('v22');
    const lateResult = { ...first, title: `Late ${operation} result`, status: operation === 'ingestion' ? 'READY' : 'INACTIVE' };
    delayedMutation.resolve(response(requests.find(({ url, method }) => url === (operation === 'ingestion' ? '/documents/21/ingestions' : '/documents/21') && (operation === 'ingestion' || method === 'patch'))!, lateResult));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(within(drawer).getByText('Quote 22')).toBeTruthy();
    expect(within(drawer).getByText('v22')).toBeTruthy();
    expect(within(drawer).queryByText(/Late .* result/)).toBeNull();
    await user.click(within(drawer).getByRole('button', { name: '核准出站' }));
    await user.click(within(latestDialog()).getByRole('button', { name: '確認核准出站' }));
    expect(await within(drawer).findByText('本次審核回應：已核准')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/semantic-index/documents/22/approve-outbound')).toHaveLength(1);
    expect(requests.some(({ url }) => url === '/semantic-index/documents/21/approve-outbound')).toBe(false);
  });

  it('ignores a delayed ingestion verification response after switching documents', async () => {
    const first = { ...documentProcessing, title: 'Quote 21', status: 'PROCESSING' };
    const second = { ...documentProcessing, id: 22, title: 'Quote 22', version: 'v22', status: 'READY' };
    const delayedVerification = deferred<AxiosResponse>();
    const requests: InternalAxiosRequestConfig[] = [];
    let detail21Count = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, { id: 1, username: 'admin', displayName: 'Admin', role: 'ADMIN' });
      if (config.url === '/documents' && config.method === 'get') return response(config, page([first, second]));
      if (config.url === '/documents/21' && config.method === 'get') return ++detail21Count === 1 ? response(config, first) : delayedVerification.promise;
      if (config.url === '/documents/22' && config.method === 'get') return response(config, second);
      if (config.url === '/documents/21/ingestions') throw new AxiosError('timeout', 'ETIMEDOUT');
      if (config.url === '/semantic-index/documents/22/approve-outbound') return response(config, { documentId: 22, version: 'v22', sourceHash: 'hash22', approvedBy: 1, status: 'APPROVED', reason: null });
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/documents'); const user = userEvent.setup();
    await screen.findByText('語意索引管理');
    await user.click(await screen.findByRole('button', { name: '#21' }));
    const drawer = documentDrawer();
    await within(drawer).findByText('Quote 21');
    await user.click(await within(drawer).findByRole('button', { name: '開始文件解析與索引' }));
    await user.click(within(latestDialog()).getByRole('button', { name: '確認開始' }));
    await within(drawer).findByText('索引結果不明，已鎖定重送');
    const verificationButtons = within(drawer).getAllByRole('button', { name: '查核真實文件狀態' });
    await user.click(verificationButtons[verificationButtons.length - 1]);
    await waitFor(() => expect(detail21Count).toBe(2));
    await router.navigate('/documents?documentId=22');
    await within(drawer).findByText('v22');
    delayedVerification.resolve(response(requests.find(({ url }) => url === '/documents/21')!, { ...first, title: 'Late verification', status: 'READY' }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(within(drawer).getByText('Quote 22')).toBeTruthy();
    expect(within(drawer).queryByText(/已查核系統目前狀態/)).toBeNull();
    expect(within(drawer).queryByText('索引結果不明，已鎖定重送')).toBeNull();
  });

  it('refreshes the same deep-linked document and closes without reloading it', async () => {
    const original = { ...documentProcessing, title: 'Original quote', version: 'v1' };
    const refreshed = { ...documentProcessing, title: 'Updated quote', version: 'v2' };
    const calls: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      calls.push(config);
      if (config.url === '/auth/me') return response(config, { id: 1, username: 'admin', displayName: 'Admin', role: 'ADMIN' });
      if (config.url === '/documents') return response(config, page([original]));
      if (config.url === '/documents/21') return response(config, calls.filter(({ url }) => url === '/documents/21').length === 1 ? original : refreshed);
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/documents?documentId=21');
    await waitFor(() => expect(calls.filter(({ url }) => url === '/documents/21')).toHaveLength(1));
    const drawer = documentDrawer();
    await userEvent.setup().click(await within(drawer).findByRole('button', { name: '重新取得文件資訊' }));
    await within(drawer).findByText('v2');
    expect(within(drawer).getByText('v2')).toBeTruthy();
    const getCountBeforeClose = calls.filter(({ url }) => url === '/documents/21').length;
    await userEvent.setup().click(within(drawer).getByRole('button', { name: '關閉' }));
    await waitFor(() => expect(new URLSearchParams(window.location.search).has('documentId')).toBe(false));
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(calls.filter(({ url }) => url === '/documents/21')).toHaveLength(getCountBeforeClose);
    await waitFor(() => expect(documentDrawer().className).not.toContain('ant-drawer-open'));
  });

  it.each([
    ['upload', 'upload'] as const,
    ['ingestion', 'ingestion'] as const,
    ['outbound approval', 'approve'] as const,
    ['outbound exclusion', 'exclude'] as const,
  ])('marks saved Gate 稽核 responses historical after %s', async (_label, operation) => {
    let auditId = 90;
    const requests: InternalAxiosRequestConfig[] = [];
    const document = operation === 'ingestion'
      ? documentProcessing
      : { ...documentProcessing, status: 'READY' };
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, { id: 1, username: 'admin', displayName: 'Admin', role: 'ADMIN' });
      if (config.url === '/documents' && config.method === 'get') return response(config, page([document]));
      if (config.url === '/documents/21' && config.method === 'get') return response(config, document);
      if (config.url === '/documents/21/ingestions') return response(config, { ...document, status: 'READY' });
      if (config.url === '/documents' && config.method === 'post') return response(config, { ...documentProcessing, id: 22, title: 'New quote' });
      if (config.url === '/semantic-index/gate-audit') return response(config, { passed: true, generationKey: 'gen', modelId: 'model', dimensions: 768, ready: 1, skipped: 0, failed: 0, requiresReview: 0, pending: 0, findings: [], auditId: auditId++ });
      if (config.url === '/semantic-index/documents/21/approve-outbound') return response(config, { documentId: 21, version: 'v1', sourceHash: 'hash', approvedBy: 1, status: 'APPROVED', reason: null });
      if (config.url === '/semantic-index/documents/21/exclude-outbound') return response(config, { documentId: 21, version: 'v1', sourceHash: 'hash', approvedBy: 1, status: 'SKIPPED', reason: null });
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/documents'); const user = userEvent.setup();
    await screen.findByText('語意索引管理');
    const saveAudit = async () => {
      await user.click(screen.getByRole('button', { name: '執行並保存索引稽核' }));
      await user.click(screen.getByRole('button', { name: '確認並保存稽核結果' }));
      await screen.findByText('本次索引稽核通過');
    };

    await saveAudit();
    if (operation === 'upload') {
      await user.click(screen.getByRole('button', { name: /上傳文件/ }));
      const uploadDialog = latestDialog();
      fireEvent.mouseDown(within(uploadDialog).getByLabelText('上傳文件類型'));
      await user.click(await screen.findByText('報價單', { selector: '.ant-select-item-option-content' }));
      await user.type(within(uploadDialog).getByLabelText('文件標題'), 'New quote');
      await user.type(within(uploadDialog).getByLabelText('文件版本'), 'v1');
      await user.upload(within(uploadDialog).getByLabelText('上傳檔案'), new File(['quote'], 'quote.txt', { type: 'text/plain' }));
      await user.click(within(uploadDialog).getByRole('button', { name: /上\s*傳/ }));
      expect(requests.filter(({ url, method }) => url === '/documents' && method === 'post')).toHaveLength(1);
    } else {
      await user.click(await screen.findByRole('button', { name: '#21' }));
      const drawer = documentDrawer();
      if (operation === 'ingestion') {
        await user.click(await within(drawer).findByRole('button', { name: '開始文件解析與索引' }));
        await user.click(within(latestDialog()).getByRole('button', { name: '確認開始' }));
        expect(await within(drawer).findByText('已就緒')).toBeTruthy();
      } else if (operation === 'approve') {
        await user.click(await within(drawer).findByRole('button', { name: '核准出站' }));
        await user.click(within(latestDialog()).getByRole('button', { name: '確認核准出站' }));
        await within(drawer).findByText('本次審核回應：已核准');
      } else {
        await user.click(await within(drawer).findByRole('button', { name: '排除出站' }));
        await user.click(within(latestDialog()).getByRole('button', { name: '確認排除出站' }));
        await within(drawer).findByText('本次審核回應：已略過');
      }
    }
    expect(await screen.findByText('歷史索引稽核紀錄（已過期）')).toBeTruthy();
  });

  it('shows backend status, hides storagePath, and performs one explicitly confirmed ingestion followed by refresh', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, staff);
      if (config.url === '/documents' && config.method === 'get') return response(config, page([documentProcessing]));
      if (config.url === '/documents/21' && config.method === 'get') return response(config, documentProcessing);
      if (config.url === '/documents/21/ingestions') return response(config, { ...documentProcessing, status: 'READY', updatedAt: '2026-10-12T00:01:00Z' });
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/documents'); const user = userEvent.setup();
    await waitFor(() => expect(requests.map(({ method, url }) => `${method} ${url}`)).toContain('get /documents'));
    expect(await screen.findByText('Supplier quote')).toBeTruthy();
    expect(screen.getByText('處理中')).toBeTruthy();
    expect(screen.getByRole('button', { name: /上傳文件/ })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '#21' }));
    expect(await screen.findByText('文件明細')).toBeTruthy();
    expect(screen.queryByText('/private/do-not-show')).toBeNull();
    await user.click(screen.getByRole('button', { name: '開始文件解析與索引' }));
    const dialog = latestDialog();
    await user.click(within(dialog).getByRole('button', { name: '確認開始' }));
    expect((await screen.findAllByText('已就緒')).length).toBeGreaterThan(0);
    expect(requests.filter(({ url }) => url === '/documents/21/ingestions')).toHaveLength(1);
  }, 30_000);

  it('locks an unknown or 409 ingestion result until the official document GET succeeds, without a resend', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let verificationAttempts = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, staff);
      if (config.url === '/documents' && config.method === 'get') return response(config, page([documentProcessing]));
      if (config.url === '/documents/21' && config.method === 'get') {
        verificationAttempts += 1;
        if (verificationAttempts === 2) throw new AxiosError('Network error', 'ERR_NETWORK', config);
        return response(config, { ...documentProcessing, status: 'READY' });
      }
      if (config.url === '/documents/21/ingestions') {
        throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, undefined,
          response(config, { code: 'DOCUMENT_INGESTION_CONFLICT', message: 'Conflict', traceId: 'trace', timestamp: '', path: '' }, 409));
      }
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/documents'); const user = userEvent.setup();
    await waitFor(() => expect(requests.map(({ method, url }) => `${method} ${url}`)).toContain('get /documents'));
    expect(await screen.findByText('Supplier quote')).toBeTruthy();
    await user.click(await screen.findByRole('button', { name: '#21' }));
    await user.click(screen.getByRole('button', { name: '開始文件解析與索引' }));
    await user.click(within(latestDialog()).getByRole('button', { name: '確認開始' }));
    expect(await screen.findByText(/系統不會重送/)).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/documents/21/ingestions')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: '開始文件解析與索引' })).toBeNull();

    await user.click(screen.getAllByRole('button', { name: '查核真實文件狀態' })[0]);
    expect(await screen.findByText(/索引操作維持鎖定/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: '開始文件解析與索引' })).toBeNull();
    expect(requests.filter(({ url }) => url === '/documents/21/ingestions')).toHaveLength(1);

    const verifyButtons = screen.getAllByRole('button', { name: '查核真實文件狀態' });
    await user.click(verifyButtons[verifyButtons.length - 1]);
    expect(await screen.findByText(/已查核系統目前狀態：已就緒/)).toBeTruthy();
    expect(screen.getByRole('button', { name: '開始文件解析與索引' })).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/documents/21/ingestions')).toHaveLength(1);
  }, 30_000);

  it('validates document file extension, browser content type, and empty content', () => {
    expect(validateDocumentFile(new File(['not-pdf'], 'fake.pdf', { type: 'text/plain' }))).toMatch(/內容類型相符/);
    expect(validateDocumentFile(new File([], 'empty.txt', { type: 'text/plain' }))).toMatch(/非空白/);
    expect(validateDocumentFile(new File(['quote'], 'quote.txt', { type: 'text/plain' }))).toBeNull();
  });

  it('submits one upload on rapid clicks and locks an unknown upload instead of guessing its result', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, staff);
      if (config.url === '/documents' && config.method === 'get') return response(config, page([]));
      if (config.url === '/documents' && config.method === 'post') throw new AxiosError('Timeout', 'ECONNABORTED', config);
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });
    await open('/documents'); const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /上傳文件/ }));
    const uploadDialog = latestDialog();
    const type = within(uploadDialog).getByLabelText('上傳文件類型');
    fireEvent.mouseDown(type);
    const quotationOption = await screen.findByText('報價單', { selector: '.ant-select-item-option-content' });
    await user.click(quotationOption);
    expect(within(uploadDialog).getByTitle('報價單')).toBeTruthy();
    await user.type(within(uploadDialog).getByLabelText('文件標題'), 'Quote');
    await user.type(within(uploadDialog).getByLabelText('文件版本'), 'v1');
    await user.upload(within(uploadDialog).getByLabelText('上傳檔案'), new File(['quote'], 'quote.txt', { type: 'text/plain' }));
    const upload = within(uploadDialog).getByRole('button', { name: /上\s*傳/ });
    fireEvent.click(upload); fireEvent.click(upload);
    await waitFor(() => expect(requests.filter(({ method, url }) => method === 'post' && url === '/documents')).toHaveLength(1));
    expect(await within(uploadDialog).findByText(/上傳結果不明/)).toBeTruthy();
    expect(screen.getByRole('button', { name: /上傳文件/ }).hasAttribute('disabled')).toBe(true);
  }, 30_000);

  it('gives manager read-only Documents without upload, edit, or ingestion controls', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config.url ?? '');
      if (config.url === '/auth/me') return response(config, manager);
      if (config.url === '/documents') return response(config, page([documentProcessing]));
      if (config.url === '/documents/21') return response(config, documentProcessing);
      throw new Error(`Unexpected ${config.url}`);
    });
    await open('/documents'); const user = userEvent.setup();
    await screen.findByRole('heading', { name: '文件管理' });
    expect(await screen.findByText('Supplier quote')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /上傳文件/ })).toBeNull();
    await user.click(screen.getByRole('button', { name: '#21' }));
    await screen.findByText('文件明細');
    expect(screen.queryByRole('button', { name: '儲存標題' })).toBeNull();
    expect(screen.queryByRole('button', { name: '開始文件解析與索引' })).toBeNull();
    expect(requests).toEqual(['/auth/me', '/documents', '/documents/21']);
  }, 30_000);

  it('does not call any Document or AI endpoint for warehouse role', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config.url ?? ''); if (config.url === '/auth/me') return response(config, warehouse); throw new Error(`Unexpected ${config.url}`); });
    await open('/documents');
    expect(await screen.findByText('403')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /AI 助理/ })).toBeNull();
    expect(requests).toEqual(['/auth/me']);
  }, 30_000);
});

describe('SP-806 Global AI 助理', () => {
  it('keeps the route unchanged, renders verified query sources, and gives manager no Proposal tab', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, manager);
      if (config.url === '/ai/query') return response(config, {
        status: 'ANSWERED', answer: 'Recommendation #91 uses the verified ranking.', tools: ['EXPLAIN_RECOMMENDATION'], answerSource: 'BACKEND_FACTS',
        sources: [{ resourceType: 'RECOMMENDATION', resourceId: '91', title: 'Recommendation 91', documentKey: null, version: null, supplierId: 7, chunkIndex: null, startOffset: null, endOffset: null, excerpt: null, relevanceScore: null }],
      });
      throw new Error(`Unexpected ${config.url}`);
    });
    await open('/dashboard'); const user = userEvent.setup();
    await screen.findByText('Manager（manager）');
    await user.click(await screen.findByRole('button', { name: /AI 助理/ }));
    expect(screen.queryByRole('tab', { name: '採購草稿提案' })).toBeNull();
    await user.type(screen.getByLabelText('AI 問題'), 'Explain Recommendation #91');
    await user.click(screen.getByRole('button', { name: '送出問題' }));
    expect(await screen.findByText('Recommendation #91 uses the verified ranking.')).toBeTruthy();
    expect(screen.getByRole('link', { name: '91' }).getAttribute('href')).toBe('/recommendations/91');
    expect(router.state.location.pathname).toBe('/dashboard');
    expect(JSON.parse(requests.find(({ url }) => url === '/ai/query')!.data as string)).toEqual({ message: 'Explain Recommendation #91' });
  }, 30_000);

  it('isolates AI 503 so navigation stays usable and the drawer can close', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      if (config.url === '/auth/me') return response(config, staff);
      if (config.url === '/ai/query') throw new AxiosError('Unavailable', 'ERR_BAD_RESPONSE', config, undefined,
        response(config, { code: 'AI_PROVIDER_UNAVAILABLE', message: 'Unavailable', traceId: 'trace-ai', timestamp: '', path: '' }, 503));
      throw new Error(`Unexpected ${config.url}`);
    });
    await open('/dashboard'); const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /AI 助理/ }));
    await user.type(screen.getByLabelText('AI 問題'), 'Question');
    await user.click(screen.getByRole('button', { name: '送出問題' }));
    expect(await screen.findByText(/核心採購功能與導覽仍可正常操作/)).toBeTruthy();
    expect(screen.getByText('SupplyPilot — AI 採購與供應鏈決策平台')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '關閉' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(router.state.location.pathname).toBe('/dashboard');
  }, 30_000);

  it('reviews a multi-item backend snapshot and sends one token-based Confirm with no ordinary PO create call', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    let finishConfirm!: () => void;
    const backendDraft = {
      supplierId: 12, expectedDeliveryDate: '2099-10-20', items: [
        { materialId: 7, quantity: '999999999999999.1234000', unitPrice: '0.0000001234500', recommendationId: 91, supplierRecommendationResultId: 18 },
        { materialId: 8, quantity: '2.0000', unitPrice: '50.0000', supplierOverrideReason: 'Approved alternate' },
      ],
    };
    axiosClient.defaults.adapter = vi.fn((config) => {
      requests.push(config);
      if (config.url === '/auth/me') return Promise.resolve(response(config, staff));
      if (config.url === '/ai/purchase-draft-proposals') return Promise.resolve(response(config, { status: 'PENDING_CONFIRMATION', proposalId: 'proposal-1', draft: backendDraft, expiresAt: '2099-10-20T00:00:00Z', confirmationToken: 'server-secret-token', missingFields: [] }));
      if (config.url === '/ai/purchase-draft-proposals/proposal-1/confirm') return new Promise((resolve) => { finishConfirm = () => resolve(response(config, { id: 42, poNumber: 'PO-000042', status: 'DRAFT', items: [] })); });
      return Promise.reject(new Error(`Unexpected ${config.url}`));
    });
    await open('/dashboard'); const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /AI 助理/ }));
    const drawer = aiAssistantDrawer();
    await user.click(within(drawer).getByRole('tab', { name: '採購草稿提案' }));
    fireEvent.change(within(drawer).getByLabelText('AI 草稿供應商編號'), { target: { value: '12' } });
    fireEvent.change(within(drawer).getByLabelText('AI 草稿預計交期'), { target: { value: '2099-10-20' } });
    fireEvent.change(within(drawer).getByLabelText('AI 草稿品項 1 物料編號'), { target: { value: '7' } });
    fireEvent.change(within(drawer).getByLabelText('AI 草稿品項 1 數量'), { target: { value: '999999999999999.1234000' } });
    fireEvent.change(within(drawer).getByLabelText('AI 草稿品項 1 單價'), { target: { value: '0.0000001234500' } });
    await user.click(within(drawer).getByRole('button', { name: /新增品項/ }));
    fireEvent.change(within(drawer).getByLabelText('AI 草稿品項 2 物料編號'), { target: { value: '8' } });
    fireEvent.change(within(drawer).getByLabelText('AI 草稿品項 2 數量'), { target: { value: '2.0000' } });
    fireEvent.change(within(drawer).getByLabelText('AI 草稿品項 2 單價'), { target: { value: '50.0000' } });
    await user.click(within(drawer).getByRole('button', { name: '產生提案供審閱' }));
    expect(await within(drawer).findByText('後端提案快照')).toBeTruthy();
    expect(within(drawer).getByText('999999999999999.1234000')).toBeTruthy();
    expect(screen.queryByText('server-secret-token')).toBeNull();
    expect(requests.filter(({ url, method }) => url === '/purchase-orders' && method?.toUpperCase() === 'POST')).toHaveLength(0);
    await user.click(within(drawer).getByRole('button', { name: '審閱並確認草稿' }));
    const confirm = within(latestDialog()).getByRole('button', { name: '確認建立草稿' });
    fireEvent.click(confirm); fireEvent.click(confirm);
    await waitFor(() => expect(requests.filter(({ url }) => url === '/ai/purchase-draft-proposals/proposal-1/confirm')).toHaveLength(1));
    finishConfirm();
    expect(await within(drawer).findByText(/採購單 PO-000042 已建立/)).toBeTruthy();
    const confirmRequest = requests.find(({ url }) => url === '/ai/purchase-draft-proposals/proposal-1/confirm')!;
    expect(JSON.parse(confirmRequest.data as string)).toEqual({ confirmationToken: 'server-secret-token', draft: backendDraft });
    expect(requests.filter(({ url, method }) => url === '/purchase-orders' && method?.toUpperCase() === 'POST')).toHaveLength(0);
  }, 30_000);

  it('shows clarification without Confirm and locks unknown Confirm results without resending', async () => {
    let proposalCount = 0;
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, staff);
      if (config.url === '/ai/purchase-draft-proposals') {
        proposalCount += 1;
        if (proposalCount === 1) return response(config, { status: 'NEEDS_CLARIFICATION', proposalId: null, draft: null, expiresAt: null, confirmationToken: null, missingFields: ['items[0].unitPrice'] });
        return response(config, { status: 'PENDING_CONFIRMATION', proposalId: 'p2', draft: { supplierId: 12, items: [{ materialId: 7, quantity: '1', unitPrice: '2' }] }, expiresAt: '2099-01-01T00:00:00Z', confirmationToken: 'token-2', missingFields: [] });
      }
      if (config.url === '/ai/purchase-draft-proposals/p2/confirm') throw new AxiosError('Timeout', 'ECONNABORTED', config);
      throw new Error(`Unexpected ${config.url}`);
    });
    await open('/dashboard'); const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /AI 助理/ }));
    const drawer = aiAssistantDrawer();
    await user.click(within(drawer).getByRole('tab', { name: '採購草稿提案' }));
    fireEvent.change(within(drawer).getByLabelText('AI 草稿供應商編號'), { target: { value: '12' } });
    fireEvent.change(within(drawer).getByLabelText('AI 草稿品項 1 物料編號'), { target: { value: '7' } });
    fireEvent.change(within(drawer).getByLabelText('AI 草稿品項 1 數量'), { target: { value: '1' } });
    fireEvent.change(within(drawer).getByLabelText('AI 草稿品項 1 單價'), { target: { value: '2' } });
    await user.click(within(drawer).getByRole('button', { name: '產生提案供審閱' }));
    expect(await within(drawer).findByText(/items\[0\]\.unitPrice/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: '審閱並確認草稿' })).toBeNull();
    await user.click(within(drawer).getByRole('button', { name: '產生提案供審閱' }));
    await user.click(await within(drawer).findByRole('button', { name: '審閱並確認草稿' }));
    await user.click(within(latestDialog()).getByRole('button', { name: '確認建立草稿' }));
    expect(await within(drawer).findByText(/系統已鎖定重送/)).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/ai/purchase-draft-proposals/p2/confirm')).toHaveLength(1);
    expect(within(drawer).getByRole('button', { name: '審閱並確認草稿' }).hasAttribute('disabled')).toBe(true);
    expect(within(drawer).getByRole('button', { name: '捨棄並編輯' }).hasAttribute('disabled')).toBe(true);
    await user.click(within(drawer).getByRole('button', { name: '捨棄並編輯' }));
    expect(within(drawer).getByText('後端提案快照')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/ai/purchase-draft-proposals')).toHaveLength(2);
  }, 30_000);

  it.each(['AI_PROPOSAL_ALREADY_CONFIRMED', 'AI_PROPOSAL_EXPIRED'] as const)('locks the old Proposal after %s', async (code) => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, staff);
      if (config.url === '/ai/purchase-draft-proposals') return response(config, {
        status: 'PENDING_CONFIRMATION', proposalId: 'terminal-proposal', draft: { supplierId: 12, items: [{ materialId: 7, quantity: '1', unitPrice: '2' }] },
        expiresAt: '2099-01-01T00:00:00Z', confirmationToken: 'server-token', missingFields: [],
      });
      if (config.url === '/ai/purchase-draft-proposals/terminal-proposal/confirm') {
        throw new AxiosError(code, 'ERR_BAD_REQUEST', config, undefined,
          response(config, { code, message: code, traceId: 'trace', timestamp: '', path: '' }, 409));
      }
      throw new Error(`Unexpected ${config.url}`);
    });
    const user = userEvent.setup();
    await openDraftProposal(user);
    await user.click(screen.getByRole('button', { name: '審閱並確認草稿' }));
    await user.click(within(latestDialog()).getByRole('button', { name: '確認建立草稿' }));
    expect(await screen.findByText(code === 'AI_PROPOSAL_ALREADY_CONFIRMED' ? /可能已建立採購單/ : /舊權杖不可重用/)).toBeTruthy();
    expect(screen.getByRole('button', { name: '審閱並確認草稿' }).hasAttribute('disabled')).toBe(true);
    if (code === 'AI_PROPOSAL_ALREADY_CONFIRMED') expect(screen.getByRole('button', { name: '捨棄並編輯' }).hasAttribute('disabled')).toBe(true);
    expect(requests.filter(({ url }) => url === '/ai/purchase-draft-proposals/terminal-proposal/confirm')).toHaveLength(1);
  }, 30_000);

  it('fails closed when Proposal expiresAt is invalid without calling Confirm', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, staff);
      if (config.url === '/ai/purchase-draft-proposals') return response(config, {
        status: 'PENDING_CONFIRMATION', proposalId: 'invalid-expiry', draft: { supplierId: 12, items: [{ materialId: 7, quantity: '1', unitPrice: '2' }] },
        expiresAt: 'not-a-date', confirmationToken: 'server-token', missingFields: [],
      });
      throw new Error(`Unexpected ${config.url}`);
    });
    const user = userEvent.setup();
    await openDraftProposal(user);
    expect(screen.getByText('提案到期時間無效')).toBeTruthy();
    expect(screen.getByRole('button', { name: '審閱並確認草稿' }).hasAttribute('disabled')).toBe(true);
    expect(requests.filter(({ url }) => url === '/ai/purchase-draft-proposals/invalid-expiry/confirm')).toHaveLength(0);
  }, 30_000);
});
