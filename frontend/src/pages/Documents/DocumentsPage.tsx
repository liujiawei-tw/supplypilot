import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import {
  Alert, Button, Collapse, Descriptions, Drawer, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography,
  type TablePaginationConfig,
} from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import {
  getDocument, ingestDocument, listDocuments, updateDocument, uploadDocument,
  type DocumentQuery, type DocumentResponse, type DocumentStatus, type DocumentType,
} from '../../api/document';
import { isUnknownMutationResult, requiresMutationVerification, toApiError } from '../../api/apiError';
import { useAuth } from '../../auth/useAuth';
import { can } from '../../auth/permissions';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import type { ApiErrorResponse, PageResponse } from '../../types/api';
import { validateDocumentFile } from './documentValidation';
import SemanticOperationsPanel from './SemanticOperationsPanel';
import { approveOutbound, excludeOutbound, type OutboundReviewResponse } from '../../api/semanticIndex';
import AiQueryPanel from '../../components/AiAssistant/AiQueryPanel';
import { displayLabel } from '../../utils/displayLabels';
import { listSuppliers, type SupplierResponse } from '../../api/supplier';

const { Title, Text } = Typography;
const REVIEW_UNKNOWN_KEY = 'supplypilot.semanticUnknownReviews';

function storedUnknownReviews(): string[] {
  try {
    const value: unknown = JSON.parse(sessionStorage.getItem(REVIEW_UNKNOWN_KEY) ?? '[]');
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
  } catch { return []; }
}

const documentTypes: DocumentType[] = ['SUPPLIER_CONTRACT', 'QUOTATION', 'PROCUREMENT_SOP', 'PRODUCT_SPECIFICATION', 'QUALITY_REQUIREMENT', 'SUPPLIER_PROFILE', 'SUPPLIER_COOPERATION_RECORD', 'SUPPLIER_EVALUATION'];
const statuses: DocumentStatus[] = ['PROCESSING', 'READY', 'FAILED', 'INACTIVE'];
const statusColor: Record<DocumentStatus, string> = { PROCESSING: 'processing', READY: 'success', FAILED: 'error', INACTIVE: 'default' };

interface UploadValues { documentType: DocumentType; supplierId?: number; title: string; version: string; }

function formatInstant(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-TW', { timeZone: 'Asia/Taipei' });
}

function isUnknownResult(error: unknown): boolean {
  return isUnknownMutationResult(error);
}

