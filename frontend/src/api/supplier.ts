import axiosClient from './axiosClient';
import type { PageResponse } from '../types/api';

export type SupplierStatus = 'ACTIVE' | 'INACTIVE';

export interface SupplierResponse {
  id: number;
  code: string;
  name: string;
  country: string | null;
  reliabilityScore: number;
  qualityScore: number;
  status: SupplierStatus;
  createdAt: string;
  updatedAt: string;
}

export interface SupplierQuery {
  status?: SupplierStatus;
  code?: string;
  name?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface CreateSupplierRequest {
  code: string;
  name: string;
  country?: string;
  reliabilityScore: number;
  qualityScore: number;
}

export interface UpdateSupplierRequest {
  name?: string;
  country?: string;
  reliabilityScore?: number;
  qualityScore?: number;
  status?: SupplierStatus;
}

export async function listSuppliers(query: SupplierQuery): Promise<PageResponse<SupplierResponse>> {
  return (await axiosClient.get<PageResponse<SupplierResponse>>('/suppliers', { params: query })).data;
}

export async function getSupplier(id: number): Promise<SupplierResponse> {
  return (await axiosClient.get<SupplierResponse>(`/suppliers/${id}`)).data;
}

export async function createSupplier(request: CreateSupplierRequest): Promise<SupplierResponse> {
  return (await axiosClient.post<SupplierResponse>('/suppliers', request)).data;
}

export async function updateSupplier(id: number, request: UpdateSupplierRequest): Promise<SupplierResponse> {
  return (await axiosClient.patch<SupplierResponse>(`/suppliers/${id}`, request)).data;
}
