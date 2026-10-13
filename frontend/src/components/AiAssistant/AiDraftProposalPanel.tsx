import { useEffect, useMemo, useRef, useState } from 'react';
import { isAxiosError } from 'axios';
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Modal, Space, Typography } from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { confirmPurchaseDraft, proposePurchaseDraft, type AiDraftProposalResponse } from '../../api/ai';
import type { CreatePurchaseOrderRequest, PurchaseOrderResponse } from '../../api/purchaseOrder';
import { toApiError } from '../../api/apiError';
import { isNonNegativeDecimal, isPositiveDecimal } from '../../pages/PurchaseOrders/purchaseOrderUi';
import ErrorState from '../ErrorState';
import type { ApiErrorResponse } from '../../types/api';

const { Text, Title } = Typography;
interface ItemValues { materialId?: number; quantity?: string; unitPrice?: string; recommendationId?: number; supplierRecommendationResultId?: number; supplierOverrideReason?: string; quantityOverrideReason?: string; }
interface Values { message: string; supplierId?: number; expectedDeliveryDate?: string; items?: ItemValues[]; }

function unknownResult(error: unknown): boolean {
  return isAxiosError(error) && (!error.response || error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT');
}

function proposalError(error: ApiErrorResponse, unknown: boolean): string {
  if (unknown) return 'Confirm 結果不明；系統已鎖定重送。請透過 Purchase Orders 查詢人工核實，不能假設成功或失敗。';
  if (error.code === 'AI_PROPOSAL_EXPIRED') return 'Proposal 已過期，舊 token 不可重用；請重新建立 Proposal。';
  if (error.code === 'AI_PROPOSAL_ALREADY_CONFIRMED') return '此 Proposal 可能已建立採購單；不會將它當成新的成功結果，請先人工查核。';
  if (error.code === 'AI_CONFIRMATION_INVALID') return 'Backend 拒絕 confirmation token 或 payload；未建立採購單。';
  if (error.code === 'AI_PROPOSAL_NOT_FOUND') return 'Backend 找不到此 Proposal；請重新建立。';
  if (error.code === 'AI_PROVIDER_UNAVAILABLE') return 'AI 服務暫時無法使用；核心採購功能不受影響。';
  return error.message;
}

export default function AiDraftProposalPanel() {
  const [form] = Form.useForm<Values>();
  const [proposal, setProposal] = useState<AiDraftProposalResponse | null>(null);
  const [created, setCreated] = useState<PurchaseOrderResponse | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [proposing, setProposing] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [confirmUnknown, setConfirmUnknown] = useState(false);
  const [now, setNow] = useState(0);
  const confirmingRef = useRef(false);

  useEffect(() => {
    if (!proposal?.expiresAt) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [proposal?.expiresAt]);

  const expiry = proposal?.expiresAt ? Date.parse(proposal.expiresAt) : Number.NaN;
  const expired = Number.isFinite(expiry) && now >= expiry;
  const secondsLeft = Number.isFinite(expiry) ? Math.max(0, Math.ceil((expiry - now) / 1000)) : null;
  const draft = proposal?.status === 'PENDING_CONFIRMATION' ? proposal.draft : null;

  const propose = async () => {
    let values: Values;
    try { values = await form.validateFields(); } catch { return; }
    const rows = values.items ?? [];
    if (new Set(rows.map((row) => row.materialId)).size !== rows.length) {
      form.setFields([{ name: 'items', errors: ['Material ID 不可重複'] }]); return;
    }
    const request: CreatePurchaseOrderRequest = {
      supplierId: values.supplierId!,
      ...(values.expectedDeliveryDate ? { expectedDeliveryDate: values.expectedDeliveryDate } : {}),
      items: rows.map((row) => ({
        materialId: row.materialId!, quantity: row.quantity!.trim(), unitPrice: row.unitPrice!.trim(),
        ...(row.recommendationId ? { recommendationId: row.recommendationId } : {}),
        ...(row.supplierRecommendationResultId ? { supplierRecommendationResultId: row.supplierRecommendationResultId } : {}),
        ...(row.supplierOverrideReason?.trim() ? { supplierOverrideReason: row.supplierOverrideReason.trim() } : {}),
        ...(row.quantityOverrideReason?.trim() ? { quantityOverrideReason: row.quantityOverrideReason.trim() } : {}),
      })),
    };
    setProposing(true); setError(null); setCreated(null); setConfirmUnknown(false);
    try { setProposal(await proposePurchaseDraft(values.message.trim(), request)); setNow(Date.now()); }
    catch (requestError) { const apiError = toApiError(requestError); setError({ ...apiError, message: proposalError(apiError, false) }); }
    finally { setProposing(false); }
  };

  const confirm = async () => {
    if (!proposal?.proposalId || !proposal.confirmationToken || !draft || expired || confirmingRef.current || confirmUnknown) return;
    confirmingRef.current = true; setConfirming(true); setError(null);
    try {
      const result = await confirmPurchaseDraft(proposal.proposalId, proposal.confirmationToken, draft);
      setCreated(result); setProposal(null); setConfirmOpen(false);
    } catch (requestError) {
      const unknown = unknownResult(requestError);
      if (unknown) setConfirmUnknown(true);
      const apiError = toApiError(requestError);
      setError({ ...apiError, message: proposalError(apiError, unknown) }); setConfirmOpen(false);
    } finally { confirmingRef.current = false; setConfirming(false); }
  };

  const items = useMemo(() => draft?.items ?? [], [draft]);
  const resetProposal = () => { setProposal(null); setError(null); setConfirmUnknown(false); setCreated(null); };

  return <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
    <div><Title level={4} style={{ marginBottom: 0 }}>AI PO Draft Proposal</Title><Text type="secondary">Proposal 不會建立 PO；只有完整審閱並二次 Confirm 才可能建立 DRAFT。</Text></div>
    {created && <Alert type="success" showIcon title={`PO ${created.poNumber} 已由 Backend 建立`}
      description={<span>PO ID：<Link to={`/purchase-orders/${created.id}`}>{created.id}</Link>；狀態：{created.status}（只建立 DRAFT）。</span>} />}
    {error && <ErrorState title={confirmUnknown ? 'Confirm 結果不明' : 'AI Draft 操作失敗'} error={error} />}
    {proposal?.status === 'NEEDS_CLARIFICATION' && <Alert type="warning" showIcon title="需要補充結構化資料"
      description={`Backend 指出缺少：${proposal.missingFields.join(', ') || '未提供明確欄位'}。不會顯示 Confirm，也不會猜測資料。`} />}

    {!draft && <Form form={form} layout="vertical" initialValues={{ message: '請為下列採購需求準備 DRAFT', items: [{}] }}>
      <Form.Item name="message" label="Intent" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea aria-label="AI draft intent" rows={2} maxLength={2000} /></Form.Item>
      <Form.Item name="supplierId" label="Supplier ID" rules={[{ required: true }]}><InputNumber aria-label="AI draft supplier ID" min={1} precision={0} style={{ width: '100%' }} /></Form.Item>
      <Form.Item name="expectedDeliveryDate" label="Expected Delivery Date (optional for DRAFT)"><Input aria-label="AI draft delivery date" type="date" /></Form.Item>
      <Form.List name="items" rules={[{ validator: async (_, rows) => { if (!rows?.length) throw new Error('至少需要一筆 Item'); } }]}>
        {(fields, { add, remove }, { errors }) => <Space orientation="vertical" style={{ width: '100%' }}>
          {fields.map(({ key, ...field }, index) => <Card key={key} size="small" title={`Item ${index + 1}`} extra={fields.length > 1 ? <Button aria-label={`Remove AI draft item ${index + 1}`} icon={<MinusCircleOutlined />} onClick={() => remove(field.name)} /> : null}>
            <Form.Item {...field} name={[field.name, 'materialId']} label="Material ID" rules={[{ required: true }]}><InputNumber aria-label={`AI draft item ${index + 1} material ID`} min={1} precision={0} style={{ width: '100%' }} /></Form.Item>
            <Form.Item {...field} name={[field.name, 'quantity']} label="Quantity" rules={[{ validator: async (_, value) => { if (!isPositiveDecimal(value)) throw new Error('Enter a positive decimal'); } }]}><Input aria-label={`AI draft item ${index + 1} quantity`} inputMode="decimal" /></Form.Item>
            <Form.Item {...field} name={[field.name, 'unitPrice']} label="Unit Price" rules={[{ validator: async (_, value) => { if (!isNonNegativeDecimal(value)) throw new Error('Enter a non-negative decimal'); } }]}><Input aria-label={`AI draft item ${index + 1} unit price`} inputMode="decimal" /></Form.Item>
            <Space wrap align="start">
              <Form.Item {...field} name={[field.name, 'recommendationId']} label="Recommendation ID"><InputNumber aria-label={`AI draft item ${index + 1} recommendation ID`} min={1} precision={0} /></Form.Item>
              <Form.Item {...field} name={[field.name, 'supplierRecommendationResultId']} label="Supplier Result ID"><InputNumber aria-label={`AI draft item ${index + 1} supplier result ID`} min={1} precision={0} /></Form.Item>
            </Space>
            <Form.Item {...field} name={[field.name, 'supplierOverrideReason']} label="Supplier Override Reason"><Input.TextArea aria-label={`AI draft item ${index + 1} supplier override reason`} maxLength={2000} /></Form.Item>
            <Form.Item {...field} name={[field.name, 'quantityOverrideReason']} label="Quantity Override Reason"><Input.TextArea aria-label={`AI draft item ${index + 1} quantity override reason`} maxLength={2000} /></Form.Item>
          </Card>)}
          <Form.ErrorList errors={errors} /><Button icon={<PlusOutlined />} onClick={() => add()}>Add Item</Button>
        </Space>}
      </Form.List>
      <Button style={{ marginTop: 16 }} type="primary" loading={proposing} disabled={confirmUnknown} onClick={() => void propose()}>Create Proposal for Review</Button>
    </Form>}

    {draft && <Card title="Backend Proposal Snapshot" extra={<Button disabled={confirming} onClick={resetProposal}>Discard & Edit</Button>}>
      <Alert type={expired ? 'error' : 'warning'} showIcon title={expired ? 'Proposal 已過期，禁止 Confirm' : '請完整審閱；Confirm 只會建立 DRAFT'}
        description={`ExpiresAt：${proposal?.expiresAt}；${secondsLeft === null ? '無法解析到期時間' : `約剩 ${secondsLeft} 秒`}。Backend 仍是最終到期判斷。`} />
      <Descriptions bordered size="small" column={1} style={{ marginTop: 12 }} items={[
        { key: 'proposal', label: 'Proposal ID', children: proposal?.proposalId },
        { key: 'supplier', label: 'Supplier ID', children: draft.supplierId },
        { key: 'delivery', label: 'Expected Delivery Date', children: draft.expectedDeliveryDate ?? 'Not set (allowed for DRAFT)' },
      ]} />
      <Space orientation="vertical" style={{ width: '100%', marginTop: 12 }}>{items.map((item, index) => <div key={`${item.materialId}-${index}`}>
        <Descriptions title={`Item ${index + 1}`} bordered size="small" column={1} style={{ width: '100%' }} items={[
          { key: 'material', label: 'Material ID', children: item.materialId },
          { key: 'quantity', label: 'Quantity', children: item.quantity },
          { key: 'price', label: 'Unit Price', children: item.unitPrice },
          { key: 'recommendation', label: 'Recommendation ID', children: item.recommendationId ?? '—' },
          { key: 'result', label: 'Supplier Result ID', children: item.supplierRecommendationResultId ?? '—' },
          { key: 'supplierReason', label: 'Supplier Override Reason', children: item.supplierOverrideReason ?? '—' },
          { key: 'quantityReason', label: 'Quantity Override Reason', children: item.quantityOverrideReason ?? '—' },
        ]} />
      </div>)}</Space>
      <Button type="primary" danger disabled={expired || confirmUnknown || !proposal?.confirmationToken} onClick={() => setConfirmOpen(true)}>Review / Confirm DRAFT</Button>
    </Card>}

    <Modal title="Final Confirmation" open={confirmOpen} okText="Confirm Create DRAFT" confirmLoading={confirming}
      okButtonProps={{ danger: true }} onOk={() => void confirm()} onCancel={() => { if (!confirming) setConfirmOpen(false); }} closable={!confirming} mask={{ closable: !confirming }}>
      <Alert type="warning" showIcon title="這是唯一會建立採購單的步驟"
        description="Backend 將驗證 Proposal ID、使用者、未顯示的 server-issued token、完整 snapshot 與 expiresAt。只建立 DRAFT，不會 Approve、Order、Receive 或調整庫存。" />
    </Modal>
  </Space>;
}
