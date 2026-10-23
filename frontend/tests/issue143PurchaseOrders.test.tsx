// Issue #143 development-phase provisional-contract tests.
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import PurchaseOrderKanban from '../src/pages/PurchaseOrders/PurchaseOrderKanban';
import { defaultPurchaseOrderViewMode } from '../src/pages/PurchaseOrders/purchaseOrderUi';
import {
  getPurchaseOrderStageTransition,
  isActivePurchaseOrderStatus,
  purchaseOrderStageGateway,
  type PurchaseOrderStageBoardSnapshot,
} from '../src/api/purchaseOrderStage';
import { AuthContext } from '../src/auth/authContextValue';

const authValue = {
  user: null,
  isAuthenticated: false,
  isLoading: false,
  verificationError: false,
  retryVerification: () => undefined,
  login: async () => { throw new Error('not used'); },
  logout: () => undefined,
};

function renderKanban(node: ReactNode) {
  return render(<AuthContext.Provider value={authValue}><MemoryRouter>{node}</MemoryRouter></AuthContext.Provider>);
}

const po = (overrides: Record<string, unknown> = {}) => ({
  id: 42,
  poNumber: 'PO-20261022-000042',
  supplierCode: 'SUP-007',
  supplierName: '台灣測試供應商',
  status: 'ORDERED',
  expectedDeliveryDate: '2026-10-20',
  supplierPromisedDeliveryDate: '2026-10-24',
  isOverdue: true,
  stage: 'ORDER_FOLLOW_UP',
  ...overrides,
}) as unknown as PurchaseOrderStageBoardSnapshot['purchaseOrders'][number];

