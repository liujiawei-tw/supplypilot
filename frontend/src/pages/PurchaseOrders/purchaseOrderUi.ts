import type { PurchaseOrderStatus } from '../../api/purchaseOrder';
import { isUnknownMutationResult } from '../../api/apiError';
import { formatDate, formatDateTime } from '../../utils/displayFormatters';

export const decimalPattern = /^(?:0|[1-9]\d*)(?:\.\d+)?$/;

export function isPositiveDecimal(value: unknown): boolean {
  return typeof value === 'string' && decimalPattern.test(value.trim()) && !/^0(?:\.0+)?$/.test(value.trim());
}

export function isNonNegativeDecimal(value: unknown): boolean {
  return typeof value === 'string' && decimalPattern.test(value.trim());
}

export function isUnknownMutation(error: unknown): boolean {
  return isUnknownMutationResult(error);
}

export function formatTimestamp(value: string | null | undefined): string {
  return formatDateTime(value);
}

export function formatPurchaseOrderDate(value: string | null | undefined): string {
  return formatDate(value, '未設定');
}

export const statusColors: Record<PurchaseOrderStatus, string> = {
  DRAFT: 'default', APPROVED: 'blue', ORDERED: 'gold', RECEIVED: 'green', REJECTED: 'red', CANCELLED: 'default',
};

export function defaultPurchaseOrderViewMode(isMobile: boolean): 'kanban' | 'list' {
  return isMobile ? 'list' : 'kanban';
}
