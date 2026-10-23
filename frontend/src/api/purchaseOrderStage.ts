import {
  approvePurchaseOrder,
  beginPurchaseOrderSourcing,
  beginReceivingInspection,
  confirmPurchaseOrderPayment,
  getPurchaseOrder,
  listPurchaseOrders,
  markPurchaseOrderOrdered,
  revokePurchaseOrderPaymentConfirmation,
  submitPurchaseOrderForApproval,
  type PurchaseOrderQuery,
  type PurchaseOrderResponse,
  type PurchaseOrderStage,
  type PurchaseOrderStatus,
} from './purchaseOrder';
import type { PageResponse } from '../types/api';

/**
 * PROVISIONAL — #142 LOCAL IMPLEMENTATION / NOT MERGED / INTEGRATION NOT VERIFIED.
 * These values match the current #142 enum; re-check after #142 merges.
 */
export const purchaseOrderStages: ReadonlyArray<{ id: PurchaseOrderStage; label: string }> = [
  { id: 'NEW_REQUEST', label: '待處理／新請購' },
  { id: 'SOURCING', label: '詢比議價' },
  { id: 'APPROVAL', label: '審核／簽核中' },
  { id: 'APPROVED_PENDING_ORDER', label: '已核准待下單' },
  { id: 'ORDER_FOLLOW_UP', label: '已下單／生產／交期跟催' },
  { id: 'RECEIVING_INSPECTION', label: '到貨驗收' },
  { id: 'PAYMENT_RECONCILIATION', label: '請款核銷中' },
  { id: 'COMPLETED', label: '已完成／封存結案' },
];

/** A complete snapshot must include every page returned by the backend query. */
export interface PurchaseOrderStageBoardSnapshot {
  complete: boolean;
  purchaseOrders: PurchaseOrderResponse[];
}

export type PurchaseOrderStageBoardFilters = Omit<PurchaseOrderQuery, 'page' | 'size' | 'sort'>;

export type PurchaseOrderStageTransition =
  | 'BEGIN_SOURCING'
  | 'SUBMIT_FOR_APPROVAL'
  | 'APPROVE'
  | 'ORDER'
  | 'BEGIN_RECEIVING_INSPECTION'
  | 'RECEIVE'
  | 'CONFIRM_PAYMENT'
  | 'REVOKE_PAYMENT_CONFIRMATION';

export type PurchaseOrderStageBoardRead =
  | { kind: 'ready'; snapshot: PurchaseOrderStageBoardSnapshot }
  | { kind: 'incomplete'; reason: string };

/** A command committed, but its authoritative PO read did not complete. */
export class PurchaseOrderStageCommittedReadError extends Error {
  readonly originalError: unknown;

  constructor(originalError: unknown) {
    super('採購單命令已送出，但最新狀態尚未查核。');
    this.name = 'PurchaseOrderStageCommittedReadError';
    this.originalError = originalError;
  }
}

export interface PurchaseOrderStageGateway {
  readCompleteBoard(filters?: PurchaseOrderStageBoardFilters): Promise<PurchaseOrderStageBoardRead>;
  executeTransition(purchaseOrderId: number, transition: PurchaseOrderStageTransition): Promise<PurchaseOrderResponse>;
  readPurchaseOrder(purchaseOrderId: number): Promise<PurchaseOrderResponse>;
}

/**
 * #142 exposes the existing filtered PO list as a page query, not a board
 * endpoint. Read every page with stable ID ordering and expose cards only when
 * the response metadata and row counts agree across all pages.
 */
