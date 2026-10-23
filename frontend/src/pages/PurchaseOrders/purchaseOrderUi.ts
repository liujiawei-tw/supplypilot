import type { PurchaseOrderStatus } from '../../api/purchaseOrder';
import { isUnknownMutationResult } from '../../api/apiError';

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
  return value ? new Intl.DateTimeFormat('zh-TW', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

export function formatPurchaseOrderDate(value: string | null | undefined): string {
  if (!value) return '未設定';
  const [year, month, day] = value.split('-');
  return year && month && day ? `${year}/${month}/${day}` : value;
}

export const statusColors: Record<PurchaseOrderStatus, string> = {
  DRAFT: 'default', APPROVED: 'blue', ORDERED: 'gold', RECEIVED: 'green', REJECTED: 'red', CANCELLED: 'default',
};

export function defaultPurchaseOrderViewMode(isMobile: boolean): 'kanban' | 'list' {
  return isMobile ? 'list' : 'kanban';
}
