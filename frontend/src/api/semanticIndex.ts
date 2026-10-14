import { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import axiosClient from './axiosClient';

export type SemanticReviewStatus = 'APPROVED' | 'SKIPPED' | 'REQUIRES_REVIEW';

export interface OutboundReviewResponse {
  documentId: number;
  version: string;
  sourceHash: string;
  approvedBy: number;
  status: SemanticReviewStatus;
  reason: string | null;
}

export interface BackfillBatchResponse {
  processed: number;
  ready: number;
  failed: number;
  skipped: number;
  requiresReview: number;
  lastId: number;
  morePossible: boolean;
}

export interface SemanticFinding {
  documentId: number;
  version: string;
  status: string;
  reason: string | null;
}

export interface SemanticGateReport {
  passed: boolean;
  generationKey: string;
  modelId: string;
  dimensions: number;
  ready: number;
  skipped: number;
  failed: number;
  requiresReview: number;
  pending: number;
  findings: SemanticFinding[];
  auditId: number | null;
}

function validNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function validReview(value: unknown): value is OutboundReviewResponse {
  if (!value || typeof value !== 'object') return false;
  const item = value as Record<string, unknown>;
  return validNumber(item.documentId) && typeof item.version === 'string'
    && typeof item.sourceHash === 'string' && validNumber(item.approvedBy)
    && ['APPROVED', 'SKIPPED', 'REQUIRES_REVIEW'].includes(String(item.status))
    && (item.reason === null || typeof item.reason === 'string');
}

function validBatch(value: unknown): value is BackfillBatchResponse {
  if (!value || typeof value !== 'object') return false;
  const item = value as Record<string, unknown>;
  return ['processed', 'ready', 'failed', 'skipped', 'requiresReview', 'lastId'].every((key) => validNumber(item[key]))
    && typeof item.morePossible === 'boolean';
}

function validReport(value: unknown): value is SemanticGateReport {
  if (!value || typeof value !== 'object') return false;
  const item = value as Record<string, unknown>;
  const findings = item.findings;
  return typeof item.passed === 'boolean' && typeof item.generationKey === 'string'
    && typeof item.modelId === 'string' && validNumber(item.dimensions)
    && ['ready', 'skipped', 'failed', 'requiresReview', 'pending'].every((key) => validNumber(item[key]))
    && (item.auditId === null || validNumber(item.auditId))
    && Array.isArray(findings) && findings.every((finding) => {
      if (!finding || typeof finding !== 'object') return false;
      const entry = finding as Record<string, unknown>;
      return validNumber(entry.documentId) && typeof entry.version === 'string'
        && typeof entry.status === 'string' && (entry.reason === null || typeof entry.reason === 'string');
    });
}

function validAuditReport(value: unknown): value is SemanticGateReport {
  return validReport(value) && validNumber(value.auditId);
}

function unknownResponse(config: InternalAxiosRequestConfig): AxiosError {
  return new AxiosError('Backend returned an incomplete Semantic mutation response.', 'ERR_BAD_RESPONSE', config);
}

function mutationResult<T>(config: InternalAxiosRequestConfig, data: unknown, valid: (value: unknown) => value is T): T {
  if (!valid(data)) throw unknownResponse(config);
  return data;
}

export function approveOutbound(documentId: number): Promise<OutboundReviewResponse> {
  return axiosClient.post<OutboundReviewResponse>(`/semantic-index/documents/${documentId}/approve-outbound`).then(({ data, config }) => mutationResult(config, data, validReview));
}

export function excludeOutbound(documentId: number): Promise<OutboundReviewResponse> {
  return axiosClient.post<OutboundReviewResponse>(`/semantic-index/documents/${documentId}/exclude-outbound`).then(({ data, config }) => mutationResult(config, data, validReview));
}

export function runBackfill(afterId = 0, limit = 25): Promise<BackfillBatchResponse> {
  return axiosClient.post<BackfillBatchResponse>('/semantic-index/backfill', null, { params: { afterId, limit } }).then(({ data, config }) => mutationResult(config, data, validBatch));
}

export function getSemanticReadiness(): Promise<SemanticGateReport> {
  return axiosClient.get<SemanticGateReport>('/semantic-index/readiness').then(({ data }) => {
    if (!validReport(data)) throw new Error('Backend returned an incomplete Readiness report.');
    return data;
  });
}

export function runSemanticGateAudit(): Promise<SemanticGateReport> {
  return axiosClient.post<SemanticGateReport>('/semantic-index/gate-audit').then(({ data, config }) => mutationResult(config, data, validAuditReport));
}
