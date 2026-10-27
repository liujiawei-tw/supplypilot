import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Col, Descriptions, InputNumber, Modal, Row, Space, Statistic, Table, Tag, Typography } from 'antd';
import { useNavigate, type NavigateFunction } from 'react-router-dom';
import { getSemanticIndexJobs, getSemanticReadiness, retrySemanticIndexJob, runBackfill, runSemanticGateAudit, type BackfillBatchResponse, type SemanticGateReport, type SemanticIndexJob } from '../../api/semanticIndex';
import { isUnknownMutationResult, requiresMutationVerification, toApiError } from '../../api/apiError';
import type { ApiErrorResponse } from '../../types/api';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import { displayLabel } from '../../utils/displayLabels';

const { Text, Title } = Typography;
type Operation = 'backfill' | 'audit';
const UNKNOWN_OPERATIONS_KEY = 'supplypilot.semanticUnknownOperations';

function storedUnknownOperations(): Operation[] {
  try {
    const value: unknown = JSON.parse(sessionStorage.getItem(UNKNOWN_OPERATIONS_KEY) ?? '[]');
    return Array.isArray(value) ? value.filter((item): item is Operation => item === 'backfill' || item === 'audit') : [];
  } catch { return []; }
}

function statusColor(status: string): string {
  if (status === 'READY' || status === 'APPROVED' || status === 'SUCCEEDED') return 'success';
  if (status === 'FAILED' || status === 'REQUIRES_REVIEW') return 'error';
  if (status === 'PENDING' || status === 'PROCESSING' || status === 'QUEUED') return 'processing';
  if (status === 'RETRY_WAIT') return 'warning';
  return 'default';
}

function SemanticReport({ report, title, saved, stale = false, navigate }: { report: SemanticGateReport; title: string; saved: boolean; stale?: boolean; navigate: NavigateFunction }) {
  const rows = report.findings.map((finding, index) => ({ ...finding, key: `${finding.documentId}-${finding.version}-${index}` }));
  const columns = [
    { title: '文件編號', dataIndex: 'documentId', key: 'documentId', render: (id: number) => <Button type="link" onClick={() => navigate(`/documents?documentId=${id}`)}>#{id}</Button> },
    { title: '版本', dataIndex: 'version', key: 'version' },
    { title: '狀態', dataIndex: 'status', key: 'status', render: (status: string) => <Tag color={statusColor(status)}>{displayLabel(status)}</Tag> },
    { title: '原因', dataIndex: 'reason', key: 'reason', render: (reason: string | null) => reason || '—' },
  ];

  return <Card className="semantic-operations__report" size="small" title={title}>
    <Space className="semantic-operations__report-body" direction="vertical" style={{ width: '100%' }} size="middle">
      {saved && stale && <Alert type="warning" showIcon title="歷史索引稽核紀錄（已過期）" description={`這筆正式稽核當時${report.passed ? '通過' : '未通過'}，但之後發生文件或語意索引變更；不能代表目前檢核狀態。重新執行索引稽核才會取得新的正式稽核回應。`} />}
      {saved && !stale && <Alert type={report.passed ? 'success' : 'error'} showIcon title={report.passed ? '本次索引稽核通過' : '本次索引稽核未通過'} description={report.passed ? '檢核通過不會自動啟用語意查詢。' : '請依檢查結果處理原因後，再重新檢查並執行稽核。'} />}
      {!saved && <Alert type={report.passed ? 'success' : 'warning'} showIcon title={report.passed ? '就緒條件目前符合' : '就緒條件尚未符合'} description="這是目前計算狀態，不代表已保存索引稽核，也不能證明先前送出操作的結果。依檢查結果中的狀態與原因修正資料、授權或服務問題；不可將所有狀態一律當作重新補建索引即可修復。" />}
      <Row gutter={[16, 16]}>
        {([['READY', report.ready], ['SKIPPED', report.skipped], ['FAILED', report.failed], ['REQUIRES_REVIEW', report.requiresReview], ['PENDING', report.pending]] as const).map(([label, value]) => <Col xs={12} sm={8} md={4} key={label}><Statistic title={displayLabel(label)} value={value} /></Col>)}
      </Row>
      <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
        { key: 'model', label: '模型編號', children: report.modelId || '—' },
        { key: 'dimensions', label: '向量維度', children: report.dimensions },
        { key: 'generation', label: '生成識別碼', children: report.generationKey || '—' },
        ...(saved ? [{ key: 'auditId', label: '稽核編號', children: report.auditId ?? '回應未提供' }] : []),
      ]} />
      <div><Text strong>檢查結果</Text><Table size="small" rowKey="key" pagination={{ pageSize: 5, hideOnSinglePage: true }} scroll={{ x: 600 }} columns={columns} dataSource={rows} locale={{ emptyText: <EmptyState simple description="系統未回傳檢查結果" /> }} /></div>
    </Space>
  </Card>;
}

