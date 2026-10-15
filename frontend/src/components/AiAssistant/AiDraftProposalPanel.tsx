import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Modal, Space, Typography } from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { confirmPurchaseDraft, proposePurchaseDraft, type AiDraftProposalResponse } from '../../api/ai';
import type { CreatePurchaseOrderRequest, PurchaseOrderResponse } from '../../api/purchaseOrder';
import { isUnknownMutationResult, toApiError } from '../../api/apiError';
import { isNonNegativeDecimal, isPositiveDecimal } from '../../pages/PurchaseOrders/purchaseOrderUi';
import ErrorState from '../ErrorState';
import type { ApiErrorResponse } from '../../types/api';
import { displayLabel } from '../../utils/displayLabels';

const { Text, Title } = Typography;
interface ItemValues { materialId?: number; quantity?: string; unitPrice?: string; recommendationId?: number; supplierRecommendationResultId?: number; supplierOverrideReason?: string; quantityOverrideReason?: string; }
interface Values { message: string; supplierId?: number; expectedDeliveryDate?: string; items?: ItemValues[]; }
type ConfirmLock = 'UNKNOWN' | 'ALREADY_CONFIRMED' | 'EXPIRED' | null;

function unknownResult(error: unknown): boolean {
  return isUnknownMutationResult(error);
}

