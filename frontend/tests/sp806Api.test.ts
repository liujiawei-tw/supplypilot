import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { axiosClient } from '../src/api/axiosClient';
import { confirmPurchaseDraft, proposePurchaseDraft, queryAi } from '../src/api/ai';
import { getDocument, ingestDocument, listDocuments, updateDocument, uploadDocument } from '../src/api/document';
import { can } from '../src/auth/permissions';

function response(config: InternalAxiosRequestConfig, data: unknown = {}): AxiosResponse {
  return { config, data, status: 200, statusText: 'OK', headers: {} };
}

beforeEach(() => { localStorage.clear(); });

describe('SP-806 API and permission contracts', () => {
  it('keeps Document actions and AI Proposal separate from read/query permissions', () => {
    expect(can('PROCUREMENT_STAFF', 'documentRead')).toBe(true);
    expect(can('PROCUREMENT_STAFF', 'documentUpload')).toBe(true);
    expect(can('PROCUREMENT_MANAGER', 'documentRead')).toBe(true);
    expect(can('PROCUREMENT_MANAGER', 'documentUpload')).toBe(false);
    expect(can('PROCUREMENT_MANAGER', 'aiQuery')).toBe(true);
    expect(can('PROCUREMENT_MANAGER', 'aiProposal')).toBe(false);
    expect(can('WAREHOUSE_STAFF', 'documentRead')).toBe(false);
    expect(can('WAREHOUSE_STAFF', 'aiQuery')).toBe(false);
    expect(can('PRODUCTION_PLANNER', 'documents')).toBe(false);
    expect(can('ADMIN', 'documentIngest')).toBe(true);
    expect(can('ADMIN', 'semanticManagement')).toBe(true);
    expect(can('PROCUREMENT_MANAGER', 'semanticManagement')).toBe(false);
    expect(can('PROCUREMENT_STAFF', 'semanticManagement')).toBe(false);
    expect(can('WAREHOUSE_STAFF', 'semanticManagement')).toBe(false);
    expect(can('PRODUCTION_PLANNER', 'semanticManagement')).toBe(false);
  });

  it('uses backend Document filters, detail, archive, and explicit ingestion endpoints', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config); });
    await listDocuments({ documentType: 'QUOTATION', supplierId: 7, status: 'READY', page: 2, size: 50, sort: 'updatedAt,asc' });
    await getDocument(9);
    await updateDocument(9, { title: 'Updated', status: 'INACTIVE' });
    await ingestDocument(9);
    expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
      { method: 'get', url: '/documents' }, { method: 'get', url: '/documents/9' },
      { method: 'patch', url: '/documents/9' }, { method: 'post', url: '/documents/9/ingestions' },
    ]);
    expect(requests[0].params).toEqual({ documentType: 'QUOTATION', supplierId: 7, status: 'READY', page: 2, size: 50, sort: 'updatedAt,asc' });
    expect(JSON.parse(requests[2].data as string)).toEqual({ title: 'Updated', status: 'INACTIVE' });
  });

  it('sends multipart FormData without forcing the JSON Content-Type or a manual boundary', async () => {
    let request!: InternalAxiosRequestConfig;
    axiosClient.defaults.adapter = vi.fn(async (config) => { request = config; return response(config); });
    const file = new File(['contract'], 'contract.txt', { type: 'text/plain' });
    await uploadDocument({ documentType: 'SUPPLIER_CONTRACT', supplierId: 7, title: 'Contract', version: 'v1', file });
    expect(request.url).toBe('/documents');
    expect(request.data).toBeInstanceOf(FormData);
    const form = request.data as FormData;
    expect(form.get('documentType')).toBe('SUPPLIER_CONTRACT');
    expect(form.get('supplierId')).toBe('7');
    expect(form.get('title')).toBe('Contract');
    expect(form.get('version')).toBe('v1');
    expect(form.get('file')).toBe(file);
    expect(request.headers.get('Content-Type')).toBe(false);
  });

  it('sends exact AI query, proposal, and confirm payloads while preserving decimal strings', async () => {
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => { requests.push(config); return response(config); });
    const draft = {
      supplierId: 12, expectedDeliveryDate: '2026-10-20',
      items: [{ materialId: 7, quantity: '999999999999999.1234000', unitPrice: '0.0000001234500', recommendationId: 91, supplierRecommendationResultId: 18 }],
    };
    await queryAi('Explain Recommendation #91');
    await proposePurchaseDraft('Prepare a DRAFT', draft);
    await confirmPurchaseDraft('proposal-1', 'server-token', draft);
    expect(JSON.parse(requests[0].data as string)).toEqual({ message: 'Explain Recommendation #91' });
    expect(JSON.parse(requests[1].data as string)).toEqual({ message: 'Prepare a DRAFT', draft });
    expect(requests[2].url).toBe('/ai/purchase-draft-proposals/proposal-1/confirm');
    expect(JSON.parse(requests[2].data as string)).toEqual({ confirmationToken: 'server-token', draft });
  });

  it('parses BigDecimal tokens in AI Proposal and Confirm responses without precision loss', async () => {
    axiosClient.defaults.adapter = vi.fn(async (config) => response(config,
      config.url?.endsWith('/confirm')
        ? '{"id":42,"poNumber":"PO-42","status":"DRAFT","items":[{"quantity":999999999999999.1234000,"unitPrice":0.0000001234500,"total":123.4500}]}'
        : '{"status":"PENDING_CONFIRMATION","proposalId":"p1","draft":{"supplierId":12,"items":[{"materialId":7,"quantity":999999999999999.1234000,"unitPrice":0.0000001234500}]},"expiresAt":"2099-01-01T00:00:00Z","confirmationToken":"secret","missingFields":[]}'));
    const draft = { supplierId: 12, items: [{ materialId: 7, quantity: '1', unitPrice: '1' }] };
    const proposal = await proposePurchaseDraft('Prepare', draft);
    expect(proposal.draft?.items[0]).toMatchObject({ quantity: '999999999999999.1234000', unitPrice: '0.0000001234500' });
    const confirmed = await confirmPurchaseDraft('p1', 'secret', proposal.draft!);
    expect(confirmed.items[0]).toMatchObject({ quantity: '999999999999999.1234000', unitPrice: '0.0000001234500', total: '123.4500' });
  });
});
