import { useMemo } from 'react';
import { Alert, Button, Checkbox, Form, Input, Space } from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import type { CreatePurchaseOrderRequest, PurchaseOrderResponse, UpdatePurchaseOrderRequest } from '../../api/purchaseOrder';
import RelatedEntitySelect from '../../components/RelatedEntitySelect';
import { isNonNegativeDecimal, isPositiveDecimal } from './purchaseOrderUi';

interface ItemValues { materialId?: number; quantity?: string; unitPrice?: string; }
interface Values { supplierId?: number; expectedDeliveryDate?: string; clearExpectedDeliveryDate?: boolean; items?: ItemValues[]; }

export default function PurchaseOrderForm({ purchaseOrder, onReview, disabled }: {
  purchaseOrder?: PurchaseOrderResponse;
  onReview: (request: CreatePurchaseOrderRequest | UpdatePurchaseOrderRequest) => void;
  disabled?: boolean;
}) {
  const [form] = Form.useForm<Values>();
  const editing = !!purchaseOrder;
  const initialValues = useMemo<Values>(() => purchaseOrder ? {
    supplierId: purchaseOrder.supplierId,
    expectedDeliveryDate: purchaseOrder.expectedDeliveryDate ?? undefined,
    clearExpectedDeliveryDate: false,
    items: purchaseOrder.items.map((item) => ({
      materialId: item.materialId, quantity: String(item.quantity), unitPrice: String(item.unitPrice),
    })),
  } : { items: [{}] }, [purchaseOrder]);

  const review = async () => {
    let values: Values;
    try { values = await form.validateFields(); } catch { return; }
    const rows = values.items ?? [];
    if (new Set(rows.map((row) => row.materialId)).size !== rows.length) {
      form.setFields([{ name: 'items', errors: ['物料不可重複'] }]); return;
    }
    const items = rows.map((row) => ({
      materialId: row.materialId!, quantity: row.quantity!.trim(), unitPrice: row.unitPrice!.trim(),
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

  return <Form form={form} layout="vertical" initialValues={initialValues} disabled={disabled}>
    <Form.Item name="supplierId" label="供應商" rules={[{ required: true, message: '請選擇供應商' }]}>
      <RelatedEntitySelect entity="supplier" />
    </Form.Item>
    <Space align="start" style={{ width: '100%' }}>
      <Form.Item name="expectedDeliveryDate" label="預計交期（草稿可留空）"><Input type="date" aria-label="採購單預計交期" /></Form.Item>
      {editing && <Form.Item name="clearExpectedDeliveryDate" valuePropName="checked" label=" "><Checkbox aria-label="清除預計交期">清除交期</Checkbox></Form.Item>}
    </Space>
    <Form.List name="items" rules={[{ validator: async (_, rows) => { if (!rows?.length) throw new Error('至少需要一筆 Item'); } }]}>
      {(fields, { add, remove }, { errors }) => <Space orientation="vertical" style={{ width: '100%' }}>
        {fields.map((field, index) => {
          const { key, ...fieldProps } = field;
          return <Space key={key} align="start" wrap>
          <Form.Item {...fieldProps} name={[field.name, 'materialId']} label={`品項 ${index + 1} 物料`} rules={[{ required: true, message: '請選擇物料' }]}>
            <RelatedEntitySelect entity="material" showMaterialUnit />
          </Form.Item>
          <Form.Item {...fieldProps} name={[field.name, 'quantity']} label="數量" rules={[{ validator: async (_, value) => { if (!isPositiveDecimal(value)) throw new Error('請輸入大於 0 的數字'); } }]}><Input inputMode="decimal" aria-label={`採購單品項 ${index + 1} 數量`} /></Form.Item>
          <Form.Item {...fieldProps} name={[field.name, 'unitPrice']} label="單價" rules={[{ validator: async (_, value) => { if (!isNonNegativeDecimal(value)) throw new Error('請輸入大於或等於 0 的數字'); } }]}><Input inputMode="decimal" aria-label={`採購單品項 ${index + 1} 單價`} /></Form.Item>
          {fields.length > 1 && <Button aria-label={`移除採購單品項 ${index + 1}`} icon={<MinusCircleOutlined />} onClick={() => remove(field.name)} />}
        </Space>;
        })}
        <Form.ErrorList errors={errors} />
        <Button icon={<PlusOutlined />} onClick={() => add()}>新增品項</Button>
      </Space>}
    </Form.List>
    {editing && <Alert style={{ marginTop: 16 }} type="info" showIcon title="儲存時會送出完整品項清單；既有採購決策來源紀錄由後端保留。" />}
    <Button style={{ marginTop: 16 }} type="primary" onClick={() => void review()}>{editing ? '檢視草稿修改' : '檢視新草稿'}</Button>
  </Form>;
}
