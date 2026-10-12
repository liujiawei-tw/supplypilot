import axiosClient from './axiosClient';
import type { PageResponse } from '../types/api';

export type DemandStatus = 'ACTIVE' | 'CANCELLED';

export interface DemandResponse {
  id: number;
  materialId: number;
  quantity: number;
  requiredDate: string;
  status: DemandStatus;
  note: string | null;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface DemandQuery {
  materialId?: number;
  status?: DemandStatus;
  requiredFrom?: string;
  requiredTo?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface CreateDemandRequest {
  materialId: number;
  quantity: number;
  requiredDate: string;
  note?: string;
}

export interface UpdateDemandRequest {
  quantity?: number;
  requiredDate?: string;
  note?: string | null;
}

export async function listDemands(query: DemandQuery): Promise<PageResponse<DemandResponse>> {
  return (await axiosClient.get<PageResponse<DemandResponse>>('/demands', { params: query })).data;
}

export async function getDemand(id: number): Promise<DemandResponse> {
  return (await axiosClient.get<DemandResponse>(`/demands/${id}`)).data;
}

export async function createDemand(request: CreateDemandRequest): Promise<DemandResponse> {
  return (await axiosClient.post<DemandResponse>('/demands', request)).data;
}

export async function updateDemand(id: number, request: UpdateDemandRequest): Promise<DemandResponse> {
  return (await axiosClient.patch<DemandResponse>(`/demands/${id}`, request)).data;
}

export async function cancelDemand(id: number): Promise<DemandResponse> {
  return (await axiosClient.post<DemandResponse>(`/demands/${id}/cancel`)).data;
}
