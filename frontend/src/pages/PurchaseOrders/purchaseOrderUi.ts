import { isAxiosError } from 'axios';
import type { PurchaseOrderStatus } from '../../api/purchaseOrder';

export const decimalPattern = /^(?:0|[1-9]\d*)(?:\.\d+)?$/;

export function isPositiveDecimal(value: unknown): boolean {
  return typeof value === 'string' && decimalPattern.test(value.trim()) && !/^0(?:\.0+)?$/.test(value.trim());
}

export function isNonNegativeDecimal(value: unknown): boolean {
  return typeof value === 'string' && decimalPattern.test(value.trim());
}

export function isUnknownMutation(error: unknown): boolean {
  return isAxiosError(error) && (!error.response || error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT');
}

export function formatTimestamp(value: string | null | undefined): string {
  return value ? new Intl.DateTimeFormat('zh-TW', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

export const statusColors: Record<PurchaseOrderStatus, string> = {
  DRAFT: 'default', APPROVED: 'blue', ORDERED: 'gold', RECEIVED: 'green', REJECTED: 'red',
};
