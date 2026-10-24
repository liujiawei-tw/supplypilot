import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { approvePurchaseOrder, assignPurchaseOrder, beginReceivingInspection, cancelPurchaseOrder, claimPurchaseOrder, confirmPurchaseOrderPayment, getPurchaseOrder, markPurchaseOrderOrdered, rejectPurchaseOrder, revokePurchaseOrderPaymentConfirmation, updatePurchaseOrderDraft, updateSupplierPromisedDeliveryDate, type CreatePurchaseOrderRequest, type PurchaseOrderItemResponse, type PurchaseOrderResponse, type UpdatePurchaseOrderRequest } from '../../api/purchaseOrder';
import { listAssignableUsers, type AssignableUser } from '../../api/users';
import { requiresMutationVerification, toApiError } from '../../api/apiError';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import VerificationRequiredAlert from '../../components/VerificationRequiredAlert';
import type { ApiErrorResponse } from '../../types/api';
import PurchaseOrderForm from './PurchaseOrderForm';
import { formatPurchaseOrderDate, formatTimestamp, statusColors } from './purchaseOrderUi';
import { formatCurrency, formatDate, formatQuantity } from '../../utils/displayFormatters';
import { displayLabel } from '../../utils/displayLabels';

const emptyReceivingInspection = { allItemsReceived: false, allQuantitiesConfirmed: false, allQualityAccepted: false, note: null };

type ActionName = 'edit' | 'approve' | 'reject' | 'order' | 'cancel' | 'receiving' | 'promise' | 'confirm-payment' | 'revoke-payment';
type VerificationState = 'required' | 'checking' | 'failed' | 'verified';

