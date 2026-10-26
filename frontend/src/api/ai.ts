import axiosClient from './axiosClient';
import { decimalResponseConfig } from './decimalJson';
import type { DecimalValue } from './decimalJson';
import type { CreatePurchaseOrderRequest, PurchaseOrderResponse } from './purchaseOrder';

export type AiQueryStatus = 'ANSWERED' | 'NOT_FOUND' | 'INSUFFICIENT_EVIDENCE' | 'NEEDS_CLARIFICATION';
export type AiToolType =
  | 'QUERY_RECOMMENDATION'
  | 'EXPLAIN_RECOMMENDATION'
  | 'QUERY_SUPPLIER_RANKING'
  | 'QUERY_INVENTORY'
  | 'QUERY_SUPPLIER_QUOTES'
  | 'QUERY_AVERAGE_PRICE'
  | 'QUERY_SUPPLIER'
  | 'QUERY_PURCHASE_ORDER'
  | 'QUERY_OPEN_PO'
  | 'SEARCH_DOCUMENTS';

export interface AiAnswerData {
  inventory: AiInventoryItem[];
  quotes: AiSupplierQuote[];
  averagePrices: AiAveragePrice[];
  suppliers: AiSupplierFact[];
  purchaseOrders: AiPurchaseOrderFact[];
  candidates: AiCandidate[];
  unmatchedIdentifiers: string[];
  notes: string[];
  purchaseOrderTotalCount?: number | null;
}

export interface AiInventoryItem {
  materialId: number;
  materialCode: string;
  materialName: string;
  quantity: DecimalValue;
  unit: string;
  updatedAt: string;
}

export interface AiSupplierQuote {
  supplierMaterialId: number;
  supplierId: number;
  supplierCode: string;
  supplierName: string;
  supplierStatus: string;
  relationStatus: string;
  materialId: number;
  materialCode: string;
  materialName: string;
  unitPrice: DecimalValue;
  currency: string;
  pricingUnit: string;
  leadTimeDays: number;
}

export interface AiAveragePrice {
  materialCode: string;
  materialName: string;
  currency: string;
  pricingUnit: string;
  quoteCount: number;
  averageUnitPrice: DecimalValue;
  minimumUnitPrice: DecimalValue;
  maximumUnitPrice: DecimalValue;
}

export interface AiSupplierFact {
  supplierId: number;
  supplierCode: string;
  supplierName: string;
  status: string;
  reliabilityScore: DecimalValue;
  qualityScore: DecimalValue;
}

export interface AiPurchaseOrderFact {
  purchaseOrderId: number;
  poNumber: string;
  status: string;
  stage: string | null;
  supplierCode: string;
  supplierName: string;
  expectedDeliveryDate: string | null;
  supplierPromisedDeliveryDate: string | null;
  items: AiPurchaseOrderItemFact[];
  totals: AiMoneyTotal[];
  nextStep: string | null;
}

export interface AiPurchaseOrderItemFact {
  materialCode: string;
  materialName: string;
  quantity: DecimalValue;
  unit: string;
  unitPrice: DecimalValue;
  currency: string;
  lineTotal: DecimalValue;
}

export interface AiMoneyTotal {
  currency: string;
  amount: DecimalValue;
}

export interface AiCandidate {
  resourceType: string;
  id: number;
  code: string;
  name: string;
  unit: string | null;
}

export interface AiSourceResponse {
  resourceType: string;
  resourceId: string;
  title: string;
  documentKey: string | null;
  version: string | null;
  supplierId: number | null;
  chunkIndex: number | null;
  startOffset: number | null;
  endOffset: number | null;
  excerpt: string | null;
  relevanceScore: number | null;
}

export interface AiQueryResponse {
  status: AiQueryStatus;
  answer: string | null;
  tools: AiToolType[];
  sources: AiSourceResponse[];
  answerSource: string | null;
  summary?: string | null;
  details?: AiAnswerData;
}

export interface AiDraftProposalResponse {
  status: 'PENDING_CONFIRMATION' | 'NEEDS_CLARIFICATION';
  proposalId: string | null;
  draft: CreatePurchaseOrderRequest | null;
  expiresAt: string | null;
  confirmationToken: string | null;
  missingFields: string[];
}

export async function queryAi(message: string): Promise<AiQueryResponse> {
  return (await axiosClient.post<AiQueryResponse>('/ai/query', { message }, decimalResponseConfig)).data;
}

export async function proposePurchaseDraft(message: string, draft: CreatePurchaseOrderRequest): Promise<AiDraftProposalResponse> {
  return (await axiosClient.post<AiDraftProposalResponse>('/ai/purchase-draft-proposals', { message, draft }, decimalResponseConfig)).data;
}

export async function confirmPurchaseDraft(
  proposalId: string,
  confirmationToken: string,
  draft: CreatePurchaseOrderRequest,
): Promise<PurchaseOrderResponse> {
  return (await axiosClient.post<PurchaseOrderResponse>(
    `/ai/purchase-draft-proposals/${encodeURIComponent(proposalId)}/confirm`,
    { confirmationToken, draft },
    decimalResponseConfig,
  )).data;
}
