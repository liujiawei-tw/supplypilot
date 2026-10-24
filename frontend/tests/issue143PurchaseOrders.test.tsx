// Issue #143 persisted Stage and purchase-order view contracts.
import type { ReactNode } from 'react';
import { ConfigProvider } from 'antd';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import zhTW from 'antd/locale/zh_TW';
import { MemoryRouter } from 'react-router-dom';
import PurchaseOrderKanban from '../src/pages/PurchaseOrders/PurchaseOrderKanban';
import { defaultPurchaseOrderViewMode } from '../src/pages/PurchaseOrders/purchaseOrderUi';
import {
  getPurchaseOrderStageTransition,
  isActivePurchaseOrderStatus,
  purchaseOrderStageGateway,
  type PurchaseOrderStageBoardSnapshot,
  type PurchaseOrderStageGateway,
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

function stageMutationError(status: 403 | 409 | 500 | undefined): AxiosError {
  const config = { headers: {} } as InternalAxiosRequestConfig;
  if (status === undefined) return new AxiosError('Network Error', 'ERR_NETWORK', config);
  const response = {
    data: {
      code: status === 403 ? 'ACCESS_DENIED' : status === 409 ? 'INVALID_PO_STATUS_TRANSITION' : 'INTERNAL_SERVER_ERROR',
      message: status === 403 ? 'Mock: 權限拒絕' : status === 409 ? 'Mock: Stage 衝突' : 'Mock: 伺服器錯誤',
    },
    status,
    statusText: status === 409 ? 'Conflict' : status === 500 ? 'Internal Server Error' : 'Forbidden',
    headers: {},
    config,
  } as AxiosResponse;
  return new AxiosError('Mock mutation failure', 'ERR_BAD_RESPONSE', config, undefined, response);
}

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
    expect(within(screen.getByRole('region', { name: '採購單八階段流程欄位' })).queryByText('PO-LEGACY-45')).toBeNull();
    expect(screen.queryByRole('button', { name: '開始到貨驗收' })).toBeNull();
  });

  it('requires confirmation after a card drop and sends the command only after confirmation', async () => {
    const user = userEvent.setup();
    const gateway: PurchaseOrderStageGateway = {
      readCompleteBoard: vi.fn(),
      executeTransition: vi.fn().mockResolvedValue(po({ status: 'DRAFT', stage: 'SOURCING' })),
      readPurchaseOrder: vi.fn(),
    };
    const purchaserAuthValue = {
      ...authValue,
      user: { id: 7, username: 'buyer', displayName: '採購人員', role: 'PROCUREMENT_STAFF' as const, canManageUsers: false },
      isAuthenticated: true,
    };
    const snapshot: PurchaseOrderStageBoardSnapshot = {
      complete: true,
      purchaseOrders: [po({ status: 'DRAFT', stage: 'NEW_REQUEST', expectedDeliveryDate: '2026-10-30' })],
    };
    render(<ConfigProvider locale={zhTW}><AuthContext.Provider value={purchaserAuthValue}><MemoryRouter>
      <PurchaseOrderKanban state="ready" snapshot={snapshot} gateway={gateway} />
    </MemoryRouter></AuthContext.Provider></ConfigProvider>);

    const card = screen.getByText('PO-20261022-000042').closest('[draggable="true"]');
    const target = screen.getByRole('heading', { name: '詢比議價' }).closest('section[aria-labelledby]');
    expect(card).not.toBeNull();
    expect(target).not.toBeNull();
    const dataTransfer = { effectAllowed: '', setData: vi.fn(), getData: vi.fn(() => '42') };
    const dispatchDragEvent = (element: Element, type: 'dragstart' | 'dragover' | 'drop') => {
      const event = new Event(type, { bubbles: true, cancelable: true });
      Object.defineProperty(event, 'dataTransfer', { value: dataTransfer });
      fireEvent(element, event);
    };
    const dropCard = () => {
      dispatchDragEvent(card!, 'dragstart');
      dispatchDragEvent(target!, 'dragover');
      dispatchDragEvent(target!, 'drop');
    };

    dropCard();
    let dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('待處理／新請購 → 開始詢比議價')).toBeTruthy();
    expect(gateway.executeTransition).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(gateway.executeTransition).not.toHaveBeenCalled();

    dropCard();
    dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '開始詢比議價' }));
    await waitFor(() => expect(gateway.executeTransition).toHaveBeenCalledTimes(1));
    expect(gateway.executeTransition).toHaveBeenCalledWith(42, 'BEGIN_SOURCING');
  });

  it.each([
    ['403 permission error', 403, '看板操作失敗', false],
    ['409 Stage conflict', 409, '操作結果待查核', true],
    ['500 unknown write result', 500, '操作結果待查核', true],
    ['network failure', undefined, '操作結果待查核', true],
  ] as const)(
    'handles a %s without optimistic card movement or duplicate command',
    async (_scenario, status, errorTitle, verifyRequired) => {
      const user = userEvent.setup();
      const latestPo = po({ status: 'DRAFT', stage: 'NEW_REQUEST', expectedDeliveryDate: '2026-10-30' });
      const gateway: PurchaseOrderStageGateway = {
        readCompleteBoard: vi.fn(async () => ({ kind: 'ready', snapshot: { complete: true, purchaseOrders: [latestPo] } })),
        executeTransition: vi.fn(async () => { throw stageMutationError(status); }),
        readPurchaseOrder: vi.fn(async () => latestPo),
      };
      const purchaserAuthValue = {
        ...authValue,
        user: { id: 7, username: 'buyer', displayName: '採購人員', role: 'PROCUREMENT_STAFF' as const, canManageUsers: false },
        isAuthenticated: true,
      };
      render(<ConfigProvider locale={zhTW}><AuthContext.Provider value={purchaserAuthValue}><MemoryRouter>
        <PurchaseOrderKanban state="ready" snapshot={{ complete: true, purchaseOrders: [latestPo] }} gateway={gateway} />
      </MemoryRouter></AuthContext.Provider></ConfigProvider>);

      const sourceColumn = screen.getByRole('heading', { name: '待處理／新請購' }).closest('section[aria-labelledby]')!;
      const targetColumn = screen.getByRole('heading', { name: '詢比議價' }).closest('section[aria-labelledby]')!;
      await user.click(screen.getByRole('button', { name: '開始詢比議價' }));
      const dialog = await screen.findByRole('dialog');
      await user.click(within(dialog).getByRole('button', { name: '開始詢比議價' }));

      expect(await screen.findByText(errorTitle)).toBeTruthy();
      expect(within(sourceColumn).getByText('PO-20261022-000042')).toBeTruthy();
      expect(within(targetColumn).queryByText('PO-20261022-000042')).toBeNull();
      expect(gateway.executeTransition).toHaveBeenCalledTimes(1);

      if (verifyRequired) {
        expect(screen.getByRole('button', { name: '重新查詢採購單' })).toBeTruthy();
        await user.click(screen.getByRole('button', { name: '重新查詢採購單' }));
        await waitFor(() => expect(gateway.readPurchaseOrder).toHaveBeenCalledTimes(1));
        expect(within(sourceColumn).getByText('PO-20261022-000042')).toBeTruthy();
        expect(within(targetColumn).queryByText('PO-20261022-000042')).toBeNull();
        expect(gateway.executeTransition).toHaveBeenCalledTimes(1);
      } else {
        expect(screen.queryByRole('button', { name: '重新查詢採購單' })).toBeNull();
        expect(gateway.readPurchaseOrder).not.toHaveBeenCalled();
      }
    },
  );
});