export const purchaseOrderStageGateway: PurchaseOrderStageGateway = {
  async readCompleteBoard(filters = {}) {
    const pageSize = 100;
    const query = { ...filters, page: 0, size: pageSize, sort: 'id,asc' };
    const firstPage = await listPurchaseOrders(query);
    const expectedPageCount = firstPage.totalElements === 0
      ? 0
      : Math.ceil(firstPage.totalElements / pageSize);

    if (!isExpectedBoardPage(firstPage, 0, firstPage.totalElements, expectedPageCount, pageSize)) {
      return incompleteBoardRead();
    }

    const pages: PageResponse<PurchaseOrderResponse>[] = [firstPage];
    for (let page = 1; page < expectedPageCount; page += 1) {
      const response = await listPurchaseOrders({ ...query, page });
      if (!isExpectedBoardPage(response, page, firstPage.totalElements, expectedPageCount, pageSize)) {
        return incompleteBoardRead();
      }
      pages.push(response);
    }

    const purchaseOrders = pages.flatMap((page) => page.content);
    const ids = purchaseOrders.map((purchaseOrder) => purchaseOrder.id);
    if (purchaseOrders.length !== firstPage.totalElements || ids.some((id) => !Number.isSafeInteger(id) || id <= 0)
      || new Set(ids).size !== ids.length) {
      return incompleteBoardRead();
    }

    return { kind: 'ready', snapshot: { complete: true, purchaseOrders } };
  },

  async executeTransition(purchaseOrderId, transition) {
    switch (transition) {
      case 'BEGIN_SOURCING': await beginPurchaseOrderSourcing(purchaseOrderId); break;
      case 'SUBMIT_FOR_APPROVAL': await submitPurchaseOrderForApproval(purchaseOrderId); break;
      case 'APPROVE': await approvePurchaseOrder(purchaseOrderId); break;
      case 'ORDER': await markPurchaseOrderOrdered(purchaseOrderId); break;
      case 'BEGIN_RECEIVING_INSPECTION': await beginReceivingInspection(purchaseOrderId); break;
      // Receiving is intentionally completed only from the detail form, where
      // all three manual inspection checks are recorded before the receive API.
      case 'RECEIVE': throw new Error('請先在驗收詳情完成人工驗收表單。');
      case 'CONFIRM_PAYMENT': await confirmPurchaseOrderPayment(purchaseOrderId); break;
      case 'REVOKE_PAYMENT_CONFIRMATION': await revokePurchaseOrderPaymentConfirmation(purchaseOrderId); break;
    }

    try {
      // Mutation response is not used as the final board state. Read the PO again.
      return await getPurchaseOrder(purchaseOrderId);
    } catch (error) {
      throw new PurchaseOrderStageCommittedReadError(error);
    }
  },

  readPurchaseOrder: getPurchaseOrder,
};

export function isActivePurchaseOrderStatus(status: PurchaseOrderStatus): boolean {
  return status !== 'REJECTED' && status !== 'CANCELLED';
}

export function getPurchaseOrderStageTransition(
  source: PurchaseOrderStage | null,
  target: PurchaseOrderStage,
): PurchaseOrderStageTransition | null {
  if (source === null) return null;
  if (source === 'NEW_REQUEST' && target === 'SOURCING') return 'BEGIN_SOURCING';
  if (source === 'SOURCING' && target === 'APPROVAL') return 'SUBMIT_FOR_APPROVAL';
  if (source === 'APPROVAL' && target === 'APPROVED_PENDING_ORDER') return 'APPROVE';
  if (source === 'APPROVED_PENDING_ORDER' && target === 'ORDER_FOLLOW_UP') return 'ORDER';
  if (source === 'ORDER_FOLLOW_UP' && target === 'RECEIVING_INSPECTION') return 'BEGIN_RECEIVING_INSPECTION';
  if (source === 'RECEIVING_INSPECTION' && target === 'PAYMENT_RECONCILIATION') return 'RECEIVE';
  if (source === 'PAYMENT_RECONCILIATION' && target === 'COMPLETED') return 'CONFIRM_PAYMENT';
  if (source === 'COMPLETED' && target === 'PAYMENT_RECONCILIATION') return 'REVOKE_PAYMENT_CONFIRMATION';
  return null;
}

function isExpectedBoardPage(
  response: PageResponse<PurchaseOrderResponse>,
  page: number,
  totalElements: number,
  totalPages: number,
  pageSize: number,
): boolean {
  if (!Number.isSafeInteger(totalElements) || totalElements < 0
    || !Number.isSafeInteger(totalPages) || totalPages < 0
    || response.page !== page
    || response.size !== pageSize
    || response.totalElements !== totalElements
    || response.totalPages !== totalPages) {
    return false;
  }

  const expectedContentSize = Math.min(pageSize, Math.max(0, totalElements - page * pageSize));
  return response.content.length === expectedContentSize;
}

function incompleteBoardRead(): PurchaseOrderStageBoardRead {
  return {
    kind: 'incomplete',
    reason: '載入期間採購單頁數或筆數不一致；為避免顯示不完整看板，請重新載入。',
  };
}

export function requiresReceivingDetail(transition: PurchaseOrderStageTransition): boolean {
  return transition === 'RECEIVE';
}

export function canUseTransition(
  status: PurchaseOrderStatus,
  transition: PurchaseOrderStageTransition,
  hasExpectedDeliveryDate: boolean,
): boolean {
  switch (transition) {
    case 'BEGIN_SOURCING':
    case 'SUBMIT_FOR_APPROVAL':
      return status === 'DRAFT';
    case 'APPROVE':
      return status === 'DRAFT' && hasExpectedDeliveryDate;
    case 'ORDER':
      return status === 'APPROVED';
    case 'BEGIN_RECEIVING_INSPECTION':
      return status === 'ORDERED';
    case 'RECEIVE':
      return status === 'ORDERED';
    case 'CONFIRM_PAYMENT':
    case 'REVOKE_PAYMENT_CONFIRMATION':
      return status === 'RECEIVED';
  }
}
