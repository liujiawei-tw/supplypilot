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

const { Title, Text } = Typography;
const REVIEW_UNKNOWN_KEY = 'supplypilot.semanticUnknownReviews';

function storedUnknownReviews(): string[] {
  try {
    const value: unknown = JSON.parse(sessionStorage.getItem(REVIEW_UNKNOWN_KEY) ?? '[]');
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
  } catch { return []; }
}

const documentTypes: DocumentType[] = ['SUPPLIER_CONTRACT', 'QUOTATION', 'PROCUREMENT_SOP', 'PRODUCT_SPECIFICATION', 'QUALITY_REQUIREMENT'];
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
        setUploadError({ ...apiError, message: '上傳結果不明；系統不會重送。請人工查核 Document List，但不得僅以相同 Title／Version 判定成功或失敗。' });
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
        if (selectedDocumentRef.current?.id === documentId && selectedDocumentRef.current.version === documentVersion) setActionError({ ...apiError, message: `${operation === 'approve' ? '核准' : '排除'}結果未知，且目前沒有可查詢持久化審核狀態的 API。請依後端稽核記錄人工查核；不可把 Document READY 或 Readiness 當作審核成功，也不要重送。` });
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
      setActionError({ code: 'DOCUMENT_STATUS_VERIFIED', message: `已查核後端真實狀態：${displayLabel(document.status)}。此查核不代表先前索引成功；可用操作依目前狀態與領域規則決定。`, traceId: '', timestamp: '', path: '' });
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
        if (isCurrentDocumentRequest(id, requestSequence)) setActionError({ ...apiError, message: '索引結果尚未確認；系統不會重送。請使用正式文件狀態查核，再依 後端 真實狀態決定後續操作。' });
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
    { title: '標題', dataIndex: 'title', key: 'title' },
    { title: '文件類型', dataIndex: 'documentType', key: 'documentType', render: (value: DocumentType) => displayLabel(value) },
    { title: '供應商 ID', dataIndex: 'supplierId', key: 'supplierId', render: (value: number | null) => value ?? '—' },
    { title: '版本', dataIndex: 'version', key: 'version' },
    { title: '狀態', dataIndex: 'status', key: 'status', render: (value: DocumentStatus) => <Tag color={statusColor[value]}>{displayLabel(value)}</Tag> },
    { title: '上傳者', dataIndex: 'uploadedBy', key: 'uploadedBy' },
    { title: '上傳時間', dataIndex: 'uploadedAt', key: 'uploadedAt', render: formatInstant },
    { title: '更新時間', dataIndex: 'updatedAt', key: 'updatedAt', render: formatInstant },
  ];

  return <>
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
        <div><Title level={2} style={{ marginBottom: 0 }}>文件管理</Title><Text type="secondary">上傳與文件解析／索引是不同步驟；狀態以 後端 為準。</Text></div>
        {canUpload && <Button type="primary" icon={<UploadOutlined />} disabled={uploadUnknown} onClick={() => { setUploadError(null); setUploadOpen(true); }}>上傳文件</Button>}
      </Space>
      {uploaded && <Alert type="success" showIcon closable onClose={() => setUploaded(null)}
        title={`文件 #${uploaded.id} 上傳成功`}
        description={`後端 狀態：${displayLabel(uploaded.status)}；版本：${uploaded.version}。上傳完成不代表文件已就緒。`} />}
      {canManageSemantic && <SemanticOperationsPanel documentMutationVersion={semanticMutationVersion} />}
      {uploadUnknown && <Alert type="warning" showIcon title="文件上傳結果不明，已鎖定重送"
        description="請人工查核 Document List；相同 Title／Version 僅能作輔助資訊，不能單憑它推定上傳成功或失敗。" />}
      <Space wrap>
        <Select allowClear placeholder="文件類型" aria-label="文件類型篩選" style={{ width: 220 }} value={query.documentType}
          options={documentTypes.map((value) => ({ value, label: displayLabel(value) }))} onChange={(value) => updateQuery({ documentType: value })} />
        <InputNumber min={1} placeholder="供應商 ID" aria-label="供應商 ID 篩選" value={query.supplierId}
          onChange={(value) => updateQuery({ supplierId: value ?? undefined })} />
        <Select allowClear placeholder="狀態" aria-label="文件狀態篩選" style={{ width: 160 }} value={query.status}
          options={statuses.map((value) => ({ value, label: displayLabel(value) }))} onChange={(value) => updateQuery({ status: value })} />
        <Button onClick={() => void loadList()}>重新載入</Button>
      </Space>
      {error ? <ErrorState title="文件列表載入失敗" error={error} onRetry={() => void loadList()} />
        : <Table rowKey="id" loading={loading} columns={columns} dataSource={page?.content ?? []}
          locale={{ emptyText: <EmptyState simple description="沒有符合條件的文件" /> }} scroll={{ x: 1200 }}
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
          { key: 'supplier', label: '供應商 ID', children: selected.supplierId ?? '—' },
          { key: 'version', label: '版本', children: selected.version },
          { key: 'status', label: '狀態', children: <Tag color={statusColor[selected.status]}>{displayLabel(selected.status)}</Tag> },
          { key: 'by', label: '上傳者', children: selected.uploadedBy },
          { key: 'uploaded', label: '上傳時間', children: formatInstant(selected.uploadedAt) },
          { key: 'updated', label: '更新時間', children: formatInstant(selected.updatedAt) },
        ]} />
        {canManageSemantic && <>
          <Alert type="info" showIcon title="文件狀態與 Semantic 狀態分開管理" description="此處顯示 Documents API 的狀態。持久化出站審核狀態未包含在 DocumentResponse；請以每次審核 POST 回應為準，來源或版本變更後須重新審核。" />
          {reviewResult?.documentId === selected.id && reviewResult.version === selected.version && <Alert showIcon type={reviewResult.status === 'APPROVED' ? 'success' : reviewResult.status === 'REQUIRES_REVIEW' ? 'warning' : 'info'} title={`本次審核回應：${displayLabel(reviewResult.status)}`} description={<Descriptions size="small" column={1} items={[
            { key: 'version', label: '版本', children: reviewResult.version },
            { key: 'hash', label: '來源雜湊', children: <Typography.Text code copyable>{reviewResult.sourceHash}</Typography.Text> },
            { key: 'reviewer', label: '審核者（approvedBy）', children: reviewResult.approvedBy },
            { key: 'reason', label: '原因', children: reviewResult.reason || '—' },
          ]} />} />}
          {reviewUnknownKeys.includes(`${selected.id}:${selected.version}`) && <Alert type="warning" showIcon title="出站審核結果不明；已鎖定此版本重送" description="後端 沒有可供前端重新查詢持久化審核結果的端點。請人工查核後端紀錄；文件狀態或 Readiness 不能證明核准／排除請求成功。" action={<Button size="small" onClick={() => setReviewUnlockKey(`${selected.id}:${selected.version}`)}>我已完成人工查核</Button>} />}
          <Space wrap>
            <Button type="primary" disabled={!selectedMatchesUrl || selected.status !== 'READY' || reviewUnknownKeys.includes(`${selected.id}:${selected.version}`)} loading={reviewLoading} onClick={() => setReviewConfirm('approve')}>核准出站</Button>
            <Button danger disabled={!selectedMatchesUrl || selected.status !== 'READY' || reviewUnknownKeys.includes(`${selected.id}:${selected.version}`)} loading={reviewLoading} onClick={() => setReviewConfirm('exclude')}>排除出站</Button>
          </Space>
          {selected.status !== 'READY' && <Text type="secondary">文件目前為「{displayLabel(selected.status)}」；須先由 後端 文件流程處理至已就緒，才能審核出站。</Text>}
          <Collapse items={[{ key: 'ai-query', label: '下一步：使用 AI Query 核對來源', children: <><Alert type="info" showIcon title="查詢結果由 後端 決定" description="Gate 稽核 通過不保證一定查到此文件。請核對回傳的 Document ID、版本、Chunk 與 Offset；證據不足時 後端 會回報證據不足。" /><div style={{ marginTop: 16 }}><AiQueryPanel /></div></> }]} />
        </>}
        {canUpdate && <Space orientation="vertical" style={{ width: '100%' }}>
          <Text strong>標題</Text><Input aria-label="文件標題" value={editTitle} maxLength={255} onChange={(event) => setEditTitle(event.target.value)} />
          <Space wrap><Button loading={acting} disabled={!editTitle.trim()} onClick={() => void saveTitle()}>儲存標題</Button>
            {selected.status !== 'INACTIVE' && <Button danger loading={acting} onClick={() => void archive()}>封存文件</Button>}</Space>
        </Space>}
        {selectedIngestionUnknown && <Alert type="warning" showIcon title="索引結果不明，已鎖定重送"
          description="必須先使用正式 GET /documents/{id} 查核 後端 真實狀態；查核失敗時鎖定會保留。"
          action={<Button size="small" loading={acting} onClick={() => void verifyIngestionStatus()}>查核真實文件狀態</Button>} />}
        {canIngest && !selectedIngestionUnknown && (selected.status === 'PROCESSING' || selected.status === 'READY') && <Button type="primary" loading={acting} onClick={() => setIngestConfirm(true)}>開始文件解析與索引</Button>}
        {(selected.status === 'FAILED' || selected.status === 'INACTIVE') && <Alert type="info" showIcon title={`後端 狀態「${displayLabel(selected.status)}」不允許從此介面直接重新索引。`} />}
      </Space>}
    </Drawer>

    <Modal title="確認解析與索引文件" open={ingestConfirm} okText="確認開始" confirmLoading={acting}
      onOk={() => void ingest()} onCancel={() => { if (!acting) setIngestConfirm(false); }} closable={!acting} mask={{ closable: !acting }}>
      <Alert type="warning" showIcon title="將呼叫正式索引流程" description="執行中不會重複送出；逾時或網路中斷時不會自動重送。完成後會重新取得 後端 文件狀態。" />
    </Modal>

    <Modal title={reviewConfirm === 'approve' ? '確認核准文件出站' : '確認排除此文件出站'} open={reviewConfirm !== null} okText={reviewConfirm === 'approve' ? '確認核准出站' : '確認排除出站'} confirmLoading={reviewLoading}
      okButtonProps={{ danger: reviewConfirm === 'exclude' }} onOk={() => void confirmOutboundReview()} onCancel={() => { if (!reviewLoading) setReviewConfirm(null); }} closable={!reviewLoading} mask={{ closable: !reviewLoading }}>
      {reviewConfirm === 'approve'
        ? <Alert type="warning" showIcon title="文件內容可能送至外部 Embedding Provider" description="後端 會依出站政策檢查內容；若需要人工審查，實際回應可能是 REQUIRES_REVIEW，不會被前端覆寫成已核准。只對你有權授權外送的文件繼續。" />
        : <Alert type="info" showIcon title="排除後 Backfill 不會索引此文件" description="後端 會記錄排除決策；請確認你要將此文件排除於 Semantic 出站索引。" />}
    </Modal>
    <Modal title="解除 Outbound Review 未知結果鎖定" open={reviewUnlockKey !== null} okText="確認已人工查核" onOk={() => {
      if (reviewUnlockKey) setReviewUnknownKeys((keys) => {
        const next = keys.filter((key) => key !== reviewUnlockKey);
        sessionStorage.setItem(REVIEW_UNKNOWN_KEY, JSON.stringify(next));
        return next;
      });
      setReviewUnlockKey(null); setActionError(null);
    }} onCancel={() => setReviewUnlockKey(null)}>
      <Alert type="warning" showIcon title="請先查核後端審核紀錄" description="Document GET 和 Readiness 都不能證明原核准／排除操作結果。只在人工查核後，才解除此文件版本的重送鎖定。" />
    </Modal>
  </>;
}
