import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Descriptions, Input, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { getReceivingPurchaseOrder, receivePurchaseOrder, type ReceivingPurchaseOrderResponse } from '../../api/receiving';
import { beginReceivingInspection, recordReceivingInspection } from '../../api/purchaseOrder';
import { requiresMutationVerification, toApiError } from '../../api/apiError';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import VerificationRequiredAlert from '../../components/VerificationRequiredAlert';
import type { ApiErrorResponse } from '../../types/api';
import { formatTimestamp } from '../PurchaseOrders/purchaseOrderUi';
import { displayLabel } from '../../utils/displayLabels';

const emptyReceivingInspection = { allItemsReceived: false, allQuantitiesConfirmed: false, allQualityAccepted: false, note: null };

type VerificationState = 'required' | 'checking' | 'failed' | 'verified';

export default function ReceivingDetailPage() {
  const { id } = useParams(); const poId = Number(id); const { user } = useAuth();
  const [apiMessage, contextHolder] = message.useMessage();
  const [po, setPo] = useState<ReceivingPurchaseOrderResponse | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [receiveError, setReceiveError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [confirming, setConfirming] = useState(false);
  const [inspecting, setInspecting] = useState(false);
  const [allItemsReceived, setAllItemsReceived] = useState(false);
  const [allQuantitiesConfirmed, setAllQuantitiesConfirmed] = useState(false);
  const [allQualityAccepted, setAllQualityAccepted] = useState(false);
  const [inspectionNote, setInspectionNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [verificationRequired, setVerificationRequired] = useState(false);
  const [verificationState, setVerificationState] = useState<VerificationState | null>(null);
  const [verificationMessage, setVerificationMessage] = useState('');
  const [verificationError, setVerificationError] = useState<ApiErrorResponse | null>(null);
  const submittingRef = useRef(false);
  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try { const result = await getReceivingPurchaseOrder(poId); setPo(result); setConfirming(false); }
    catch (requestError) { setError(toApiError(requestError)); }
    finally { setLoading(false); }
  }, [poId]);
  useEffect(() => { const timer = window.setTimeout(() => { if (Number.isSafeInteger(poId) && poId > 0) void load(); else { setError({ code: 'INVALID_ID', message: 'Invalid Purchase Order ID', traceId: '', timestamp: '', path: '' }); setLoading(false); } }, 0); return () => window.clearTimeout(timer); }, [load, poId]);

  const verifyReceivingStatus = async () => {
    if (!verificationRequired || verificationState === 'checking') return;
    setVerificationState('checking'); setVerificationError(null);
    try {
      const latest = await getReceivingPurchaseOrder(poId);
      setPo(latest); setReceiveError(null); setVerificationRequired(false); setVerificationState('verified');
      setVerificationMessage(latest.status === 'RECEIVED'
        ? '此採購單已完成收貨。收貨專用查詢已確認狀態，本頁不會再送出收貨。'
        : `目前仍為待收貨（${displayLabel(latest.status)}），先前操作結果需由使用者重新確認。系統沒有自動重送；如要再操作，必須重新開啟確認流程。`);
    } catch (requestError) {
      setVerificationRequired(true); setVerificationState('failed');
      setVerificationMessage('無法透過 Receiving 專用 GET 取得真實狀態；結果尚未確認，Receive 維持鎖定。');
      setVerificationError(toApiError(requestError));
    }
  };

  const receive = async () => {
    const inspection = po?.receivingInspection ?? emptyReceivingInspection;
    if (!po || po.status !== 'ORDERED' || po.orderedProgress !== 'RECEIVING_INSPECTION'
      || !inspection.allItemsReceived || !inspection.allQuantitiesConfirmed
      || !inspection.allQualityAccepted || submittingRef.current || verificationRequired) return;
    submittingRef.current = true; setSubmitting(true); setReceiveError(null); setVerificationState(null); setVerificationError(null);
    try { const result = await receivePurchaseOrder(po.id); setPo(result); setConfirming(false); setVerificationRequired(false); apiMessage.success('整筆收貨已完成'); }
    catch (requestError) {
      setReceiveError(toApiError(requestError));
      if (requiresMutationVerification(requestError)) {
        setVerificationRequired(true); setVerificationState('required');
        setVerificationMessage('收貨遇到 409、伺服器錯誤或連線結果不明。系統不會自動重送；請先使用 Receiving 專用 GET 查核 後端 真實狀態。');
      }
      setConfirming(false);
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  const saveInspection = async () => {
    if (!po || po.orderedProgress !== 'RECEIVING_INSPECTION' || submittingRef.current || verificationRequired) return;
    submittingRef.current = true; setSubmitting(true); setReceiveError(null);
    try {
      const result = await recordReceivingInspection(po.id, { allItemsReceived, allQuantitiesConfirmed, allQualityAccepted, note: inspectionNote || null });
      setPo({ ...po, orderedProgress: result.orderedProgress, receivingInspection: result.receivingInspection });
      setInspecting(false); apiMessage.success('驗收紀錄已儲存');
    } catch (requestError) {
      setReceiveError(toApiError(requestError));
      if (requiresMutationVerification(requestError)) {
        setVerificationRequired(true); setVerificationState('required');
        setVerificationMessage('驗收紀錄結果未知。系統不會自動重送；請先重新查詢後端採購單狀態。');
      }
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  const startInspection = async () => {
    if (!po || submittingRef.current || verificationRequired) return;
    submittingRef.current = true; setSubmitting(true); setReceiveError(null);
    try {
      const result = await beginReceivingInspection(po.id);
      setPo({ ...po, orderedProgress: result.orderedProgress, receivingInspection: result.receivingInspection });
      apiMessage.success('採購單已移入到貨驗收');
    } catch (requestError) {
      setReceiveError(toApiError(requestError));
      if (requiresMutationVerification(requestError)) {
        setVerificationRequired(true); setVerificationState('required');
        setVerificationMessage('移入驗收階段結果未知。系統不會自動重送；請先重新查詢後端採購單狀態。');
      }
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  const beginEditingInspection = () => {
    if (verificationRequired || !po) return;
    const inspection = po.receivingInspection ?? emptyReceivingInspection;
    setAllItemsReceived(inspection.allItemsReceived);
    setAllQuantitiesConfirmed(inspection.allQuantitiesConfirmed);
    setAllQualityAccepted(inspection.allQualityAccepted);
    setInspectionNote(inspection.note ?? '');
    setInspecting(true);
  };

  if (loading && !po) return <LoadingState tip="Loading receiving detail..." />;
  if (error || !po) return <ErrorState title="收貨明細載入失敗" error={error ?? undefined} onRetry={() => void load()} />;
  const canViewTransactions = !!user && can(user.role, 'stockTransactions');
  const canManageInspection = user?.role === 'ADMIN';
  const receivingInspection = po.receivingInspection ?? emptyReceivingInspection;
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}<Space style={{ width: '100%', justifyContent: 'space-between' }} wrap><div><Link to="/receiving/purchase-orders">← 收貨作業</Link><Typography.Title level={2} style={{ margin: '8px 0 0' }}>{po.poNumber}</Typography.Title></div><Space>{canViewTransactions && <Link to={`/inventory?tab=stock-transactions&purchaseOrderId=${po.id}`}>庫存異動</Link>}</Space></Space>
    {verificationState && <VerificationRequiredAlert
      state={verificationState}
      title={verificationState === 'verified' ? '已取得最新收貨狀態' : verificationState === 'failed' ? '結果尚未確認，收貨維持鎖定' : '收貨結果需要查核，已鎖定再次提交'}
      message={verificationMessage}
      onVerify={verificationRequired ? () => void verifyReceivingStatus() : undefined}
      verifyText="重新查詢收貨狀態"
    />}
    {verificationError && <ErrorState title="收貨狀態查核失敗" error={verificationError} onRetry={() => void verifyReceivingStatus()} retryText="重新查核" />}
    {receiveError && <ErrorState title={verificationRequired ? '收貨結果待查核' : '收貨操作失敗'} error={receiveError} operation="mutation" />}
    <Descriptions bordered column={{ xs: 1, md: 2 }} items={[
      { key: 'id', label: '採購單編號', children: po.id }, { key: 'status', label: '狀態', children: <Tag color={po.status === 'RECEIVED' ? 'green' : 'gold'}>{displayLabel(po.status)}</Tag> },
      { key: 'supplier', label: '供應商', children: `${po.supplierCode} — ${po.supplierName}` }, { key: 'delivery', label: '預計交期', children: <Space>{po.expectedDeliveryDate ?? '—'}{po.isOverdue && <Tag color="red">已逾期</Tag>}</Space> },
      { key: 'ordered', label: '下單時間', children: formatTimestamp(po.orderedAt) }, { key: 'receiving-progress', label: '目前進度', children: displayLabel(po.orderedProgress) },
      { key: 'inspection', label: '驗收確認', children: `${receivingInspection.allItemsReceived ? '整筆到貨' : '尚未確認整筆到貨'}；${receivingInspection.allQuantitiesConfirmed ? '數量已確認' : '數量未確認'}；${receivingInspection.allQualityAccepted ? '品質合格' : '品質未合格'}` },
      { key: 'inspection-note', label: '驗收異常備註', children: receivingInspection.note ?? '—' },
      { key: 'received', label: '收貨資訊', children: po.receivedBy ? `${po.receivedByDisplayName} (#${po.receivedBy}) · ${formatTimestamp(po.receivedAt)}` : '—' },
    ]} />
    <Table rowKey="id" pagination={false} dataSource={po.items} columns={[{ title: '物料', render: (_, row) => `${row.materialCode} — ${row.materialName}` }, { title: '數量', render: (_, row) => `${String(row.quantity)} ${row.unit}` }]} />
    <Alert type="info" showIcon title="此頁僅使用收貨專用資料" description="不載入或呈現單價、總額、採購建議、覆寫資訊、付款確認或採購核准／駁回稽核。" />
    {canManageInspection && po.status === 'ORDERED' && po.orderedProgress !== 'RECEIVING_INSPECTION' && <Button loading={submitting} disabled={submitting || verificationRequired} onClick={() => Modal.confirm({ title: '開始到貨驗收？', content: '此步驟只記錄進入驗收階段，不會增加庫存。', okText: '開始驗收', onOk: () => startInspection() })}>開始到貨驗收</Button>}
    {po.status === 'ORDERED' && po.orderedProgress === 'RECEIVING_INSPECTION' && <Space wrap>
      {canManageInspection && <Button loading={submitting} disabled={submitting || verificationRequired} onClick={beginEditingInspection}>填寫／更新驗收結果</Button>}
      <Button type="primary" loading={submitting} disabled={submitting || verificationRequired || !receivingInspection.allItemsReceived || !receivingInspection.allQuantitiesConfirmed || !receivingInspection.allQualityAccepted} onClick={() => setConfirming(true)}>確認整筆收貨</Button>
    </Space>}
    {po.status === 'ORDERED' && po.orderedProgress === 'RECEIVING_INSPECTION'
      && (!receivingInspection.allItemsReceived || !receivingInspection.allQuantitiesConfirmed || !receivingInspection.allQualityAccepted)
      && <Alert type="warning" showIcon title="完整收貨尚未開放" description="請先確認整筆到貨、全部數量及全部品質合格；短收或異常須留在驗收階段並記錄備註。" />}
    <Modal title="確認整筆收貨" open={confirming} okText="確認整筆收貨" confirmLoading={submitting} okButtonProps={{ disabled: submitting }} onCancel={() => { if (!submittingRef.current) setConfirming(false); }} onOk={() => void receive()}>
      <Alert type="warning" showIcon title="此操作會一次更新庫存，並為所有品項建立庫存異動；不支援部分收貨。" />
      <Descriptions style={{ marginTop: 16 }} bordered column={1} items={[{ key: 'po', label: '採購單編號', children: po.poNumber }, { key: 'supplier', label: '供應商', children: po.supplierName }, { key: 'type', label: '收貨方式', children: '整筆收貨' }, { key: 'items', label: '全部品項', children: po.items.map((item) => `${item.materialCode}：${String(item.quantity)} ${item.unit}`).join('；') }]} />
    </Modal>
    <Modal title="人工數量與品質驗收" open={inspecting} okText="儲存驗收紀錄" confirmLoading={submitting} okButtonProps={{ disabled: submitting || verificationRequired }} onCancel={() => { if (!submittingRef.current) setInspecting(false); }} onOk={() => void saveInspection()}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <Checkbox checked={allItemsReceived} onChange={(event) => setAllItemsReceived(event.target.checked)}>整筆貨品已到齊（不接受部分收貨）</Checkbox>
        <Checkbox checked={allQuantitiesConfirmed} onChange={(event) => setAllQuantitiesConfirmed(event.target.checked)}>全部品項數量已確認</Checkbox>
        <Checkbox checked={allQualityAccepted} onChange={(event) => setAllQualityAccepted(event.target.checked)}>全部品項品質驗收合格</Checkbox>
        <Input.TextArea aria-label="驗收異常備註" maxLength={2000} rows={4} value={inspectionNote} onChange={(event) => setInspectionNote(event.target.value)} placeholder="短收、尚未到貨或品質異常請記錄於此" />
      </Space>
    </Modal>
  </Space>;
}
