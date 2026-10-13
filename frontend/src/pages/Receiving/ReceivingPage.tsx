import { useCallback, useEffect, useRef, useState } from 'react';
import { Input, Select, Space, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import { Link } from 'react-router-dom';
import { listReceivingPurchaseOrders, type ReceivingPurchaseOrderResponse, type ReceivingQuery } from '../../api/receiving';
import { toApiError } from '../../api/apiError';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import type { ApiErrorResponse, PageResponse } from '../../types/api';
import { formatTimestamp } from '../PurchaseOrders/purchaseOrderUi';

const sortable = new Set(['id', 'poNumber', 'expectedDeliveryDate', 'orderedAt', 'receivedAt']);

export default function ReceivingPage() {
  const [data, setData] = useState<PageResponse<ReceivingPurchaseOrderResponse> | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState<ReceivingQuery>({ status: 'ORDERED', page: 0, size: 20, sort: 'id,asc' });
  const requestId = useRef(0);
  const load = useCallback(async () => {
    const current = ++requestId.current; setLoading(true); setError(null);
    try { const result = await listReceivingPurchaseOrders(query); if (current === requestId.current) setData(result); }
    catch (requestError) { if (current === requestId.current) setError(toApiError(requestError)); }
    finally { if (current === requestId.current) setLoading(false); }
  }, [query]);
  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);
  const setFilter = (key: keyof ReceivingQuery, value: unknown) => setQuery((current) => ({ ...current, [key]: value || undefined, page: 0 }));
  const columns: TableProps<ReceivingPurchaseOrderResponse>['columns'] = [
    { title: 'PO Number', dataIndex: 'poNumber', sorter: true },
    { title: 'Supplier', render: (_, row) => `${row.supplierCode} — ${row.supplierName}` },
    { title: 'Status', dataIndex: 'status', render: (value) => <Tag color={value === 'RECEIVED' ? 'green' : 'gold'}>{value}</Tag> },
    { title: 'Expected Delivery', dataIndex: 'expectedDeliveryDate', sorter: true, render: (value, row) => <Space>{value ?? '—'}{row.isOverdue && <Tag color="red">OVERDUE</Tag>}</Space> },
    { title: 'Materials', render: (_, row) => row.items.map((item) => `${item.materialCode}: ${String(item.quantity)} ${item.unit}`).join('；') },
    { title: 'Ordered At', dataIndex: 'orderedAt', sorter: true, render: formatTimestamp },
    { title: 'Received At', dataIndex: 'receivedAt', sorter: true, render: formatTimestamp },
    { title: 'Action', render: (_, row) => <Link to={`/receiving/purchase-orders/${row.id}`}>Detail</Link> },
  ];
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    <div><Typography.Title level={2} style={{ marginBottom: 0 }}>Warehouse Receiving</Typography.Title><Typography.Text type="secondary">受限資料檢視與 Full Receive</Typography.Text></div>
    <Space wrap>
      <Select aria-label="Receiving status" value={query.status} style={{ width: 160 }} options={[{ value: 'ORDERED', label: 'Awaiting receive' }, { value: 'RECEIVED', label: 'Received history' }]} onChange={(value) => setFilter('status', value)} />
      <Input aria-label="Receiving material ID" placeholder="Material ID" style={{ width: 130 }} onChange={(event) => setFilter('materialId', Number(event.target.value) || undefined)} />
      <Input aria-label="Receiving delivery from" type="date" onChange={(event) => setFilter('expectedDeliveryFrom', event.target.value)} />
      <Input aria-label="Receiving delivery to" type="date" onChange={(event) => setFilter('expectedDeliveryTo', event.target.value)} />
      <Input aria-label="Receiving ordered from" type="date" onChange={(event) => setFilter('orderedFrom', event.target.value)} />
      <Input aria-label="Receiving ordered to" type="date" onChange={(event) => setFilter('orderedTo', event.target.value)} />
    </Space>
    {error && <ErrorState title="Receiving 清單載入失敗" error={error} onRetry={() => void load()} />}
    <Table rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading} locale={{ emptyText: <EmptyState simple description={query.status === 'ORDERED' ? '目前沒有待收貨 PO' : '尚無已收貨紀錄'} /> }}
      pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }}
      onChange={(pagination, _filters, sorter) => { const selected = Array.isArray(sorter) ? sorter[0] : sorter; const field = typeof selected?.field === 'string' && sortable.has(selected.field) ? selected.field : undefined; setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20, ...(field && selected.order ? { sort: `${field},${selected.order === 'ascend' ? 'asc' : 'desc'}` } : {}) })); }} />
  </Space>;
}
