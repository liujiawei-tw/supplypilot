import { useRef, useState } from 'react';
import { Alert, Button, Card, Col, Descriptions, InputNumber, Modal, Row, Space, Statistic, Table, Tag, Typography } from 'antd';
import { useNavigate, type NavigateFunction } from 'react-router-dom';
import { getSemanticReadiness, runBackfill, runSemanticGateAudit, type BackfillBatchResponse, type SemanticGateReport } from '../../api/semanticIndex';
import { isUnknownMutationResult, requiresMutationVerification, toApiError } from '../../api/apiError';
import type { ApiErrorResponse } from '../../types/api';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';

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
  if (status === 'READY' || status === 'APPROVED') return 'success';
  if (status === 'FAILED' || status === 'REQUIRES_REVIEW') return 'error';
  if (status === 'PENDING' || status === 'PROCESSING') return 'processing';
  return 'default';
}

function SemanticReport({ report, title, saved, navigate }: { report: SemanticGateReport; title: string; saved: boolean; navigate: NavigateFunction }) {
  const rows = report.findings.map((finding, index) => ({ ...finding, key: `${finding.documentId}-${finding.version}-${index}` }));
  const columns = [
    { title: 'Document ID', dataIndex: 'documentId', key: 'documentId', render: (id: number) => <Button type="link" onClick={() => navigate(`/documents?documentId=${id}`)}>#{id}</Button> },
    { title: 'Version', dataIndex: 'version', key: 'version' },
    { title: 'Status', dataIndex: 'status', key: 'status', render: (status: string) => <Tag color={statusColor(status)}>{status}</Tag> },
    { title: 'Reason', dataIndex: 'reason', key: 'reason', render: (reason: string | null) => reason || '—' },
  ];

  return <Card size="small" title={title}>
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      {saved && <Alert type={report.passed ? 'success' : 'error'} showIcon title={report.passed ? '本次 Gate Audit 通過' : '本次 Gate Audit 未通過'} description={report.passed ? 'Gate 通過不會自動啟用 Semantic Read。' : '請依 Findings 處理原因後，再重新檢查並執行稽核。'} />}
      {!saved && <Alert type={report.passed ? 'success' : 'warning'} showIcon title={report.passed ? 'Readiness 條件目前符合' : 'Readiness 尚未符合'} description="這是目前計算狀態，不代表已保存 Gate Audit，也不能證明先前 POST 的結果。依 Findings 的 Status／Reason 修正資料、授權或服務問題；不可將所有狀態一律當作重新 Backfill 即可修復。" />}
      <Row gutter={[16, 16]}>
        {([['READY', report.ready], ['SKIPPED', report.skipped], ['FAILED', report.failed], ['REQUIRES_REVIEW', report.requiresReview], ['PENDING', report.pending]] as const).map(([label, value]) => <Col xs={12} sm={8} md={4} key={label}><Statistic title={label} value={value} /></Col>)}
      </Row>
      <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
        { key: 'model', label: 'Model ID', children: report.modelId || '—' },
        { key: 'dimensions', label: 'Dimensions', children: report.dimensions },
        { key: 'generation', label: 'Generation Key', children: report.generationKey || '—' },
        ...(saved ? [{ key: 'auditId', label: 'Audit ID', children: report.auditId ?? '回應未提供' }] : []),
      ]} />
      <div><Text strong>Findings</Text><Table size="small" rowKey="key" pagination={{ pageSize: 5, hideOnSinglePage: true }} scroll={{ x: 600 }} columns={columns} dataSource={rows} locale={{ emptyText: <EmptyState simple description="Backend 未回傳 Finding" /> }} /></div>
    </Space>
  </Card>;
}

