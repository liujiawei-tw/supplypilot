import axiosClient from './axiosClient';
import type { BaseUnit } from './material';
import type { PageResponse } from '../types/api';
import { decimalResponseConfig } from './decimalJson';

export type DecimalValue = string | number;
export type PurchaseOrderStatus = 'DRAFT' | 'APPROVED' | 'ORDERED' | 'RECEIVED' | 'REJECTED' | 'CANCELLED';
export type OrderedProgress = 'FOLLOW_UP' | 'RECEIVING_INSPECTION' | 'LEGACY_UNKNOWN';
/** Persisted Kanban position from the merged #142 Stage contract; null means no known active lane. */
export type PurchaseOrderStage =
  | 'NEW_REQUEST'
  | 'SOURCING'
  | 'APPROVAL'
  | 'APPROVED_PENDING_ORDER'
  | 'ORDER_FOLLOW_UP'
  | 'RECEIVING_INSPECTION'
  | 'PAYMENT_RECONCILIATION'
  | 'COMPLETED';
export interface ReceivingInspectionResponse {
  allItemsReceived: boolean; allQuantitiesConfirmed: boolean; allQualityAccepted: boolean; note: string | null;
  inspectedBy: number | null; inspectedByDisplayName: string | null; inspectedByUsername: string | null; inspectedAt: string | null;
}

export interface CreatePurchaseOrderItemRequest {
  materialId: number;
  quantity: string;
  unitPrice: string;
  recommendationId?: number;
  supplierRecommendationResultId?: number;
  supplierOverrideReason?: string;
  quantityOverrideReason?: string;
}
export interface CreatePurchaseOrderRequest { supplierId: number; expectedDeliveryDate?: string; items: CreatePurchaseOrderItemRequest[]; }
export type UpdatePurchaseOrderItemRequest = CreatePurchaseOrderItemRequest;
export interface UpdatePurchaseOrderRequest { supplierId?: number; expectedDeliveryDate?: string | null; items?: UpdatePurchaseOrderItemRequest[]; }

export interface PurchaseOrderItemResponse {
  id: number; materialId: number; materialCode: string; materialName: string; quantity: DecimalValue; unit: BaseUnit;
  unitPrice: DecimalValue; total: DecimalValue; recommendationId: number | null; supplierRecommendationResultId: number | null;
  supplierOverride: boolean; quantityOverride: boolean; supplierOverrideReason: string | null; quantityOverrideReason: string | null;
}
export interface PurchaseOrderResponse {
  id: number; poNumber: string; supplierId: number; supplierCode: string; supplierName: string; status: PurchaseOrderStatus; stage: PurchaseOrderStage | null; orderedProgress: OrderedProgress | null;
  expectedDeliveryDate: string | null; supplierPromisedDeliveryDate: string | null;
  receivingInspection: ReceivingInspectionResponse;
  paymentConfirmed: boolean; paymentConfirmedBy: number | null; paymentConfirmedByDisplayName: string | null;
  paymentConfirmedByUsername: string | null; paymentConfirmedAt: string | null;
  items: PurchaseOrderItemResponse[]; createdBy: number | null; createdByDisplayName: string;
  createdByUsername: string | null; assignedTo: number | null; assignedToDisplayName: string | null; assignedToUsername: string | null;
  createdAt: string; approvedBy: number | null; approvedByDisplayName: string | null; approvedAt: string | null;
  approvedByUsername: string | null;
  rejectReason: string | null; rejectedBy: number | null; rejectedByDisplayName: string | null; rejectedAt: string | null;
  rejectedByUsername: string | null;
  cancelReason: string | null; cancelledBy: number | null; cancelledByDisplayName: string | null; cancelledByUsername: string | null; cancelledAt: string | null;
  orderedBy: number | null; orderedByDisplayName: string | null; orderedByUsername: string | null; orderedAt: string | null; receivedBy: number | null;
  receivedByDisplayName: string | null; receivedByUsername: string | null; receivedAt: string | null; isOverdue: boolean; updatedAt: string;
}
export interface PurchaseOrderQuery {
  status?: PurchaseOrderStatus; supplierId?: number; materialId?: number; createdFrom?: string; createdTo?: string;
  expectedDeliveryFrom?: string; expectedDeliveryTo?: string; overdue?: boolean; page?: number; size?: number; sort?: string;
}
export type PurchaseOrderDraftResponse = PurchaseOrderResponse;

