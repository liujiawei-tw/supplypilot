import axiosClient from './axiosClient';
import type { BaseUnit } from './material';
import type { PageResponse } from '../types/api';
import { decimalResponseConfig, type DecimalValue } from './decimalJson';

export interface InventoryResponse {
  materialId: number;
  materialCode: string;
  materialName: string;
  quantity: DecimalValue;
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
  quantity: string;
  reason: string;
}

export async function listInventory(query: InventoryQuery): Promise<PageResponse<InventoryResponse>> {
  return (await axiosClient.get<PageResponse<InventoryResponse>>('/inventory', { params: query, ...decimalResponseConfig })).data;
}

export async function getInventory(materialId: number): Promise<InventoryResponse> {
  return (await axiosClient.get<InventoryResponse>(`/inventory/${materialId}`, decimalResponseConfig)).data;
}

export async function adjustInventory(materialId: number, request: InventoryAdjustmentRequest): Promise<void> {
  await axiosClient.post(`/inventory/${materialId}/adjustments`, request);
}