export default function SemanticOperationsPanel() {
  const navigate = useNavigate();
  const [afterId, setAfterId] = useState(0);
  const [limit, setLimit] = useState(25);
  const [batch, setBatch] = useState<BackfillBatchResponse | null>(null);
  const [readiness, setReadiness] = useState<SemanticGateReport | null>(null);
  const [audit, setAudit] = useState<SemanticGateReport | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [errorIsRead, setErrorIsRead] = useState(false);
  const [loading, setLoading] = useState<'readiness' | Operation | null>(null);
  const [unknown, setUnknown] = useState<Operation[]>(storedUnknownOperations);
  const [confirmAudit, setConfirmAudit] = useState(false);
  const [confirmUnknownClear, setConfirmUnknownClear] = useState<Operation | null>(null);
  const busyRef = useRef(false);

  const start = async <T,>(operation: Operation, request: () => Promise<T>, success: (result: T) => void) => {
    if (busyRef.current || unknown.includes(operation)) return;
    busyRef.current = true;
    setLoading(operation);
    setError(null);
    setErrorIsRead(false);
    if (operation === 'backfill') setBatch(null);
    if (operation === 'audit') setAudit(null);
    try {
      success(await request());
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
      busyRef.current = false;
      setLoading(null);
    }
  };

  const refreshReadiness = async () => {
    if (busyRef.current) return;
    busyRef.current = true; setLoading('readiness'); setError(null);
    try { setReadiness(await getSemanticReadiness()); }
    catch (cause) { setError(toApiError(cause)); setErrorIsRead(true); }
    finally { busyRef.current = false; setLoading(null); }
  };

  return <Card title={<Space direction="vertical" size={0}><Title level={4} style={{ margin: 0 }}>Semantic 索引管理</Title><Text type="secondary">Document READY、Semantic Index READY、Gate PASS 與 Semantic Read 啟用是不同狀態。</Text></Space>}>
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      {error && <ErrorState title={unknown.length > 0 ? '操作結果未知，已鎖定重送' : 'Semantic 操作失敗'} error={error} operation={errorIsRead ? 'read' : 'mutation'} onRetry={errorIsRead ? () => void refreshReadiness() : undefined} />}
      {unknown.map((operation) => <Alert key={operation} type="warning" showIcon title={`${operation === 'audit' ? 'Gate Audit' : 'Backfill'} 結果未知；不會自動重送`}
        description={operation === 'audit' ? 'Readiness 無法證明 Audit 是否已保存；本 API 沒有 Audit 查詢端點。請依後端稽核紀錄人工確認。' : 'Readiness 可顯示目前索引計算狀態，但不能證明先前批次是否已完成。請先查核索引／後端紀錄。'}
        action={<Button size="small" onClick={() => setConfirmUnknownClear(operation)}>我已完成人工查核</Button>} />)}

      <Card size="small" title="Semantic Backfill">
        <Space wrap align="end">
          <label>afterId<InputNumber aria-label="Backfill afterId" min={0} precision={0} value={afterId} onChange={(value) => setAfterId(value ?? 0)} /></label>
          <label>limit<InputNumber aria-label="Backfill limit" min={1} max={100} precision={0} value={limit} onChange={(value) => setLimit(value ?? 25)} /></label>
          <Button type="primary" loading={loading === 'backfill'} disabled={unknown.includes('backfill') || loading !== null} onClick={() => void start('backfill', () => runBackfill(afterId, limit), (result) => { setBatch(result); })}>執行一批</Button>
        </Space>
        <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>每次最多 100 份；不會自動連續執行。以 Backend 回傳的 lastId 做下一批游標。</Text>
        {batch && <div style={{ marginTop: 16 }}>
          <Alert type={batch.failed || batch.requiresReview ? 'warning' : 'success'} showIcon title={`本批處理 ${batch.processed} 份`} description={`READY ${batch.ready} · FAILED ${batch.failed} · SKIPPED ${batch.skipped} · REQUIRES_REVIEW ${batch.requiresReview} · lastId ${batch.lastId} · morePossible ${String(batch.morePossible)}`} />
          {(batch.failed > 0 || batch.requiresReview > 0) && <Alert style={{ marginTop: 12 }} type="info" showIcon title="依狀態處理後再決定是否執行下一批" description={[
            ...(batch.failed > 0 ? ['FAILED：讀取 Readiness Findings 的失敗原因，檢查 Provider／服務狀態與錯誤代碼；不保證重跑可解決。'] : []),
            ...(batch.requiresReview > 0 ? ['REQUIRES_REVIEW：需先由管理員依出站政策人工處理；重跑 Backfill 不會解除審核需求。'] : []),
          ].join(' ')} />}
          {batch.morePossible && <Button style={{ marginTop: 12 }} disabled={loading !== null || unknown.includes('backfill')} onClick={() => setAfterId(batch.lastId)}>使用 lastId {batch.lastId} 作為下一批 afterId</Button>}
        </div>}
      </Card>

      <Card size="small" title="Readiness Inspection" extra={<Button loading={loading === 'readiness'} disabled={loading !== null} onClick={() => void refreshReadiness()}>讀取目前 Readiness</Button>}>
        <Text type="secondary">GET 只計算目前索引完整性，不會保存稽核，也不會確認先前 Approval／Audit POST 是否成功。</Text>
        {readiness && <div style={{ marginTop: 16 }}><SemanticReport report={readiness} title="目前 Readiness（即時計算）" saved={false} navigate={navigate} /></div>}
      </Card>

      <Card size="small" title="Gate Audit">
        <Space direction="vertical" style={{ width: '100%' }}>
          <Alert type="info" showIcon title="Audit 會保存正式稽核結果" description="PASS 不會啟用 Semantic Read。請依 semantic-rag-runbook.md 完成人工環境設定與重啟；新增或變更 READY 文件可能使 Gate 重新關閉。" />
          <Button danger disabled={loading !== null || unknown.includes('audit')} loading={loading === 'audit'} onClick={() => setConfirmAudit(true)}>執行並保存 Gate Audit</Button>
          {audit && <SemanticReport report={audit} title="最近一次本頁取得的 Audit 回應" saved navigate={navigate} />}
        </Space>
      </Card>
      <Alert type="warning" showIcon title="Semantic Read Switch 不在此介面控制" description="只有正式 Gate Audit PASS 後，才依 Runbook 顯式設定 SEMANTIC_READ_ENABLED 並重新啟動 Backend。此頁不會顯示或推定開關已啟用。" />
    </Space>

    <Modal title="確認執行 Gate Audit" open={confirmAudit} okText="確認並保存 Audit" confirmLoading={loading === 'audit'} onOk={() => { setConfirmAudit(false); void start('audit', runSemanticGateAudit, (report) => setAudit(report)); }} onCancel={() => setConfirmAudit(false)}>
      <Alert type="warning" showIcon title="這是會保存稽核紀錄的操作" description="Audit 依執行當下資料計算並持久化結果。若逾時或連線中斷，結果可能未知且不會自動重送。" />
    </Modal>
    <Modal title="解除未知結果鎖定" open={confirmUnknownClear !== null} okText="確認已人工查核" onOk={() => {
      if (confirmUnknownClear) setUnknown((current) => {
        const next = current.filter((operation) => operation !== confirmUnknownClear);
        sessionStorage.setItem(UNKNOWN_OPERATIONS_KEY, JSON.stringify(next));
        return next;
      });
      setConfirmUnknownClear(null); setError(null);
    }} onCancel={() => setConfirmUnknownClear(null)}>
      <Alert type="warning" showIcon title="請先查核正式紀錄" description="請確認後端稽核／索引記錄或由系統管理員查核，Readiness 不足以證明原 POST 結果。只有人工查核後才解除本頁的防重鎖定。" />
    </Modal>
  </Card>;
}
