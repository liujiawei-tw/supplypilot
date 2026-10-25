import { isAxiosError } from 'axios';
import type { ApiErrorResponse } from '../types/api';

const fallbackError: ApiErrorResponse = {
  code: 'REQUEST_FAILED',
  message: '無法完成請求，請稍後再試。',
  traceId: '',
  timestamp: '',
  path: '',
};

export type BusinessErrorKind =
  | 'validation'
  | 'unauthorized'
  | 'forbidden'
  | 'not-found'
  | 'concurrent-modification'
  | 'duplicate-receiving'
  | 'unknown-mutation-result'
  | 'internal-server-error'
  | 'ai-provider-unavailable'
  | 'request-failed';

export interface BusinessErrorPresentation {
  kind: BusinessErrorKind;
  title: string;
  message: string;
  severity: 'error' | 'warning' | 'info';
  retryAllowed: boolean;
}

const aiProviderCodes = new Set(['AI_PROVIDER_UNAVAILABLE', 'AI_TIMEOUT', 'AI_INVALID_RESPONSE']);

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function formalCode(value: unknown, fallback: string): string {
  const code = text(value);
  return /^[A-Z][A-Z0-9_]{1,79}$/.test(code) ? code : fallback;
}

function safeFieldErrors(value: unknown): ApiErrorResponse['fieldErrors'] {
  if (!Array.isArray(value)) return undefined;
  return value.flatMap((entry) => {
    if (!entry || typeof entry !== 'object') return [];
    const field = text((entry as { field?: unknown }).field);
    const message = text((entry as { message?: unknown }).message);
    return field && message ? [{ field, message }] : [];
  });
}

function safeHttpMessage(status: number, code: string, backendMessage: string): string {
  if (status === 401) return '登入狀態已失效，請重新登入。';
  if (status === 403) return backendMessage || '你沒有執行此操作的權限。';
  if (status === 404) return backendMessage || '找不到指定的資料，請回到清單確認最新狀態。';
  if (status === 409 && code === 'CONCURRENT_MODIFICATION') {
    return '資料可能已被其他使用者更新。請先取得 後端 最新狀態並重新審閱，不要直接重送原操作。';
  }
  if (status === 409 && code === 'DUPLICATE_RECEIVING') {
    return '收貨請求可能已完成。請使用 Receiving 專用查詢確認真實狀態，系統不會自動重送。';
  }
  if (status === 409) return backendMessage || '資料狀態發生衝突。請先查詢 後端 最新狀態後再決定下一步，系統不會自動重送。';
  if (status === 500) return '伺服器發生暫時性問題，請稍後再試；如持續發生，請提供 Trace ID 給管理人員。';
  if (status === 503 && code === 'AI_TIMEOUT') return 'AI 服務回應逾時。未產生或採用替代答案；核心採購功能仍可正常使用。';
  if (status === 503 && code === 'AI_INVALID_RESPONSE') return 'AI 服務回覆未通過驗證，未顯示不可信的內容；核心採購功能仍可正常使用。';
  if (status === 503 && code === 'AI_PROVIDER_UNAVAILABLE') return 'AI 服務暫時無法使用；文件、庫存、建議、採購單與收貨等核心功能仍可正常使用。';
  if (status === 503) return '服務暫時無法使用，請稍後再試。';
  if (status >= 500) return '伺服器發生暫時性問題，請稍後再試；如持續發生，請提供 Trace ID 給管理人員。';
  return backendMessage || fallbackError.message;
}

export function toApiError(error: unknown): ApiErrorResponse {
  if (isAxiosError(error)) {
    const status = error.response?.status;
    if (status !== undefined) {
      const data = error.response?.data && typeof error.response.data === 'object'
        ? error.response.data as Partial<ApiErrorResponse>
        : {};
      const unsafeCode = formalCode(data.code, `HTTP_${status}`);
      const code = status >= 500 && !(status === 503 && aiProviderCodes.has(unsafeCode)) ? 'INTERNAL_SERVER_ERROR' : unsafeCode;
      return {
        code,
        message: safeHttpMessage(status, code, text(data.message)),
        traceId: text(data.traceId),
        timestamp: text(data.timestamp),
        path: text(data.path),
        fieldErrors: status >= 500 ? undefined : safeFieldErrors(data.fieldErrors),
        httpStatus: status,
      };
    }
    const timedOut = error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT';
    return {
      ...fallbackError,
      code: timedOut ? 'NETWORK_TIMEOUT' : 'NETWORK_ERROR',
      message: timedOut
        ? '網路回應逾時，無法確認請求是否已被 後端 處理。'
        : '無法連線至伺服器，請檢查網路後再試。',
      transportError: true,
      timedOut,
    };
  }
  return { ...fallbackError };
}

export function isUnknownMutationResult(error: unknown): boolean {
  if (!isAxiosError(error)) return false;
  if (!error.response) return true;
  return error.response.status >= 500;
}

export function requiresMutationVerification(error: unknown): boolean {
  return isAxiosError(error) && (error.response?.status === 409 || isUnknownMutationResult(error));
}

export function classifyApiError(error: ApiErrorResponse, operation: 'read' | 'mutation' = 'read'): BusinessErrorPresentation {
  const status = error.httpStatus;
  if (status === 400 || error.code === 'VALIDATION_FAILED') return { kind: 'validation', title: '輸入資料無法通過驗證', message: error.message, severity: 'error', retryAllowed: false };
  if (status === 401 || error.code === 'UNAUTHENTICATED') return { kind: 'unauthorized', title: '登入狀態已失效', message: error.message, severity: 'error', retryAllowed: false };
  if (status === 403 || error.code === 'ACCESS_DENIED') return { kind: 'forbidden', title: '無權限', message: error.message, severity: 'error', retryAllowed: false };
  if (status === 404 || error.code.endsWith('_NOT_FOUND')) return { kind: 'not-found', title: '找不到資料', message: error.message, severity: 'warning', retryAllowed: operation === 'read' };
  if (status === 409 && error.code === 'DUPLICATE_RECEIVING') return { kind: 'duplicate-receiving', title: '收貨結果需要查核', message: error.message, severity: 'warning', retryAllowed: false };
  if (status === 409 || error.code === 'CONCURRENT_MODIFICATION') return { kind: 'concurrent-modification', title: '資料已被其他操作更新', message: error.message, severity: 'warning', retryAllowed: false };
  if (status === 503 && aiProviderCodes.has(error.code)) return { kind: 'ai-provider-unavailable', title: 'AI 服務暫時無法使用', message: error.message, severity: 'warning', retryAllowed: operation === 'read' };
  if ((status !== undefined && status >= 500) || error.code === 'INTERNAL_SERVER_ERROR') return { kind: 'internal-server-error', title: '伺服器暫時發生問題', message: error.message, severity: 'error', retryAllowed: operation === 'read' };
  if (operation === 'mutation' && (error.transportError || error.code === 'NETWORK_TIMEOUT' || error.code === 'NETWORK_ERROR')) {
    return { kind: 'unknown-mutation-result', title: '操作結果尚未確認', message: '連線中斷或逾時可能發生在系統完成操作之後。系統不會自動重送；請先使用正式查詢確認目前狀態。', severity: 'warning', retryAllowed: false };
  }
  return { kind: 'request-failed', title: '無法完成請求', message: error.message, severity: 'error', retryAllowed: operation === 'read' };
}
