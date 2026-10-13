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
        ? '此採購單已完成收貨。Backend Receiving 專用查詢回傳 RECEIVED，本頁不會再送出 Receive。'
        : '目前 Backend 仍顯示待收貨（ORDERED），先前操作結果需由使用者重新確認。系統沒有自動重送；如要再操作，必須重新開啟確認流程。');
    } catch (requestError) {
      setVerificationRequired(true); setVerificationState('failed');
      setVerificationMessage('無法透過 Receiving 專用 GET 取得真實狀態；結果尚未確認，Receive 維持鎖定。');
      setVerificationError(toApiError(requestError));
    }
  };

  const receive = async () => {
    if (!po || po.status !== 'ORDERED' || submittingRef.current || verificationRequired) return;
    submittingRef.current = true; setSubmitting(true); setReceiveError(null); setVerificationState(null); setVerificationError(null);
    try { const result = await receivePurchaseOrder(po.id); setPo(result); setConfirming(false); setVerificationRequired(false); apiMessage.success('Full Receive 已完成'); }
    catch (requestError) {
      setReceiveError(toApiError(requestError));
      if (requiresMutationVerification(requestError)) {
        setVerificationRequired(true); setVerificationState('required');
        setVerificationMessage('收貨遇到 409、伺服器錯誤或連線結果不明。系統不會自動重送；請先使用 Receiving 專用 GET 查核 Backend 真實狀態。');
      }
      setConfirming(false);
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  if (loading && !po) return <LoadingState tip="Loading receiving detail..." />;
  if (error || !po) return <ErrorState title="Receiving Detail 載入失敗" error={error ?? undefined} onRetry={() => void load()} />;
  const canViewTransactions = !!user && can(user.role, 'stockTransactions');
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}<Space style={{ width: '100%', justifyContent: 'space-between' }} wrap><div><Link to="/receiving/purchase-orders">← Receiving</Link><Typography.Title level={2} style={{ margin: '8px 0 0' }}>{po.poNumber}</Typography.Title></div><Space>{po.status === 'ORDERED' && <Button type="primary" disabled={verificationRequired} onClick={() => setConfirming(true)}>Confirm Full Receive</Button>}{canViewTransactions && <Link to={`/inventory?tab=stock-transactions&purchaseOrderId=${po.id}`}>Stock Transactions</Link>}</Space></Space>
    {verificationState && <VerificationRequiredAlert
      state={verificationState}
      title={verificationState === 'verified' ? '已取得最新 Receiving 狀態' : verificationState === 'failed' ? '結果尚未確認，Receive 維持鎖定' : '收貨結果需要查核，已鎖定再次提交'}
      message={verificationMessage}
      onVerify={verificationRequired ? () => void verifyReceivingStatus() : undefined}
      verifyText="重新查詢 Receiving 狀態"
    />}
    {verificationError && <ErrorState title="Receiving 狀態查核失敗" error={verificationError} onRetry={() => void verifyReceivingStatus()} retryText="重新查核" />}
    {receiveError && <ErrorState title={verificationRequired ? 'Receive 結果待查核' : 'Receive 操作失敗'} error={receiveError} operation="mutation" />}
    <Descriptions bordered column={{ xs: 1, md: 2 }} items={[
      { key: 'id', label: 'PO ID', children: po.id }, { key: 'status', label: 'Status', children: <Tag color={po.status === 'RECEIVED' ? 'green' : 'gold'}>{po.status}</Tag> },
      { key: 'supplier', label: 'Supplier', children: `${po.supplierCode} — ${po.supplierName}` }, { key: 'delivery', label: 'Expected Delivery', children: <Space>{po.expectedDeliveryDate ?? '—'}{po.isOverdue && <Tag color="red">OVERDUE</Tag>}</Space> },
      { key: 'ordered', label: 'Ordered At', children: formatTimestamp(po.orderedAt) }, { key: 'received', label: 'Received', children: po.receivedBy ? `${po.receivedByDisplayName} (#${po.receivedBy}) · ${formatTimestamp(po.receivedAt)}` : '—' },
    ]} />
    <Table rowKey="id" pagination={false} dataSource={po.items} columns={[{ title: 'Material', render: (_, row) => `${row.materialCode} — ${row.materialName}` }, { title: 'Quantity', render: (_, row) => `${String(row.quantity)} ${row.unit}` }]} />
    <Alert type="info" showIcon title="此頁只使用 Receiving 受限 DTO" description="不載入或呈現單價、總額、Recommendation、Override 或採購核准／拒絕稽核。" />
    <Modal title="Confirm Full Receive" open={confirming} okText="Confirm Full Receive" confirmLoading={submitting} okButtonProps={{ disabled: submitting }} onCancel={() => { if (!submittingRef.current) setConfirming(false); }} onOk={() => void receive()}>
      <Alert type="warning" showIcon title="這會原子更新 Inventory 並為所有 Items 建立 Stock Transactions；不支援部分收貨。" />
      <Descriptions style={{ marginTop: 16 }} bordered column={1} items={[{ key: 'po', label: 'PO Number', children: po.poNumber }, { key: 'supplier', label: 'Supplier', children: po.supplierName }, { key: 'type', label: 'Receiving Type', children: 'Full Receive' }, { key: 'items', label: 'All Items', children: po.items.map((item) => `${item.materialCode}: ${String(item.quantity)} ${item.unit}`).join('；') }]} />
    </Modal>
  </Space>;
}
