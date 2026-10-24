import axiosClient from './axiosClient';
import type { PageResponse } from '../types/api';
import { decimalRequestConfig, decimalResponseConfig, type DecimalValue } from './decimalJson';
export type { DecimalValue } from './decimalJson';

export type Currency = 'TWD';
export type SupplierMaterialStatus = 'ACTIVE' | 'INACTIVE';

export interface SupplierMaterialResponse {
  id: number;
  supplierId: number;
  materialId: number;
  unitPrice: DecimalValue;
  moq: DecimalValue;
  packageMultiple: DecimalValue | null;
  leadTimeDays: number;
  currency: Currency;
  status: SupplierMaterialStatus;
  createdAt: string;
  updatedAt: string;
}

export interface SupplierMaterialQuery {
  supplierId?: number;
  materialId?: number;
  status?: SupplierMaterialStatus;
  page?: number;
  size?: number;
  sort?: string;
}

export interface CreateSupplierMaterialRequest {
  supplierId: number;
  materialId: number;
  unitPrice: DecimalValue;
  moq: DecimalValue;
  packageMultiple?: DecimalValue;
  leadTimeDays: number;
  currency: Currency;
}

export interface UpdateSupplierMaterialRequest {
  unitPrice?: DecimalValue;
  moq?: DecimalValue;
  packageMultiple?: DecimalValue;
  leadTimeDays?: number;
  status?: SupplierMaterialStatus;
}

export async function listSupplierMaterials(query: SupplierMaterialQuery): Promise<PageResponse<SupplierMaterialResponse>> {
  return (await axiosClient.get<PageResponse<SupplierMaterialResponse>>('/supplier-materials', { params: query, ...decimalResponseConfig })).data;
}

export async function createSupplierMaterial(request: CreateSupplierMaterialRequest): Promise<SupplierMaterialResponse> {
  return (await axiosClient.post<SupplierMaterialResponse>('/supplier-materials', request, { ...decimalRequestConfig, ...decimalResponseConfig })).data;
}

export async function updateSupplierMaterial(id: number, request: UpdateSupplierMaterialRequest): Promise<SupplierMaterialResponse> {
  return (await axiosClient.patch<SupplierMaterialResponse>(`/supplier-materials/${id}`, request, decimalResponseConfig)).data;
}
