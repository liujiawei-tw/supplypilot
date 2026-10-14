import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import SemanticOperationsPanel from '../src/pages/Documents/SemanticOperationsPanel';
import * as semantic from '../src/api/semanticIndex';
import { AxiosError } from 'axios';

vi.mock('../src/api/semanticIndex', () => ({
  getSemanticReadiness: vi.fn(), runBackfill: vi.fn(), runSemanticGateAudit: vi.fn(), approveOutbound: vi.fn(), excludeOutbound: vi.fn(),
}));

const report: semantic.SemanticGateReport = { passed: true, generationKey: 'gen-1', modelId: 'embedding-model', dimensions: 768, ready: 6, skipped: 0, failed: 0, requiresReview: 0, pending: 0, findings: [], auditId: null };

function mount() { return render(<MemoryRouter><SemanticOperationsPanel /></MemoryRouter>); }

beforeEach(() => { vi.clearAllMocks(); });

describe('Semantic admin operations UI', () => {
  it('keeps Readiness PASS distinct from a saved Audit and renders the formal Audit response', async () => {
    vi.mocked(semantic.getSemanticReadiness).mockResolvedValue(report);
    vi.mocked(semantic.runSemanticGateAudit).mockResolvedValue({ ...report, auditId: 81 });
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '讀取目前 Readiness' }));
    expect(await screen.findByText(/Readiness 條件目前符合/)).toBeTruthy();
    expect(screen.getByText(/不代表已保存 Gate Audit/)).toBeTruthy();
    expect(semantic.runSemanticGateAudit).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '執行並保存 Gate Audit' }));
    await user.click(screen.getByRole('button', { name: '確認並保存 Audit' }));
    expect(await screen.findByText('Audit ID')).toBeTruthy();
    expect(screen.getByText('81')).toBeTruthy();
    expect(screen.getByText(/不會啟用 Semantic Read/)).toBeTruthy();
  });

  it('uses the Backend lastId and sends only one batch when clicked rapidly', async () => {
    let resolveBatch!: (value: semantic.BackfillBatchResponse) => void;
    vi.mocked(semantic.runBackfill).mockImplementation(() => new Promise((resolve) => { resolveBatch = resolve; }));
    mount();
    const button = screen.getByRole('button', { name: '執行一批' });
    fireEvent.click(button); fireEvent.click(button);
    await waitFor(() => expect(semantic.runBackfill).toHaveBeenCalledTimes(1));
    expect(semantic.runBackfill).toHaveBeenCalledWith(0, 25);
    resolveBatch({ processed: 25, ready: 20, failed: 1, skipped: 2, requiresReview: 2, lastId: 34, morePossible: true });
    await screen.findByRole('button', { name: /使用 lastId 34/ });
    await userEvent.setup().click(screen.getByRole('button', { name: /使用 lastId 34/ }));
    expect((screen.getByRole('spinbutton', { name: 'Backfill afterId' }) as HTMLInputElement).value).toBe('34');
  });

  it('locks unknown timeout or 503 mutations and does not retry until explicit human verification', async () => {
    vi.mocked(semantic.runSemanticGateAudit).mockRejectedValue(new AxiosError('timeout', 'ETIMEDOUT'));
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '執行並保存 Gate Audit' }));
    await user.click(screen.getByRole('button', { name: '確認並保存 Audit' }));
    expect(await screen.findByText(/Gate Audit 結果未知/)).toBeTruthy();
    expect(screen.getByRole('button', { name: '執行並保存 Gate Audit' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByText(/Readiness 無法證明 Audit 是否已保存/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '我已完成人工查核' }));
    expect(screen.getByText(/Readiness 不足以證明原 POST 結果/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '確認已人工查核' }));
    expect(screen.getByRole('button', { name: '執行並保存 Gate Audit' }).hasAttribute('disabled')).toBe(false);
    expect(semantic.runSemanticGateAudit).toHaveBeenCalledTimes(1);
  });
});
