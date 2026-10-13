import { useMemo, useRef, useState } from 'react';
import { Alert, Button, Card, Descriptions, Form, Input, Modal, Select, Space, Typography } from 'antd';
import { createPurchaseOrderDraft, type CreatePurchaseOrderRequest, type PurchaseOrderDraftResponse } from '../../api/purchaseOrder';
import type { DecimalValue, RecommendationResponse, SupplierRecommendationResultResponse } from '../../api/recommendation';
import { isUnknownMutationResult, toApiError } from '../../api/apiError';
import { useAuth } from '../../auth/useAuth';
import { can } from '../../auth/permissions';
import ErrorState from '../ErrorState';
import type { ApiErrorResponse } from '../../types/api';

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
    <Card title="Create Linked PO Draft" extra={<Typography.Text type="secondary">SP-804 scope: create DRAFT only</Typography.Text>}>
      {created && <Alert type="success" showIcon title={`PO DRAFT #${created.id} 已建立`}
        description={<span>Backend 狀態：{created.status}；PO Number：{created.poNumber}。PO Detail 與後續 Approve／Order／Receive 屬於 SP-805。</span>} />}
      {error && <ErrorState title={resultUnknown ? '建立結果不明，請先人工查核' : 'PO Draft 建立失敗'}
        message={resultUnknown ? '連線逾時或中斷，Backend 可能已完成建立。本頁已鎖定重新送出；請先人工查核 PO 資料，確認結果後再重新載入頁面。' : undefined}
        error={error} />}
      {!created && <Form form={form} layout="vertical" initialValues={{
        supplierResultId: defaultCandidate.id,
        quantity: decimalText(defaultCandidate.recommendedQuantity!),
      }}>
        <Form.Item name="supplierResultId" label="Qualified Supplier Result" rules={[{ required: true, message: 'Supplier Result is required' }]}>
          <Select aria-label="Qualified supplier result" onChange={(id: number) => {
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
          { key: 'recommendation', label: 'Recommendation ID', children: recommendation.id },
          { key: 'result', label: 'Backend Result Reference', children: selected.id },
          { key: 'supplier', label: 'Supplier', children: `${selected.supplierName} (#${selected.supplierId})` },
          { key: 'material', label: 'Material', children: `${recommendation.material.code} — ${recommendation.material.name} (#${recommendation.material.id})` },
          { key: 'price', label: 'Unit Price Snapshot', children: `${decimalText(selected.unitPrice!)} TWD` },
          { key: 'recommendedQuantity', label: 'Supplier Recommended Quantity', children: `${decimalText(selected.recommendedQuantity!)} ${recommendation.material.unit}` },
        ] : []} />
        <Form.Item name="quantity" label={`Purchase Quantity (${recommendation.material.unit})`} rules={[
          { required: true, message: 'Quantity is required' },
          { validator: (_, value: string | undefined) => isPositiveDecimal(value) ? Promise.resolve() : Promise.reject(new Error('Enter a positive decimal quantity')) },
        ]} style={{ marginTop: 16 }}>
          <Input aria-label="Purchase quantity" inputMode="decimal" />
        </Form.Item>
        {supplierOverride && <Form.Item name="supplierOverrideReason" label="Supplier Override Reason" rules={[
          { required: true, whitespace: true, message: 'Supplier override reason is required' },
          { max: 2000, message: 'Supplier override reason must not exceed 2000 characters' },
        ]}><Input.TextArea aria-label="Supplier override reason" rows={2} /></Form.Item>}
        {quantityOverride && <Form.Item name="quantityOverrideReason" label="Quantity Override Reason" rules={[
          { required: true, whitespace: true, message: 'Quantity override reason is required' },
          { max: 2000, message: 'Quantity override reason must not exceed 2000 characters' },
        ]}><Input.TextArea aria-label="Quantity override reason" rows={2} /></Form.Item>}
        <Form.Item name="expectedDeliveryDate" label="Expected Delivery Date (optional for DRAFT)">
          <Input aria-label="Expected delivery date" type="date" />
        </Form.Item>
        <Button type="primary" disabled={resultUnknown} onClick={() => void openConfirmation()}>Review PO Draft</Button>
      </Form>}
    </Card>
    <Modal title="Confirm Create PO DRAFT" open={pending !== null} confirmLoading={submitting}
      okText="Confirm Create DRAFT" cancelText="Back to edit" onOk={() => void confirmCreate()}
      onCancel={() => { if (!submittingRef.current) setPending(null); }} closable={!submitting} mask={{ closable: !submitting }}>
      {pending && <Space orientation="vertical" style={{ width: '100%' }}>
        <Alert type="warning" showIcon title="確認後才會建立一筆新的 DRAFT；不會自動 Approve、Order 或 Receive。" />
        <Descriptions bordered size="small" column={1} items={[
          { key: 'recommendation', label: 'Recommendation ID', children: recommendation.id },
          { key: 'result', label: 'Result Reference', children: pending.supplier.id },
          { key: 'supplier', label: 'Supplier', children: `${pending.supplier.supplierName} (#${pending.supplier.supplierId})` },
          { key: 'material', label: 'Material', children: `${recommendation.material.code} — ${recommendation.material.name}` },
          { key: 'quantity', label: 'Quantity', children: `${pending.quantity} ${recommendation.material.unit}` },
          { key: 'price', label: 'Unit Price', children: `${pending.unitPrice} TWD` },
          { key: 'delivery', label: 'Expected Delivery Date', children: pending.request.expectedDeliveryDate ?? 'Not set (allowed for DRAFT)' },
          { key: 'supplierReason', label: 'Supplier Override Reason', children: pending.supplierOverrideReason ?? 'N/A' },
          { key: 'quantityReason', label: 'Quantity Override Reason', children: pending.quantityOverrideReason ?? 'N/A' },
        ]} />
      </Space>}
    </Modal>
  </>;
}
