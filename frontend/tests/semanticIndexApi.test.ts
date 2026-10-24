import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { approveOutbound, excludeOutbound, getSemanticIndexJobs, getSemanticReadiness, retrySemanticIndexJob, runBackfill, runSemanticGateAudit } from '../src/api/semanticIndex';
import { isUnknownMutationResult, requiresMutationVerification, toApiError } from '../src/api/apiError';

function response(config: InternalAxiosRequestConfig, data: unknown = {}, status = 200): AxiosResponse {
  return { config, data, status, statusText: 'OK', headers: {} };
}

beforeEach(() => { localStorage.clear(); });

describe('Semantic Index API contract', () => {
  it('uses the existing Axios client and exact HTTP methods, paths, and cursor params', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url?.endsWith('approve-outbound') || config.url?.endsWith('exclude-outbound')) return response(config, { documentId: 17, version: '1.0', sourceHash: 'hash', approvedBy: 3, status: 'APPROVED', reason: null });
      if (config.url?.endsWith('/backfill')) return response(config, { processed: 0, ready: 0, failed: 0, skipped: 0, requiresReview: 0, lastId: 129, morePossible: false });
      const gate = { passed: true, generationKey: 'gen', modelId: 'model', dimensions: 768, ready: 1, skipped: 0, failed: 0, requiresReview: 0, pending: 0, findings: [], auditId: config.url?.endsWith('/gate-audit') ? 9 : null };
      return response(config, gate);
    });
    await approveOutbound(17);
    await excludeOutbound(17);
    await runBackfill(129, 50);
    await getSemanticReadiness();
    await runSemanticGateAudit();
    expect(requests.map(({ method, url }) => [method, url])).toEqual([
      ['post', '/semantic-index/documents/17/approve-outbound'],
      ['post', '/semantic-index/documents/17/exclude-outbound'],
      ['post', '/semantic-index/backfill'],
      ['get', '/semantic-index/readiness'],
      ['post', '/semantic-index/gate-audit'],
    ]);
    expect(requests[2].params).toEqual({ afterId: 129, limit: 50 });
    expect(requests[2].data).toBe('null');
  });

  it('returns actual response fields without renaming and does not retry failed mutations', async () => {
    const calls: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      calls.push(config.url ?? '');
      if (config.url?.endsWith('approve-outbound')) return response(config, { documentId: 17, version: '1.0', sourceHash: 'sha256', approvedBy: 3, status: 'REQUIRES_REVIEW', reason: 'SENSITIVE_CONTENT' });
      throw new AxiosError('timeout', 'ETIMEDOUT', config);
    });
    await expect(approveOutbound(17)).resolves.toMatchObject({ status: 'REQUIRES_REVIEW', reason: 'SENSITIVE_CONTENT', sourceHash: 'sha256' });
    await expect(runSemanticGateAudit()).rejects.toMatchObject({ code: 'ETIMEDOUT' });
    expect(calls).toEqual(['/semantic-index/documents/17/approve-outbound', '/semantic-index/gate-audit']);
  });

  it('treats incomplete mutation responses as unknown and rejects incomplete Readiness without inventing success', async () => {
    const calls: string[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { calls.push(config.url ?? ''); return response(config, {}); });
    let mutationError: unknown;
    try { await runSemanticGateAudit(); } catch (error) { mutationError = error; }
    expect(isUnknownMutationResult(mutationError)).toBe(true);
    await expect(getSemanticReadiness()).rejects.toThrow(/incomplete Readiness/);
    expect(calls).toEqual(['/semantic-index/gate-audit', '/semantic-index/readiness']);
  });


  it('lists per-document jobs and retries a failed job through the exact admin endpoints', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    const job = { id: 4, documentId: 17, version: 'v2', status: 'FAILED', attempts: 4,
      maxAttempts: 4, retryCount: 0, nextAttemptAt: null, failureCode: 'PROVIDER_TIMEOUT',
      updatedAt: '2026-10-24T01:00:00Z' };
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      return response(config, config.method === 'get' ? [job] : { ...job, status: 'QUEUED', attempts: 0, retryCount: 1 });
    });
    await expect(getSemanticIndexJobs()).resolves.toEqual([job]);
    await expect(retrySemanticIndexJob(4)).resolves.toMatchObject({ status: 'QUEUED', retryCount: 1 });
    expect(requests.map(({ method, url }) => [method, url])).toEqual([
      ['get', '/semantic-index/jobs'], ['post', '/semantic-index/jobs/4/retry'],
    ]);
    expect(requests[0].params).toEqual({ limit: 50 });
  });

  it('rejects malformed per-document job responses', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => response(config, [{ id: 4, status: 'FAILED' }]));
    await expect(getSemanticIndexJobs()).rejects.toThrow(/incomplete Semantic indexing job list/);
  });
  it.each([401, 403, 404, 409, 503])('preserves HTTP %i semantics and never retries a Gate Audit POST', async (status) => {
    let calls = 0;
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      calls += 1;
      throw new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, undefined, response(config, { code: `HTTP_${status}`, message: 'server message', traceId: 'trace-1' }, status));
    });
    let error: unknown;
    try { await runSemanticGateAudit(); } catch (cause) { error = cause; }
    const apiError = toApiError(error);
    expect(apiError.httpStatus).toBe(status);
    expect(apiError.traceId).toBe('trace-1');
    if (status === 409 || status === 503) expect(requiresMutationVerification(error)).toBe(true);
    expect(calls).toBe(1);
  });
});