function proposalError(error: ApiErrorResponse, unknown: boolean): string {
  if (unknown) return '確認結果不明；系統已鎖定重送。請透過 Purchase Orders 查詢人工核實，不能假設成功或失敗。';
  if (error.code === 'AI_PROPOSAL_EXPIRED') return '提案已過期，舊權杖不可重用；請重新建立提案。';
  if (error.code === 'AI_PROPOSAL_ALREADY_CONFIRMED') return '此提案可能已建立採購單；不會將它當成新的成功結果，請先人工查核。';
  if (error.code === 'AI_CONFIRMATION_INVALID') return '後端 拒絕 confirmation token 或 payload；未建立採購單。';
  if (error.code === 'AI_PROPOSAL_NOT_FOUND') return '後端 找不到此 Proposal；請重新建立。';
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
  const [confirmLock, setConfirmLock] = useState<ConfirmLock>(null);
  const [now, setNow] = useState(0);
  const confirmingRef = useRef(false);

  useEffect(() => {
    if (!proposal?.expiresAt) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [proposal?.expiresAt]);

  const expiry = proposal?.expiresAt ? Date.parse(proposal.expiresAt) : Number.NaN;
  const hasValidExpiry = Number.isFinite(expiry);
  const expired = hasValidExpiry && now >= expiry;
  const terminalConfirmLock = confirmLock === 'UNKNOWN' || confirmLock === 'ALREADY_CONFIRMED';
  const confirmLocked = terminalConfirmLock || !hasValidExpiry || expired || confirmLock === 'EXPIRED';
  const secondsLeft = hasValidExpiry ? Math.max(0, Math.ceil((expiry - now) / 1000)) : null;
  const draft = proposal?.status === 'PENDING_CONFIRMATION' ? proposal.draft : null;

  const propose = async () => {
    if (terminalConfirmLock) return;
    let values: Values;
    try { values = await form.validateFields(); } catch { return; }
    const rows = values.items ?? [];
    if (new Set(rows.map((row) => row.materialId)).size !== rows.length) {
      form.setFields([{ name: 'items', errors: ['物料編號 不可重複'] }]); return;
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
    setProposing(true); setError(null); setCreated(null); setConfirmLock(null);
    try { setProposal(await proposePurchaseDraft(values.message.trim(), request)); setNow(Date.now()); }
    catch (requestError) { const apiError = toApiError(requestError); setError({ ...apiError, message: proposalError(apiError, false) }); }
    finally { setProposing(false); }
  };

  const confirm = async () => {
    if (!proposal?.proposalId || !proposal.confirmationToken || !draft || confirmLocked || confirmingRef.current) return;
    confirmingRef.current = true; setConfirming(true); setError(null);
    try {
      const result = await confirmPurchaseDraft(proposal.proposalId, proposal.confirmationToken, draft);
      setCreated(result); setProposal(null); setConfirmOpen(false);
    } catch (requestError) {
      const unknown = unknownResult(requestError);
      const apiError = toApiError(requestError);
      if (unknown) setConfirmLock('UNKNOWN');
      else if (apiError.code === 'AI_PROPOSAL_ALREADY_CONFIRMED') setConfirmLock('ALREADY_CONFIRMED');
      else if (apiError.code === 'AI_PROPOSAL_EXPIRED') setConfirmLock('EXPIRED');
      setError({ ...apiError, message: proposalError(apiError, unknown) }); setConfirmOpen(false);
    } finally { confirmingRef.current = false; setConfirming(false); }
  };

  const items = useMemo(() => draft?.items ?? [], [draft]);
  const resetProposal = () => {
    if (terminalConfirmLock) return;
    setProposal(null); setError(null); setConfirmLock(null); setCreated(null);
  };

  return <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
    <div><Title level={4} style={{ marginBottom: 0 }}>AI 採購單草稿提案</Title><Text type="secondary">提案本身不會建立採購單；完整審閱並再次確認後才會建立草稿。</Text></div>
    {created && <Alert type="success" showIcon title={`採購單 ${created.poNumber} 已建立`}
      description={<span>採購單編號：<Link to={`/purchase-orders/${created.id}`}>{created.id}</Link>；狀態：{displayLabel(created.status)}（僅建立草稿）。</span>} />}
    {error && <ErrorState title={confirmLock === 'UNKNOWN' ? '確認結果不明' : 'AI 草稿操作失敗'} error={error} />}
    {proposal?.status === 'NEEDS_CLARIFICATION' && <Alert type="warning" showIcon title="需要補充結構化資料"
      description={`後端指出缺少：${proposal.missingFields.join(', ') || '未提供明確欄位'}。不會顯示確認操作，也不會猜測資料。`} />}

    {!draft && <Form form={form} layout="vertical" initialValues={{ message: '請為下列採購需求準備 DRAFT', items: [{}] }}>
      <Form.Item name="message" label="採購需求說明" rules={[{ required: true, whitespace: true }, { max: 2000 }]}><Input.TextArea aria-label="AI 草稿需求說明" rows={2} maxLength={2000} /></Form.Item>
      <Form.Item name="supplierId" label="供應商編號" rules={[{ required: true }]}><InputNumber aria-label="AI 草稿供應商編號" min={1} precision={0} style={{ width: '100%' }} /></Form.Item>
      <Form.Item name="expectedDeliveryDate" label="預計交期（建立草稿時可留空）"><Input aria-label="AI 草稿預計交期" type="date" /></Form.Item>
      <Form.List name="items" rules={[{ validator: async (_, rows) => { if (!rows?.length) throw new Error('至少需要一筆品項'); } }]}>
        {(fields, { add, remove }, { errors }) => <Space orientation="vertical" style={{ width: '100%' }}>
          {fields.map(({ key, ...field }, index) => <Card key={key} size="small" title={`品項 ${index + 1}`} extra={fields.length > 1 ? <Button aria-label={`移除 AI 草稿品項 ${index + 1}`} icon={<MinusCircleOutlined />} onClick={() => remove(field.name)} /> : null}>
            <Form.Item {...field} name={[field.name, 'materialId']} label="物料編號" rules={[{ required: true }]}><InputNumber aria-label={`AI 草稿品項 ${index + 1} 物料編號`} min={1} precision={0} style={{ width: '100%' }} /></Form.Item>
            <Form.Item {...field} name={[field.name, 'quantity']} label="數量" rules={[{ validator: async (_, value) => { if (!isPositiveDecimal(value)) throw new Error('請輸入大於 0 的數字'); } }]}><Input aria-label={`AI 草稿品項 ${index + 1} 數量`} inputMode="decimal" /></Form.Item>
            <Form.Item {...field} name={[field.name, 'unitPrice']} label="單價" rules={[{ validator: async (_, value) => { if (!isNonNegativeDecimal(value)) throw new Error('請輸入大於或等於 0 的數字'); } }]}><Input aria-label={`AI 草稿品項 ${index + 1} 單價`} inputMode="decimal" /></Form.Item>
            <Space wrap align="start">
              <Form.Item {...field} name={[field.name, 'recommendationId']} label="採購建議編號"><InputNumber aria-label={`AI 草稿品項 ${index + 1} 採購建議編號`} min={1} precision={0} /></Form.Item>
              <Form.Item {...field} name={[field.name, 'supplierRecommendationResultId']} label="供應商結果編號"><InputNumber aria-label={`AI 草稿品項 ${index + 1} 供應商結果編號`} min={1} precision={0} /></Form.Item>
            </Space>
            <Form.Item {...field} name={[field.name, 'supplierOverrideReason']} label="供應商覆寫原因"><Input.TextArea aria-label={`AI 草稿品項 ${index + 1} 供應商覆寫原因`} maxLength={2000} /></Form.Item>
            <Form.Item {...field} name={[field.name, 'quantityOverrideReason']} label="數量覆寫原因"><Input.TextArea aria-label={`AI 草稿品項 ${index + 1} 數量覆寫原因`} maxLength={2000} /></Form.Item>
          </Card>)}
          <Form.ErrorList errors={errors} /><Button icon={<PlusOutlined />} onClick={() => add()}>新增品項</Button>
        </Space>}
      </Form.List>
      <Button style={{ marginTop: 16 }} type="primary" loading={proposing} disabled={terminalConfirmLock} onClick={() => void propose()}>產生提案供審閱</Button>
    </Form>}

    {draft && <Card title="後端提案快照" extra={<Button disabled={confirming || terminalConfirmLock} onClick={resetProposal}>捨棄並編輯</Button>}>
      {confirmLock === 'UNKNOWN' ? <Alert type="warning" showIcon title="確認結果不明"
        description="可能已建立採購單。請先由人工查核採購單真實狀態；查核前不可重新確認、丟棄草稿或重新產生提案。" />
        : confirmLock === 'ALREADY_CONFIRMED' ? <Alert type="error" showIcon title="提案已確認"
          description="此提案已不可再次使用。請先由人工查核已建立的採購單真實狀態。" />
          : !hasValidExpiry ? <Alert type="error" showIcon title="提案到期時間無效"
            description="此提案缺少到期時間或時間格式無法解析，為避免重複建立採購單，請修改草稿後重新產生提案。" />
            : <Alert type={expired || confirmLock === 'EXPIRED' ? 'error' : 'warning'} showIcon title={expired || confirmLock === 'EXPIRED' ? '提案已過期，禁止確認' : '請完整審閱；確認後只會建立草稿'}
              description={`到期時間：${proposal?.expiresAt}；${secondsLeft === null ? '無法解析到期時間' : `約剩 ${secondsLeft} 秒`}。後端 仍是最終到期判斷。`} />}
      <Descriptions bordered size="small" column={1} style={{ marginTop: 12 }} items={[
        { key: 'proposal', label: '提案編號', children: proposal?.proposalId },
        { key: 'supplier', label: '供應商編號', children: draft.supplierId },
        { key: 'delivery', label: '預計交期', children: draft.expectedDeliveryDate ?? '未設定（草稿允許留空）' },
      ]} />
      <Space orientation="vertical" style={{ width: '100%', marginTop: 12 }}>{items.map((item, index) => <div key={`${item.materialId}-${index}`}>
        <Descriptions title={`品項 ${index + 1}`} bordered size="small" column={1} style={{ width: '100%' }} items={[
          { key: 'material', label: '物料編號', children: item.materialId },
          { key: 'quantity', label: '數量', children: item.quantity },
          { key: 'price', label: '單價', children: item.unitPrice },
          { key: 'recommendation', label: '採購建議編號', children: item.recommendationId ?? '—' },
          { key: 'result', label: '供應商結果編號', children: item.supplierRecommendationResultId ?? '—' },
          { key: 'supplierReason', label: '供應商覆寫原因', children: item.supplierOverrideReason ?? '—' },
          { key: 'quantityReason', label: '數量覆寫原因', children: item.quantityOverrideReason ?? '—' },
        ]} />
      </div>)}</Space>
      <Button type="primary" danger disabled={confirmLocked || !proposal?.confirmationToken} onClick={() => setConfirmOpen(true)}>審閱並確認草稿</Button>
    </Card>}

    <Modal title="最後確認" open={confirmOpen} okText="確認建立草稿" confirmLoading={confirming}
      okButtonProps={{ danger: true, disabled: confirmLocked || !proposal?.confirmationToken }} onOk={() => void confirm()} onCancel={() => { if (!confirming) setConfirmOpen(false); }} closable={!confirming} mask={{ closable: !confirming }}>
      <Alert type="warning" showIcon title="這是唯一會建立採購單的步驟"
        description="後端 將驗證 提案編號、使用者、未顯示的 server-issued token、完整 snapshot 與 expiresAt。只建立 DRAFT，不會 Approve、Order、Receive 或調整庫存。" />
    </Modal>
  </Space>;
}
