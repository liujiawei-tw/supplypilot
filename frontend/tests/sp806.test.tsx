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
async function open(path: string) { localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token'); await router.navigate(path); render(<App />); }
function latestDialog() { const dialogs = screen.getAllByRole('dialog'); return dialogs[dialogs.length - 1]; }
async function openDraftProposal(user: ReturnType<typeof userEvent.setup>) {
  await open('/dashboard');
  await user.click(await screen.findByRole('button', { name: /AI Assistant/ }));
  await user.click(screen.getByRole('tab', { name: 'Draft Proposal' }));
  fireEvent.change(screen.getByLabelText('AI draft supplier ID'), { target: { value: '12' } });
  fireEvent.change(screen.getByLabelText('AI draft item 1 material ID'), { target: { value: '7' } });
  await user.type(screen.getByLabelText('AI draft item 1 quantity'), '1');
  await user.type(screen.getByLabelText('AI draft item 1 unit price'), '2');
  await user.click(screen.getByRole('button', { name: 'Create Proposal for Review' }));
  await screen.findByText('Backend Proposal Snapshot');
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
    if (isAdmin) expect(await screen.findByText('Semantic 索引管理')).toBeTruthy();
    else {
      await screen.findByText('SupplyPilot');
      expect(screen.queryByText('Semantic 索引管理')).toBeNull();
      expect(screen.queryByRole('button', { name: '執行並保存 Gate Audit' })).toBeNull();
    }
    cleanup();
  });

  it('confirms outbound approval and renders the actual Backend Review result', async () => {
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
    await screen.findByText('Supplier quote');
    await user.click(screen.getByRole('button', { name: '#21' }));
    await user.click(await screen.findByRole('button', { name: '核准出站' }));
    expect(await screen.findByText(/可能送至外部 Embedding Provider/)).toBeTruthy();
    await user.click(within(latestDialog()).getByRole('button', { name: '確認核准出站' }));
    expect(await screen.findByText('Backend 本次審核回應：APPROVED')).toBeTruthy();
    expect(screen.getByText('abc123')).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/semantic-index/documents/21/approve-outbound')).toHaveLength(1);
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
    expect(screen.getByText('PROCESSING')).toBeTruthy();
    expect(screen.getByRole('button', { name: /Upload Document/ })).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '#21' }));
    expect(await screen.findByText('Document Detail')).toBeTruthy();
    expect(screen.queryByText('/private/do-not-show')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Start Explicit Ingestion' }));
    const dialog = latestDialog();
    await user.click(within(dialog).getByRole('button', { name: 'Confirm Ingestion' }));
    expect((await screen.findAllByText('READY')).length).toBeGreaterThan(0);
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
    await user.click(screen.getByRole('button', { name: 'Start Explicit Ingestion' }));
    await user.click(within(latestDialog()).getByRole('button', { name: 'Confirm Ingestion' }));
    expect(await screen.findByText(/系統不會重送/)).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/documents/21/ingestions')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Start Explicit Ingestion' })).toBeNull();

    await user.click(screen.getAllByRole('button', { name: '查核真實文件狀態' })[0]);
    expect(await screen.findByText(/索引操作維持鎖定/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Start Explicit Ingestion' })).toBeNull();
    expect(requests.filter(({ url }) => url === '/documents/21/ingestions')).toHaveLength(1);

    const verifyButtons = screen.getAllByRole('button', { name: '查核真實文件狀態' });
    await user.click(verifyButtons[verifyButtons.length - 1]);
    expect(await screen.findByText(/已查核 Backend 真實狀態：READY/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Start Explicit Ingestion' })).toBeTruthy();
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
    await user.click(await screen.findByRole('button', { name: /Upload Document/ }));
    const uploadDialog = latestDialog();
    const type = within(uploadDialog).getByLabelText('Upload document type');
    fireEvent.mouseDown(type);
    const quotationOption = await screen.findByText('QUOTATION', { selector: '.ant-select-item-option-content' });
    await user.click(quotationOption);
    expect(within(uploadDialog).getByTitle('QUOTATION')).toBeTruthy();
    await user.type(within(uploadDialog).getByLabelText('Upload title'), 'Quote');
    await user.type(within(uploadDialog).getByLabelText('Upload version'), 'v1');
    await user.upload(within(uploadDialog).getByLabelText('Upload file'), new File(['quote'], 'quote.txt', { type: 'text/plain' }));
    const upload = within(uploadDialog).getByRole('button', { name: 'Upload' });
    fireEvent.click(upload); fireEvent.click(upload);
    await waitFor(() => expect(requests.filter(({ method, url }) => method === 'post' && url === '/documents')).toHaveLength(1));
    expect(await within(uploadDialog).findByText(/上傳結果不明/)).toBeTruthy();
    expect(screen.getByRole('button', { name: /Upload Document/ }).hasAttribute('disabled')).toBe(true);
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
    expect(await screen.findByText('Supplier quote')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Upload Document/ })).toBeNull();
    await user.click(screen.getByRole('button', { name: '#21' }));
    await screen.findByText('Document Detail');
    expect(screen.queryByRole('button', { name: 'Save Title' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Start Explicit Ingestion' })).toBeNull();
    expect(requests).toEqual(['/auth/me', '/documents', '/documents/21']);
  }, 30_000);

  it('does not call any Document or AI endpoint for warehouse role', async () => {
    const requests: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config.url ?? ''); if (config.url === '/auth/me') return response(config, warehouse); throw new Error(`Unexpected ${config.url}`); });
    await open('/documents');
    expect(await screen.findByText('403')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /AI Assistant/ })).toBeNull();
    expect(requests).toEqual(['/auth/me']);
  }, 30_000);
});

