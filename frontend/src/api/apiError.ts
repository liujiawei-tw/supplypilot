import { isAxiosError } from 'axios';
import type { ApiErrorResponse } from '../types/api';

const fallbackError: ApiErrorResponse = {
  code: 'REQUEST_FAILED',
  message: '無法完成請求，請稍後再試。',
  traceId: '',
  timestamp: '',
  path: '',
};

export function toApiError(error: unknown): ApiErrorResponse {
  if (isAxiosError<ApiErrorResponse>(error) && error.response?.data?.message) return error.response.data;
  if (error instanceof Error) return { ...fallbackError, message: error.message };
  return fallbackError;
}
