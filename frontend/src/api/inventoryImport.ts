import axiosClient from './axiosClient';

export type InventoryImportStatus =
  | 'UPLOADED'
  | 'VALIDATED'
  | 'VALIDATION_FAILED'
  | 'IMPORTED'
  | 'IMPORT_FAILED';

export interface InventoryImportErrorResponse {
  row: number;
  materialCode: string | null;
  code: string;
  message: string;
}

export interface InventoryImportValidationResponse {
  batchId: number;
  status: InventoryImportStatus;
  totalRows: number;
  validRows: number;
  errorRows: number;
  errors: InventoryImportErrorResponse[];
}

export async function validateInventoryImport(file: File): Promise<InventoryImportValidationResponse> {
  const formData = new FormData();
  formData.append('file', file);
  const response = await axiosClient.post<InventoryImportValidationResponse>(
    '/inventory-imports/validate',
    formData,
    { headers: { 'Content-Type': undefined } },
  );
  return response.data;
}

export async function confirmInventoryImport(batchId: number): Promise<void> {
  await axiosClient.post(`/inventory-imports/${batchId}/confirm`);
}