describe('SP-806 Global AI Assistant', () => {
  it('keeps the route unchanged, renders verified query sources, and gives manager no Proposal tab', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, manager);
      if (config.url === '/ai/query') return response(config, {
        status: 'ANSWERED', answer: 'Recommendation #91 uses the verified ranking.', tools: ['EXPLAIN_RECOMMENDATION'], answerSource: 'BACKEND_FACTS',
        sources: [{ resourceType: 'RECOMMENDATION', resourceId: '91', title: 'Recommendation 91', version: null, supplierId: 7, chunkIndex: null, startOffset: null, endOffset: null, excerpt: null, relevanceScore: null }],
      });
      throw new Error(`Unexpected ${config.url}`);
    });
    await open('/dashboard'); const user = userEvent.setup();
    await screen.findByText('Manager（manager）');
    await user.click(await screen.findByRole('button', { name: /AI Assistant/ }));
    expect(screen.queryByRole('tab', { name: 'Draft Proposal' })).toBeNull();
    await user.type(screen.getByLabelText('AI question'), 'Explain Recommendation #91');
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
    await user.click(await screen.findByRole('button', { name: /AI Assistant/ }));
    await user.type(screen.getByLabelText('AI question'), 'Question');
    await user.click(screen.getByRole('button', { name: '送出問題' }));
    expect(await screen.findByText(/核心採購功能與導覽仍可正常操作/)).toBeTruthy();
    expect(screen.getByText('SupplyPilot — AI Procurement & Supply Chain Decision Platform')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Close' }));
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
    await user.click(await screen.findByRole('button', { name: /AI Assistant/ }));
    await user.click(screen.getByRole('tab', { name: 'Draft Proposal' }));
    fireEvent.change(screen.getByLabelText('AI draft supplier ID'), { target: { value: '12' } });
    fireEvent.change(screen.getByLabelText('AI draft delivery date'), { target: { value: '2099-10-20' } });
    fireEvent.change(screen.getByLabelText('AI draft item 1 material ID'), { target: { value: '7' } });
    await user.type(screen.getByLabelText('AI draft item 1 quantity'), '999999999999999.1234000');
    await user.type(screen.getByLabelText('AI draft item 1 unit price'), '0.0000001234500');
    await user.click(screen.getByRole('button', { name: /Add Item/ }));
    fireEvent.change(screen.getByLabelText('AI draft item 2 material ID'), { target: { value: '8' } });
    await user.type(screen.getByLabelText('AI draft item 2 quantity'), '2.0000');
    await user.type(screen.getByLabelText('AI draft item 2 unit price'), '50.0000');
    await user.click(screen.getByRole('button', { name: 'Create Proposal for Review' }));
    expect(await screen.findByText('Backend Proposal Snapshot')).toBeTruthy();
    expect(screen.getByText('999999999999999.1234000')).toBeTruthy();
    expect(screen.queryByText('server-secret-token')).toBeNull();
    expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Review / Confirm DRAFT' }));
    const confirm = within(latestDialog()).getByRole('button', { name: 'Confirm Create DRAFT' });
    fireEvent.click(confirm); fireEvent.click(confirm);
    await waitFor(() => expect(requests.filter(({ url }) => url === '/ai/purchase-draft-proposals/proposal-1/confirm')).toHaveLength(1));
    finishConfirm();
    expect(await screen.findByText(/PO PO-000042 已由 Backend 建立/)).toBeTruthy();
    const confirmRequest = requests.find(({ url }) => url === '/ai/purchase-draft-proposals/proposal-1/confirm')!;
    expect(JSON.parse(confirmRequest.data as string)).toEqual({ confirmationToken: 'server-secret-token', draft: backendDraft });
    expect(requests.filter(({ url }) => url === '/purchase-orders')).toHaveLength(0);
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
    await user.click(await screen.findByRole('button', { name: /AI Assistant/ }));
    await user.click(screen.getByRole('tab', { name: 'Draft Proposal' }));
    fireEvent.change(screen.getByLabelText('AI draft supplier ID'), { target: { value: '12' } });
    fireEvent.change(screen.getByLabelText('AI draft item 1 material ID'), { target: { value: '7' } });
    await user.type(screen.getByLabelText('AI draft item 1 quantity'), '1');
    await user.type(screen.getByLabelText('AI draft item 1 unit price'), '2');
    await user.click(screen.getByRole('button', { name: 'Create Proposal for Review' }));
    expect(await screen.findByText(/items\[0\]\.unitPrice/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Review / Confirm DRAFT' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Create Proposal for Review' }));
    await user.click(await screen.findByRole('button', { name: 'Review / Confirm DRAFT' }));
    await user.click(within(latestDialog()).getByRole('button', { name: 'Confirm Create DRAFT' }));
    expect(await screen.findByText(/系統已鎖定重送/)).toBeTruthy();
    expect(requests.filter(({ url }) => url === '/ai/purchase-draft-proposals/p2/confirm')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Review / Confirm DRAFT' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('button', { name: 'Discard & Edit' }).hasAttribute('disabled')).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Discard & Edit' }));
    expect(screen.getByText('Backend Proposal Snapshot')).toBeTruthy();
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
    await user.click(screen.getByRole('button', { name: 'Review / Confirm DRAFT' }));
    await user.click(within(latestDialog()).getByRole('button', { name: 'Confirm Create DRAFT' }));
    expect(await screen.findByText(code === 'AI_PROPOSAL_ALREADY_CONFIRMED' ? /可能已建立採購單/ : /舊 token 不可重用/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Review / Confirm DRAFT' }).hasAttribute('disabled')).toBe(true);
    if (code === 'AI_PROPOSAL_ALREADY_CONFIRMED') expect(screen.getByRole('button', { name: 'Discard & Edit' }).hasAttribute('disabled')).toBe(true);
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
    expect(screen.getByText('Proposal 到期時間無效')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Review / Confirm DRAFT' }).hasAttribute('disabled')).toBe(true);
    expect(requests.filter(({ url }) => url === '/ai/purchase-draft-proposals/invalid-expiry/confirm')).toHaveLength(0);
  }, 30_000);
});