export async function listPurchaseOrders(query: PurchaseOrderQuery): Promise<PageResponse<PurchaseOrderResponse>> {
  return (await axiosClient.get<PageResponse<PurchaseOrderResponse>>('/purchase-orders', { params: query, ...decimalResponseConfig })).data;
}
export async function getPurchaseOrder(id: number): Promise<PurchaseOrderResponse> {
  return (await axiosClient.get<PurchaseOrderResponse>(`/purchase-orders/${id}`, decimalResponseConfig)).data;
}
export async function createPurchaseOrderDraft(request: CreatePurchaseOrderRequest): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>('/purchase-orders', request, decimalResponseConfig)).data;
}
export async function updatePurchaseOrderDraft(id: number, request: UpdatePurchaseOrderRequest): Promise<PurchaseOrderResponse> {
  return (await axiosClient.patch<PurchaseOrderResponse>(`/purchase-orders/${id}`, request, decimalResponseConfig)).data;
}
export async function updateSupplierPromisedDeliveryDate(id: number, supplierPromisedDeliveryDate: string | null): Promise<PurchaseOrderResponse> {
  return (await axiosClient.put<PurchaseOrderResponse>(`/purchase-orders/${id}/supplier-promised-delivery-date`, { supplierPromisedDeliveryDate }, decimalResponseConfig)).data;
}
export async function approvePurchaseOrder(id: number): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(`/purchase-orders/${id}/approve`, undefined, decimalResponseConfig)).data;
}
export async function beginPurchaseOrderSourcing(id: number): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(`/purchase-orders/${id}/begin-sourcing`, undefined, decimalResponseConfig)).data;
}
export async function submitPurchaseOrderForApproval(id: number): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(`/purchase-orders/${id}/submit-for-approval`, undefined, decimalResponseConfig)).data;
}
export async function rejectPurchaseOrder(id: number, reason: string): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(`/purchase-orders/${id}/reject`, { reason }, decimalResponseConfig)).data;
}
export async function cancelPurchaseOrder(id: number, reason: string): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(`/purchase-orders/${id}/cancel`, { reason }, decimalResponseConfig)).data;
}
export async function beginReceivingInspection(id: number): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(`/purchase-orders/${id}/begin-receiving-inspection`, undefined, decimalResponseConfig)).data;
}
export async function recordReceivingInspection(id: number, request: {
  allItemsReceived: boolean; allQuantitiesConfirmed: boolean; allQualityAccepted: boolean; note: string | null;
}): Promise<PurchaseOrderResponse> {
  return (await axiosClient.put<PurchaseOrderResponse>(`/purchase-orders/${id}/receiving-inspection`, request, decimalResponseConfig)).data;
}
export async function confirmPurchaseOrderPayment(id: number): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(`/purchase-orders/${id}/confirm-payment`, undefined, decimalResponseConfig)).data;
}
export async function revokePurchaseOrderPaymentConfirmation(id: number): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(`/purchase-orders/${id}/revoke-payment-confirmation`, undefined, decimalResponseConfig)).data;
}
export async function markPurchaseOrderOrdered(id: number): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(`/purchase-orders/${id}/order`, undefined, decimalResponseConfig)).data;
}
export async function claimPurchaseOrder(id: number): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(`/purchase-orders/${id}/claim`, undefined, decimalResponseConfig)).data;
}
export async function assignPurchaseOrder(id: number, userId: number): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(`/purchase-orders/${id}/assign`, { userId }, decimalResponseConfig)).data;
}
