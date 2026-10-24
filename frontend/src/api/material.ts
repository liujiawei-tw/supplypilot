import axiosClient from './axiosClient';
import type { PageResponse } from '../types/api';
import { decimalResponseConfig, type DecimalValue } from './decimalJson';

export type BaseUnit = 'G' | 'KG' | 'ML' | 'L' | 'PCS';
export type MaterialStatus = 'ACTIVE' | 'INACTIVE';

export interface MaterialResponse {
  id: number;
  code: string;
  name: string;
  baseUnit: BaseUnit;
  safetyStock: DecimalValue;
  status: MaterialStatus;
  createdAt: string;
  updatedAt: string;
}

export interface MaterialQuery {
  status?: MaterialStatus;
  code?: string;
  name?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface CreateMaterialRequest {
  code: string;
  name: string;
  baseUnit: BaseUnit;
  safetyStock: DecimalValue;
}

export interface UpdateMaterialRequest {
  name?: string;
  safetyStock?: DecimalValue;
  status?: MaterialStatus;
}

export async function listMaterials(query: MaterialQuery): Promise<PageResponse<MaterialResponse>> {
  return (await axiosClient.get<PageResponse<MaterialResponse>>('/materials', { params: query, ...decimalResponseConfig })).data;
}

export async function getMaterial(id: number): Promise<MaterialResponse> {
  return (await axiosClient.get<MaterialResponse>('/materials/' + id, decimalResponseConfig)).data;
}

export async function createMaterial(request: CreateMaterialRequest): Promise<MaterialResponse> {
  return (await axiosClient.post<MaterialResponse>('/materials', request, decimalResponseConfig)).data;
}

export async function updateMaterial(id: number, request: UpdateMaterialRequest): Promise<MaterialResponse> {
  return (await axiosClient.patch<MaterialResponse>('/materials/' + id, request, decimalResponseConfig)).data;
}
