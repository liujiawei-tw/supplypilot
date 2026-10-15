import { useMemo, useRef, useState } from 'react';
import { Alert, Button, Card, Descriptions, Form, Input, Modal, Select, Space, Typography } from 'antd';
import { createPurchaseOrderDraft, type CreatePurchaseOrderRequest, type PurchaseOrderDraftResponse } from '../../api/purchaseOrder';
import type { DecimalValue, RecommendationResponse, SupplierRecommendationResultResponse } from '../../api/recommendation';
import { isUnknownMutationResult, toApiError } from '../../api/apiError';
import { useAuth } from '../../auth/useAuth';
import { can } from '../../auth/permissions';
import ErrorState from '../ErrorState';
import type { ApiErrorResponse } from '../../types/api';
import { displayLabel } from '../../utils/displayLabels';

interface DraftValues {
  supplierResultId: number;
  quantity: string;
  expectedDeliveryDate?: string;
  supplierOverrideReason?: string;
  quantityOverrideReason?: string;
}

interface PendingDraft {
  request: CreatePurchaseOrderRequest;
  supplier: SupplierRecommendationResultResponse;
  quantity: string;
  unitPrice: string;
  supplierOverrideReason?: string;
  quantityOverrideReason?: string;
}

const plainDecimal = /^\d+(?:\.\d+)?$/;

function decimalText(value: DecimalValue): string {
  return String(value);
}

function normalizedDecimal(value: DecimalValue | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  const raw = String(value).trim();
  if (!plainDecimal.test(raw)) return null;
  const [integer, fraction = ''] = raw.split('.');
  const normalizedInteger = integer.replace(/^0+(?=\d)/, '');
  const normalizedFraction = fraction.replace(/0+$/, '');
  return normalizedFraction ? `${normalizedInteger}.${normalizedFraction}` : normalizedInteger;
}

function isPositiveDecimal(value: string | undefined): boolean {
  const normalized = normalizedDecimal(value);
  return normalized !== null && normalized !== '0';
}

function decimalEquals(left: DecimalValue | null | undefined, right: DecimalValue | null | undefined): boolean {
  const normalizedLeft = normalizedDecimal(left);
  const normalizedRight = normalizedDecimal(right);
  return normalizedLeft !== null && normalizedRight !== null && normalizedLeft === normalizedRight;
}

function unknownOutcome(error: unknown): boolean {
  return isUnknownMutationResult(error);
}

