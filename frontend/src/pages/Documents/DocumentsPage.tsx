import { useCallback, useEffect, useRef, useState } from 'react';
import { isAxiosError } from 'axios';
import {
  Alert, Button, Descriptions, Drawer, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography,
  type TablePaginationConfig,
} from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import {
  getDocument, ingestDocument, listDocuments, updateDocument, uploadDocument,
  type DocumentQuery, type DocumentResponse, type DocumentStatus, type DocumentType,
} from '../../api/document';
import { toApiError } from '../../api/apiError';
import { useAuth } from '../../auth/useAuth';
import { can } from '../../auth/permissions';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import type { ApiErrorResponse, PageResponse } from '../../types/api';
import { validateDocumentFile } from './documentValidation';

const { Title, Text } = Typography;
const documentTypes: DocumentType[] = ['SUPPLIER_CONTRACT', 'QUOTATION', 'PROCUREMENT_SOP', 'PRODUCT_SPECIFICATION', 'QUALITY_REQUIREMENT'];
const statuses: DocumentStatus[] = ['PROCESSING', 'READY', 'FAILED', 'INACTIVE'];
const statusColor: Record<DocumentStatus, string> = { PROCESSING: 'processing', READY: 'success', FAILED: 'error', INACTIVE: 'default' };

interface UploadValues { documentType: DocumentType; supplierId?: number; title: string; version: string; }