interface SemanticOperationsPanelProps {
  documentMutationVersion?: number;
}

export default function SemanticOperationsPanel({ documentMutationVersion = 0 }: SemanticOperationsPanelProps) {
  const navigate = useNavigate();
  const [afterId, setAfterId] = useState(0);
  const [limit, setLimit] = useState(25);
  const [batch, setBatch] = useState<BackfillBatchResponse | null>(null);
  const [readiness, setReadiness] = useState<SemanticGateReport | null>(null);
  const [audit, setAudit] = useState<SemanticGateReport | null>(null);
  const [auditStale, setAuditStale] = useState(false);
  const [jobs, setJobs] = useState<SemanticIndexJob[]>([]);
  const [jobsLoading, setJobsLoading] = useState(false);
  const [retryingJobId, setRetryingJobId] = useState<number | null>(null);
  const [jobError, setJobError] = useState<ApiErrorResponse | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [errorIsRead, setErrorIsRead] = useState(false);
  const [loading, setLoading] = useState<'readiness' | Operation | null>(null);
  const [unknown, setUnknown] = useState<Operation[]>(storedUnknownOperations);
  const [confirmAudit, setConfirmAudit] = useState(false);
  const [confirmUnknownClear, setConfirmUnknownClear] = useState<Operation | null>(null);
  const mutationBusyRef = useRef(false);
  const readinessBusyRef = useRef(false);
  const requestEpoch = useRef(0);
  const previousDocumentMutationVersion = useRef(documentMutationVersion);

  const refreshJobs = useCallback(async () => {
    setJobsLoading(true);
    try {
      const current = await getSemanticIndexJobs();
      setJobs(current);
      setJobError(null);
    } catch (cause) {
      setJobError(toApiError(cause));
    } finally {
      setJobsLoading(false);
    }
  }, []);

  useEffect(() => { void refreshJobs(); }, [refreshJobs]);

  const retryJob = async (jobId: number) => {
    if (retryingJobId !== null) return;
    setRetryingJobId(jobId);
    setJobError(null);
    try {
      await retrySemanticIndexJob(jobId);
      await refreshJobs();
    } catch (cause) {
      await refreshJobs();
      setJobError(toApiError(cause));
    } finally {
      setRetryingJobId(null);
    }
  };

  const invalidateReports = useCallback(() => {
    requestEpoch.current += 1;
    setReadiness(null);
    if (audit) setAuditStale(true);
  }, [audit]);

  useLayoutEffect(() => {
    if (previousDocumentMutationVersion.current === documentMutationVersion) return;
    previousDocumentMutationVersion.current = documentMutationVersion;
    invalidateReports();
    void refreshJobs();
  }, [documentMutationVersion, invalidateReports, refreshJobs]);

  const start = async <T,>(operation: Operation, request: () => Promise<T>, success: (result: T) => void) => {
    if (mutationBusyRef.current || unknown.includes(operation)) return;
    mutationBusyRef.current = true;
    setLoading(operation);
    setError(null);
    setErrorIsRead(false);
    invalidateReports();
    const operationEpoch = requestEpoch.current;
    if (operation === 'backfill') setBatch(null);
    try {
      const result = await request();
      if (operation === 'audit') {
        success(result);
        setAuditStale(operationEpoch !== requestEpoch.current);
      } else if (operationEpoch === requestEpoch.current) success(result);
    } catch (cause) {
      const apiError = toApiError(cause);
      setError(apiError); setErrorIsRead(false);
      if (requiresMutationVerification(cause) || isUnknownMutationResult(cause)) {
        setUnknown((current) => {
          const next = current.includes(operation) ? current : [...current, operation];
          sessionStorage.setItem(UNKNOWN_OPERATIONS_KEY, JSON.stringify(next));
          return next;
        });
      }
    } finally {
      mutationBusyRef.current = false;
      setLoading((current) => current === operation ? null : current);
    }
  };

  const refreshReadiness = async () => {
    if (mutationBusyRef.current || readinessBusyRef.current) return;
    readinessBusyRef.current = true; setLoading('readiness'); setError(null); setErrorIsRead(false); setReadiness(null);
    const operationEpoch = requestEpoch.current;
    try {
      const result = await getSemanticReadiness();
      if (operationEpoch === requestEpoch.current) setReadiness(result);
    }
    catch (cause) { if (operationEpoch === requestEpoch.current) { setError(toApiError(cause)); setErrorIsRead(true); } }
    finally { readinessBusyRef.current = false; setLoading((current) => current === 'readiness' ? null : current); }
  };

  return <section className="semantic-operations sp-section" aria-labelledby="semantic-operations-title">
    <header className="semantic-operations__heading sp-section__heading">
      <Title id="semantic-operations-title" className="semantic-operations__title" level={4}>語意索引管理</Title>
      <Text type="secondary">文件核准後會自動排入逐份索引；每份文件分別發布，檢核報表可用於診斷環境。</Text>
    </header>
    <Space className="semantic-operations__body" direction="vertical" size="large" style={{ width: '100%' }}>
      {error && <ErrorState title={unknown.length > 0 ? '操作結果未知，已鎖定重送' : '語意索引操作失敗'} error={error} operation={errorIsRead ? 'read' : 'mutation'} onRetry={errorIsRead ? () => void refreshReadiness() : undefined} />}
      {unknown.map((operation) => <Alert key={operation} type="warning" showIcon title={`${operation === 'audit' ? '索引稽核' : '索引補建'} 結果未知；不會自動重送`}
        description={operation === 'audit' ? '目前狀態無法證明稽核是否已保存；系統尚無查詢稽核紀錄的功能。請依系統稽核紀錄人工確認。' : '目前狀態可顯示索引計算結果，但不能證明先前批次是否已完成。請先查核索引與系統紀錄。'}
        action={<Button size="small" onClick={() => setConfirmUnknownClear(operation)}>我已完成人工查核</Button>} />)}

      <Card className="semantic-operations__card" size="small" title="語意索引補建">
        <Space wrap align="end">
          <label>起始文件編號（afterId）<InputNumber aria-label="補建起始文件編號" min={0} precision={0} value={afterId} onChange={(value) => setAfterId(value ?? 0)} /></label>
          <label>每批上限<InputNumber aria-label="每批補建上限" min={1} max={100} precision={0} value={limit} onChange={(value) => setLimit(value ?? 25)} /></label>
          <Button type="primary" loading={loading === 'backfill'} disabled={unknown.includes('backfill') || (loading !== null && loading !== 'readiness')} onClick={() => void start('backfill', () => runBackfill(afterId, limit), (result) => { setBatch(result); })}>執行一批</Button>
        </Space>
        <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>每批最多 100 份，不會自動連續執行。請使用系統回傳的最後文件編號作為下一批起點。</Text>
        {batch && <div style={{ marginTop: 16 }}>
          <Alert type={batch.failed || batch.requiresReview ? 'warning' : 'success'} showIcon title={`本批處理 ${batch.processed} 份`} description={`已就緒 ${batch.ready} · 失敗 ${batch.failed} · 略過 ${batch.skipped} · 需人工審核 ${batch.requiresReview} · 最後編號 ${batch.lastId} · 還有下一批：${batch.morePossible ? '有' : '沒有'}`} />
          {(batch.failed > 0 || batch.requiresReview > 0) && <Alert style={{ marginTop: 12 }} type="info" showIcon title="依狀態處理後再決定是否執行下一批" description={[
            ...(batch.failed > 0 ? ['處理失敗：查看就緒狀態檢查中的原因，確認外部向量服務狀態與錯誤代碼；重試不保證能解決問題。'] : []),
            ...(batch.requiresReview > 0 ? ['需人工審核：請管理員依出站政策處理；重新補建索引不會解除審核要求。'] : []),
          ].join(' ')} />}
          {batch.morePossible && <Button style={{ marginTop: 12 }} disabled={loading !== null || unknown.includes('backfill')} onClick={() => setAfterId(batch.lastId)}>以編號 {batch.lastId} 作為下一批起點</Button>}
        </div>}
      </Card>

      <Card className="semantic-operations__card" size="small" title="就緒狀態檢查" extra={<Button loading={loading === 'readiness'} disabled={loading !== null && loading !== 'readiness'} onClick={() => void refreshReadiness()}>讀取目前就緒狀態</Button>}>
        <Text type="secondary">讀取操作只計算目前索引完整性，不會保存稽核，也不會確認先前核准或稽核送出的結果。</Text>
        {readiness && <div style={{ marginTop: 16 }}><SemanticReport report={readiness} title="目前就緒狀態（即時計算）" saved={false} navigate={navigate} /></div>}
        {!readiness && errorIsRead && <Alert style={{ marginTop: 16 }} type="warning" showIcon title="無法確認目前就緒狀態" description="最新就緒狀態查詢失敗；先前回應已清除，不可視為目前檢核狀態。" />}
      </Card>

      <Card className="semantic-operations__card" size="small" title="逐份文件索引工作" extra={<Button loading={jobsLoading} onClick={() => void refreshJobs()}>重新整理</Button>}>
        <Text type="secondary">核准後的索引由背景工作執行。失敗工作可由管理員手動重試；錯誤欄位只顯示安全代碼。</Text>
        {jobError && <ErrorState title="索引工作查詢或重試失敗" error={jobError} operation="read" onRetry={() => void refreshJobs()} />}
        <Table size="small" rowKey="id" loading={jobsLoading} scroll={{ x: 760 }}
          dataSource={jobs}
          locale={{ emptyText: <EmptyState simple description="目前沒有索引工作" /> }}
          columns={[
            { title: '工作編號', dataIndex: 'id', key: 'id' },
            { title: '文件編號', dataIndex: 'documentId', key: 'documentId', render: (id: number) => <Button type="link" onClick={() => navigate('/documents?documentId=' + id)}>#{id}</Button> },
            { title: '版本', dataIndex: 'version', key: 'version' },
            { title: '狀態', dataIndex: 'status', key: 'status', render: (status: string) => <Tag color={statusColor(status)}>{displayLabel(status)}</Tag> },
            { title: '嘗試次數', key: 'attempts', render: (_: unknown, job: SemanticIndexJob) => job.attempts + ' / ' + job.maxAttempts },
            { title: '錯誤代碼', dataIndex: 'failureCode', key: 'failureCode', render: (code: string | null) => code || '—' },
            { title: '更新時間', dataIndex: 'updatedAt', key: 'updatedAt' },
            { title: '操作', key: 'action', render: (_: unknown, job: SemanticIndexJob) => job.status === 'FAILED'
              ? <Button size="small" loading={retryingJobId === job.id} disabled={retryingJobId !== null} onClick={() => void retryJob(job.id)}>手動重試</Button> : '—' },
          ]} />
      </Card>

      <Card className="semantic-operations__card" size="small" title="索引稽核">
        <Space direction="vertical" style={{ width: '100%' }}>
          <Alert type="info" showIcon title="索引稽核會保存正式稽核結果" description="索引稽核是整體環境的診斷紀錄，不是逐份文件的發布條件或每次查詢的全域阻擋。請以各文件工作狀態和目前核准版本為準。" />
          <Button danger disabled={(loading !== null && loading !== 'readiness') || unknown.includes('audit')} loading={loading === 'audit'} onClick={() => setConfirmAudit(true)}>執行並保存索引稽核</Button>
          {audit && <SemanticReport report={audit} title={auditStale ? '歷史索引稽核回應' : '本頁最近取得的稽核回應'} saved stale={auditStale} navigate={navigate} />}
        </Space>
      </Card>
      <Alert type="warning" showIcon title="語意查詢由部署設定控制" description="SEMANTIC_READ_ENABLED 仍由部署環境管理；索引稽核不會逐文件啟用或關閉已發布文件的查詢。此頁不會顯示或推定環境開關已啟用。" />
    </Space>

    <Modal title="確認執行索引稽核" open={confirmAudit} okText="確認並保存稽核結果" confirmLoading={loading === 'audit'} onOk={() => { setConfirmAudit(false); void start('audit', runSemanticGateAudit, (report) => setAudit(report)); }} onCancel={() => setConfirmAudit(false)}>
      <Alert type="warning" showIcon title="這是會保存稽核紀錄的操作" description="稽核依執行當下資料計算並持久化結果。若逾時或連線中斷，結果可能未知且不會自動重送。" />
    </Modal>
    <Modal title="解除未知結果鎖定" open={confirmUnknownClear !== null} okText="確認已人工查核" onOk={() => {
      if (confirmUnknownClear) setUnknown((current) => {
        const next = current.filter((operation) => operation !== confirmUnknownClear);
        sessionStorage.setItem(UNKNOWN_OPERATIONS_KEY, JSON.stringify(next));
        return next;
      });
      setConfirmUnknownClear(null); setError(null);
    }} onCancel={() => setConfirmUnknownClear(null)}>
      <Alert type="warning" showIcon title="請先查核正式紀錄" description="請確認系統稽核與索引記錄或由系統管理員查核，就緒狀態不足以證明原操作結果。只有人工查核後才解除本頁的防重鎖定。" />
    </Modal>
  </section>;
}
