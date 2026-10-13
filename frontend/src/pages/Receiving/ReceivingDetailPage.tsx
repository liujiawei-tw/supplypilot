import { useCallback, useEffect, useRef, useState } from 'react';
import { isAxiosError } from 'axios';
import { Alert, Button, Descriptions, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { getReceivingPurchaseOrder, receivePurchaseOrder, type ReceivingPurchaseOrderResponse } from '../../api/receiving';
import { toApiError } from '../../api/apiError';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import type { ApiErrorResponse } from '../../types/api';
import { formatTimestamp, isUnknownMutation } from '../PurchaseOrders/purchaseOrderUi';

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
  const submittingRef = useRef(false);
  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try { const result = await getReceivingPurchaseOrder(poId); setPo(result); setVerificationRequired(false); setConfirming(false); }
    catch (requestError) { setError(toApiError(requestError)); }
    finally { setLoading(false); }
  }, [poId]);
  useEffect(() => { const timer = window.setTimeout(() => { if (Number.isSafeInteger(poId) && poId > 0) void load(); else { setError({ code: 'INVALID_ID', message: 'Invalid Purchase Order ID', traceId: '', timestamp: '', path: '' }); setLoading(false); } }, 0); return () => window.clearTimeout(timer); }, [load, poId]);

  const receive = async () => {
    if (!po || po.status !== 'ORDERED' || submittingRef.current || verificationRequired) return;
    submittingRef.current = true; setSubmitting(true); setReceiveError(null);
    try { const result = await receivePurchaseOrder(po.id); setPo(result); setConfirming(false); apiMessage.success('Full Receive 已完成'); }
    catch (requestError) {
      setReceiveError(toApiError(requestError));
      setVerificationRequired(isUnknownMutation(requestError) || (isAxiosError(requestError) && requestError.response?.status === 409));
      setConfirming(false);
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  if (loading && !po) return <LoadingState tip="Loading receiving detail..." />;
  if (error || !po) return <ErrorState title="Receiving Detail 載入失敗" error={error ?? undefined} onRetry={() => void load()} />;
  const canViewTransactions = !!user && can(user.role, 'stockTransactions');
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}<Space style={{ width: '100%', justifyContent: 'space-between' }} wrap><div><Link to="/receiving/purchase-orders">← Receiving</Link><Typography.Title level={2} style={{ margin: '8px 0 0' }}>{po.poNumber}</Typography.Title></div><Space>{po.status === 'ORDERED' && <Button type="primary" disabled={verificationRequired} onClick={() => setConfirming(true)}>Confirm Full Receive</Button>}{canViewTransactions && <Link to={`/inventory?tab=stock-transactions&purchaseOrderId=${po.id}`}>Stock Transactions</Link>}</Space></Space>
    {verificationRequired && <Alert type="warning" showIcon title="收貨結果需要查核，已鎖定再次提交" description={<Space orientation="vertical"><span>409 或連線中斷可能代表另一請求已完成。系統不會自動重送；請用 Receiving GET 查核狀態。</span><Button onClick={() => void load()}>重新查詢 Receiving 狀態</Button></Space>} />}
    {receiveError && <ErrorState title="Receive 操作失敗" error={receiveError} />}
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
