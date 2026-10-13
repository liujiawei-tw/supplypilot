import { useCallback, useEffect, useRef, useState } from 'react';
import { isAxiosError } from 'axios';
import { Alert, Button, Descriptions, Form, Input, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { approvePurchaseOrder, getPurchaseOrder, markPurchaseOrderOrdered, rejectPurchaseOrder, updatePurchaseOrderDraft, type CreatePurchaseOrderRequest, type PurchaseOrderItemResponse, type PurchaseOrderResponse, type UpdatePurchaseOrderRequest } from '../../api/purchaseOrder';
import { toApiError } from '../../api/apiError';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import type { ApiErrorResponse } from '../../types/api';
import PurchaseOrderForm from './PurchaseOrderForm';
import { formatTimestamp, isUnknownMutation, statusColors } from './purchaseOrderUi';

type ActionName = 'edit' | 'approve' | 'reject' | 'order';

export default function PurchaseOrderDetailPage() {
  const { id } = useParams(); const poId = Number(id);
  const { user } = useAuth();
  const [apiMessage, contextHolder] = message.useMessage();
  const [po, setPo] = useState<PurchaseOrderResponse | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [actionError, setActionError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [active, setActive] = useState<ActionName | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [pendingEdit, setPendingEdit] = useState<UpdatePurchaseOrderRequest | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [verificationRequired, setVerificationRequired] = useState(false);
  const submittingRef = useRef(false);

  const load = useCallback(async () => {
    if (!Number.isSafeInteger(poId) || poId <= 0) { setError({ code: 'INVALID_ID', message: 'Invalid Purchase Order ID', traceId: '', timestamp: '', path: '' }); setLoading(false); return; }
    setLoading(true); setError(null);
    try { setPo(await getPurchaseOrder(poId)); setVerificationRequired(false); }
    catch (requestError) { setError(toApiError(requestError)); }
    finally { setLoading(false); }
  }, [poId]);
  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);

  const perform = async (name: ActionName, operation: () => Promise<PurchaseOrderResponse>) => {
    if (submittingRef.current || verificationRequired) return;
    submittingRef.current = true; setSubmitting(true); setActive(name); setActionError(null);
    try {
      const result = await operation(); setPo(result); setPendingEdit(null); setRejectReason(''); apiMessage.success('Purchase Order 已更新');
    } catch (requestError) {
      setActionError(toApiError(requestError));
      setVerificationRequired(isUnknownMutation(requestError) || (isAxiosError(requestError) && requestError.response?.status === 409));
      if (name === 'edit') setPendingEdit(null);
    } finally { submittingRef.current = false; setSubmitting(false); setActive(null); }
  };

  if (loading && !po) return <LoadingState tip="Loading Purchase Order..." />;
  if (error || !po) return <ErrorState title="Purchase Order 載入失敗" error={error ?? undefined} onRetry={() => void load()} />;
  const canEdit = !!user && can(user.role, 'purchaseOrderEdit') && po.status === 'DRAFT';
  const canDecide = !!user && can(user.role, 'purchaseOrderApproveReject') && po.status === 'DRAFT';
  const canOrder = !!user && can(user.role, 'purchaseOrderOrder') && po.status === 'APPROVED';
  const canViewTransactions = !!user && can(user.role, 'stockTransactions');

  const items = po.items.map((item) => ({ key: item.id, ...item }));
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}
    <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
      <div><Link to="/purchase-orders">← Purchase Orders</Link><Typography.Title level={2} style={{ margin: '8px 0 0' }}>{po.poNumber}</Typography.Title></div>
      <Space wrap>{canEdit && <Button onClick={() => { setActive('edit'); setActionError(null); }}>Edit DRAFT</Button>}{canDecide && <><Button type="primary" disabled={!po.expectedDeliveryDate || verificationRequired} title={!po.expectedDeliveryDate ? '請先編輯 DRAFT 補齊交期' : undefined} onClick={() => setActive('approve')}>Approve</Button><Button danger disabled={verificationRequired} onClick={() => setActive('reject')}>Reject</Button></>}{canOrder && <Button type="primary" disabled={!po.expectedDeliveryDate || verificationRequired} onClick={() => setActive('order')}>Mark Ordered</Button>}{canViewTransactions && <Link to={`/inventory?tab=stock-transactions&purchaseOrderId=${po.id}`}>Stock Transactions</Link>}</Space>
    </Space>
    {!po.expectedDeliveryDate && canDecide && <Alert type="warning" showIcon title="此 DRAFT 尚未設定交期，無法核准。請先使用 Edit DRAFT 補齊。" />}
    {verificationRequired && <Alert type="warning" showIcon title="狀態可能已變更，操作已鎖定" description={<Space orientation="vertical"><span>409 或連線結果不明時不會自動重送。請先重新查詢 Backend 真實狀態。</span><Button onClick={() => void load()}>重新查詢 PO</Button></Space>} />}
    {actionError && <ErrorState title="Purchase Order 操作失敗" error={actionError} />}
    <Descriptions bordered column={{ xs: 1, md: 2 }} items={[
      { key: 'id', label: 'PO ID', children: po.id }, { key: 'status', label: 'Status', children: <Space><Tag color={statusColors[po.status]}>{po.status}</Tag>{po.isOverdue && <Tag color="red">OVERDUE</Tag>}</Space> },
      { key: 'supplier', label: 'Supplier Snapshot', children: `${po.supplierCode} — ${po.supplierName} (#${po.supplierId})` }, { key: 'delivery', label: 'Expected Delivery', children: po.expectedDeliveryDate ?? '—' },
      { key: 'created', label: 'Created', children: `${po.createdByDisplayName} (#${po.createdBy}) · ${formatTimestamp(po.createdAt)}` },
      { key: 'approved', label: 'Approved', children: po.approvedBy ? `${po.approvedByDisplayName} (#${po.approvedBy}) · ${formatTimestamp(po.approvedAt)}` : '—' },
      { key: 'rejected', label: 'Rejected', children: po.rejectedBy ? `${po.rejectedByDisplayName} (#${po.rejectedBy}) · ${formatTimestamp(po.rejectedAt)} · ${po.rejectReason}` : '—' },
      { key: 'ordered', label: 'Ordered', children: po.orderedBy ? `${po.orderedByDisplayName} (#${po.orderedBy}) · ${formatTimestamp(po.orderedAt)}` : '—' },
      { key: 'received', label: 'Received', children: po.receivedBy ? `${po.receivedByDisplayName} (#${po.receivedBy}) · ${formatTimestamp(po.receivedAt)}` : '—' },
      { key: 'updated', label: 'Updated At', children: formatTimestamp(po.updatedAt) },
    ]} />
    <Typography.Title level={4}>Items</Typography.Title>
    <Table<PurchaseOrderItemResponse> rowKey="id" pagination={false} dataSource={items} columns={[
      { title: 'Material', render: (_, row) => `${row.materialCode} — ${row.materialName} (#${row.materialId})` },
      { title: 'Quantity', render: (_, row) => `${String(row.quantity)} ${row.unit}` },
      { title: 'Unit Price', render: (_, row) => String(row.unitPrice) }, { title: 'Total', render: (_, row) => String(row.total) },
      { title: 'Recommendation Evidence', render: (_, row) => row.recommendationId ? <Space orientation="vertical" size={0}><span>Recommendation #{row.recommendationId} / Result #{row.supplierRecommendationResultId}</span>{row.supplierOverride && <span>Supplier override: {row.supplierOverrideReason}</span>}{row.quantityOverride && <span>Quantity override: {row.quantityOverrideReason}</span>}</Space> : 'Manual item' },
    ]} />

    <Modal width={900} title="Edit Purchase Order DRAFT" open={active === 'edit'} footer={null} destroyOnHidden onCancel={() => { if (!submitting) setActive(null); }}>
      {actionError && <ErrorState title="DRAFT 更新失敗" error={actionError} />}
      <PurchaseOrderForm purchaseOrder={po} disabled={submitting} onReview={(request: CreatePurchaseOrderRequest | UpdatePurchaseOrderRequest) => setPendingEdit(request as UpdatePurchaseOrderRequest)} />
    </Modal>
    <Modal title="Confirm DRAFT Changes" open={!!pendingEdit} okText="Confirm Update DRAFT" confirmLoading={active === 'edit' && submitting} onCancel={() => setPendingEdit(null)} onOk={() => void perform('edit', () => updatePurchaseOrderDraft(po.id, pendingEdit!))}>
      <Alert type="warning" showIcon title="只允許修改 DRAFT；Items 是完整替換清單。" />
    </Modal>
    <Modal title="Approve Purchase Order" open={active === 'approve'} okText="Confirm Approve" confirmLoading={submitting} onCancel={() => setActive(null)} onOk={() => void perform('approve', () => approvePurchaseOrder(po.id))}>
      <Alert type="warning" showIcon title="核准後 DRAFT 將不可再編輯。" /><Descriptions style={{ marginTop: 16 }} column={1} bordered items={[{ key: 'delivery', label: 'Expected Delivery Date', children: po.expectedDeliveryDate ?? '未設定' }]} />
    </Modal>
    <Modal title="Reject Purchase Order" open={active === 'reject'} okText="Confirm Reject" okButtonProps={{ danger: true, disabled: !rejectReason.trim() }} confirmLoading={submitting} onCancel={() => { setActive(null); setRejectReason(''); }} onOk={() => void perform('reject', () => rejectPurchaseOrder(po.id, rejectReason.trim()))}>
      <Form layout="vertical"><Form.Item label="Reason" required><Input.TextArea aria-label="PO reject reason" maxLength={2000} rows={4} value={rejectReason} onChange={(event) => setRejectReason(event.target.value)} /></Form.Item></Form>
    </Modal>
    <Modal title="Mark Purchase Order Ordered" open={active === 'order'} okText="Confirm Mark Ordered" confirmLoading={submitting} onCancel={() => setActive(null)} onOk={() => void perform('order', () => markPurchaseOrderOrdered(po.id))}>
      <Alert type="warning" showIcon title="此動作只標記正式下單，不會修改交期，也不會增加庫存。" /><Descriptions style={{ marginTop: 16 }} column={1} bordered items={[{ key: 'delivery', label: 'Approved Expected Delivery Date', children: po.expectedDeliveryDate ?? '未設定' }]} />
    </Modal>
  </Space>;
}
