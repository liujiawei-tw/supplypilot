import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import AiAnswerDisplay from '../src/components/AiAssistant/AiAnswerDisplay';

vi.mock('../src/api/ai', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api/ai')>();
  return { ...actual, queryAi: vi.fn() };
});

import AiQueryPanel from '../src/components/AiAssistant/AiQueryPanel';
import { queryAi } from '../src/api/ai';

describe('AI answer display', () => {
  beforeEach(() => vi.mocked(queryAi).mockReset());

  it('formats TWD amounts while leaving item quantities at their original precision', () => {
    const { container } = render(
      <AiAnswerDisplay answer={'單價欄位 unitPrice=100.5000 TWD；totalCost=100.5678 TWD；price=19125.00000000 TWD；amount=900.0000 USD；quantity=150.0000 KG'} />,
    );

    expect(container.textContent).toContain('單價：NT$100.5');
    expect(container.textContent).toContain('總成本：NT$100.57');
    expect(container.textContent).toContain('單價：NT$19,125');
    expect(container.textContent).toContain('金額：USD 900');
    expect(container.textContent).toContain('quantity=150.0000 KG');
  });

  it('renders answer text as text instead of interpreting it as markup', () => {
    const unsafeMarkup = '<img src=x onerror=alert(1)>';
    const { container } = render(<AiAnswerDisplay answer={unsafeMarkup} />);

    expect(container.querySelector('img')).toBeNull();
    expect(container.textContent).toContain(unsafeMarkup);
  });
  it('shows each PO as a readable section and keeps warnings and quantities visible', () => {
    const answer = [
      '查詢結果整理如下。',
      'Open PO 依既有定義只包含 ORDERED 狀態；總計 3 筆，本次回傳 3 筆：',
      'PO-001（ID 1、supplier=長名稱材料供應商股份有限公司、status=ORDERED、expectedDeliveryDate=2026-12-01、currency=TWD、items=[MAT-01：quantity=150.0000 KG、unitPrice=100.0000 TWD、total=15000.00000000 TWD]）；',
      'PO-002（ID 2、supplier=供應商乙、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-02：quantity=25 PCS、unitPrice=100.5000 TWD、total=2512.5000 TWD]）；',
      'PO-003（ID 3、supplier=供應商丙、status=ORDERED、expectedDeliveryDate=2026-12-20、currency=TWD、items=[MAT-03：quantity=4 KG、unitPrice=100.5678 TWD、total=402.2712 TWD]）；',
      '文件證據不足，未據此作出結論。',
    ].join(' ');

    const { container } = render(<AiAnswerDisplay answer={answer} />);

    expect(screen.getByText('已下單採購單：共 3 筆，以下列出 3 筆。')).toBeTruthy();
    expect(screen.getAllByRole('region', { name: /^採購單/u })).toHaveLength(3);
    expect(container.textContent).toContain('NT$15,000');
    expect(container.textContent).toContain('NT$100.5');
    expect(container.textContent).toContain('NT$100.57');
    expect(screen.getByText('文件證據不足，未據此作出結論。')).toBeTruthy();
    expect(screen.getByText('長名稱材料供應商股份有限公司')).toBeTruthy();
    expect(container.textContent).not.toContain('items=[');
    expect(container.textContent).toContain('150.0000 KG');
  });

  it('shows empty PO results and keeps additional rows available through a native disclosure', () => {
    const empty = 'Open PO 依既有定義只包含 ORDERED 狀態；總計 0 筆，本次回傳 0 筆：';
    const { rerender } = render(<AiAnswerDisplay answer={empty} />);

    expect(screen.getByText('已下單採購單：共 0 筆，以下列出 0 筆。')).toBeTruthy();
    expect(screen.getByText('目前沒有可列出的採購單明細。')).toBeTruthy();

    const rows = Array.from({ length: 6 }, (_, index) =>
      'PO-' + String(index + 1).padStart(3, '0') + '（ID ' + (index + 1) +
      '、supplier=供應商' + (index + 1) + '、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-01：quantity=1 KG、unitPrice=1.0000 TWD、total=1.0000 TWD]）；',
    ).join(' ');
    rerender(<AiAnswerDisplay answer={'Open PO 依既有定義只包含 ORDERED 狀態；總計 6 筆，本次回傳 6 筆：' + rows} />);

    expect(screen.getAllByRole('region', { name: /^採購單/u })).toHaveLength(6);
    const disclosure = screen.getByText('展開其餘 1 筆採購單').parentElement as HTMLDetailsElement;
    expect(disclosure.tagName.toLowerCase()).toBe('details');
    expect(disclosure.open).toBe(false);
    expect(disclosure.querySelector('summary')?.textContent).toBe('展開其餘 1 筆採購單');
  });
});

describe('AI query panel response contract', () => {
  beforeEach(() => vi.mocked(queryAi).mockReset());

  it('keeps trusted sources and answer source visible alongside the formatted answer', async () => {
    vi.mocked(queryAi).mockResolvedValue({
      status: 'ANSWERED',
      answer: 'Open PO 依既有定義只包含 ORDERED 狀態；總計 1 筆，本次回傳 1 筆：PO-101（ID 101、supplier=供應商甲、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-01：quantity=1 KG、unitPrice=12.3400 TWD、total=12.3400 TWD]）；',
      tools: ['QUERY_OPEN_PO'],
      sources: [{
        resourceType: 'PURCHASE_ORDER',
        resourceId: '101',
        title: 'PO-101',
        version: null,
        supplierId: 4,
        chunkIndex: null,
        startOffset: null,
        endOffset: null,
        excerpt: null,
        relevanceScore: null,
      }],
      answerSource: 'BACKEND_FACTS',
    });
    const user = userEvent.setup();

    render(<MemoryRouter><AiQueryPanel /></MemoryRouter>);
    await user.type(screen.getByRole('textbox', { name: 'AI 問題' }), '查詢採購單');
    await user.click(screen.getByRole('button', { name: '送出問題' }));

    expect(await screen.findByText('已下單採購單：共 1 筆，以下列出 1 筆。')).toBeTruthy();
    expect(screen.getByText('Answer Source：BACKEND_FACTS')).toBeTruthy();
    expect(screen.getByRole('link', { name: '101' }).getAttribute('href')).toBe('/purchase-orders/101');
    expect(screen.getByText('供應商 ID').parentElement?.textContent).toContain('4');
    expect(screen.getByText('QUERY_OPEN_PO')).toBeTruthy();
  });

  it('keeps the insufficient evidence warning and empty source state', async () => {
    vi.mocked(queryAi).mockResolvedValue({
      status: 'INSUFFICIENT_EVIDENCE',
      answer: null,
      tools: ['QUERY_OPEN_PO'],
      sources: [],
      answerSource: null,
    });
    const user = userEvent.setup();

    render(<MemoryRouter><AiQueryPanel /></MemoryRouter>);
    await user.type(screen.getByRole('textbox', { name: 'AI 問題' }), '查詢採購單');
    await user.click(screen.getByRole('button', { name: '送出問題' }));

    expect(await screen.findByText('證據不足')).toBeTruthy();
    expect(screen.getByText('後端 沒有足夠證據支持肯定答案，因此不會顯示推測內容。')).toBeTruthy();
    expect(screen.getByText('後端 未提供可核對來源。')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: '回答' })).toBeNull();
  });
});