export default function PurchaseOrderDetailPage() {
  const { id } = useParams(); const poId = Number(id);
  const { user } = useAuth();
  const [apiMessage, contextHolder] = message.useMessage();
  const [po, setPo] = useState<PurchaseOrderResponse | null>(null);
  const [assignableUsers, setAssignableUsers] = useState<AssignableUser[]>([]);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [actionError, setActionError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [active, setActive] = useState<ActionName | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [pendingEdit, setPendingEdit] = useState<UpdatePurchaseOrderRequest | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [cancelReason, setCancelReason] = useState('');
  const [supplierPromisedDeliveryDate, setSupplierPromisedDeliveryDate] = useState('');
  const [verificationRequired, setVerificationRequired] = useState(false);
  const [verificationState, setVerificationState] = useState<VerificationState | null>(null);
  const [verificationMessage, setVerificationMessage] = useState('');
  const [verificationError, setVerificationError] = useState<ApiErrorResponse | null>(null);
  const submittingRef = useRef(false);

  const load = useCallback(async () => {
    if (!Number.isSafeInteger(poId) || poId <= 0) { setError({ code: 'INVALID_ID', message: 'Invalid Purchase Order ID', traceId: '', timestamp: '', path: '' }); setLoading(false); return; }
    setLoading(true); setError(null);
    try { setPo(await getPurchaseOrder(poId)); }
    catch (requestError) { setError(toApiError(requestError)); }
    finally { setLoading(false); }
  }, [poId]);
  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);
  useEffect(() => { void listAssignableUsers().then(setAssignableUsers).catch(() => setAssignableUsers([])); }, []);

  const updateOwner = async (userId: number | 'claim') => {
    if (!po || submittingRef.current || verificationRequired) return;
    submittingRef.current = true; setSubmitting(true); setActionError(null);
    try {
      const updated = userId === 'claim' ? await claimPurchaseOrder(po.id) : await assignPurchaseOrder(po.id, userId);
      setPo(updated); apiMessage.success('採購單負責人已更新');
    } catch (cause) {
      setActionError(toApiError(cause));
      if (requiresMutationVerification(cause)) {
        setVerificationRequired(true); setVerificationState('required'); setVerificationError(null);
        setVerificationMessage('負責人操作遇到 409、伺服器錯誤或連線結果不明。系統不會自動重送；請先查詢後端真實狀態。');
      }
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  const verifyLatestState = async () => {
    if (!verificationRequired || verificationState === 'checking') return;
    setVerificationState('checking'); setVerificationError(null);
    try {
      const latest = await getPurchaseOrder(poId);
      setPo(latest); setVerificationRequired(false); setActionError(null);
      setVerificationState('verified');
      setVerificationMessage(`已取得後端最新資料：目前狀態為「${displayLabel(latest.status)}」。請重新審閱畫面；如仍符合角色與生命週期條件，必須重新開啟確認流程。`);
    } catch (requestError) {
      setVerificationRequired(true); setVerificationState('failed');
      setVerificationMessage('無法取得 後端 最新狀態，所有可能重複的寫入操作仍維持鎖定。');
      setVerificationError(toApiError(requestError));
    }
  };

  const perform = async (name: ActionName, operation: () => Promise<PurchaseOrderResponse>) => {
    if (submittingRef.current || verificationRequired) return;
    submittingRef.current = true; setSubmitting(true); setActive(name); setActionError(null); setVerificationState(null); setVerificationError(null);
    try {
      const result = await operation(); setPo(result); setPendingEdit(null); setRejectReason(''); setCancelReason(''); setVerificationRequired(false); apiMessage.success('採購單已更新');
    } catch (requestError) {
      setActionError(toApiError(requestError));
      const mustVerify = requiresMutationVerification(requestError);
      if (mustVerify) {
        setVerificationRequired(true); setVerificationState('required');
        setVerificationMessage('操作遇到 409、伺服器錯誤或連線結果不明。系統不會自動重送；請先查詢 後端 真實狀態。');
      }
      setPendingEdit(null); setRejectReason(''); setCancelReason('');
    } finally { submittingRef.current = false; setSubmitting(false); setActive(null); }
  };

  if (loading && !po) return <LoadingState tip="正在載入採購單…" />;
  if (error || !po) return <ErrorState title="採購單載入失敗" error={error ?? undefined} onRetry={() => void load()} />;
  const receivingInspection = po.receivingInspection ?? emptyReceivingInspection;
  const canEdit = !!user && can(user.role, 'purchaseOrderEdit') && po.status === 'DRAFT';
  const canDecide = !!user && can(user.role, 'purchaseOrderApproveReject') && po.status === 'DRAFT';
  const canOrder = !!user && can(user.role, 'purchaseOrderOrder') && po.status === 'APPROVED';
  const canCancel = user?.role === 'ADMIN' && (po.status === 'APPROVED'
    || (po.status === 'ORDERED' && po.orderedProgress === 'FOLLOW_UP'));
  const canBeginReceiving = user?.role === 'ADMIN' && po.status === 'ORDERED'
    && (po.orderedProgress === 'FOLLOW_UP' || po.orderedProgress === 'LEGACY_UNKNOWN');
  const canUpdatePromise = !!user && can(user.role, 'purchaseOrderEdit')
    && (po.status === 'APPROVED' || po.status === 'ORDERED');
  const canViewTransactions = !!user && can(user.role, 'stockTransactions');
  const canConfirmPayment = user?.role === 'ADMIN' && po.status === 'RECEIVED' && !po.paymentConfirmed;
  const canRevokePayment = user?.role === 'ADMIN' && po.status === 'RECEIVED' && po.paymentConfirmed;

  const items = po.items.map((item) => ({ key: item.id, ...item }));
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}
    <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
      <div><Link to="/purchase-orders">← 採購單管理</Link><Typography.Title level={2} style={{ margin: '8px 0 0' }}>{po.poNumber}</Typography.Title></div>
      <Space wrap>{!po.assignedTo && <Button aria-label={`Claim ${po.poNumber}`} loading={submitting} disabled={verificationRequired} onClick={() => Modal.confirm({ title: '認領此採購單？', content: '確認將目前登入的帳號設為負責人。', onOk: () => updateOwner('claim') })}>認領</Button>}<Select aria-label="指派採購單負責人" size="small" placeholder="指派負責人" style={{ width: 170 }} value={po.assignedTo ?? undefined} disabled={submitting || verificationRequired} options={assignableUsers.map((candidate) => ({ value: candidate.id, label: candidate.displayName }))} onChange={(value: number) => Modal.confirm({ title: '變更採購單負責人？', content: `將負責人指派給 ${assignableUsers.find((candidate) => candidate.id === value)?.displayName ?? '所選帳號'}。`, onOk: () => updateOwner(value) })} />{canEdit && <Button disabled={verificationRequired} onClick={() => { if (!verificationRequired) { setActive('edit'); setActionError(null); } }}>編輯草稿</Button>}{canUpdatePromise && <Button disabled={submitting || verificationRequired} onClick={() => { setSupplierPromisedDeliveryDate(po.supplierPromisedDeliveryDate ?? ''); setActionError(null); setActive('promise'); }}>更新供應商承諾交期</Button>}{canDecide && <><Button type="primary" disabled={!po.expectedDeliveryDate || verificationRequired} title={!po.expectedDeliveryDate ? '請先編輯草稿並補齊交期' : undefined} onClick={() => setActive('approve')}>核准</Button><Button danger disabled={verificationRequired} onClick={() => setActive('reject')}>駁回</Button></>}{canOrder && <Button type="primary" disabled={!po.expectedDeliveryDate || verificationRequired} onClick={() => setActive('order')}>標記為已下單</Button>}{canBeginReceiving && <Button loading={submitting} disabled={submitting || verificationRequired} onClick={() => Modal.confirm({ title: '移入到貨驗收階段？', content: '進入到貨驗收後，採購單不可再取消。', onOk: () => perform('receiving', () => beginReceivingInspection(po.id)) })}>開始到貨驗收</Button>}{canConfirmPayment && <Button type="primary" loading={submitting} disabled={submitting || verificationRequired} onClick={() => Modal.confirm({ title: '確認已完成付款？', content: '僅記錄人工付款確認，不會執行付款或變更庫存。', okText: '確認已付款', onOk: () => perform('confirm-payment', () => confirmPurchaseOrderPayment(po.id)) })}>標記已付款</Button>}{canRevokePayment && <Button loading={submitting} disabled={submitting || verificationRequired} onClick={() => Modal.confirm({ title: '撤銷付款確認？', content: '採購單會回到請款核銷中；已完成的收貨與庫存異動不變。', okText: '撤銷確認', onOk: () => perform('revoke-payment', () => revokePurchaseOrderPaymentConfirmation(po.id)) })}>撤銷付款確認</Button>}{canCancel && <Button danger disabled={submitting || verificationRequired} onClick={() => { setCancelReason(''); setActive('cancel'); }}>取消採購單</Button>}{canViewTransactions && <Link to={`/inventory?tab=stock-transactions&purchaseOrderId=${po.id}`}>庫存異動</Link>}</Space>
    </Space>
    {!po.expectedDeliveryDate && canDecide && <Alert type="warning" showIcon title="此草稿尚未設定交期，無法核准。請先編輯草稿補齊。" />}
    {verificationState && <VerificationRequiredAlert
      state={verificationState}
      title={verificationState === 'verified' ? '已取得最新採購單狀態' : verificationState === 'failed' ? '結果尚未確認，操作維持鎖定' : '狀態可能已變更，操作已鎖定'}
      message={verificationMessage}
      onVerify={verificationRequired ? () => void verifyLatestState() : undefined}
      verifyText="重新查詢採購單"
    />}
    {verificationError && <ErrorState title="採購單狀態查核失敗" error={verificationError} onRetry={() => void verifyLatestState()} retryText="重新查核" />}
    {actionError && <ErrorState title={verificationRequired ? '採購單操作結果待查核' : '採購單操作失敗'} error={actionError} operation="mutation" />}
    <Descriptions bordered column={{ xs: 1, md: 2 }} items={[
      { key: 'id', label: '採購單編號', children: po.id }, { key: 'status', label: '狀態', children: <Space><Tag color={statusColors[po.status]}>{displayLabel(po.status)}</Tag>{po.isOverdue && <Tag color="red">已逾期</Tag>}</Space> },
      { key: 'supplier', label: '供應商快照', children: `${po.supplierCode} — ${po.supplierName} (#${po.supplierId})` }, { key: 'delivery', label: '公司期望到貨日', children: formatPurchaseOrderDate(po.expectedDeliveryDate) },
      { key: 'supplier-promise', label: '供應商承諾交期', children: formatDate(po.supplierPromisedDeliveryDate, '尚未提供') },
      { key: 'created', label: '建立資訊', children: `${po.createdByDisplayName} (#${po.createdBy}) · ${formatTimestamp(po.createdAt)}` },
      { key: 'assigned', label: '目前負責人', children: po.assignedToDisplayName ?? '未指派' },
      { key: 'approved', label: '核准資訊', children: po.approvedBy ? `${po.approvedByDisplayName} (#${po.approvedBy}) · ${formatTimestamp(po.approvedAt)}` : '—' },
      { key: 'rejected', label: '駁回資訊', children: po.rejectedBy ? `${po.rejectedByDisplayName} (#${po.rejectedBy}) · ${formatTimestamp(po.rejectedAt)} · ${po.rejectReason}` : '—' },
      { key: 'cancelled', label: '取消資訊', children: po.cancelledBy ? `${po.cancelledByDisplayName} (#${po.cancelledBy}) · ${formatTimestamp(po.cancelledAt)} · ${po.cancelReason}` : '—' },
      { key: 'ordered-progress', label: '下單階段', children: displayLabel(po.orderedProgress) },
      { key: 'ordered', label: '下單資訊', children: po.orderedBy ? `${po.orderedByDisplayName} (#${po.orderedBy}) · ${formatTimestamp(po.orderedAt)}` : '—' },
      { key: 'received', label: '收貨資訊', children: po.receivedBy ? `${po.receivedByDisplayName} (#${po.receivedBy}) · ${formatTimestamp(po.receivedAt)}` : '—' },
      { key: 'inspection', label: '到貨驗收', children: `${receivingInspection.allItemsReceived ? '整筆到貨' : '尚未確認整筆到貨'}；${receivingInspection.allQuantitiesConfirmed ? '數量已確認' : '數量未確認'}；${receivingInspection.allQualityAccepted ? '品質合格' : '品質未合格'}` },
      { key: 'inspection-note', label: '驗收備註', children: receivingInspection.note ?? '—' },
      { key: 'payment', label: '付款確認', children: po.paymentConfirmed ? `${po.paymentConfirmedByDisplayName ?? po.paymentConfirmedByUsername} · ${formatTimestamp(po.paymentConfirmedAt)}` : '尚未確認付款' },
      { key: 'updated', label: '更新時間', children: formatTimestamp(po.updatedAt) },
    ]} />
    <Typography.Title level={4}>品項</Typography.Title>
    <Table<PurchaseOrderItemResponse> rowKey="id" pagination={false} dataSource={items} scroll={{ x: 'max-content' }} columns={[
      { title: '物料', render: (_, row) => `${row.materialCode} — ${row.materialName} (#${row.materialId})` },
      { title: '數量', render: (_, row) => formatQuantity(row.quantity, row.unit) },
      { title: '單價', render: (_, row) => formatCurrency(row.unitPrice, row.currency) }, { title: '小計', render: (_, row) => formatCurrency(row.total, row.currency) },
      { title: '歷史採購決策依據', render: (_, row) => row.recommendationId ? <Space orientation="vertical" size={0}><span>歷史建議 #{row.recommendationId} / 結果 #{row.supplierRecommendationResultId}</span>{row.supplierOverride && <span>原供應商覆寫：{row.supplierOverrideReason}</span>}{row.quantityOverride && <span>原數量覆寫：{row.quantityOverrideReason}</span>}</Space> : '手動採購品項' },
    ]} />

    <Modal width={900} title="編輯採購單草稿" open={active === 'edit'} footer={null} destroyOnHidden onCancel={() => { if (!submitting) setActive(null); }}>
      {actionError && <ErrorState title="草稿更新失敗" error={actionError} operation="mutation" />}
      <PurchaseOrderForm purchaseOrder={po} disabled={submitting} onReview={(request: CreatePurchaseOrderRequest | UpdatePurchaseOrderRequest) => setPendingEdit(request as UpdatePurchaseOrderRequest)} />
    </Modal>
    <Modal title="確認草稿變更" open={!!pendingEdit} okText="確認更新草稿" confirmLoading={active === 'edit' && submitting} onCancel={() => setPendingEdit(null)} onOk={() => void perform('edit', () => updatePurchaseOrderDraft(po.id, pendingEdit!))}>
      <Alert type="warning" showIcon title="僅允許修改草稿；採購明細需整批替換。" />
    </Modal>
    <Modal title="更新供應商承諾交期" open={active === 'promise'} okText="確認更新" confirmLoading={active === 'promise' && submitting} onCancel={() => { if (!submitting) setActive(null); }} onOk={() => void perform('promise', () => updateSupplierPromisedDeliveryDate(po.id, supplierPromisedDeliveryDate || null))}>
      {actionError && <ErrorState title="供應商承諾交期更新失敗" error={actionError} operation="mutation" />}
      <Alert type="info" showIcon title="此日期不會改變公司的期望到貨日；清空日期會移除目前的供應商承諾。" />
      <Input aria-label="供應商承諾交期" type="date" value={supplierPromisedDeliveryDate} disabled={submitting} onChange={(event) => setSupplierPromisedDeliveryDate(event.target.value)} style={{ marginTop: 16 }} />
    </Modal>
    <Modal title="核准採購單" open={active === 'approve'} okText="確認核准" confirmLoading={submitting} onCancel={() => setActive(null)} onOk={() => void perform('approve', () => approvePurchaseOrder(po.id))}>
      <Alert type="warning" showIcon title="核准後草稿將不可再編輯。" /><Descriptions style={{ marginTop: 16 }} column={1} bordered items={[{ key: 'delivery', label: '預計交期', children: formatPurchaseOrderDate(po.expectedDeliveryDate) }]} />
    </Modal>
    <Modal title="駁回採購單" open={active === 'reject'} okText="確認駁回" okButtonProps={{ danger: true, disabled: !rejectReason.trim() }} confirmLoading={submitting} onCancel={() => { setActive(null); setRejectReason(''); }} onOk={() => void perform('reject', () => rejectPurchaseOrder(po.id, rejectReason.trim()))}>
      <Form layout="vertical"><Form.Item label="駁回原因" required><Input.TextArea aria-label="採購單駁回原因" maxLength={2000} rows={4} value={rejectReason} onChange={(event) => setRejectReason(event.target.value)} /></Form.Item></Form>
    </Modal>
    <Modal title="確認取消採購單" open={active === 'cancel'} okText="確認取消" okButtonProps={{ danger: true, disabled: !cancelReason.trim() || submitting || verificationRequired }} confirmLoading={submitting} onCancel={() => { if (!submitting) { setActive(null); setCancelReason(''); } }} onOk={() => void perform('cancel', () => cancelPurchaseOrder(po.id, cancelReason.trim()))}>
      <Alert type="warning" showIcon title="系統內取消不代表供應商同意。此採購單取消後不可恢復。" />
      <Form layout="vertical" style={{ marginTop: 16 }}><Form.Item label="取消原因" required><Input.TextArea aria-label="採購單取消原因" maxLength={2000} rows={4} value={cancelReason} onChange={(event) => setCancelReason(event.target.value)} /></Form.Item></Form>
    </Modal>
    <Modal title="標記採購單已下單" open={active === 'order'} okText="確認下單" confirmLoading={submitting} onCancel={() => setActive(null)} onOk={() => void perform('order', () => markPurchaseOrderOrdered(po.id))}>
      <Alert type="warning" showIcon title="此動作只標記正式下單，不會修改交期，也不會增加庫存。" /><Descriptions style={{ marginTop: 16 }} column={1} bordered items={[{ key: 'delivery', label: '核准的預計交期', children: formatPurchaseOrderDate(po.expectedDeliveryDate) }]} />
    </Modal>
  </Space>;
}
