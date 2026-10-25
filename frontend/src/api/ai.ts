import axiosClient from './axiosClient';
import { decimalResponseConfig } from './decimalJson';
import type { CreatePurchaseOrderRequest, PurchaseOrderResponse } from './purchaseOrder';

export type AiQueryStatus = 'ANSWERED' | 'INSUFFICIENT_EVIDENCE' | 'NEEDS_CLARIFICATION';
export type AiToolType =
  | 'QUERY_RECOMMENDATION'
  | 'EXPLAIN_RECOMMENDATION'
  | 'QUERY_SUPPLIER_RANKING'
  | 'QUERY_INVENTORY'
  | 'QUERY_OPEN_PO'
  | 'SEARCH_DOCUMENTS';

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
  return (await axiosClient.post<AiQueryResponse>('/ai/query', { message })).data;
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
