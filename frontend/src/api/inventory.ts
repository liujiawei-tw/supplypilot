import axiosClient from './axiosClient';
import type { BaseUnit } from './material';
import type { PageResponse } from '../types/api';

export interface InventoryResponse {
  materialId: number;
  materialCode: string;
  materialName: string;
  quantity: number;
  unit: BaseUnit;
  updatedAt: string;
}

export interface InventoryQuery {
  materialId?: number;
  materialCode?: string;
  page?: number;
  size?: number;
}

export interface InventoryAdjustmentRequest {
  quantity: number;
  reason: string;
}

export async function listInventory(query: InventoryQuery): Promise<PageResponse<InventoryResponse>> {
  return (await axiosClient.get<PageResponse<InventoryResponse>>('/inventory', { params: query })).data;
}

export async function getInventory(materialId: number): Promise<InventoryResponse> {
  return (await axiosClient.get<InventoryResponse>(`/inventory/${materialId}`)).data;
}

export async function adjustInventory(materialId: number, request: InventoryAdjustmentRequest): Promise<void> {
  await axiosClient.post(`/inventory/${materialId}/adjustments`, request);
}
