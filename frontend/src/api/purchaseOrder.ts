import axiosClient from './axiosClient';
export interface CreatePurchaseOrderItemRequest {
  materialId: number;
  quantity: string;
  unitPrice: string;
  recommendationId: number;
  supplierRecommendationResultId: number;
  supplierOverrideReason?: string;
  quantityOverrideReason?: string;
}

export interface CreatePurchaseOrderRequest {
  supplierId: number;
  expectedDeliveryDate?: string;
  items: CreatePurchaseOrderItemRequest[];
}

export interface PurchaseOrderDraftResponse {
  id: number;
  poNumber: string;
  status: 'DRAFT';
}

export async function createPurchaseOrderDraft(
  request: CreatePurchaseOrderRequest,
): Promise<PurchaseOrderDraftResponse> {
  return (await axiosClient.post<PurchaseOrderDraftResponse>('/purchase-orders', request)).data;
}