export default function RecommendationDraftForm({
  recommendation,
  ranking,
}: {
  recommendation: RecommendationResponse;
  ranking: SupplierRecommendationResultResponse[];
}) {
  const { user } = useAuth();
  const [form] = Form.useForm<DraftValues>();
  const [pending, setPending] = useState<PendingDraft | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [resultUnknown, setResultUnknown] = useState(false);
  const [created, setCreated] = useState<PurchaseOrderDraftResponse | null>(null);
  const submittingRef = useRef(false);
  const resultUnknownRef = useRef(false);

  const canCreate = !!user && can(user.role, 'purchaseOrderDraft');
  const candidates = useMemo(() => ranking.filter((row) =>
    row.qualified && Number.isSafeInteger(row.id) && row.id > 0
      && row.recommendedQuantity !== null && row.unitPrice !== null), [ranking]);
  const defaultCandidate = candidates.find((row) => row.supplierId === recommendation.recommendation.supplierId)
    ?? candidates[0];
  const selectedResultId = Form.useWatch('supplierResultId', form) ?? defaultCandidate?.id;
  const quantity = Form.useWatch('quantity', form) ?? (defaultCandidate?.recommendedQuantity === null || defaultCandidate?.recommendedQuantity === undefined
    ? undefined : decimalText(defaultCandidate.recommendedQuantity));
  const selected = candidates.find((row) => row.id === selectedResultId) ?? defaultCandidate;
  const supplierOverride = !!selected && selected.supplierId !== recommendation.recommendation.supplierId;
  const quantityOverride = !!selected && !decimalEquals(quantity, recommendation.recommendation.quantity);

  if (!canCreate || recommendation.outcome !== 'SUPPLIER_RECOMMENDED') return null;

  if (!defaultCandidate) {
    return <Alert type="warning" showIcon title="無法建立 Linked PO Draft"
      description="Supplier Ranking 沒有具備完整 Result ID、推薦數量與單價的合格結果，因此不會送出建立請求。" />;
  }

  const openConfirmation = async () => {
    if (resultUnknownRef.current) return;
    let values: DraftValues;
    try { values = await form.validateFields(); } catch { return; }
    const supplier = candidates.find((row) => row.id === values.supplierResultId);
    if (!supplier || supplier.unitPrice === null || supplier.recommendedQuantity === null) return;
    const trimmedQuantity = values.quantity.trim();
    const request: CreatePurchaseOrderRequest = {
      supplierId: supplier.supplierId,
      ...(values.expectedDeliveryDate ? { expectedDeliveryDate: values.expectedDeliveryDate } : {}),
      items: [{
        materialId: recommendation.material.id,
        quantity: trimmedQuantity,
        unitPrice: decimalText(supplier.unitPrice),
        recommendationId: recommendation.id,
        supplierRecommendationResultId: supplier.id,
        ...(supplierOverride ? { supplierOverrideReason: values.supplierOverrideReason?.trim() } : {}),
        ...(quantityOverride ? { quantityOverrideReason: values.quantityOverrideReason?.trim() } : {}),
      }],
    };
    setError(null);
    setPending({
      request,
      supplier,
      quantity: trimmedQuantity,
      unitPrice: decimalText(supplier.unitPrice),
      supplierOverrideReason: supplierOverride ? values.supplierOverrideReason?.trim() : undefined,
      quantityOverrideReason: quantityOverride ? values.quantityOverrideReason?.trim() : undefined,
    });
  };

  const confirmCreate = async () => {
    if (!pending || submittingRef.current || resultUnknownRef.current) return;
    submittingRef.current = true;
    setSubmitting(true);
    setError(null);
    setResultUnknown(false);
    try {
      const response = await createPurchaseOrderDraft(pending.request);
      setCreated(response);
      setPending(null);
    } catch (requestError) {
      const isResultUnknown = unknownOutcome(requestError);
      if (isResultUnknown) resultUnknownRef.current = true;
      setResultUnknown(isResultUnknown);
      setError(toApiError(requestError));
      setPending(null);
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  return <>
    <Card title="建立關聯採購單草稿" extra={<Typography.Text type="secondary">僅建立草稿，不會核准或下單</Typography.Text>}>
      {created && <Alert type="success" showIcon title={`採購單草稿 #${created.id} 已建立`}
        description={<span>後端狀態：{displayLabel(created.status)}；採購單編號：{created.poNumber}。採購單明細與後續核准／下單／收貨流程屬於 SP-805。</span>} />}
      {error && <ErrorState title={resultUnknown ? '建立結果不明，請先人工查核' : '採購單草稿建立失敗'}
        message={resultUnknown ? '連線逾時或中斷，後端可能已完成建立。本頁已鎖定重新送出；請先人工查核採購單資料，確認結果後再重新載入頁面。' : undefined}
        error={error} />}
      {!created && <Form form={form} layout="vertical" initialValues={{
        supplierResultId: defaultCandidate.id,
        quantity: decimalText(defaultCandidate.recommendedQuantity!),
      }}>
        <Form.Item name="supplierResultId" label="合格供應商結果" rules={[{ required: true, message: '請選擇供應商結果' }]}>
          <Select aria-label="合格供應商結果" onChange={(id: number) => {
            const candidate = candidates.find((row) => row.id === id);
            if (candidate?.recommendedQuantity !== null && candidate?.recommendedQuantity !== undefined) {
              form.setFieldValue('quantity', decimalText(candidate.recommendedQuantity));
            }
          }} options={candidates.map((row) => ({
            value: row.id,
            label: `${row.supplierCode} — ${row.supplierName} (Result #${row.id})`,
          }))} />
        </Form.Item>
        <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }} items={selected ? [
          { key: 'recommendation', label: '採購建議編號', children: recommendation.id },
          { key: 'result', label: '後端結果參照編號', children: selected.id },
          { key: 'supplier', label: '供應商', children: `${selected.supplierName} (#${selected.supplierId})` },
          { key: 'material', label: '物料', children: `${recommendation.material.code} — ${recommendation.material.name} (#${recommendation.material.id})` },
          { key: 'price', label: '單價快照', children: `${decimalText(selected.unitPrice!)} TWD` },
          { key: 'recommendedQuantity', label: '供應商建議數量', children: `${decimalText(selected.recommendedQuantity!)} ${recommendation.material.unit}` },
        ] : []} />
        <Form.Item name="quantity" label={`採購數量（${recommendation.material.unit}）`} rules={[
          { required: true, message: '請輸入採購數量' },
          { validator: (_, value: string | undefined) => isPositiveDecimal(value) ? Promise.resolve() : Promise.reject(new Error('請輸入大於 0 的數量')) },
        ]} style={{ marginTop: 16 }}>
          <Input aria-label="採購數量" inputMode="decimal" />
        </Form.Item>
        {supplierOverride && <Form.Item name="supplierOverrideReason" label="供應商覆寫原因" rules={[
          { required: true, whitespace: true, message: '請填寫供應商覆寫原因' },
          { max: 2000, message: '供應商覆寫原因不可超過 2,000 個字元' },
        ]}><Input.TextArea aria-label="供應商覆寫原因" rows={2} /></Form.Item>}
        {quantityOverride && <Form.Item name="quantityOverrideReason" label="數量覆寫原因" rules={[
          { required: true, whitespace: true, message: '請填寫數量覆寫原因' },
          { max: 2000, message: '數量覆寫原因不可超過 2,000 個字元' },
        ]}><Input.TextArea aria-label="數量覆寫原因" rows={2} /></Form.Item>}
        <Form.Item name="expectedDeliveryDate" label="預計交期（建立草稿時可留空）">
          <Input aria-label="預計交期" type="date" />
        </Form.Item>
        <Button type="primary" disabled={resultUnknown} onClick={() => void openConfirmation()}>檢視採購單草稿</Button>
      </Form>}
    </Card>
    <Modal title="確認建立採購單草稿" open={pending !== null} confirmLoading={submitting}
      okText="確認建立草稿" cancelText="返回編輯" onOk={() => void confirmCreate()}
      onCancel={() => { if (!submittingRef.current) setPending(null); }} closable={!submitting} mask={{ closable: !submitting }}>
      {pending && <Space orientation="vertical" style={{ width: '100%' }}>
        <Alert type="warning" showIcon title="確認後才會建立一筆新草稿；不會自動核准、下單或收貨。" />
        <Descriptions bordered size="small" column={1} items={[
          { key: 'recommendation', label: '採購建議編號', children: recommendation.id },
          { key: 'result', label: '結果參照編號', children: pending.supplier.id },
          { key: 'supplier', label: '供應商', children: `${pending.supplier.supplierName} (#${pending.supplier.supplierId})` },
          { key: 'material', label: '物料', children: `${recommendation.material.code} — ${recommendation.material.name}` },
          { key: 'quantity', label: '數量', children: `${pending.quantity} ${recommendation.material.unit}` },
          { key: 'price', label: '單價', children: `${pending.unitPrice} TWD` },
          { key: 'delivery', label: 'Expected Delivery Date', children: pending.request.expectedDeliveryDate ?? 'Not set (allowed for DRAFT)' },
          { key: 'supplierReason', label: 'Supplier Override Reason', children: pending.supplierOverrideReason ?? 'N/A' },
          { key: 'quantityReason', label: 'Quantity Override Reason', children: pending.quantityOverrideReason ?? 'N/A' },
        ]} />
      </Space>}
    </Modal>
  </>;
}
