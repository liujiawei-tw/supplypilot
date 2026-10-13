import axiosClient from './axiosClient';
import type { PageResponse } from '../types/api';

export type DocumentType =
  | 'SUPPLIER_CONTRACT'
  | 'QUOTATION'
  | 'PROCUREMENT_SOP'
  | 'PRODUCT_SPECIFICATION'
  | 'QUALITY_REQUIREMENT';

export type DocumentStatus = 'PROCESSING' | 'READY' | 'FAILED' | 'INACTIVE';

export interface DocumentResponse {
  id: number;
  documentType: DocumentType;
  supplierId: number | null;
  title: string;
  version: string;
  storagePath: string;
  status: DocumentStatus;
  uploadedBy: number;
  uploadedAt: string;
  updatedAt: string;
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
