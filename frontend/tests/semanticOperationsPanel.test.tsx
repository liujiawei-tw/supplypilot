import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import SemanticOperationsPanel from '../src/pages/Documents/SemanticOperationsPanel';
import * as semantic from '../src/api/semanticIndex';
import { AxiosError } from 'axios';

vi.mock('../src/api/semanticIndex', () => ({
  getSemanticIndexJobs: vi.fn(), retrySemanticIndexJob: vi.fn(), getSemanticReadiness: vi.fn(), runBackfill: vi.fn(), runSemanticGateAudit: vi.fn(), approveOutbound: vi.fn(), excludeOutbound: vi.fn(),
}));

const report: semantic.SemanticGateReport = { passed: true, generationKey: 'gen-1', modelId: 'embedding-model', dimensions: 768, ready: 6, skipped: 0, failed: 0, requiresReview: 0, pending: 0, findings: [], auditId: null };
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

function mount(documentMutationVersion = 0) { return render(<MemoryRouter><SemanticOperationsPanel documentMutationVersion={documentMutationVersion} /></MemoryRouter>); }

beforeEach(() => { vi.clearAllMocks(); vi.mocked(semantic.getSemanticIndexJobs).mockResolvedValue([]); });

describe('Semantic admin operations UI', () => {

  it('shows per-document status and retries only FAILED jobs', async () => {
    const failed: semantic.SemanticIndexJob = { id: 9, documentId: 21, version: 'v3', status: 'FAILED',
      attempts: 4, maxAttempts: 4, retryCount: 0, nextAttemptAt: null,
      failureCode: 'PROVIDER_TIMEOUT', updatedAt: '2026-10-24T01:00:00Z' };
    const queued: semantic.SemanticIndexJob = { ...failed, status: 'QUEUED', attempts: 0, retryCount: 1, failureCode: null };
    vi.mocked(semantic.getSemanticIndexJobs).mockResolvedValueOnce([failed]).mockResolvedValueOnce([queued]);
    vi.mocked(semantic.retrySemanticIndexJob).mockResolvedValue(queued);
    const user = userEvent.setup(); mount();
    expect(await screen.findByText('PROVIDER_TIMEOUT')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '手動重試' }));
    expect(semantic.retrySemanticIndexJob).toHaveBeenCalledWith(9);
    await waitFor(() => expect(screen.queryByRole('button', { name: '手動重試' })).toBeNull());
    expect(semantic.getSemanticIndexJobs).toHaveBeenCalledTimes(2);
  });
  it('keeps Readiness PASS distinct from a saved 稽核 and renders the formal 稽核 response', async () => {
    vi.mocked(semantic.getSemanticReadiness).mockResolvedValue(report);
    vi.mocked(semantic.runSemanticGateAudit).mockResolvedValue({ ...report, auditId: 81 });
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '讀取目前就緒狀態' }));
    expect(await screen.findByText(/就緒條件目前符合/)).toBeTruthy();
    expect(screen.getByText(/不代表已保存索引稽核/)).toBeTruthy();
    expect(semantic.runSemanticGateAudit).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '執行並保存索引稽核' }));
    await user.click(screen.getByRole('button', { name: '確認並保存稽核結果' }));
    expect(await screen.findByText('稽核編號')).toBeTruthy();
    expect(screen.getByText('81')).toBeTruthy();
    expect(screen.getByText(/檢核通過不會自動啟用語意查詢/)).toBeTruthy();
  });

  it('marks a saved 稽核 as historical after Backfill without clearing its audit record', async () => {
    vi.mocked(semantic.runSemanticGateAudit).mockResolvedValue({ ...report, auditId: 82 });
    vi.mocked(semantic.runBackfill).mockResolvedValue({ processed: 1, ready: 1, failed: 0, skipped: 0, requiresReview: 0, lastId: 19, morePossible: false });
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '執行並保存索引稽核' }));
    await user.click(screen.getByRole('button', { name: '確認並保存稽核結果' }));
    expect(await screen.findByText('本次索引稽核通過')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '執行一批' }));
    expect(await screen.findByText('歷史索引稽核紀錄（已過期）')).toBeTruthy();
    expect(screen.getByText('稽核編號')).toBeTruthy();
    expect(screen.getByText('82')).toBeTruthy();
    expect(screen.queryByText('本次索引稽核通過')).toBeNull();
  });

  it('invalidates external document mutations and clears an old Readiness response when refresh fails', async () => {
    vi.mocked(semantic.runSemanticGateAudit).mockResolvedValue({ ...report, auditId: 83 });
    vi.mocked(semantic.getSemanticReadiness).mockResolvedValueOnce(report).mockRejectedValueOnce(new Error('offline'));
    const user = userEvent.setup(); const view = mount();
    await user.click(screen.getByRole('button', { name: '執行並保存索引稽核' }));
    await user.click(screen.getByRole('button', { name: '確認並保存稽核結果' }));
    expect(await screen.findByText('本次索引稽核通過')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '讀取目前就緒狀態' }));
    expect(await screen.findByText('就緒條件目前符合')).toBeTruthy();
    view.rerender(<MemoryRouter><SemanticOperationsPanel documentMutationVersion={1} /></MemoryRouter>);
    expect(await screen.findByText('歷史索引稽核紀錄（已過期）')).toBeTruthy();
    expect(screen.queryByText('就緒條件目前符合')).toBeNull();
    await user.click(screen.getByRole('button', { name: '讀取目前就緒狀態' }));
    expect(await screen.findByText('無法確認目前就緒狀態')).toBeTruthy();
    expect(screen.queryByText('就緒條件目前符合')).toBeNull();
  });

  it('keeps 稽核 FAIL distinct from a later Readiness PASS', async () => {
    vi.mocked(semantic.runSemanticGateAudit).mockResolvedValue({ ...report, passed: false, auditId: 84 });
    vi.mocked(semantic.getSemanticReadiness).mockResolvedValue(report);
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '執行並保存索引稽核' }));
    await user.click(screen.getByRole('button', { name: '確認並保存稽核結果' }));
    expect(await screen.findByText('本次索引稽核未通過')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '讀取目前就緒狀態' }));
    expect(await screen.findByText('就緒條件目前符合')).toBeTruthy();
    expect(screen.getByText('本次索引稽核未通過')).toBeTruthy();
    expect(screen.getByText(/不代表已保存索引稽核/)).toBeTruthy();
  });

  it('does not restore a pending Readiness PASS after a document mutation', async () => {
    const pending = deferred<semantic.SemanticGateReport>();
    vi.mocked(semantic.getSemanticReadiness).mockReturnValue(pending.promise);
    const user = userEvent.setup(); const view = mount();
    await user.click(screen.getByRole('button', { name: '讀取目前就緒狀態' }));
    expect(semantic.getSemanticReadiness).toHaveBeenCalledTimes(1);
    view.rerender(<MemoryRouter><SemanticOperationsPanel documentMutationVersion={1} /></MemoryRouter>);
    pending.resolve(report);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(screen.queryByText('就緒條件目前符合')).toBeNull();
  });

  it('keeps an 稽核 PASS historical when a document mutation occurs before its response', async () => {
    const pending = deferred<semantic.SemanticGateReport>();
    vi.mocked(semantic.runSemanticGateAudit).mockReturnValue(pending.promise);
    const user = userEvent.setup(); const view = mount();
    await user.click(screen.getByRole('button', { name: '執行並保存索引稽核' }));
    await user.click(screen.getByRole('button', { name: '確認並保存稽核結果' }));
    expect(semantic.runSemanticGateAudit).toHaveBeenCalledTimes(1);
    view.rerender(<MemoryRouter><SemanticOperationsPanel documentMutationVersion={1} /></MemoryRouter>);
    pending.resolve({ ...report, auditId: 85 });
    expect(await screen.findByText('歷史索引稽核紀錄（已過期）')).toBeTruthy();
    expect(screen.queryByText('本次索引稽核通過')).toBeNull();
    expect(screen.getByText('85')).toBeTruthy();
  });

  it('does not restore a pending Readiness response after Backfill completes', async () => {
    const pendingReadiness = deferred<semantic.SemanticGateReport>();
    const pendingBackfill = deferred<semantic.BackfillBatchResponse>();
    vi.mocked(semantic.getSemanticReadiness).mockReturnValue(pendingReadiness.promise);
    vi.mocked(semantic.runBackfill).mockReturnValue(pendingBackfill.promise);
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '讀取目前就緒狀態' }));
    await user.click(screen.getByRole('button', { name: '執行一批' }));
    expect(semantic.runBackfill).toHaveBeenCalledTimes(1);
    pendingBackfill.resolve({ processed: 1, ready: 1, failed: 0, skipped: 0, requiresReview: 0, lastId: 1, morePossible: false });
    expect(await screen.findByText(/本批處理 1 份/)).toBeTruthy();
    pendingReadiness.resolve(report);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(screen.queryByText('就緒條件目前符合')).toBeNull();
  });

  it('uses the 系統回傳的索引編號 and sends only one batch when clicked rapidly', async () => {
    let resolveBatch!: (value: semantic.BackfillBatchResponse) => void;
    vi.mocked(semantic.runBackfill).mockImplementation(() => new Promise((resolve) => { resolveBatch = resolve; }));
    mount();
    const button = screen.getByRole('button', { name: '執行一批' });
    fireEvent.click(button); fireEvent.click(button);
    await waitFor(() => expect(semantic.runBackfill).toHaveBeenCalledTimes(1));
    expect(semantic.runBackfill).toHaveBeenCalledWith(0, 25);
    resolveBatch({ processed: 25, ready: 20, failed: 1, skipped: 2, requiresReview: 2, lastId: 34, morePossible: true });
    await screen.findByRole('button', { name: /以編號 34/ });
    await userEvent.setup().click(screen.getByRole('button', { name: /以編號 34/ }));
    expect((screen.getByRole('spinbutton', { name: '補建起始文件編號' }) as HTMLInputElement).value).toBe('34');
  });

  it('locks unknown timeout or 503 mutations and does not retry until explicit human verification', async () => {
    vi.mocked(semantic.runSemanticGateAudit).mockRejectedValue(new AxiosError('timeout', 'ETIMEDOUT'));
    const user = userEvent.setup(); const view = mount();
    await user.click(screen.getByRole('button', { name: '執行並保存索引稽核' }));
    await user.click(screen.getByRole('button', { name: '確認並保存稽核結果' }));
    expect(await screen.findByText(/索引稽核 結果未知/)).toBeTruthy();
    expect(screen.getByRole('button', { name: '執行並保存索引稽核' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByText(/目前狀態無法證明稽核是否已保存/)).toBeTruthy();
    view.rerender(<MemoryRouter><SemanticOperationsPanel documentMutationVersion={1} /></MemoryRouter>);
    expect(screen.getByRole('button', { name: '執行並保存索引稽核' }).hasAttribute('disabled')).toBe(true);
    await user.click(screen.getByRole('button', { name: '我已完成人工查核' }));
    expect(screen.getByText(/就緒狀態不足以證明原操作結果/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '確認已人工查核' }));
    expect(screen.getByRole('button', { name: '執行並保存索引稽核' }).hasAttribute('disabled')).toBe(false);
    expect(semantic.runSemanticGateAudit).toHaveBeenCalledTimes(1);
  });
});
