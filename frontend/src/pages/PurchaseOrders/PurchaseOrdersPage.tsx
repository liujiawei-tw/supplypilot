import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd';
import type { TableProps } from 'antd';
import { Link } from 'react-router-dom';
import { createPurchaseOrderDraft, listPurchaseOrders, type CreatePurchaseOrderRequest, type PurchaseOrderQuery, type PurchaseOrderResponse, type UpdatePurchaseOrderRequest } from '../../api/purchaseOrder';
import { toApiError } from '../../api/apiError';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import PurchaseOrderForm from './PurchaseOrderForm';
import { formatTimestamp, isUnknownMutation, statusColors } from './purchaseOrderUi';
import type { ApiErrorResponse, PageResponse } from '../../types/api';

const statuses = ['DRAFT', 'APPROVED', 'ORDERED', 'RECEIVED', 'REJECTED'] as const;
const sortable = new Set(['id', 'poNumber', 'status', 'expectedDeliveryDate', 'createdAt', 'updatedAt', 'orderedAt']);

export default function PurchaseOrdersPage() {
  const { user } = useAuth();
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<PurchaseOrderResponse> | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState<PurchaseOrderQuery>({ page: 0, size: 20, sort: 'createdAt,desc' });
  const [createOpen, setCreateOpen] = useState(false);
  const [pending, setPending] = useState<CreatePurchaseOrderRequest | null>(null);
  const [createError, setCreateError] = useState<ApiErrorResponse | null>(null);
  const [createUnknown, setCreateUnknown] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const current = ++requestId.current; setLoading(true); setError(null);
    try { const result = await listPurchaseOrders(query); if (current === requestId.current) setData(result); }
    catch (requestError) { if (current === requestId.current) setError(toApiError(requestError)); }
    finally { if (current === requestId.current) setLoading(false); }
  }, [query]);
  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);

  const confirmCreate = async () => {
    if (!pending || submittingRef.current || createUnknown) return;
    submittingRef.current = true; setSubmitting(true); setCreateError(null);
    try {
      const created = await createPurchaseOrderDraft(pending);
      apiMessage.success(`PO DRAFT ${created.poNumber} 已建立`);
      setPending(null); setCreateOpen(false); await load();
    } catch (requestError) {
      const unknown = isUnknownMutation(requestError); setCreateUnknown(unknown); setCreateError(toApiError(requestError)); setPending(null);
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  const columns: TableProps<PurchaseOrderResponse>['columns'] = [
    { title: 'PO Number', dataIndex: 'poNumber', sorter: true },
    { title: 'Supplier', render: (_, row) => `${row.supplierCode} — ${row.supplierName}` },
    { title: 'Status', dataIndex: 'status', sorter: true, render: (value) => <Tag color={statusColors[value as PurchaseOrderResponse['status']]}>{value}</Tag> },
    { title: 'Expected Delivery', dataIndex: 'expectedDeliveryDate', sorter: true, render: (value, row) => <Space>{value ?? '—'}{row.isOverdue && <Tag color="red">OVERDUE</Tag>}</Space> },
    { title: 'Created At', dataIndex: 'createdAt', sorter: true, render: formatTimestamp },
    { title: 'Ordered At', dataIndex: 'orderedAt', sorter: true, render: formatTimestamp },
    { title: 'Action', render: (_, row) => <Link to={`/purchase-orders/${row.id}`}>Detail</Link> },
  ];

  const setFilter = (key: keyof PurchaseOrderQuery, value: unknown) => setQuery((current) => ({ ...current, [key]: value || undefined, page: 0 }));
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}
    <Space style={{ width: '100%', justifyContent: 'space-between' }}><div><Typography.Title level={2} style={{ marginBottom: 0 }}>Purchase Orders</Typography.Title><Typography.Text type="secondary">採購單生命週期與稽核</Typography.Text></div>{user && can(user.role, 'purchaseOrderCreate') && <Button type="primary" onClick={() => { setCreateOpen(true); setCreateError(null); }}>Create Manual DRAFT</Button>}</Space>
    <Space wrap>
      <Select allowClear placeholder="Status" style={{ width: 150 }} options={statuses.map((value) => ({ value }))} onChange={(value) => setFilter('status', value)} />
      <Input aria-label="PO supplier ID filter" placeholder="Supplier ID" style={{ width: 130 }} onChange={(event) => setFilter('supplierId', Number(event.target.value) || undefined)} />
      <Input aria-label="PO material ID filter" placeholder="Material ID" style={{ width: 130 }} onChange={(event) => setFilter('materialId', Number(event.target.value) || undefined)} />
      <Input aria-label="PO created from" type="date" onChange={(event) => setFilter('createdFrom', event.target.value)} />
      <Input aria-label="PO created to" type="date" onChange={(event) => setFilter('createdTo', event.target.value)} />
      <Input aria-label="PO delivery from" type="date" onChange={(event) => setFilter('expectedDeliveryFrom', event.target.value)} />
      <Input aria-label="PO delivery to" type="date" onChange={(event) => setFilter('expectedDeliveryTo', event.target.value)} />
      <Select allowClear placeholder="Overdue" style={{ width: 140 }} options={[{ value: true, label: 'Overdue only' }, { value: false, label: 'Exclude overdue' }]} onChange={(value) => setQuery((current) => ({ ...current, overdue: value, page: 0 }))} />
    </Space>
    {error && <ErrorState title="Purchase Orders 載入失敗" error={error} onRetry={() => void load()} />}
    <Table rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading}
      locale={{ emptyText: <EmptyState simple description="沒有符合條件的 Purchase Order" /> }}
      pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }}
      onChange={(pagination, _filters, sorter) => {
        const selected = Array.isArray(sorter) ? sorter[0] : sorter;
        const field = typeof selected?.field === 'string' && sortable.has(selected.field) ? selected.field : undefined;
        setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20, ...(field && selected.order ? { sort: `${field},${selected.order === 'ascend' ? 'asc' : 'desc'}` } : {}) }));
      }} />
    <Modal width={900} title="Create Manual PO DRAFT" open={createOpen} footer={null} destroyOnHidden onCancel={() => { if (!submittingRef.current) setCreateOpen(false); }}>
      {createUnknown && <Alert type="warning" showIcon title="建立結果不明，已鎖定再次送出" description="請由 PO 清單人工核實是否已建立；Create API 沒有可安全使用的唯一查核鍵，本頁不會自動重送。重新載入頁面後才可重新操作。" />}
      {createError && <ErrorState title={createUnknown ? 'PO DRAFT 建立結果待確認' : 'PO DRAFT 建立失敗'} error={createError} />}
      {!createUnknown && <PurchaseOrderForm disabled={submitting} onReview={(request: CreatePurchaseOrderRequest | UpdatePurchaseOrderRequest) => setPending(request as CreatePurchaseOrderRequest)} />}
    </Modal>
    <Modal title="Confirm Create Manual DRAFT" open={!!pending} okText="Confirm Create DRAFT" confirmLoading={submitting} okButtonProps={{ disabled: submitting }} onOk={() => void confirmCreate()} onCancel={() => { if (!submittingRef.current) setPending(null); }}>
      {pending && <><Alert type="warning" showIcon title="只會建立 DRAFT，不會自動核准或下單。" /><Descriptions style={{ marginTop: 16 }} bordered column={1} items={[
        { key: 'supplier', label: 'Supplier ID', children: pending.supplierId },
        { key: 'delivery', label: 'Expected Delivery', children: pending.expectedDeliveryDate ?? '未設定' },
        { key: 'items', label: 'Items', children: pending.items.map((item) => `Material #${item.materialId}: ${item.quantity} @ ${item.unitPrice}`).join('；') },
      ]} /></>}
    </Modal>
  </Space>;
}
