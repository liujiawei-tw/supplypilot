import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { getReceivingPurchaseOrder, receivePurchaseOrder, type ReceivingPurchaseOrderResponse } from '../../api/receiving';
import { requiresMutationVerification, toApiError } from '../../api/apiError';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import VerificationRequiredAlert from '../../components/VerificationRequiredAlert';
import type { ApiErrorResponse } from '../../types/api';
import { formatTimestamp } from '../PurchaseOrders/purchaseOrderUi';
import { displayLabel } from '../../utils/displayLabels';

type VerificationState = 'required' | 'checking' | 'failed' | 'verified';

export default function ReceivingDetailPage() {
  const { id } = useParams(); const poId = Number(id); const { user } = useAuth();
  const [apiMessage, contextHolder] = message.useMessage();
  const [po, setPo] = useState<ReceivingPurchaseOrderResponse | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [receiveError, setReceiveError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [confirming, setConfirming] = useState(false);
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
    if (!po || po.status !== 'ORDERED' || submittingRef.current || verificationRequired) return;
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

  if (loading && !po) return <LoadingState tip="Loading receiving detail..." />;
  if (error || !po) return <ErrorState title="收貨明細載入失敗" error={error ?? undefined} onRetry={() => void load()} />;
  const canViewTransactions = !!user && can(user.role, 'stockTransactions');
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}<Space style={{ width: '100%', justifyContent: 'space-between' }} wrap><div><Link to="/receiving/purchase-orders">← 收貨作業</Link><Typography.Title level={2} style={{ margin: '8px 0 0' }}>{po.poNumber}</Typography.Title></div><Space>{po.status === 'ORDERED' && <Button type="primary" disabled={verificationRequired} onClick={() => setConfirming(true)}>確認整筆收貨</Button>}{canViewTransactions && <Link to={`/inventory?tab=stock-transactions&purchaseOrderId=${po.id}`}>庫存異動</Link>}</Space></Space>
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
      { key: 'ordered', label: '下單時間', children: formatTimestamp(po.orderedAt) }, { key: 'received', label: '收貨資訊', children: po.receivedBy ? `${po.receivedByDisplayName} (#${po.receivedBy}) · ${formatTimestamp(po.receivedAt)}` : '—' },
    ]} />
    <Table rowKey="id" pagination={false} dataSource={po.items} columns={[{ title: '物料', render: (_, row) => `${row.materialCode} — ${row.materialName}` }, { title: '數量', render: (_, row) => `${String(row.quantity)} ${row.unit}` }]} />
    <Alert type="info" showIcon title="此頁僅使用收貨專用資料" description="不載入或呈現單價、總額、採購建議、覆寫資訊或採購核准／駁回稽核。" />
    <Modal title="確認整筆收貨" open={confirming} okText="確認整筆收貨" confirmLoading={submitting} okButtonProps={{ disabled: submitting }} onCancel={() => { if (!submittingRef.current) setConfirming(false); }} onOk={() => void receive()}>
      <Alert type="warning" showIcon title="此操作會一次更新庫存，並為所有品項建立庫存異動；不支援部分收貨。" />
      <Descriptions style={{ marginTop: 16 }} bordered column={1} items={[{ key: 'po', label: '採購單編號', children: po.poNumber }, { key: 'supplier', label: '供應商', children: po.supplierName }, { key: 'type', label: '收貨方式', children: '整筆收貨' }, { key: 'items', label: '全部品項', children: po.items.map((item) => `${item.materialCode}：${String(item.quantity)} ${item.unit}`).join('；') }]} />
    </Modal>
  </Space>;
}
