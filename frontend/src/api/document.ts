import axiosClient from './axiosClient';
import type { PageResponse } from '../types/api';

export type DocumentType =
  | 'SUPPLIER_CONTRACT'
  | 'QUOTATION'
  | 'PROCUREMENT_SOP'
  | 'PRODUCT_SPECIFICATION'
  | 'QUALITY_REQUIREMENT'
  | 'SUPPLIER_PROFILE'
  | 'SUPPLIER_COOPERATION_RECORD'
  | 'SUPPLIER_EVALUATION';

export type DocumentStatus = 'PROCESSING' | 'READY' | 'FAILED' | 'INACTIVE';

export interface DocumentDemoProvenance {
  datasetKey: string;
  documentKey: string;
  themeKey: string;
  corpusVersion: string;
  sourceSha256: string;
  origin: 'LEGACY_SP906' | 'ADDED_SP907' | string;
}

export interface DocumentSemanticReview {
  status: 'APPROVED' | 'REQUIRES_REVIEW' | 'SKIPPED';
  sourceHash: string;
  version: string;
  reason: string | null;
  actorType: 'USER' | 'SYSTEM' | string;
  actor: string;
  trigger: string;
  policyDecision: string;
  reviewedAt: string;
  valid: boolean;
  invalidatedAt: string | null;
  invalidationReason: string | null;
}

export interface DocumentSemanticIndex {
  status: string;
  jobId: number | null;
  sourceHash: string | null;
  expectedChunks: number;
  publishedChunks: number;
  failureCode: string | null;
  generationKey: string;
  publicationGeneration: number | null;
}

export interface DocumentResponse {
  id: number;
  documentType: DocumentType;
  supplierId: number | null;
  supplierCode: string | null;
  supplierName: string | null;
  title: string;
  version: string;
  storagePath: string;
  status: DocumentStatus;
  uploadedBy: number | null;
  uploadedAt: string;
  updatedAt: string;
  demoProvenance: DocumentDemoProvenance | null;
  semanticReview: DocumentSemanticReview | null;
  semanticIndex: DocumentSemanticIndex | null;
}

export interface DocumentQuery {
  documentType?: DocumentType;
  supplierId?: number;
  status?: DocumentStatus;
  page?: number;
  size?: number;
  sort?: string;
}

export interface UploadDocumentRequest {
  documentType: DocumentType;
  supplierId?: number;
  title: string;
  version: string;
  file: File;
}

export async function listDocuments(query: DocumentQuery): Promise<PageResponse<DocumentResponse>> {
  return (await axiosClient.get<PageResponse<DocumentResponse>>('/documents', { params: query })).data;
}

export async function getDocument(id: number): Promise<DocumentResponse> {
  return (await axiosClient.get<DocumentResponse>(`/documents/${id}`)).data;
}

export async function uploadDocument(request: UploadDocumentRequest): Promise<DocumentResponse> {
  const form = new FormData();
  form.append('documentType', request.documentType);
  if (request.supplierId !== undefined) form.append('supplierId', String(request.supplierId));
  form.append('title', request.title);
  form.append('version', request.version);
  form.append('file', request.file);
  return (await axiosClient.post<DocumentResponse>('/documents', form)).data;
}

export async function updateDocument(id: number, request: { title?: string; status?: 'INACTIVE' }): Promise<DocumentResponse> {
  return (await axiosClient.patch<DocumentResponse>(`/documents/${id}`, request)).data;
}

export async function ingestDocument(id: number): Promise<DocumentResponse> {
  return (await axiosClient.post<DocumentResponse>(`/documents/${id}/ingestions`)).data;
}