function formatInstant(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

function isUnknownResult(error: unknown): boolean {
  return isAxiosError(error) && (!error.response || error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT');
}

export default function DocumentsPage() {
  const { user } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage] = useState<PageResponse<DocumentResponse> | null>(null);
  const [query, setQuery] = useState<DocumentQuery>({ page: 0, size: 20, sort: 'uploadedAt,desc' });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [selected, setSelected] = useState<DocumentResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<ApiErrorResponse | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploadError, setUploadError] = useState<ApiErrorResponse | null>(null);
  const [uploaded, setUploaded] = useState<DocumentResponse | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [actionError, setActionError] = useState<ApiErrorResponse | null>(null);
  const [ingestConfirm, setIngestConfirm] = useState(false);
  const [acting, setActing] = useState(false);
  const [form] = Form.useForm<UploadValues>();
  const ingestingRef = useRef(false);
  const canUpload = !!user && can(user.role, 'documentUpload');
  const canUpdate = !!user && can(user.role, 'documentUpdate');
  const canIngest = !!user && can(user.role, 'documentIngest');

  const loadList = useCallback(async (next = query) => {
    setLoading(true); setError(null);
    try { setPage(await listDocuments(next)); }
    catch (requestError) { setError(toApiError(requestError)); }
    finally { setLoading(false); }
  }, [query]);

  useEffect(() => { const timer = window.setTimeout(() => void loadList(), 0); return () => window.clearTimeout(timer); }, [loadList]);

  const openDetail = useCallback(async (id: number) => {
    setDetailLoading(true); setDetailError(null); setActionError(null);
    try {
      const document = await getDocument(id);
      setSelected(document); setEditTitle(document.title);
      setSearchParams((current) => { const next = new URLSearchParams(current); next.set('documentId', String(id)); return next; });
    } catch (requestError) { setDetailError(toApiError(requestError)); setSelected(null); }
    finally { setDetailLoading(false); }
  }, [setSearchParams]);

  useEffect(() => {
    const raw = searchParams.get('documentId');
    if (raw && /^\d+$/.test(raw) && Number.isSafeInteger(Number(raw)) && Number(raw) > 0 && selected?.id !== Number(raw)) {
      const timer = window.setTimeout(() => void openDetail(Number(raw)), 0);
      return () => window.clearTimeout(timer);
    }
  }, [openDetail, searchParams, selected?.id]);

  const closeDetail = () => {
    setSelected(null); setDetailError(null); setActionError(null); setIngestConfirm(false);
    setSearchParams((current) => { const next = new URLSearchParams(current); next.delete('documentId'); return next; });
  };

  const submitUpload = async () => {
    let values: UploadValues;
    try { values = await form.validateFields(); } catch { return; }
    const fileMessage = validateDocumentFile(uploadFile);
    if (fileMessage) { setUploadError({ code: 'INVALID_FILE_FORMAT', message: fileMessage, traceId: '', timestamp: '', path: '' }); return; }
    setUploading(true); setUploadError(null);
    try {
      const result = await uploadDocument({ ...values, title: values.title.trim(), version: values.version.trim(), file: uploadFile! });
      setUploaded(result); setUploadOpen(false); form.resetFields(); setUploadFile(null);
      await loadList({ ...query, page: 0 });
    } catch (requestError) { setUploadError(toApiError(requestError)); }
    finally { setUploading(false); }
  };

  const refreshDetail = async () => { if (selected) await openDetail(selected.id); };

  const saveTitle = async () => {
    if (!selected || !editTitle.trim()) return;
    setActing(true); setActionError(null);
    try { const next = await updateDocument(selected.id, { title: editTitle.trim() }); setSelected(next); await loadList(); }
    catch (requestError) { setActionError(toApiError(requestError)); }
    finally { setActing(false); }
  };

  const archive = async () => {
    if (!selected) return;
    setActing(true); setActionError(null);
    try { const next = await updateDocument(selected.id, { status: 'INACTIVE' }); setSelected(next); await loadList(); }
    catch (requestError) { setActionError(toApiError(requestError)); }
    finally { setActing(false); }
  };

  const ingest = async () => {
    if (!selected || ingestingRef.current) return;
    ingestingRef.current = true; setActing(true); setActionError(null); setIngestConfirm(false);
    try { const next = await ingestDocument(selected.id); setSelected(next); await loadList(); }
    catch (requestError) {
      const apiError = toApiError(requestError);
      setActionError(isUnknownResult(requestError)
        ? { ...apiError, message: '索引結果不明；系統不會自動重送。請先重新取得文件狀態再決定後續操作。' }
        : apiError);
      if (isAxiosError(requestError) && requestError.response?.status === 409) await refreshDetail();
    } finally { ingestingRef.current = false; setActing(false); }
  };

  const updateQuery = (patch: Partial<DocumentQuery>) => {
    const next = { ...query, ...patch, page: patch.page ?? 0 };
    setQuery(next);
  };

  const columns = [
    { title: 'Document ID', dataIndex: 'id', key: 'id', render: (id: number) => <Button type="link" onClick={() => void openDetail(id)}>#{id}</Button> },
    { title: 'Title', dataIndex: 'title', key: 'title' },
    { title: 'Document Type', dataIndex: 'documentType', key: 'documentType' },
    { title: 'Supplier ID', dataIndex: 'supplierId', key: 'supplierId', render: (value: number | null) => value ?? '—' },
    { title: 'Version', dataIndex: 'version', key: 'version' },
    { title: 'Status', dataIndex: 'status', key: 'status', render: (value: DocumentStatus) => <Tag color={statusColor[value]}>{value}</Tag> },
    { title: 'Uploaded By', dataIndex: 'uploadedBy', key: 'uploadedBy' },
    { title: 'Uploaded At', dataIndex: 'uploadedAt', key: 'uploadedAt', render: formatInstant },
    { title: 'Updated At', dataIndex: 'updatedAt', key: 'updatedAt', render: formatInstant },
  ];

  return <>
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
        <div><Title level={2} style={{ marginBottom: 0 }}>Document Management</Title><Text type="secondary">Upload 與索引是兩個獨立步驟；狀態以 Backend 為準。</Text></div>
        {canUpload && <Button type="primary" icon={<UploadOutlined />} onClick={() => { setUploadError(null); setUploadOpen(true); }}>Upload Document</Button>}
      </Space>
      {uploaded && <Alert type="success" showIcon closable onClose={() => setUploaded(null)}
        title={`Document #${uploaded.id} 上傳成功`}
        description={`Backend 狀態：${uploaded.status}；Version：${uploaded.version}。文件尚未因上傳成功而自動成為 READY。`} />}
      <Space wrap>
        <Select allowClear placeholder="Document Type" aria-label="Document type filter" style={{ width: 220 }} value={query.documentType}
          options={documentTypes.map((value) => ({ value, label: value }))} onChange={(value) => updateQuery({ documentType: value })} />
        <InputNumber min={1} placeholder="Supplier ID" aria-label="Supplier ID filter" value={query.supplierId}
          onChange={(value) => updateQuery({ supplierId: value ?? undefined })} />
        <Select allowClear placeholder="Status" aria-label="Document status filter" style={{ width: 160 }} value={query.status}
          options={statuses.map((value) => ({ value, label: value }))} onChange={(value) => updateQuery({ status: value })} />
        <Button onClick={() => void loadList()}>Retry / Refresh</Button>
      </Space>
      {error ? <ErrorState title="文件列表載入失敗" error={error} onRetry={() => void loadList()} />
        : <Table rowKey="id" loading={loading} columns={columns} dataSource={page?.content ?? []}
          locale={{ emptyText: <EmptyState simple description="沒有符合條件的文件" /> }} scroll={{ x: 1200 }}
          pagination={{ current: (page?.page ?? query.page ?? 0) + 1, pageSize: page?.size ?? query.size, total: page?.totalElements ?? 0, showSizeChanger: true }}
          onChange={(pagination: TablePaginationConfig) => updateQuery({ page: Math.max(0, (pagination.current ?? 1) - 1), size: pagination.pageSize ?? 20 })} />}
    </Space>

    <Modal title="Upload Document" open={uploadOpen} confirmLoading={uploading} okText="Upload" onOk={() => void submitUpload()}
      onCancel={() => { if (!uploading) { setUploadOpen(false); setUploadError(null); } }} closable={!uploading} mask={{ closable: !uploading }}>
      {uploadError && <ErrorState title="文件上傳失敗" error={uploadError} />}
      <Form form={form} layout="vertical">
        <Form.Item name="documentType" label="Document Type" rules={[{ required: true }]}><Select aria-label="Upload document type" options={documentTypes.map((value) => ({ value, label: value }))} /></Form.Item>
        <Form.Item name="supplierId" label="Supplier ID (optional)"><InputNumber min={1} precision={0} aria-label="Upload supplier ID" style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="title" label="Title" rules={[{ required: true, whitespace: true }, { max: 255 }]}><Input aria-label="Upload title" /></Form.Item>
        <Form.Item name="version" label="Version" rules={[{ required: true, whitespace: true }, { max: 50 }]}><Input aria-label="Upload version" /></Form.Item>
        <Form.Item label="File" required help="PDF、TXT、DOCX；最大 20 MiB。副檔名與瀏覽器回報的內容類型皆須相符。">
          <input aria-label="Upload file" type="file" accept=".pdf,.txt,.docx,application/pdf,text/plain,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            onChange={(event) => { setUploadFile(event.target.files?.[0] ?? null); setUploadError(null); }} />
        </Form.Item>
      </Form>
    </Modal>

    <Drawer title="Document Detail" open={!!selected || detailLoading || !!detailError} onClose={closeDetail} width={520}>
      {detailError && <ErrorState title="文件明細載入失敗" error={detailError} onRetry={() => { const id = Number(searchParams.get('documentId')); if (id) void openDetail(id); }} />}
      {detailLoading && <Text>Loading document…</Text>}
      {selected && <Space orientation="vertical" size="large" style={{ width: '100%' }}>
        {actionError && <ErrorState title="文件操作失敗" error={actionError} onRetry={() => void refreshDetail()} retryText="重新取得狀態" />}
        <Descriptions bordered size="small" column={1} items={[
          { key: 'id', label: 'Document ID', children: selected.id },
          { key: 'type', label: 'Document Type', children: selected.documentType },
          { key: 'supplier', label: 'Supplier ID', children: selected.supplierId ?? '—' },
          { key: 'version', label: 'Version', children: selected.version },
          { key: 'status', label: 'Status', children: <Tag color={statusColor[selected.status]}>{selected.status}</Tag> },
          { key: 'by', label: 'Uploaded By', children: selected.uploadedBy },
          { key: 'uploaded', label: 'Uploaded At', children: formatInstant(selected.uploadedAt) },
          { key: 'updated', label: 'Updated At', children: formatInstant(selected.updatedAt) },
        ]} />
        {canUpdate && <Space orientation="vertical" style={{ width: '100%' }}>
          <Text strong>Title</Text><Input aria-label="Document title" value={editTitle} maxLength={255} onChange={(event) => setEditTitle(event.target.value)} />
          <Space wrap><Button loading={acting} disabled={!editTitle.trim()} onClick={() => void saveTitle()}>Save Title</Button>
            {selected.status !== 'INACTIVE' && <Button danger loading={acting} onClick={() => void archive()}>Archive as INACTIVE</Button>}</Space>
        </Space>}
        {canIngest && (selected.status === 'PROCESSING' || selected.status === 'READY') && <Button type="primary" loading={acting} onClick={() => setIngestConfirm(true)}>Start Explicit Ingestion</Button>}
        {(selected.status === 'FAILED' || selected.status === 'INACTIVE') && <Alert type="info" showIcon title={`Backend 狀態 ${selected.status} 不允許從此介面直接重新索引。`} />}
      </Space>}
    </Drawer>

    <Modal title="Confirm Document Ingestion" open={ingestConfirm} okText="Confirm Ingestion" confirmLoading={acting}
      onOk={() => void ingest()} onCancel={() => { if (!acting) setIngestConfirm(false); }} closable={!acting} mask={{ closable: !acting }}>
      <Alert type="warning" showIcon title="將呼叫正式索引流程" description="執行中不會重複送出；逾時或網路中斷時不會自動重送。完成後會重新取得 Backend 文件狀態。" />
    </Modal>
  </>;
}
