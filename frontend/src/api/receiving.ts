import axiosClient from './axiosClient';
import type { BaseUnit } from './material';
import type { DecimalValue } from './purchaseOrder';
import type { PageResponse } from '../types/api';
import { decimalResponseConfig } from './decimalJson';

export type ReceivingStatus = 'ORDERED' | 'RECEIVED';
export interface ReceivingPurchaseOrderItemResponse { id: number; materialId: number; materialCode: string; materialName: string; quantity: DecimalValue; unit: BaseUnit; }
export interface ReceivingPurchaseOrderResponse {
  id: number; poNumber: string; supplierId: number; supplierCode: string; supplierName: string; status: ReceivingStatus;
  expectedDeliveryDate: string | null; isOverdue: boolean; items: ReceivingPurchaseOrderItemResponse[]; orderedAt: string;
  receivedBy: number | null; receivedByDisplayName: string | null; receivedByUsername: string | null; receivedAt: string | null;
}
export interface ReceivingQuery { status?: ReceivingStatus; materialId?: number; expectedDeliveryFrom?: string; expectedDeliveryTo?: string; orderedFrom?: string; orderedTo?: string; page?: number; size?: number; sort?: string; }
export async function listReceivingPurchaseOrders(query: ReceivingQuery): Promise<PageResponse<ReceivingPurchaseOrderResponse>> {
  return (await axiosClient.get<PageResponse<ReceivingPurchaseOrderResponse>>('/receiving/purchase-orders', { params: query, ...decimalResponseConfig })).data;
}
export async function getReceivingPurchaseOrder(id: number): Promise<ReceivingPurchaseOrderResponse> {
  return (await axiosClient.get<ReceivingPurchaseOrderResponse>(`/receiving/purchase-orders/${id}`, decimalResponseConfig)).data;
}
export async function receivePurchaseOrder(id: number): Promise<ReceivingPurchaseOrderResponse> {
  return (await axiosClient.post<ReceivingPurchaseOrderResponse>(`/purchase-orders/${id}/receive`, undefined, decimalResponseConfig)).data;
}