export default function DocumentsPage() {
  const { user } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage] = useState<PageResponse<DocumentResponse> | null>(null);
  const [supplierOptions, setSupplierOptions] = useState<SupplierResponse[]>([]);
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
  const [uploadUnknown, setUploadUnknown] = useState(false);
  const [uploaded, setUploaded] = useState<DocumentResponse | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [actionError, setActionError] = useState<ApiErrorResponse | null>(null);
  const [ingestConfirm, setIngestConfirm] = useState(false);
  const [ingestionUnknownDocumentId, setIngestionUnknownDocumentId] = useState<number | null>(null);
  const [acting, setActing] = useState(false);
  const [reviewLoading, setReviewLoading] = useState(false);
  const [reviewConfirm, setReviewConfirm] = useState<'approve' | 'exclude' | null>(null);
  const [reviewResult, setReviewResult] = useState<OutboundReviewResponse | null>(null);
  const [reviewUnknownKeys, setReviewUnknownKeys] = useState<string[]>(storedUnknownReviews);
  const [reviewUnlockKey, setReviewUnlockKey] = useState<string | null>(null);
  const [semanticMutationVersion, setSemanticMutationVersion] = useState(0);
  const [form] = Form.useForm<UploadValues>();
  const uploadRef = useRef(false);
  const ingestingRef = useRef(false);
  const reviewRef = useRef(false);
  const selectedDocumentRef = useRef(selected);
  useLayoutEffect(() => { selectedDocumentRef.current = selected; }, [selected]);
  const detailRequestSequence = useRef(0);
  const detailEffectTimer = useRef<number | null>(null);
  const activeDocumentIdRef = useRef<number | null>(null);
  const ingestionUnknownRef = useRef<number | null>(null);
  const canUpload = !!user && can(user.role, 'documentUpload');
  const canUpdate = !!user && can(user.role, 'documentUpdate');
  const canIngest = !!user && can(user.role, 'documentIngest');
  const canManageSemantic = !!user && can(user.role, 'semanticManagement');
  const requestedDocumentIdText = searchParams.get('documentId');
  const requestedDocumentIdValue = Number(requestedDocumentIdText);
  const requestedDocumentId = requestedDocumentIdText && /^\d+$/.test(requestedDocumentIdText)
    && Number.isSafeInteger(requestedDocumentIdValue) && requestedDocumentIdValue > 0 ? requestedDocumentIdValue : null;
  const selectedMatchesUrl = !!selected && requestedDocumentId === selected.id;
  const selectedIngestionUnknown = !!selected && ingestionUnknownDocumentId === selected.id;
  useLayoutEffect(() => { activeDocumentIdRef.current = requestedDocumentId; }, [requestedDocumentId]);

  const isCurrentDocumentRequest = (id: number, requestSequence: number) =>
    detailRequestSequence.current === requestSequence
    && activeDocumentIdRef.current === id
    && selectedDocumentRef.current?.id === id;

  const loadList = useCallback(async (next = query) => {
    setLoading(true); setError(null);
    try { setPage(await listDocuments(next)); }
    catch (requestError) { setError(toApiError(requestError)); }
    finally { setLoading(false); }
  }, [query]);

  useEffect(() => {
    let active = true;
    void listSuppliers({ page: 0, size: 100, sort: 'code,asc' })
      .then((result) => { if (active) setSupplierOptions(result.content ?? []); })
      .catch(() => { if (active) setSupplierOptions([]); });
    return () => { active = false; };
  }, []);

  useEffect(() => { const timer = window.setTimeout(() => void loadList(), 0); return () => window.clearTimeout(timer); }, [loadList]);

  const loadDetail = useCallback(async (id: number) => {
    const requestSequence = ++detailRequestSequence.current;
    setDetailLoading(true); setDetailError(null); setActionError(null);
    try {
      const document = await getDocument(id);
      if (requestSequence !== detailRequestSequence.current) return;
      setSelected(document); setEditTitle(document.title);
    } catch (requestError) {
      if (requestSequence === detailRequestSequence.current) { setDetailError(toApiError(requestError)); setSelected(null); }
    } finally {
      if (requestSequence === detailRequestSequence.current) setDetailLoading(false);
    }
  }, []);

  const openDetail = useCallback((id: number) => {
    // Invalidate an in-flight GET immediately; URL changes are committed on the next render.
    if (detailEffectTimer.current !== null) window.clearTimeout(detailEffectTimer.current);
    detailEffectTimer.current = null;
    detailRequestSequence.current += 1;
    activeDocumentIdRef.current = id;
    setSelected(null); setReviewResult(null); setReviewConfirm(null); setIngestConfirm(false); setActing(false);
    setDetailLoading(true); setDetailError(null); setActionError(null);
    if (searchParams.get('documentId') === String(id)) {
      void loadDetail(id);
      return;
    }
    setSearchParams((current) => { const next = new URLSearchParams(current); next.set('documentId', String(id)); return next; });
  }, [loadDetail, searchParams, setSearchParams]);

  useEffect(() => {
    const raw = searchParams.get('documentId');
    const id = Number(raw);
    if (raw && /^\d+$/.test(raw) && Number.isSafeInteger(id) && id > 0) {
      const timer = window.setTimeout(() => { detailEffectTimer.current = null; void loadDetail(id); }, 0);
      detailEffectTimer.current = timer;
      return () => {
        window.clearTimeout(timer);
        if (detailEffectTimer.current === timer) detailEffectTimer.current = null;
        detailRequestSequence.current += 1;
      };
    }
    detailRequestSequence.current += 1;
  }, [loadDetail, searchParams]);

  const closeDetail = () => {
    detailRequestSequence.current += 1;
    activeDocumentIdRef.current = null;
    setSelected(null); setDetailLoading(false); setDetailError(null); setActionError(null); setIngestConfirm(false); setReviewConfirm(null);
    setSearchParams((current) => { const next = new URLSearchParams(current); next.delete('documentId'); return next; });
  };

  const invalidateSemanticReports = () => setSemanticMutationVersion((version) => version + 1);

  const submitUpload = async () => {
    if (uploadRef.current || uploadUnknown) return;
    uploadRef.current = true;
    let values: UploadValues;
    try { values = await form.validateFields(); } catch { uploadRef.current = false; return; }
    const fileMessage = validateDocumentFile(uploadFile);
    if (fileMessage) {
      uploadRef.current = false;
      setUploadError({ code: 'INVALID_FILE_FORMAT', message: fileMessage, traceId: '', timestamp: '', path: '' });
      return;
    }
    setUploading(true); setUploadError(null);
    try {
      invalidateSemanticReports();
      const result = await uploadDocument({ ...values, title: values.title.trim(), version: values.version.trim(), file: uploadFile! });
      setUploaded(result); setUploadOpen(false); form.resetFields(); setUploadFile(null);
      await loadList({ ...query, page: 0 });
    } catch (requestError) {
      const apiError = toApiError(requestError);
      if (isUnknownResult(requestError)) {
        setUploadUnknown(true);
        setUploadError({ ...apiError, message: '上傳結果不明；系統不會重送。請人工查核文件清單，不得僅以相同標題與版本判定成功或失敗。' });
      } else {
        setUploadError(apiError);
      }
    } finally { uploadRef.current = false; setUploading(false); }
  };

  const refreshDetail = async () => { if (selected) await openDetail(selected.id); };

  const confirmOutboundReview = async () => {
    if (!selected || !selectedMatchesUrl || !reviewConfirm || reviewRef.current) return;
    const operation = reviewConfirm;
    const documentId = selected.id;
    const documentVersion = selected.version;
    const reviewKey = `${selected.id}:${selected.version}`;
    if (reviewUnknownKeys.includes(reviewKey)) return;
    reviewRef.current = true; setReviewLoading(true); setActionError(null); setReviewConfirm(null); setReviewResult(null);
    try {
      invalidateSemanticReports();
      const result = await (operation === 'approve' ? approveOutbound(documentId) : excludeOutbound(documentId));
      if (selectedDocumentRef.current?.id !== documentId || selectedDocumentRef.current.version !== documentVersion) return;
      setReviewResult(result);
    } catch (requestError) {
      const apiError = toApiError(requestError);
      if (requiresMutationVerification(requestError) || isUnknownResult(requestError)) {
        setReviewUnknownKeys((keys) => {
          const next = keys.includes(reviewKey) ? keys : [...keys, reviewKey];
          sessionStorage.setItem(REVIEW_UNKNOWN_KEY, JSON.stringify(next));
          return next;
        });
        if (selectedDocumentRef.current?.id === documentId && selectedDocumentRef.current.version === documentVersion) setActionError({ ...apiError, message: `${operation === 'approve' ? '核准' : '排除'}結果未知，且目前沒有可查詢持久化審核狀態的端點。請依系統稽核記錄人工查核；文件已就緒或就緒檢核通過都不代表審核成功，也不要重送。` });
      } else if (selectedDocumentRef.current?.id === documentId && selectedDocumentRef.current.version === documentVersion) setActionError(apiError);
    } finally { reviewRef.current = false; setReviewLoading(false); }
  };

  const verifyIngestionStatus = async () => {
    const id = ingestionUnknownRef.current;
    if (!id || !isCurrentDocumentRequest(id, detailRequestSequence.current)) return;
    const requestSequence = detailRequestSequence.current;
    setActing(true); setActionError(null);
    try {
      const document = await getDocument(id);
      if (!isCurrentDocumentRequest(id, requestSequence)) return;
      setSelected(document); setEditTitle(document.title);
      ingestionUnknownRef.current = null; setIngestionUnknownDocumentId(null);
      setActionError({ code: 'DOCUMENT_STATUS_VERIFIED', message: `已查核系統目前狀態：${displayLabel(document.status)}。此查核不代表先前索引成功；可用操作依目前狀態與領域規則決定。`, traceId: '', timestamp: '', path: '' });
    } catch (requestError) {
      if (!isCurrentDocumentRequest(id, requestSequence)) return;
      const apiError = toApiError(requestError);
      setActionError({ ...apiError, message: `文件狀態查核失敗；索引操作維持鎖定。${apiError.message}` });
    } finally { if (isCurrentDocumentRequest(id, requestSequence)) setActing(false); }
  };

  const saveTitle = async () => {
    if (!selected || !editTitle.trim()) return;
    const id = selected.id;
    const requestSequence = detailRequestSequence.current;
    setActing(true); setActionError(null);
    try { invalidateSemanticReports(); const next = await updateDocument(id, { title: editTitle.trim() }); if (isCurrentDocumentRequest(id, requestSequence)) { setSelected(next); setEditTitle(next.title); } await loadList(); }
    catch (requestError) { if (isCurrentDocumentRequest(id, requestSequence)) setActionError(toApiError(requestError)); }
    finally { if (isCurrentDocumentRequest(id, requestSequence)) setActing(false); }
  };

  const archive = async () => {
    if (!selected) return;
    const id = selected.id;
    const requestSequence = detailRequestSequence.current;
    setActing(true); setActionError(null);
    try { invalidateSemanticReports(); const next = await updateDocument(id, { status: 'INACTIVE' }); if (isCurrentDocumentRequest(id, requestSequence)) setSelected(next); await loadList(); }
    catch (requestError) { if (isCurrentDocumentRequest(id, requestSequence)) setActionError(toApiError(requestError)); }
    finally { if (isCurrentDocumentRequest(id, requestSequence)) setActing(false); }
  };

  const loadDetailForRetry = () => {
    const id = Number(searchParams.get('documentId'));
    if (id) void loadDetail(id);
  };

  const ingest = async () => {
    if (!selected || ingestingRef.current || ingestionUnknownRef.current !== null) return;
    const id = selected.id;
    const requestSequence = detailRequestSequence.current;
    ingestingRef.current = true; setActing(true); setActionError(null); setIngestConfirm(false);
    try { invalidateSemanticReports(); const next = await ingestDocument(id); if (isCurrentDocumentRequest(id, requestSequence)) setSelected(next); await loadList(); }
    catch (requestError) {
      const apiError = toApiError(requestError);
      const requiresVerification = requiresMutationVerification(requestError);
      if (requiresVerification) {
        ingestionUnknownRef.current = id;
        setIngestionUnknownDocumentId(id);
        if (isCurrentDocumentRequest(id, requestSequence)) setActionError({ ...apiError, message: '索引結果尚未確認；系統不會重送。請使用正式文件狀態查核，再依系統目前狀態決定後續操作。' });
      } else {
        if (isCurrentDocumentRequest(id, requestSequence)) setActionError(apiError);
      }
    } finally { ingestingRef.current = false; if (isCurrentDocumentRequest(id, requestSequence)) setActing(false); }
  };

  const updateQuery = (patch: Partial<DocumentQuery>) => {
    const next = { ...query, ...patch, page: patch.page ?? 0 };
    setQuery(next);
  };

  const columns = [
    { title: '文件 ID', dataIndex: 'id', key: 'id', render: (id: number) => <Button type="link" onClick={() => void openDetail(id)}>#{id}</Button> },
    { title: '標題', dataIndex: 'title', key: 'title', render: (_: string, record: DocumentResponse) => <Space wrap>
      <span>{record.title}</span>{record.demoProvenance && <Tag color="blue">展示基線</Tag>}
    </Space> },
    { title: '文件類型', dataIndex: 'documentType', key: 'documentType', render: (value: DocumentType) => displayLabel(value) },
    { title: '供應商', dataIndex: 'supplierCode', key: 'supplier', render: (_: string | null, record: DocumentResponse) =>
      record.supplierCode ? `${record.supplierCode} — ${record.supplierName ?? '未命名'}` : record.supplierId ?? '—' },
    { title: '版本', dataIndex: 'version', key: 'version' },
    { title: '狀態', dataIndex: 'status', key: 'status', render: (value: DocumentStatus) => <Tag color={statusColor[value]}>{displayLabel(value)}</Tag> },
    { title: '語意索引發布', dataIndex: 'semanticIndex', key: 'semanticIndex', render: (_: unknown, record: DocumentResponse) => {
      const state = record.semanticIndex?.status ?? (record.status === 'READY' ? 'PENDING' : 'NOT_READY');
      const color = state === 'INDEXED' ? 'success' : state === 'UNAVAILABLE' ? 'warning' : state === 'FAILED' ? 'error' : 'default';
      return <Tag color={color}>{state === 'INDEXED' ? '已發布' : displayLabel(state)}</Tag>;
    } },
    { title: '上傳者', dataIndex: 'uploadedBy', key: 'uploadedBy' },
    { title: '上傳時間', dataIndex: 'uploadedAt', key: 'uploadedAt', render: formatInstant },
    { title: '更新時間', dataIndex: 'updatedAt', key: 'updatedAt', render: formatInstant },
  ];

  return <>
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
        <div><Title level={2} style={{ marginBottom: 0 }}>文件管理</Title><Text type="secondary">上傳與文件解析／索引是不同步驟；狀態以系統回報為準。</Text></div>
        {canUpload && <Button type="primary" icon={<UploadOutlined />} disabled={uploadUnknown} onClick={() => { setUploadError(null); setUploadOpen(true); }}>上傳文件</Button>}
      </Space>
      {uploaded && <Alert type="success" showIcon closable onClose={() => setUploaded(null)}
        title={`文件 #${uploaded.id} 上傳成功`}
        description={`系統狀態：${displayLabel(uploaded.status)}；版本：${uploaded.version}。上傳完成不代表文件已就緒。`} />}
      {canManageSemantic && <SemanticOperationsPanel documentMutationVersion={semanticMutationVersion} />}
      {uploadUnknown && <Alert type="warning" showIcon title="文件上傳結果不明，已鎖定重送"
        description="請人工查核文件清單；相同標題與版本僅供輔助，不能單憑它推定上傳成功或失敗。" />}
      <Space wrap>
        <Select allowClear placeholder="文件類型" aria-label="文件類型篩選" style={{ width: 220 }} value={query.documentType}
          options={documentTypes.map((value) => ({ value, label: displayLabel(value) }))} onChange={(value) => updateQuery({ documentType: value })} />
        <Select allowClear showSearch optionFilterProp="label" placeholder="供應商" aria-label="供應商篩選" style={{ width: 280 }}
          value={query.supplierId} options={supplierOptions.map((supplier) => ({
            value: supplier.id, label: `${supplier.code} — ${supplier.name}`,
          }))} onChange={(value) => updateQuery({ supplierId: value })} />
        <Select allowClear placeholder="狀態" aria-label="文件狀態篩選" style={{ width: 160 }} value={query.status}
          options={statuses.map((value) => ({ value, label: displayLabel(value) }))} onChange={(value) => updateQuery({ status: value })} />
        <Button onClick={() => void loadList()}>重新載入</Button>
      </Space>
      {error ? <ErrorState title="文件列表載入失敗" error={error} onRetry={() => void loadList()} />
        : <Table rowKey="id" loading={loading} columns={columns} dataSource={page?.content ?? []}
          locale={{ emptyText: <EmptyState simple description="沒有符合條件的文件" /> }} scroll={{ x: 1600 }}
          pagination={{ current: (page?.page ?? query.page ?? 0) + 1, pageSize: page?.size ?? query.size, total: page?.totalElements ?? 0, showSizeChanger: true }}
          onChange={(pagination: TablePaginationConfig) => updateQuery({ page: Math.max(0, (pagination.current ?? 1) - 1), size: pagination.pageSize ?? 20 })} />}
    </Space>

    <Modal title="上傳文件" open={uploadOpen} confirmLoading={uploading} okText="上傳" okButtonProps={{ disabled: uploadUnknown }} onOk={() => void submitUpload()}
      onCancel={() => { if (!uploading) { setUploadOpen(false); setUploadError(null); } }} closable={!uploading} mask={{ closable: !uploading }}>
      {uploadError && <ErrorState title="文件上傳失敗" error={uploadError} />}
      <Form form={form} layout="vertical">
        <Form.Item name="documentType" label="文件類型" rules={[{ required: true, message: '請選擇文件類型' }]}><Select aria-label="上傳文件類型" options={documentTypes.map((value) => ({ value, label: displayLabel(value) }))} /></Form.Item>
        <Form.Item name="supplierId" label="供應商 ID（選填）"><InputNumber min={1} precision={0} aria-label="上傳文件供應商 ID" style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="title" label="文件標題" rules={[{ required: true, whitespace: true, message: '請輸入文件標題' }, { max: 255 }]}><Input aria-label="文件標題" /></Form.Item>
        <Form.Item name="version" label="版本" rules={[{ required: true, whitespace: true, message: '請輸入版本' }, { max: 50 }]}><Input aria-label="文件版本" /></Form.Item>
        <Form.Item label="檔案" required help="支援 PDF、TXT、DOCX，最大 20 MiB。副檔名與瀏覽器回報的內容類型都必須相符。">
          <input aria-label="上傳檔案" type="file" accept=".pdf,.txt,.docx,application/pdf,text/plain,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            onChange={(event) => { setUploadFile(event.target.files?.[0] ?? null); setUploadError(null); }} />
        </Form.Item>
      </Form>
    </Modal>

    <Drawer title="文件明細" open={requestedDocumentId !== null} onClose={closeDetail} width={520}>
      {detailError && requestedDocumentId !== null && <ErrorState title="文件明細載入失敗" error={detailError} onRetry={loadDetailForRetry} />}
      {(detailLoading || (requestedDocumentId !== null && !selectedMatchesUrl && !detailError)) && <Text>正在載入文件…</Text>}
      {selected && selectedMatchesUrl && <Space orientation="vertical" size="large" style={{ width: '100%' }}>
        <Button loading={detailLoading} disabled={detailLoading} onClick={() => void refreshDetail()}>重新取得文件資訊</Button>
        {actionError && <ErrorState title={selectedIngestionUnknown ? '索引結果待查核' : reviewUnknownKeys.includes(`${selected.id}:${selected.version}`) ? '出站審核結果待人工查核' : '文件操作訊息'} error={actionError}
          onRetry={selectedIngestionUnknown ? () => void verifyIngestionStatus() : reviewUnknownKeys.includes(`${selected.id}:${selected.version}`) ? undefined : () => void refreshDetail()}
          retryText={selectedIngestionUnknown ? '查核真實文件狀態' : '重新取得文件資訊'} />}
        <Descriptions bordered size="small" column={1} items={[
          { key: 'id', label: '文件 ID', children: selected.id },
          { key: 'title', label: '標題', children: <Typography.Text style={{ overflowWrap: 'anywhere' }}>{selected.title}</Typography.Text> },
          { key: 'type', label: '文件類型', children: displayLabel(selected.documentType) },
          { key: 'supplier', label: '供應商', children: selected.supplierCode
            ? `${selected.supplierCode} — ${selected.supplierName ?? '未命名'}（ID ${selected.supplierId}）`
            : selected.supplierId ?? '—' },
          { key: 'version', label: '版本', children: selected.version },
          { key: 'status', label: '狀態', children: <Tag color={statusColor[selected.status]}>{displayLabel(selected.status)}</Tag> },
          { key: 'semantic-index', label: '語意索引發布', children: <Tag color={selected.semanticIndex?.status === 'INDEXED' ? 'success' : selected.semanticIndex?.status === 'UNAVAILABLE' ? 'warning' : 'default'}>
            {selected.semanticIndex?.status === 'INDEXED' ? '已發布' : displayLabel(selected.semanticIndex?.status ?? 'PENDING')}
          </Tag> },
          ...(selected.demoProvenance ? [{ key: 'baseline', label: '展示基線來源', children: `${selected.demoProvenance.datasetKey} · ${selected.demoProvenance.documentKey} · ${selected.demoProvenance.themeKey} · ${selected.demoProvenance.corpusVersion}` }] : []),
          ...(selected.semanticReview ? [{ key: 'review', label: '語意出站審核', children: <Space wrap>
            <Tag color={selected.semanticReview.status === 'APPROVED' && selected.semanticReview.valid ? 'success' : 'warning'}>
              {displayLabel(selected.semanticReview.status)}{selected.semanticReview.valid ? '' : '（已失效）'}
            </Tag><Text type="secondary">{selected.semanticReview.actorType === 'SYSTEM' ? '系統' : '使用者'} · {selected.semanticReview.reason ?? '—'}</Text>
          </Space> }] : []),
          { key: 'by', label: '上傳者', children: selected.uploadedBy ?? '系統基線' },
          { key: 'uploaded', label: '上傳時間', children: formatInstant(selected.uploadedAt) },
          { key: 'updated', label: '更新時間', children: formatInstant(selected.updatedAt) },
        ]} />
        {canManageSemantic && <>
          <Alert type="info" showIcon title="文件已就緒與語意索引發布分開顯示" description="文件狀態、持久化出站審核與語意索引發布狀態均由文件 API 分開回報；只有語意索引狀態為「已發布」代表持久索引已確認。來源、標題或版本變更後，舊審核會失效。" />
          {reviewResult?.documentId === selected.id && reviewResult.version === selected.version && <Alert showIcon type={reviewResult.status === 'APPROVED' ? 'success' : reviewResult.status === 'REQUIRES_REVIEW' ? 'warning' : 'info'} title={`本次審核回應：${displayLabel(reviewResult.status)}`} description={<Descriptions size="small" column={1} items={[
            { key: 'version', label: '版本', children: reviewResult.version },
            { key: 'hash', label: '來源雜湊', children: <Typography.Text code copyable>{reviewResult.sourceHash}</Typography.Text> },
            { key: 'reviewer', label: '核准者', children: reviewResult.approvedBy },
            { key: 'reason', label: '原因', children: reviewResult.reason || '—' },
          ]} />} />}
          {reviewUnknownKeys.includes(`${selected.id}:${selected.version}`) && <Alert type="warning" showIcon title="出站審核結果不明；已鎖定此版本重送" description="目前沒有可重新查詢審核狀態的端點。請人工查核系統紀錄；文件狀態或就緒檢核不能證明核准／排除請求成功。" action={<Button size="small" onClick={() => setReviewUnlockKey(`${selected.id}:${selected.version}`)}>我已完成人工查核</Button>} />}
          <Space wrap>
            <Button type="primary" disabled={!selectedMatchesUrl || selected.status !== 'READY' || reviewUnknownKeys.includes(`${selected.id}:${selected.version}`)} loading={reviewLoading} onClick={() => setReviewConfirm('approve')}>核准出站</Button>
            <Button danger disabled={!selectedMatchesUrl || selected.status !== 'READY' || reviewUnknownKeys.includes(`${selected.id}:${selected.version}`)} loading={reviewLoading} onClick={() => setReviewConfirm('exclude')}>排除出站</Button>
          </Space>
          {selected.status !== 'READY' && <Text type="secondary">文件目前為「{displayLabel(selected.status)}」；須先由系統處理至已就緒，才能審核出站。</Text>}
          <Collapse items={[{ key: 'ai-query', label: '下一步：使用 AI 查詢核對來源', children: <><Alert type="info" showIcon title="查詢結果由系統決定" description="索引檢核通過不保證一定查到此文件。請核對回傳的文件 ID、版本、文字分塊與字元位置；證據不足時系統會拒答。" /><div style={{ marginTop: 16 }}><AiQueryPanel /></div></> }]} />
        </>}
        {canUpdate && <Space orientation="vertical" style={{ width: '100%' }}>
          <Text strong>標題</Text><Input aria-label="文件標題" value={editTitle} maxLength={255} onChange={(event) => setEditTitle(event.target.value)} />
          <Space wrap><Button loading={acting} disabled={!editTitle.trim()} onClick={() => void saveTitle()}>儲存標題</Button>
            {selected.status !== 'INACTIVE' && <Button danger loading={acting} onClick={() => void archive()}>封存文件</Button>}</Space>
        </Space>}
        {selectedIngestionUnknown && <Alert type="warning" showIcon title="索引結果不明，已鎖定重送"
          description="必須先重新查詢文件明細（GET /documents/{id}），確認系統目前狀態；查詢失敗時會保留鎖定。"
          action={<Button size="small" loading={acting} onClick={() => void verifyIngestionStatus()}>查核真實文件狀態</Button>} />}
        {canIngest && !selectedIngestionUnknown && (selected.status === 'PROCESSING' || selected.status === 'READY') && <Button type="primary" loading={acting} onClick={() => setIngestConfirm(true)}>開始文件解析與索引</Button>}
        {(selected.status === 'FAILED' || selected.status === 'INACTIVE') && <Alert type="info" showIcon title={`系統狀態「${displayLabel(selected.status)}」不允許從此介面直接重新解析。`} />}
      </Space>}
    </Drawer>

    <Modal title="確認解析與索引文件" open={ingestConfirm} okText="確認開始" confirmLoading={acting}
      onOk={() => void ingest()} onCancel={() => { if (!acting) setIngestConfirm(false); }} closable={!acting} mask={{ closable: !acting }}>
      <Alert type="warning" showIcon title="即將開始文件解析與索引" description="執行中不會重複送出；逾時或網路中斷時不會自動重送。完成後會重新取得系統文件狀態。" />
    </Modal>

    <Modal title={reviewConfirm === 'approve' ? '確認核准文件出站' : '確認排除此文件出站'} open={reviewConfirm !== null} okText={reviewConfirm === 'approve' ? '確認核准出站' : '確認排除出站'} confirmLoading={reviewLoading}
      okButtonProps={{ danger: reviewConfirm === 'exclude' }} onOk={() => void confirmOutboundReview()} onCancel={() => { if (!reviewLoading) setReviewConfirm(null); }} closable={!reviewLoading} mask={{ closable: !reviewLoading }}>
      {reviewConfirm === 'approve'
        ? <Alert type="warning" showIcon title="文件內容可能送至外部向量嵌入服務" description="系統會依出站政策檢查內容；若需要人工審查，實際回應可能是 REQUIRES_REVIEW，不會被前端改成已核准。只對你有權授權外送的文件繼續。" />
        : <Alert type="info" showIcon title="排除後批次索引不會納入此文件" description="系統會記錄排除決策；請確認你要將此文件排除於語意索引。" />}
    </Modal>
    <Modal title="解除 出站審核未知結果鎖定" open={reviewUnlockKey !== null} okText="確認已人工查核" onOk={() => {
      if (reviewUnlockKey) setReviewUnknownKeys((keys) => {
        const next = keys.filter((key) => key !== reviewUnlockKey);
        sessionStorage.setItem(REVIEW_UNKNOWN_KEY, JSON.stringify(next));
        return next;
      });
      setReviewUnlockKey(null); setActionError(null);
    }} onCancel={() => setReviewUnlockKey(null)}>
      <Alert type="warning" showIcon title="請先查核系統審核紀錄" description="文件查詢與就緒檢核都不能證明原核准／排除操作結果。只在人工查核後，才解除此文件版本的重送鎖定。" />
    </Modal>
  </>;
}
