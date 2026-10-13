import axiosClient from './axiosClient';
import type { BaseUnit } from './material';
import type { DecimalValue } from './purchaseOrder';
import type { PageResponse } from '../types/api';
import { decimalResponseConfig } from './decimalJson';

export type StockTransactionType = 'PURCHASE_RECEIVE' | 'MANUAL_ADJUSTMENT';
export interface StockTransactionResponse {
  id: number; materialId: number; materialCode: string; materialName: string; unit: BaseUnit; type: StockTransactionType;
  quantity: DecimalValue; purchaseOrderId: number | null; purchaseOrderNumber: string | null; reason: string | null;
  createdBy: number; createdByDisplayName: string; createdAt: string;
}
export interface StockTransactionQuery { materialId?: number; type?: StockTransactionType; purchaseOrderId?: number; from?: string; to?: string; page?: number; size?: number; sort?: string; }
export async function listStockTransactions(query: StockTransactionQuery): Promise<PageResponse<StockTransactionResponse>> {
  return (await axiosClient.get<PageResponse<StockTransactionResponse>>('/stock-transactions', { params: query, ...decimalResponseConfig })).data;
}
export async function getStockTransaction(id: number): Promise<StockTransactionResponse> {
  return (await axiosClient.get<StockTransactionResponse>(`/stock-transactions/${id}`, decimalResponseConfig)).data;
}
