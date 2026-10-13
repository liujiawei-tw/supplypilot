import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Form, Input, Select, Space } from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { listMaterials, type MaterialResponse } from '../../api/material';
import { listSuppliers, type SupplierResponse } from '../../api/supplier';
import type { CreatePurchaseOrderRequest, PurchaseOrderResponse, UpdatePurchaseOrderRequest } from '../../api/purchaseOrder';
import { toApiError } from '../../api/apiError';
import ErrorState from '../../components/ErrorState';
import { isNonNegativeDecimal, isPositiveDecimal } from './purchaseOrderUi';

interface ItemValues { materialId?: number; quantity?: string; unitPrice?: string; recommendationId?: number; supplierRecommendationResultId?: number; supplierOverrideReason?: string; quantityOverrideReason?: string; }
interface Values { supplierId?: number; expectedDeliveryDate?: string; clearExpectedDeliveryDate?: boolean; items?: ItemValues[]; }

export default function PurchaseOrderForm({ purchaseOrder, onReview, disabled }: {
  purchaseOrder?: PurchaseOrderResponse;
  onReview: (request: CreatePurchaseOrderRequest | UpdatePurchaseOrderRequest) => void;
  disabled?: boolean;
}) {
  const [form] = Form.useForm<Values>();
  const [suppliers, setSuppliers] = useState<SupplierResponse[]>([]);
  const [materials, setMaterials] = useState<MaterialResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<ReturnType<typeof toApiError> | null>(null);
  const editing = !!purchaseOrder;
  const hasRecommendationEvidence = !!purchaseOrder?.items.some((item) => item.recommendationId !== null);
  const initialValues = useMemo<Values>(() => purchaseOrder ? {
    supplierId: purchaseOrder.supplierId,
    expectedDeliveryDate: purchaseOrder.expectedDeliveryDate ?? undefined,
    clearExpectedDeliveryDate: false,
    items: purchaseOrder.items.map((item) => ({
      materialId: item.materialId, quantity: String(item.quantity), unitPrice: String(item.unitPrice),
      recommendationId: item.recommendationId ?? undefined,
      supplierRecommendationResultId: item.supplierRecommendationResultId ?? undefined,
      supplierOverrideReason: item.supplierOverrideReason ?? undefined,
      quantityOverrideReason: item.quantityOverrideReason ?? undefined,
    })),
  } : { items: [{}] }, [purchaseOrder]);

  const loadOptions = async () => {
    setLoading(true); setLoadError(null);
    try {
      const [supplierPage, materialPage] = await Promise.all([
        listSuppliers({ status: 'ACTIVE', page: 0, size: 100, sort: 'code,asc' }),
        listMaterials({ status: 'ACTIVE', page: 0, size: 100, sort: 'code,asc' }),
      ]);
      setSuppliers(supplierPage.content); setMaterials(materialPage.content);
    } catch (error) { setLoadError(toApiError(error)); }
    finally { setLoading(false); }
  };
  useEffect(() => { const timer = window.setTimeout(() => void loadOptions(), 0); return () => window.clearTimeout(timer); }, []);

  const review = async () => {
    let values: Values;
    try { values = await form.validateFields(); } catch { return; }
    const rows = values.items ?? [];
    if (new Set(rows.map((row) => row.materialId)).size !== rows.length) {
      form.setFields([{ name: 'items', errors: ['Material 不可重複'] }]); return;
    }
    const items = rows.map((row) => ({
      materialId: row.materialId!, quantity: row.quantity!.trim(), unitPrice: row.unitPrice!.trim(),
      ...(row.recommendationId ? { recommendationId: row.recommendationId } : {}),
      ...(row.supplierRecommendationResultId ? { supplierRecommendationResultId: row.supplierRecommendationResultId } : {}),
      ...(row.supplierOverrideReason ? { supplierOverrideReason: row.supplierOverrideReason.trim() } : {}),
      ...(row.quantityOverrideReason ? { quantityOverrideReason: row.quantityOverrideReason.trim() } : {}),
    }));
    if (!editing) {
      onReview({ supplierId: values.supplierId!, ...(values.expectedDeliveryDate ? { expectedDeliveryDate: values.expectedDeliveryDate } : {}), items });
      return;
    }
    const request: UpdatePurchaseOrderRequest = { items };
    if (values.supplierId !== purchaseOrder.supplierId) request.supplierId = values.supplierId;
    if (values.clearExpectedDeliveryDate) request.expectedDeliveryDate = null;
    else if ((values.expectedDeliveryDate ?? null) !== purchaseOrder.expectedDeliveryDate) request.expectedDeliveryDate = values.expectedDeliveryDate ?? null;
    onReview(request);
  };

  if (loadError) return <ErrorState title="Master data 載入失敗" error={loadError} onRetry={() => void loadOptions()} />;
  return <Form form={form} layout="vertical" initialValues={initialValues} disabled={disabled || loading}>
    <Form.Item name="supplierId" label="Supplier" rules={[{ required: true, message: 'Supplier is required' }]}>
      <Select showSearch optionFilterProp="label" aria-label="PO supplier" disabled={hasRecommendationEvidence} options={suppliers.map((supplier) => ({ value: supplier.id, label: `${supplier.code} — ${supplier.name}` }))} />
    </Form.Item>
    <Space align="start" style={{ width: '100%' }}>
      <Form.Item name="expectedDeliveryDate" label="Expected Delivery Date (DRAFT 可空)"><Input type="date" aria-label="PO expected delivery date" /></Form.Item>
      {editing && <Form.Item name="clearExpectedDeliveryDate" valuePropName="checked" label=" "><Checkbox aria-label="Clear expected delivery date">清除交期</Checkbox></Form.Item>}
    </Space>
    <Form.List name="items" rules={[{ validator: async (_, rows) => { if (!rows?.length) throw new Error('至少需要一筆 Item'); } }]}>
      {(fields, { add, remove }, { errors }) => <Space orientation="vertical" style={{ width: '100%' }}>
        {fields.map((field, index) => <Space key={field.key} align="start" wrap>
          <Form.Item {...field} name={[field.name, 'materialId']} label={`Item ${index + 1} Material`} rules={[{ required: true, message: 'Material is required' }]}>
            <Select style={{ width: 240 }} showSearch optionFilterProp="label" aria-label={`PO item ${index + 1} material`} options={materials.map((material) => ({ value: material.id, label: `${material.code} — ${material.name} (${material.baseUnit})` }))} />
          </Form.Item>
          <Form.Item {...field} name={[field.name, 'quantity']} label="Quantity" rules={[{ validator: async (_, value) => { if (!isPositiveDecimal(value)) throw new Error('Enter a positive decimal'); } }]}><Input inputMode="decimal" aria-label={`PO item ${index + 1} quantity`} /></Form.Item>
          <Form.Item {...field} name={[field.name, 'unitPrice']} label="Unit Price" rules={[{ validator: async (_, value) => { if (!isNonNegativeDecimal(value)) throw new Error('Enter a non-negative decimal'); } }]}><Input inputMode="decimal" aria-label={`PO item ${index + 1} unit price`} /></Form.Item>
          {fields.length > 1 && <Button aria-label={`Remove PO item ${index + 1}`} icon={<MinusCircleOutlined />} onClick={() => remove(field.name)} />}
          <Form.Item {...field} name={[field.name, 'recommendationId']} hidden><Input /></Form.Item>
          <Form.Item {...field} name={[field.name, 'supplierRecommendationResultId']} hidden><Input /></Form.Item>
          <Form.Item {...field} name={[field.name, 'supplierOverrideReason']} hidden><Input /></Form.Item>
          <Form.Item {...field} name={[field.name, 'quantityOverrideReason']} hidden><Input /></Form.Item>
        </Space>)}
        <Form.ErrorList errors={errors} />
        <Button icon={<PlusOutlined />} onClick={() => add()}>Add Item</Button>
      </Space>}
    </Form.List>
    {editing && <Alert style={{ marginTop: 16 }} type="info" showIcon title={hasRecommendationEvidence ? '此 DRAFT 含 Recommendation 證據，因此 Supplier 已鎖定；儲存完整 Items 時會原樣保留所有 Reference 與 Override Reason。' : '儲存時會送出完整 Items 清單。'} />}
    <Button style={{ marginTop: 16 }} type="primary" onClick={() => void review()}>Review {editing ? 'Draft Changes' : 'New Draft'}</Button>
  </Form>;
}
