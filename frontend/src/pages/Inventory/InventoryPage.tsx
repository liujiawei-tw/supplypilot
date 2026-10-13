import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Form, Input, InputNumber, message, Modal, Space, Table, Tabs, Typography } from 'antd';
import type { TableProps } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { adjustInventory, getInventory, listInventory, type InventoryResponse } from '../../api/inventory';
import { requiresMutationVerification, toApiError } from '../../api/apiError';
import { useAuth } from '../../auth/useAuth';
import { can } from '../../auth/permissions';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import UnauthorizedPage from '../Unauthorized/UnauthorizedPage';
import StockTransactions from '../../components/StockTransactions';
import type { ApiErrorResponse, PageResponse } from '../../types/api';
import { getAdjustmentQuantityError, getAdjustmentReasonError } from './inventoryValidation';

type InventoryTab = 'stock-levels' | 'stock-transactions';

interface AdjustmentValues {
  quantity?: string;
  reason?: string;
}

function formatTimestamp(value: string): string {
  return new Intl.DateTimeFormat('zh-TW', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

function StockLevels() {
  const { user } = useAuth();
  const canAdjust = user?.role === 'WAREHOUSE_STAFF' || user?.role === 'ADMIN';
  const [form] = Form.useForm<AdjustmentValues>();
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<InventoryResponse> | null>(null);
  const [listError, setListError] = useState<ApiErrorResponse | null>(null);
  const [detail, setDetail] = useState<InventoryResponse | null>(null);
  const [detailError, setDetailError] = useState<ApiErrorResponse | null>(null);
  const [adjusting, setAdjusting] = useState<InventoryResponse | null>(null);
  const [adjustmentError, setAdjustmentError] = useState<ApiErrorResponse | null>(null);
  const [unknownResult, setUnknownResult] = useState(false);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [query, setQuery] = useState({ page: 0, size: 20, materialId: undefined as number | undefined, materialCode: '' });
  const requestId = useRef(0);
  const detailRequestId = useRef(0);

  const load = useCallback(async () => {
    const current = ++requestId.current;
    setLoading(true);
    setListError(null);
    try {
      const response = await listInventory({ ...query, materialCode: query.materialCode.trim() || undefined });
      if (current === requestId.current) setData(response);
    } catch (error) {
      if (current === requestId.current) setListError(toApiError(error));
    } finally {
      if (current === requestId.current) setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  const openDetail = async (materialId: number) => {
    const current = ++detailRequestId.current;
    setDetail(null);
    setDetailError(null);
    setDetailLoading(true);
    try {
      const response = await getInventory(materialId);
      if (current === detailRequestId.current) setDetail(response);
    } catch (error) {
      if (current === detailRequestId.current) setDetailError(toApiError(error));
    } finally {
      if (current === detailRequestId.current) setDetailLoading(false);
    }
  };

  const openAdjustment = (inventory: InventoryResponse) => {
    setAdjusting(inventory);
    setAdjustmentError(null);
    setUnknownResult(false);
    form.resetFields();
  };

  const submitAdjustment = async () => {
    if (!adjusting || submitting) return;
    let values: AdjustmentValues;
    try { values = await form.validateFields(); } catch { return; }
    setSubmitting(true);
    setAdjustmentError(null);
    setUnknownResult(false);
    try {
      await adjustInventory(adjusting.materialId, { quantity: values.quantity!, reason: values.reason!.trim() });
      apiMessage.success('Inventory adjustment 已完成');
      setAdjusting(null);
      form.resetFields();
      await load();
    } catch (error) {
      setAdjustmentError(toApiError(error));
      setUnknownResult(requiresMutationVerification(error));
    } finally {
      setSubmitting(false);
    }
  };

  const verifyAdjustmentResult = async () => {
    if (!adjusting) return;
    setAdjustmentError(null);
    try {
      const latest = await getInventory(adjusting.materialId);
      setAdjusting(latest);
      setUnknownResult(false);
      await load();
      apiMessage.info('已重新查詢目前庫存，請確認後再決定是否重新送出');
    } catch (error) {
      setAdjustmentError(toApiError(error));
    }
  };

  const columns: TableProps<InventoryResponse>['columns'] = [
    { title: 'Material ID', dataIndex: 'materialId' },
    { title: 'Material Code', dataIndex: 'materialCode' },
    { title: 'Material Name', dataIndex: 'materialName' },
    { title: 'Quantity', dataIndex: 'quantity' },
    { title: 'Unit', dataIndex: 'unit' },
    { title: 'Updated At', dataIndex: 'updatedAt', render: formatTimestamp },
    { title: 'Actions', key: 'actions', render: (_value, inventory) => <Space>
      <Button size="small" onClick={() => void openDetail(inventory.materialId)}>Detail</Button>
      {canAdjust && <Button size="small" type="primary" onClick={() => openAdjustment(inventory)}>Adjust</Button>}
    </Space> },
  ];
  const filtered = query.materialId !== undefined || !!query.materialCode;

  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}
    <Space wrap>
      <InputNumber aria-label="Inventory material ID filter" placeholder="Material ID" min={1} precision={0} value={query.materialId} onChange={(materialId) => setQuery((current) => ({ ...current, materialId: materialId ?? undefined, page: 0 }))} />
      <Input aria-label="Inventory material code filter" placeholder="Material Code" allowClear value={query.materialCode} onChange={(event) => setQuery((current) => ({ ...current, materialCode: event.target.value, page: 0 }))} />
    </Space>
    {listError && <ErrorState title="Inventory request failed" error={listError} onRetry={() => void load()} />}
    {loading && !data ? <LoadingState tip="Loading inventory..." /> : <Table<InventoryResponse>
      rowKey="materialId" columns={columns} dataSource={data?.content ?? []} loading={loading}
      locale={{ emptyText: <EmptyState simple description={filtered ? '沒有符合查詢條件的 Inventory' : '尚無 Inventory 資料'} /> }}
      pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }}
      onChange={(pagination) => setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20 }))}
    />}
    <Drawer title={detail ? `${detail.materialCode} Inventory` : 'Inventory Detail'} open={detailLoading || !!detail || !!detailError} onClose={() => { detailRequestId.current += 1; setDetail(null); setDetailError(null); setDetailLoading(false); }} size="large">
      {detailLoading && <LoadingState tip="Loading inventory detail..." />}
      {detailError && <ErrorState title="Inventory detail failed" error={detailError} />}
      {detail && <Descriptions column={1} bordered size="small" items={[
        { key: 'id', label: 'Material ID', children: detail.materialId },
        { key: 'code', label: 'Material Code', children: detail.materialCode },
        { key: 'name', label: 'Material Name', children: detail.materialName },
        { key: 'quantity', label: 'Quantity', children: detail.quantity },
        { key: 'unit', label: 'Unit', children: detail.unit },
        { key: 'updatedAt', label: 'Updated At', children: formatTimestamp(detail.updatedAt) },
      ]} />}
    </Drawer>
    <Modal title="Manual Inventory Adjustment" open={!!adjusting} onCancel={() => { if (!submitting) setAdjusting(null); }} onOk={() => void submitAdjustment()} okText="Confirm Adjustment" confirmLoading={submitting} okButtonProps={{ disabled: submitting }} destroyOnHidden>
      {adjusting && <>
        <Descriptions column={1} size="small" bordered items={[
          { key: 'material', label: 'Material', children: `${adjusting.materialCode} — ${adjusting.materialName}` },
          { key: 'current', label: 'Current Quantity', children: `${adjusting.quantity} ${adjusting.unit}` },
        ]} />
        <Alert style={{ marginTop: 16 }} type="info" showIcon title="正數增加庫存，負數減少庫存；輸入的是異動量，不是調整後總量。" />
        {unknownResult && <Alert style={{ marginTop: 16 }} type="warning" showIcon title="操作結果待確認" description={<Space orientation="vertical"><span>請先重新查詢庫存確認實際結果，再決定是否重新送出。本系統不會自動重送。</span><Button size="small" onClick={() => void verifyAdjustmentResult()}>重新查詢庫存</Button></Space>} />}
        {adjustmentError && <ErrorState title="Inventory adjustment failed" error={adjustmentError} />}
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="quantity" label="Change Quantity" rules={[
            { required: true, message: 'Quantity is required' },
            { validator: async (_rule, value: string | undefined) => {
              const error = getAdjustmentQuantityError(value);
              if (error) throw new Error(error);
            } },
          ]}><InputNumber<string> aria-label="Adjustment quantity" stringMode style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="reason" label="Reason" rules={[{ validator: async (_rule, value: string | undefined) => {
            const error = getAdjustmentReasonError(value);
            if (error) throw new Error(error);
          } }]}><Input.TextArea aria-label="Adjustment reason" maxLength={500} showCount rows={3} /></Form.Item>
        </Form>
      </>}
    </Modal>
  </Space>;
}

