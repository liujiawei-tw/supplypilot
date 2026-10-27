import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Space, Spin, Tag, Typography } from 'antd';
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { getDocumentFile, getDocumentPreview, type DocumentResponse } from '../../api/document';
import { displayLabel } from '../../utils/displayLabels';
import './DocumentsPage.css';

const { Text, Title } = Typography;

type PreviewState =
  | { kind: 'idle' | 'loading' }
  | { kind: 'text'; content: string }
  | { kind: 'pdf'; url: string }
  | { kind: 'error'; message: string }
  | { kind: 'unsupported' };

interface DocumentPreviewDrawerProps {
  document: DocumentResponse | null;
  open: boolean;
  onClose: () => void;
}

function statusOf(error: unknown): { status?: number; code?: string } {
  if (!error || typeof error !== 'object') return {};
  const candidate = error as { response?: { status?: number }; code?: string };
  return { status: candidate.response?.status, code: candidate.code };
}

function readErrorMessage(error: unknown, downloading = false): string {
  const { status, code } = statusOf(error);
  if (status === 401) return '登入已逾時，請重新登入後再試。';
  if (status === 403) return '你沒有權限查看此文件。';
  if (status === 404) return '找不到原始文件，檔案可能已遺失。';
  if (status === 413) return downloading
    ? '原始文件超出目前下載服務限制，請聯絡系統管理員。'
    : '文件超出預覽服務限制；你可以嘗試下載原始文件。';
  if (status === 415) return '此文件不支援直接預覽；你可以下載原始文件查看。';
  if (status && status >= 500) return '文件服務暫時無法讀取，請稍後重試。';
  if (code === 'ECONNABORTED' || code === 'ETIMEDOUT') return '讀取逾時，請確認網路後重試。';
  if (code === 'ERR_NETWORK' || !status) return '目前無法連線至文件服務，請確認網路後重試。';
  return '無法讀取文件，請重試。';
}

function responseContentType(headers: Record<string, unknown>, blob: Blob): string {
  const value = headers['content-type'];
  return (typeof value === 'string' ? value : blob.type).split(';', 1)[0].trim().toLowerCase();
}

function safeDownloadName(headers: Record<string, unknown>, record: DocumentResponse): string {
  const disposition = headers['content-disposition'];
  if (typeof disposition === 'string') {
    const encodedName = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
    const plainName = /filename="?([^";]+)"?/i.exec(disposition)?.[1];
    const candidate = encodedName ?? plainName;
    if (candidate) {
      try {
        const decoded = encodedName ? decodeURIComponent(candidate) : candidate;
        const basename = decoded.split(/[\\/]/).pop()?.split('')
          .filter((character) => {
            const code = character.charCodeAt(0);
            return code >= 0x20 && code !== 0x7f;
          })
          .join('')
          .trim();
        if (basename) return basename.slice(0, 180);
      } catch { /* Use the safe document title below for malformed header values. */ }
    }
  }
  return '文件-' + record.id;
}

function formatInstant(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-TW', { timeZone: 'Asia/Taipei' });
}

const statusColor: Record<DocumentResponse['status'], string> = {
  PROCESSING: 'processing', READY: 'success', FAILED: 'error', INACTIVE: 'default',
};