describe('Issue #143 purchase-order view contracts', () => {
  it('defaults desktop to Kanban and mobile to List', () => {
    expect(defaultPurchaseOrderViewMode(false)).toBe('kanban');
    expect(defaultPurchaseOrderViewMode(true)).toBe('list');
  });

  it('renders all eight stages and authoritative PO details from a complete snapshot', () => {
    const snapshot: PurchaseOrderStageBoardSnapshot = { complete: true, purchaseOrders: [po()] };
    renderKanban(<PurchaseOrderKanban state="ready" snapshot={snapshot} />);

    const board = screen.getByRole('region', { name: '採購單八階段看板' });
    for (const label of ['待處理／新請購', '詢比議價', '審核／簽核中', '已核准待下單', '已下單／生產／交期跟催', '到貨驗收', '請款核銷中', '已完成／封存結案']) {
      expect(within(board).getByRole('heading', { name: label })).toBeTruthy();
    }
    expect(within(board).getByText('PO-20261022-000042')).toBeTruthy();
    expect(within(board).getByText('公司期望到貨日：')).toBeTruthy();
    expect(within(board).getByText('2026/10/20')).toBeTruthy();
    expect(within(board).getByText('供應商承諾交期：')).toBeTruthy();
    expect(within(board).getByText('2026/10/24')).toBeTruthy();
    expect(within(board).getByText('後端標示已逾期')).toBeTruthy();
    expect(within(board).getByRole('link', { name: '檢視採購單 PO-20261022-000042' }).getAttribute('href'))
      .toBe('/purchase-orders/42');
  });

  it('keeps rejected and cancelled records out of active stages', () => {
    const snapshot: PurchaseOrderStageBoardSnapshot = {
      complete: true,
      purchaseOrders: [
        po({ id: 43, poNumber: 'PO-REJECTED-43', status: 'REJECTED', stage: 'NEW_REQUEST' }),
        po({ id: 44, poNumber: 'PO-CANCELLED-44', status: 'CANCELLED', stage: 'COMPLETED' }),
      ],
    };
    renderKanban(<PurchaseOrderKanban state="ready" snapshot={snapshot} />);
    expect(screen.queryByText('PO-REJECTED-43')).toBeNull();
    expect(screen.queryByText('PO-CANCELLED-44')).toBeNull();
  });

  it('hides cards rather than presenting a partial page as a complete board', () => {
    const snapshot: PurchaseOrderStageBoardSnapshot = { complete: false, purchaseOrders: [po()] };
    renderKanban(<PurchaseOrderKanban state="ready" snapshot={snapshot} />);
    expect(screen.getByText('看板資料不完整，已隱藏卡片')).toBeTruthy();
    expect(screen.queryByText('PO-20261022-000042')).toBeNull();
  });

  it('does not show cards when the complete list could not be loaded', () => {
    renderKanban(<PurchaseOrderKanban state="incomplete" reason="分頁筆數在載入期間變動。" />);
    expect(screen.getByText('看板資料不完整，已隱藏卡片')).toBeTruthy();
    expect(screen.getByText('分頁筆數在載入期間變動。')).toBeTruthy();
    expect(screen.queryByText('PO-20261022-000042')).toBeNull();
  });

  it('shows loading and read-error states without rendering stale cards', () => {
    const { rerender } = renderKanban(<PurchaseOrderKanban state="loading" />);
    expect(screen.getByText('正在載入完整採購單看板…')).toBeTruthy();
    rerender(<AuthContext.Provider value={authValue}><MemoryRouter><PurchaseOrderKanban state="error" error={new Error('讀取失敗')} /></MemoryRouter></AuthContext.Provider>);
    expect(screen.getByText('採購單看板載入失敗')).toBeTruthy();
    expect(screen.queryByText('PO-20261022-000042')).toBeNull();
  });

  it('shows a per-stage empty state for an authoritative complete snapshot with no cards', () => {
    renderKanban(<PurchaseOrderKanban state="ready" snapshot={{ complete: true, purchaseOrders: [] }} />);
    expect(screen.getAllByText('此階段目前沒有採購單')).toHaveLength(8);
  });

  it('maps only explicit adjacent Stage commands and preserves nullable Stage', () => {
    expect(getPurchaseOrderStageTransition(null, 'SOURCING')).toBeNull();
    expect(getPurchaseOrderStageTransition(null, 'RECEIVING_INSPECTION')).toBeNull();
    expect(getPurchaseOrderStageTransition('NEW_REQUEST', 'SOURCING')).toBe('BEGIN_SOURCING');
    expect(getPurchaseOrderStageTransition('SOURCING', 'APPROVAL')).toBe('SUBMIT_FOR_APPROVAL');
    expect(getPurchaseOrderStageTransition('APPROVAL', 'APPROVED_PENDING_ORDER')).toBe('APPROVE');
    expect(getPurchaseOrderStageTransition('APPROVED_PENDING_ORDER', 'ORDER_FOLLOW_UP')).toBe('ORDER');
    expect(getPurchaseOrderStageTransition('ORDER_FOLLOW_UP', 'RECEIVING_INSPECTION')).toBe('BEGIN_RECEIVING_INSPECTION');
    expect(getPurchaseOrderStageTransition('RECEIVING_INSPECTION', 'PAYMENT_RECONCILIATION')).toBe('RECEIVE');
    expect(getPurchaseOrderStageTransition('PAYMENT_RECONCILIATION', 'COMPLETED')).toBe('CONFIRM_PAYMENT');
    expect(getPurchaseOrderStageTransition('COMPLETED', 'PAYMENT_RECONCILIATION')).toBe('REVOKE_PAYMENT_CONFIRMATION');
    expect(getPurchaseOrderStageTransition('NEW_REQUEST', 'APPROVAL')).toBeNull();
  });

  it('excludes only terminal statuses from active lanes', () => {
    expect(isActivePurchaseOrderStatus('REJECTED')).toBe(false);
    expect(isActivePurchaseOrderStatus('CANCELLED')).toBe(false);
    expect(isActivePurchaseOrderStatus('DRAFT')).toBe(true);
    expect(isActivePurchaseOrderStatus('RECEIVED')).toBe(true);
  });

  it('keeps NULL Stage records read-only outside the eight known stages', () => {
    const legacyOrder = po({ id: 45, poNumber: 'PO-LEGACY-45', stage: null, orderedProgress: 'LEGACY_UNKNOWN' });
    const snapshot: PurchaseOrderStageBoardSnapshot = { complete: true, purchaseOrders: [legacyOrder] };
    renderKanban(<PurchaseOrderKanban state="ready" snapshot={snapshot} gateway={purchaseOrderStageGateway} />);
    expect(screen.getByText('1 筆採購單的 Stage 為 NULL')).toBeTruthy();
    expect(within(screen.getByRole('region', { name: '八階段欄位，可水平捲動' })).queryByText('PO-LEGACY-45')).toBeNull();
    expect(screen.queryByRole('button', { name: '開始到貨驗收' })).toBeNull();
  });
});
