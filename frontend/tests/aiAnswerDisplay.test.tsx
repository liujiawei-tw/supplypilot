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
import { formatAiCurrency } from '../src/utils/displayFormatters';

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

  it('renders typed inventory, current quote, supplier and PO facts without parsing backend strings', () => {
    const details = {
      inventory: [{ materialId: 1, materialCode: 'MAT-001', materialName: '阿薩姆紅茶萃取物', quantity: '150.0000', unit: 'KG', updatedAt: '2026-10-15T12:12:39Z' }],
      quotes: [{ supplierMaterialId: 4, supplierId: 2, supplierCode: 'SUP-002', supplierName: '台灣供應商', supplierStatus: 'ACTIVE', relationStatus: 'ACTIVE', materialId: 1, materialCode: 'MAT-001', materialName: '阿薩姆紅茶萃取物', unitPrice: '100.5678', currency: 'TWD', pricingUnit: 'KG', leadTimeDays: 5 }],
      averagePrices: [{ materialCode: 'MAT-001', materialName: '阿薩姆紅茶萃取物', currency: 'TWD', pricingUnit: 'KG', quoteCount: 2, averageUnitPrice: '100.5678', minimumUnitPrice: '90.0000', maximumUnitPrice: '111.1356' }],
      suppliers: [{ supplierId: 2, supplierCode: 'SUP-002', supplierName: '台灣供應商', status: 'ACTIVE', reliabilityScore: '91.00', qualityScore: '88.50' }],
      purchaseOrders: [{ purchaseOrderId: 8, poNumber: 'PO-20261102-000004', status: 'ORDERED', stage: 'ORDER_FOLLOW_UP', supplierCode: 'SUP-002', supplierName: '台灣供應商', expectedDeliveryDate: '2026-11-20', supplierPromisedDeliveryDate: null, items: [{ materialCode: 'MAT-001', materialName: '阿薩姆紅茶萃取物', quantity: '2.0000', unit: 'KG', unitPrice: '100.5678', currency: 'TWD', lineTotal: '201.1356' }], totals: [{ currency: 'TWD', amount: '201.1356' }], nextStep: '追蹤供應商交貨。' }],
      candidates: [], unmatchedIdentifiers: [], notes: [],
    };
    const summary = '已依據查得的資料整理以下結果。';
    const { container } = render(<AiAnswerDisplay answer={'internal raw fact string'} details={details} summary={summary} />);

    expect(container.textContent).toContain(summary);
    expect(container.textContent).toContain('目前庫存');
    expect(container.textContent).toContain('150.0000 KG');
    expect(container.textContent).toContain('目前有效供應商報價');
    expect(container.textContent).toContain('NT$100.57');
    expect(container.textContent).toContain('91.00');
    expect(container.textContent).toContain('PO-20261102-000004');
    expect(container.textContent).toContain('下單與交期跟催');
    expect(container.textContent).not.toContain('internal raw fact string');
  });

  it('shows fetched open PO count separately from the matching total and undisplayed remainder', () => {
    const details = {
      inventory: [], quotes: [], averagePrices: [], suppliers: [],
      purchaseOrders: [
        { purchaseOrderId: 8, poNumber: 'PO-1', status: 'ORDERED', stage: null, supplierCode: 'SUP-001', supplierName: 'One', expectedDeliveryDate: null, supplierPromisedDeliveryDate: null, items: [], totals: [], nextStep: null },
        { purchaseOrderId: 9, poNumber: 'PO-2', status: 'ORDERED', stage: null, supplierCode: 'SUP-002', supplierName: 'Two', expectedDeliveryDate: null, supplierPromisedDeliveryDate: null, items: [], totals: [], nextStep: null },
      ],
      candidates: [], unmatchedIdentifiers: [], notes: [], purchaseOrderTotalCount: 150,
    };

    const { container } = render(<AiAnswerDisplay answer={null} details={details} summary="已查得未收貨採購單。" />);

    expect(container.textContent).toContain('符合條件總數 150 筆');
    expect(container.textContent).toContain('本次取得 2 筆');
    expect(container.textContent).toContain('尚有 148 筆未列出');
    expect(container.textContent).toContain('已查得未收貨採購單。');
  });

  it('does not present fetched rows as the total when merged PO totals are unknown', () => {
    const details = {
      inventory: [], quotes: [], averagePrices: [], suppliers: [],
      purchaseOrders: [
        { purchaseOrderId: 8, poNumber: 'PO-1', status: 'ORDERED', stage: null, supplierCode: 'SUP-001', supplierName: 'One', expectedDeliveryDate: null, supplierPromisedDeliveryDate: null, items: [], totals: [], nextStep: null },
        { purchaseOrderId: 9, poNumber: 'PO-2', status: 'ORDERED', stage: null, supplierCode: 'SUP-002', supplierName: 'Two', expectedDeliveryDate: null, supplierPromisedDeliveryDate: null, items: [], totals: [], nextStep: null },
      ],
      candidates: [], unmatchedIdentifiers: [], notes: [], purchaseOrderTotalCount: null,
    };

    const { container } = render(<AiAnswerDisplay answer={null} details={details} />);

    expect(container.textContent).toContain('符合條件總數尚未確認；本次取得 2 筆，目前顯示 2 筆。');
    expect(container.textContent).not.toContain('符合條件總數 2 筆');
    expect(container.textContent).not.toContain('尚有');
  });

  it('renders a structured zero open PO count', () => {
    const details = {
      inventory: [], quotes: [], averagePrices: [], suppliers: [], purchaseOrders: [],
      candidates: [], unmatchedIdentifiers: [], notes: [], purchaseOrderTotalCount: 0,
    };

    const { container } = render(<AiAnswerDisplay answer={null} details={details} />);

    expect(container.textContent).toContain('符合條件總數 0 筆');
    expect(container.textContent).toContain('本次取得 0 筆');
  });

  it('applies the V2 two-decimal display rule without changing the source decimal', () => {
    expect(formatAiCurrency('100.5678', 'TWD')).toBe('NT$100.57');
    expect(formatAiCurrency('15000.0000', 'USD')).toBe('USD 15,000');
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

  it('keeps a warning between structured PO sections', () => {
    const answer = [
      'PO-001（ID 1、supplier=供應商甲、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-01：quantity=1 KG、unitPrice=10 TWD、total=10 TWD]）；',
      '警告：以下資料尚未經人工核實。',
      'PO-002（ID 2、supplier=供應商乙、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-02：quantity=2 KG、unitPrice=20 TWD、total=40 TWD]）；',
    ].join('\n');

    render(<AiAnswerDisplay answer={answer} />);

    expect(screen.getByText('警告：以下資料尚未經人工核實。')).toBeTruthy();
    expect(screen.getAllByRole('region', { name: /^採購單/u })).toHaveLength(2);
  });

  it('keeps different explanations between PO sections in their original order', () => {
    const firstExplanation = '說明：第一筆採購單使用供應商確認價格。';
    const secondExplanation = '限制：第二筆採購單的交期尚待回覆。';
    const answer = [
      'PO-001（ID 1、supplier=供應商甲、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-01：quantity=1 KG、unitPrice=10 TWD、total=10 TWD]）；',
      firstExplanation,
      'PO-002（ID 2、supplier=供應商乙、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-02：quantity=2 KG、unitPrice=20 TWD、total=40 TWD]）；',
      secondExplanation,
      'PO-003（ID 3、supplier=供應商丙、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-03：quantity=3 KG、unitPrice=30 TWD、total=90 TWD]）；',
    ].join('\n');

    const { container } = render(<AiAnswerDisplay answer={answer} />);

    expect(container.textContent).toContain(firstExplanation);
    expect(container.textContent).toContain(secondExplanation);
    expect(container.textContent!.indexOf(firstExplanation)).toBeLessThan(container.textContent!.indexOf('PO-002'));
    expect(container.textContent!.indexOf(secondExplanation)).toBeLessThan(container.textContent!.indexOf('PO-003'));
  });

  it('keeps incomplete PO text and additional source and risk explanations', () => {
    const incompletePo = 'PO-UNKNOWN（ID 9、supplier=供應商丁、status=ORDERED、items=[格式不完整';
    const sourceAndRisk = '來源：採購單附件報價單；風險：尚未完成人工核對。';
    const answer = [
      'PO-001（ID 1、supplier=供應商甲、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-01：quantity=1 KG、unitPrice=10 TWD、total=10 TWD]）；',
      incompletePo,
      sourceAndRisk,
      'PO-002（ID 2、supplier=供應商乙、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-02：quantity=2 KG、unitPrice=20 TWD、total=40 TWD]）；',
    ].join('\n');

    const { container } = render(<AiAnswerDisplay answer={answer} />);

    expect(container.textContent).toContain(incompletePo);
    expect(container.textContent).toContain(sourceAndRisk);
  });

  it('renders unparsed markup between PO sections as harmless text', () => {
    const unsafeMarkup = '<img src=x onerror=alert(1)> & <script>alert("x")</script>';
    const answer = [
      'PO-001（ID 1、supplier=供應商甲、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-01：quantity=1 KG、unitPrice=10 TWD、total=10 TWD]）；',
      unsafeMarkup,
      'PO-002（ID 2、supplier=供應商乙、status=ORDERED、expectedDeliveryDate=null、currency=TWD、items=[MAT-02：quantity=2 KG、unitPrice=20 TWD、total=40 TWD]）；',
    ].join('\n');

    const { container } = render(<AiAnswerDisplay answer={answer} />);

    expect(container.querySelector('img, script')).toBeNull();
    expect(container.textContent).toContain(unsafeMarkup);
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
        documentKey: null,
        version: null,
        supplierId: 4,
        chunkIndex: null,
        startOffset: null,
        endOffset: null,
        excerpt: null,
        relevanceScore: null,
      }, {
        resourceType: 'DOCUMENT',
        resourceId: '42',
        title: '採購流程手冊',
        documentKey: 'DOC-SUP-001-PROCUREMENT-SOP',
        version: 'v1',
        supplierId: 5,
        chunkIndex: 3,
        startOffset: 10,
        endOffset: 20,
        excerpt: '驗收流程摘要',
        relevanceScore: 0.9,
      }],
      answerSource: 'BACKEND_FACTS',
    });
    const user = userEvent.setup();

    render(<MemoryRouter><AiQueryPanel /></MemoryRouter>);
    await user.type(screen.getByRole('textbox', { name: 'AI 問題' }), '查詢採購單');
    await user.click(screen.getByRole('button', { name: '送出問題' }));

    expect(await screen.findByText('已下單採購單：共 1 筆，以下列出 1 筆。')).toBeTruthy();
    expect(screen.getByText('答案來源：後端查核資料')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'PO-101' }).getAttribute('href')).toBe('/purchase-orders/101');
    expect(screen.getByText('查詢待收貨採購單')).toBeTruthy();
    expect(screen.getByText('採購單')).toBeTruthy();
    expect(screen.queryByText('PURCHASE_ORDER')).toBeNull();
    expect(screen.getByRole('link', { name: '採購流程手冊' }).getAttribute('href')).toBe('/documents?documentId=42');
    expect(screen.getByText('文件')).toBeTruthy();
    expect(screen.queryByText('Document Key')).toBeNull();
    expect(screen.queryByText('DOC-SUP-001-PROCUREMENT-SOP')).toBeNull();
    expect(screen.queryByText('來源片段序號')).toBeNull();
    expect(screen.queryByText('原文位置')).toBeNull();
    expect(screen.queryByText('DOCUMENT')).toBeNull();
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
    expect(screen.getByText('未提供可核對來源。')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: '回答' })).toBeNull();
  });

  it('shows verified partial facts and names the missing identifier on an insufficient answer', async () => {
    vi.mocked(queryAi).mockResolvedValue({
      status: 'INSUFFICIENT_EVIDENCE',
      answer: 'MAT-001 庫存為 1 KG。',
      tools: ['QUERY_INVENTORY'],
      sources: [],
      answerSource: 'BACKEND_FACTS',
      details: {
        inventory: [{ materialId: 1, materialCode: 'MAT-001', materialName: 'Assam', quantity: '1', unit: 'KG', updatedAt: '2026-10-15T12:00:00Z' }],
        quotes: [], averagePrices: [], suppliers: [], purchaseOrders: [], candidates: [],
        unmatchedIdentifiers: [], notes: ['尚未取得以下物料代碼的結果：MAT-002。'],
      },
    });
    const user = userEvent.setup();

    render(<MemoryRouter><AiQueryPanel /></MemoryRouter>);
    await user.type(screen.getByRole('textbox', { name: 'AI 問題' }), 'MAT-001 / MAT-002 庫存?');
    await user.click(screen.getByRole('button', { name: '送出問題' }));

    expect(await screen.findByText('部分查詢結果')).toBeTruthy();
    expect(screen.getByText('目前庫存')).toBeTruthy();
    expect(document.body.textContent).toContain('MAT-001');
    expect(screen.getByText('尚未取得以下物料代碼的結果：MAT-002。')).toBeTruthy();
  });

  it('distinguishes a successful query with no matching business data', async () => {
    vi.mocked(queryAi).mockResolvedValue({
      status: 'NOT_FOUND', answer: '查無此物料的庫存資料。', tools: ['QUERY_INVENTORY'], sources: [], answerSource: null,
    });
    const user = userEvent.setup();

    render(<MemoryRouter><AiQueryPanel /></MemoryRouter>);
    await user.type(screen.getByRole('textbox', { name: 'AI 問題' }), 'MAT-999 庫存?');
    await user.click(screen.getByRole('button', { name: '送出問題' }));

    expect(await screen.findByText('查無資料')).toBeTruthy();
    expect(screen.getByText('查無此物料的庫存資料。')).toBeTruthy();
    expect(screen.queryByText('AI 問答失敗')).toBeNull();
  });

  it('shows live master candidates when a name needs clarification', async () => {
    vi.mocked(queryAi).mockResolvedValue({
      status: 'NEEDS_CLARIFICATION', answer: '請確認物料。', tools: ['QUERY_INVENTORY'], sources: [], answerSource: null,
      details: {
        inventory: [], quotes: [], averagePrices: [], suppliers: [], purchaseOrders: [],
        candidates: [{ resourceType: 'MATERIAL', id: 1, code: 'MAT-001', name: '阿薩姆紅茶萃取物', unit: 'KG' }],
        unmatchedIdentifiers: [], notes: [],
      },
    });
    const user = userEvent.setup();

    render(<MemoryRouter><AiQueryPanel /></MemoryRouter>);
    await user.type(screen.getByRole('textbox', { name: 'AI 問題' }), '阿薩姆紅茶庫存?');
    await user.click(screen.getByRole('button', { name: '送出問題' }));

    expect(await screen.findByText('需要補充資料')).toBeTruthy();
    expect(screen.getByText('阿薩姆紅茶萃取物（MAT-001，KG）')).toBeTruthy();
  });

});