export default function InventoryPage() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const allowedTabs: InventoryTab[] = [
    ...(user && can(user.role, 'inventoryLevels') ? ['stock-levels' as const] : []),
    ...(user && can(user.role, 'stockTransactions') ? ['stock-transactions' as const] : []),
  ];
  const requested = params.get('tab');
  const activeKey: InventoryTab | undefined = allowedTabs.includes(requested as InventoryTab) ? requested as InventoryTab : allowedTabs[0];

  useEffect(() => {
    if (activeKey && requested !== activeKey) {
      setParams((current) => {
        const next = new URLSearchParams(current);
        next.set('tab', activeKey);
        return next;
      }, { replace: true });
    }
  }, [activeKey, requested, setParams]);

  if (!user) return null;
  if (!activeKey) return <UnauthorizedPage />;
  const items = allowedTabs.map((key) => ({
    key,
    label: key === 'stock-levels' ? 'Stock Levels' : 'Stock Transactions',
    children: key === activeKey
      ? key === 'stock-levels'
        ? <StockLevels />
        : <StockTransactions />
      : null,
  }));

  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    <div><Typography.Title level={2} style={{ marginBottom: 0 }}>Inventory</Typography.Title><Typography.Text type="secondary">庫存水位與異動查詢</Typography.Text></div>
    <Tabs items={items} activeKey={activeKey} onChange={(key) => {
      const next = new URLSearchParams(params);
      next.set('tab', key);
      setParams(next);
    }} />
  </Space>;
}
