import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DocumentPreviewDrawer from '../src/pages/Documents/DocumentPreviewDrawer';
import { getDocumentFile, getDocumentPreview, type DocumentResponse } from '../src/api/document';

vi.mock('../src/api/document', () => ({
  getDocumentFile: vi.fn(),
  getDocumentPreview: vi.fn(),
}));

const record = {
  id: 31,
  documentType: 'SUPPLIER_PROFILE',
  supplierId: 1,
  supplierCode: 'SUP-001',
  supplierName: '福爾摩沙原料工坊',
  title: 'SUP-001 供應商檔案',
  version: '1.0',
  status: 'READY',
  uploadedBy: null,
  uploadedAt: '2026-10-25T00:00:00Z',
  updatedAt: '2026-10-25T00:00:00Z',
  demoProvenance: null,
  semanticReview: null,
  semanticIndex: null,
} satisfies DocumentResponse;

function textBlob(text: string): Blob {
  const blob = new Blob([], { type: 'text/plain;charset=UTF-8' });
  Object.defineProperty(blob, 'text', { configurable: true, value: () => Promise.resolve(text) });
  return blob;
}

function textResponse(text: string) {
  return { data: textBlob(text), headers: { 'content-type': 'text/plain;charset=UTF-8' } };
}

function pdfResponse() {
  return { data: new Blob(['%PDF-1.7'], { type: 'application/pdf' }), headers: { 'content-type': 'application/pdf' } };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('URL', {
    createObjectURL: vi.fn(() => 'blob:document-preview'),
    revokeObjectURL: vi.fn(),
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('DocumentPreviewDrawer', () => {
  it('shows the complete Traditional Chinese text as inert text with document metadata', async () => {
    const content = '供應商合作會議紀錄\n\n第二段完整內容。\n<script>alert("xss")</script>';
    vi.mocked(getDocumentPreview).mockResolvedValue(textResponse(content) as never);
    render(<DocumentPreviewDrawer document={record} open onClose={vi.fn()} />);

    expect((await screen.findByLabelText('文件全文內容')).textContent).toBe(content);
    expect(screen.getByText('SUP-001 供應商檔案')).toBeTruthy();
    expect(screen.getByText(/SUP-001 — 福爾摩沙原料工坊/)).toBeTruthy();
    expect(screen.getByText('全文文字預覽 · 文件內容已完整顯示')).toBeTruthy();
    expect(document.querySelector('script')).toBeNull();
    expect(getDocumentPreview).toHaveBeenCalledWith(31, expect.any(AbortSignal));
  });

  it('renders the complete PDF in a viewer and releases its Blob URL when closed', async () => {
    vi.mocked(getDocumentPreview).mockResolvedValue(pdfResponse() as never);
    const onClose = vi.fn();
    const view = render(<DocumentPreviewDrawer document={record} open onClose={onClose} />);

    expect(await screen.findByTitle('SUP-001 供應商檔案 PDF 全文預覽')).toBeTruthy();
    expect((screen.getByTitle('SUP-001 供應商檔案 PDF 全文預覽') as HTMLIFrameElement).src).toBe('blob:document-preview');
    view.rerender(<DocumentPreviewDrawer document={record} open={false} onClose={onClose} />);
    await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:document-preview'));
  });

  it('does not let a slower response from another document replace the selected document', async () => {
    const first = deferred<Awaited<ReturnType<typeof getDocumentPreview>>>();
    const second = deferred<Awaited<ReturnType<typeof getDocumentPreview>>>();
    vi.mocked(getDocumentPreview).mockImplementation((id) => id === record.id ? first.promise : second.promise);
    const other = { ...record, id: 32, title: '第二份供應商文件' };
    const view = render(<DocumentPreviewDrawer document={record} open onClose={vi.fn()} />);
    view.rerender(<DocumentPreviewDrawer document={other} open onClose={vi.fn()} />);

    await act(async () => { second.resolve(textResponse('B 文件的完整內容') as never); });
    expect(await screen.findByText('B 文件的完整內容')).toBeTruthy();
    await act(async () => { first.resolve(textResponse('A 文件的較慢內容') as never); });
    expect(screen.getByText('B 文件的完整內容')).toBeTruthy();
    expect(screen.queryByText('A 文件的較慢內容')).toBeNull();
  });

  it('aborts a pending request when the drawer closes and maps authorization errors', async () => {
    let signal: AbortSignal | undefined;
    vi.mocked(getDocumentPreview).mockImplementation((_id, requestSignal) => {
      signal = requestSignal;
      return new Promise(() => {});
    });
    const view = render(<DocumentPreviewDrawer document={record} open onClose={vi.fn()} />);
    await waitFor(() => expect(signal).toBeInstanceOf(AbortSignal));
    view.rerender(<DocumentPreviewDrawer document={record} open={false} onClose={vi.fn()} />);
    expect(signal?.aborted).toBe(true);

    vi.mocked(getDocumentPreview).mockRejectedValue({ response: { status: 403 } });
    view.rerender(<DocumentPreviewDrawer document={record} open onClose={vi.fn()} />);
    expect(await screen.findByText('你沒有權限查看此文件。')).toBeTruthy();
    expect(screen.getByRole('button', { name: /重新載入/ })).toBeTruthy();
  });

  it.each([
    [401, '登入已逾時，請重新登入後再試。'],
    [413, '文件超出預覽服務限制；你可以嘗試下載原始文件。'],
    [415, '此文件不支援直接預覽；你可以下載原始文件查看。'],
  ] as const)('shows a recovery message for HTTP %i', async (status, message) => {
    vi.mocked(getDocumentPreview).mockRejectedValue({ response: { status } });
    render(<DocumentPreviewDrawer document={record} open onClose={vi.fn()} />);
    expect(await screen.findByText(message)).toBeTruthy();
  });

  it('can retry a failed preview and download the authenticated original file', async () => {
    const user = userEvent.setup();
    vi.mocked(getDocumentPreview)
      .mockRejectedValueOnce({ response: { status: 404 } })
      .mockResolvedValueOnce(textResponse('重試後完整內容') as never);
    vi.mocked(getDocumentFile).mockResolvedValue({
      data: new Blob(['original'], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }),
      headers: { 'content-disposition': "attachment; filename*=UTF-8''%E5%8E%9F%E5%A7%8BDOCX.docx" },
    } as never);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    render(<DocumentPreviewDrawer document={record} open onClose={vi.fn()} />);

    expect(await screen.findByText('找不到原始文件，檔案可能已遺失。')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /重新載入/ }));
    expect(await screen.findByText('重試後完整內容')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /下載原始文件/ }));
    await waitFor(() => expect(getDocumentFile).toHaveBeenCalledWith(31, expect.any(AbortSignal)));
    expect(click).toHaveBeenCalledOnce();
    expect(URL.createObjectURL).toHaveBeenCalled();
  });

  it('does not cancel an in-progress original download when preview retry starts', async () => {
    const user = userEvent.setup();
    const fileRequest = deferred<Awaited<ReturnType<typeof getDocumentFile>>>();
    vi.mocked(getDocumentPreview)
      .mockRejectedValueOnce({ response: { status: 404 } })
      .mockResolvedValueOnce(textResponse('重新載入的全文') as never);
    let downloadSignal: AbortSignal | undefined;
    vi.mocked(getDocumentFile).mockImplementation((_id, signal) => {
      downloadSignal = signal;
      return fileRequest.promise;
    });
    render(<DocumentPreviewDrawer document={record} open onClose={vi.fn()} />);

    expect(await screen.findByText('找不到原始文件，檔案可能已遺失。')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: /下載原始文件/ }));
    await waitFor(() => expect(downloadSignal).toBeInstanceOf(AbortSignal));
    await user.click(screen.getByRole('button', { name: /重新載入/ }));

    expect(downloadSignal?.aborted).toBe(false);
    expect(await screen.findByText('重新載入的全文')).toBeTruthy();
    await act(async () => {
      fileRequest.resolve({
        data: new Blob(['original']),
        headers: { 'content-disposition': 'attachment; filename="original.txt"' },
      } as never);
    });
    await waitFor(() => {
      const downloadButton = screen.getByRole('button', { name: /下載原始文件/ }) as HTMLButtonElement;
      expect(downloadButton.disabled).toBe(false);
    });
  });
});