export default function DocumentPreviewDrawer({ document: record, open, onClose }: DocumentPreviewDrawerProps) {
  const [previewResult, setPreviewResult] = useState<{ requestKey: string; state: PreviewState } | null>(null);
  const [retryVersion, setRetryVersion] = useState(0);
  const [openCycle, setOpenCycle] = useState(0);
  const [downloadResult, setDownloadResult] = useState<{ sessionKey: string; loading: boolean; error: string | null } | null>(null);
  const downloadController = useRef<AbortController | null>(null);
  const downloadPending = useRef(false);
  const documentId = record?.id;
  const documentVersion = record?.version;
  const sessionKey = open && documentId
    ? JSON.stringify([openCycle, documentId, documentVersion])
    : null;
  const requestKey = sessionKey ? JSON.stringify([sessionKey, retryVersion]) : null;
  const preview: PreviewState = requestKey
    ? previewResult?.requestKey === requestKey ? previewResult.state : { kind: 'loading' }
    : { kind: 'idle' };
  const downloadLoading = !!sessionKey && downloadResult?.sessionKey === sessionKey && downloadResult.loading;
  const downloadError = sessionKey && downloadResult?.sessionKey === sessionKey ? downloadResult.error : null;

  useEffect(() => {
    if (!open || !documentId || !requestKey) return;

    let active = true;
    let objectUrl: string | null = null;
    const controller = new AbortController();

    const publishPreview = (state: PreviewState) => {
      if (active) setPreviewResult({ requestKey, state });
    };

    void getDocumentPreview(documentId, controller.signal)
      .then(async (response) => {
        if (!active) return;
        const contentType = responseContentType(response.headers as Record<string, unknown>, response.data);
        if (contentType === 'application/pdf') {
          objectUrl = URL.createObjectURL(response.data);
          publishPreview({ kind: 'pdf', url: objectUrl });
          return;
        }
        if (contentType === 'text/plain' || contentType.startsWith('text/')) {
          const content = await response.data.text();
          publishPreview({ kind: 'text', content });
          return;
        }
        publishPreview({ kind: 'unsupported' });
      })
      .catch((error: unknown) => {
        if (active && !controller.signal.aborted) {
          publishPreview({ kind: 'error', message: readErrorMessage(error) });
        }
      });

    return () => {
      active = false;
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [open, documentId, documentVersion, openCycle, requestKey]);

  useEffect(() => () => {
    downloadController.current?.abort();
    downloadController.current = null;
    downloadPending.current = false;
  }, [sessionKey]);

  const downloadOriginal = async () => {
    if (!record || !sessionKey || downloadPending.current) return;
    downloadPending.current = true;
    const controller = new AbortController();
    downloadController.current = controller;
    setDownloadResult({ sessionKey, loading: true, error: null });
    try {
      const response = await getDocumentFile(record.id, controller.signal);
      if (controller.signal.aborted) return;
      const url = URL.createObjectURL(response.data);
      const link = window.document.createElement('a');
      link.href = url;
      link.download = safeDownloadName(response.headers as Record<string, unknown>, record);
      link.rel = 'noopener';
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (error) {
      if (!controller.signal.aborted) {
        setDownloadResult({ sessionKey, loading: false, error: readErrorMessage(error, true) });
      }
    } finally {
      if (!controller.signal.aborted) {
        downloadPending.current = false;
        downloadController.current = null;
        setDownloadResult((previous) => previous?.sessionKey === sessionKey
          ? { sessionKey, loading: false, error: previous.error }
          : previous);
      }
    }
  };

  const retryPreview = () => setRetryVersion((version) => version + 1);
  const closePreview = () => {
    setOpenCycle((cycle) => cycle + 1);
    onClose();
  };

  return <Drawer
    className="document-preview"
    title="文件全文預覽"
    open={open && !!record}
    onClose={closePreview}
    size="min(1100px, 100vw)"
    destroyOnHidden
  >
    {record && <>
      <Space className="document-preview__body" orientation="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          <Title level={4} className="document-preview__title">{record.title}</Title>
          <Descriptions className="document-preview__meta" bordered size="small" column={{ xs: 1, sm: 2 }} items={[
            { key: 'type', label: '文件類型', children: displayLabel(record.documentType) },
            { key: 'version', label: '版本', children: record.version },
            { key: 'supplier', label: '供應商', children: record.supplierCode
              ? record.supplierCode + ' — ' + (record.supplierName ?? '未命名')
              : record.supplierId ?? '—' },
            { key: 'uploaded', label: '上傳日期', children: formatInstant(record.uploadedAt) },
            { key: 'status', label: '文件狀態', children: <Tag color={statusColor[record.status]}>{displayLabel(record.status)}</Tag> },
          ]} />
        </div>

        <Alert
          type="info"
          showIcon
          title="預覽讀取持久化原始文件"
          description="全文預覽與文件解析、出站審核及語意索引發布分開；可預覽不代表文件已核准外送或已建立語意索引。"
        />

        <Space wrap>
          <Button icon={<DownloadOutlined aria-hidden="true" />} loading={downloadLoading} disabled={downloadLoading} onClick={() => void downloadOriginal()}>
            下載原始文件
          </Button>
          {(preview.kind === 'error' || preview.kind === 'unsupported') && <Button icon={<ReloadOutlined aria-hidden="true" />} onClick={retryPreview}>重新載入</Button>}
        </Space>
        {downloadError && <Alert type="error" showIcon title="無法下載原始文件" description={downloadError} />}

        {preview.kind === 'loading' && <div className="document-preview__loading">
          <Spin size="large" description="正在載入文件" />
        </div>}
        {preview.kind === 'error' && <Alert type="error" showIcon title="無法讀取文件" description={preview.message} />}
        {preview.kind === 'unsupported' && <Alert type="warning" showIcon title="此文件不支援直接預覽" description="你可以下載原始文件查看；系統不會以摘要或索引內容替代原文。" />}
        {preview.kind === 'text' && <>
          <Text type="secondary">全文文字預覽 · 文件內容已完整顯示</Text>
          <pre className="document-preview__text" aria-label="文件全文內容">{preview.content}</pre>
        </>}
        {preview.kind === 'pdf' && <iframe
          className="document-preview__pdf"
          title={record.title + ' PDF 全文預覽'}
          src={preview.url}
          referrerPolicy="no-referrer"
        />}
      </Space>
    </>}
  </Drawer>;
}
