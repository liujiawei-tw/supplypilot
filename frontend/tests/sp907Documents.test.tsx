import { beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import App from '../src/App';
import { axiosClient, TOKEN_STORAGE_KEY } from '../src/api/axiosClient';
import { router } from '../src/router/routes';
import type { AuthenticatedUser } from '../src/types/api';

const manager: AuthenticatedUser = { id: 3, username: 'manager', displayName: 'Manager', role: 'PROCUREMENT_MANAGER' };
const baseline = {
  id: 31, documentType: 'SUPPLIER_PROFILE', supplierId: 1, supplierCode: 'SUP-001', supplierName: '福爾摩沙原料工坊',
  title: 'SUP-001 供應商檔案', version: '1.0', storagePath: '/private/document.txt', status: 'READY',
  uploadedBy: null, uploadedAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z',
  demoProvenance: { datasetKey: 'SP-901', documentKey: 'DOC-SUP-001-PROFILE-SP907', themeKey: 'supplier_profile', corpusVersion: 'sp-907-zh-tw-v3', sourceSha256: 'a'.repeat(64), origin: 'ADDED_SP907' },
  semanticReview: { status: 'APPROVED', sourceHash: 'b'.repeat(64), version: '1.0', reason: null, actorType: 'SYSTEM', actor: 'TRUSTED_DEMO_CORPUS', trigger: 'TRUSTED_DEMO_CORPUS_AUTO_REVIEW', policyDecision: 'APPROVED', reviewedAt: '2026-10-25T00:00:00Z', valid: true, invalidatedAt: null, invalidationReason: null },
  semanticIndex: { status: 'INDEXED', jobId: 9, sourceHash: 'b'.repeat(64), expectedChunks: 1, publishedChunks: 1, failureCode: null, generationKey: 'generation-v1', publicationGeneration: 1 },
};
const secondPage = { ...baseline, id: 32, title: 'SUP-001 第二份基線文件', demoProvenance: null,
  semanticIndex: { ...baseline.semanticIndex, status: 'PENDING', publishedChunks: 0 } };
const page = (content: unknown[], pageNumber: number, totalElements: number) => ({
  content, page: pageNumber, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20),
});
function response(config: InternalAxiosRequestConfig, data: unknown): AxiosResponse {
  return { config, data, status: 200, statusText: 'OK', headers: {} };
}
async function openDocuments() {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'valid-token');
  await router.navigate('/documents');
  render(<App />);
}

beforeEach(() => { localStorage.clear(); cleanup(); });

describe('SP-907 Documents UI', () => {
  it('filters by supplier name/code and keeps READY distinct from published Semantic indexing', async () => {
    const user = userEvent.setup();
    const requests: InternalAxiosRequestConfig[] = [];
    axiosClient.defaults.adapter = vi.fn(async (config) => {
      requests.push(config);
      if (config.url === '/auth/me') return response(config, manager);
      if (config.url === '/suppliers') return response(config, page([
        { id: 1, code: 'SUP-001', name: '福爾摩沙原料工坊', country: 'TW', reliabilityScore: 0, qualityScore: 0, status: 'ACTIVE', createdAt: '', updatedAt: '' },
      ], 0, 1));
      if (config.url === '/documents') {
        const pageNumber = Number(config.params?.page ?? 0);
        return response(config, page(pageNumber === 1 ? [secondPage] : [baseline], pageNumber, 21));
      }
      throw new Error(`Unexpected ${config.method} ${config.url}`);
    });

    await openDocuments();
    const table = await screen.findByRole('table');
    const row = await within(table).findByRole('row', { name: /SUP-001 供應商檔案/ });
    expect(within(row).getByText('供應商檔案')).toBeTruthy();
    expect(within(row).getByText('展示基線')).toBeTruthy();
    expect(within(row).getByText('已就緒')).toBeTruthy();
    expect(within(row).getByText('已發布')).toBeTruthy();
    expect(within(row).getByText('SUP-001 — 福爾摩沙原料工坊')).toBeTruthy();

    await user.click(screen.getByTitle('下一頁'));
    await vi.waitFor(() => expect(requests.some((request) => request.url === '/documents'
      && Number(request.params?.page) === 1)).toBe(true));
    expect(await screen.findByText('SUP-001 第二份基線文件')).toBeTruthy();
    expect(screen.getByText('待處理')).toBeTruthy();

    fireEvent.mouseDown(screen.getByRole('combobox', { name: '供應商篩選' }));
    await user.click(await screen.findByText('SUP-001 — 福爾摩沙原料工坊', { selector: '.ant-select-item-option-content' }));
    await waitForDocumentFilter(requests, 1, 0);
    expect(await screen.findByText('SUP-001 供應商檔案')).toBeTruthy();
  });
});

async function waitForDocumentFilter(requests: InternalAxiosRequestConfig[], supplierId: number, pageNumber: number) {
  await vi.waitFor(() => {
    const matched = requests.some((request) => request.url === '/documents'
      && Number(request.params?.supplierId) === supplierId
      && Number(request.params?.page) === pageNumber);
    expect(matched, JSON.stringify(requests.map(({ url, params }) => ({ url, params })))).toBe(true);
  });
}
