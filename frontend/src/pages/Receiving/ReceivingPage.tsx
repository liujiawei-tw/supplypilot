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
import { displayLabel } from '../../utils/displayLabels';

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
    { title: '採購單編號', dataIndex: 'poNumber', sorter: true },
    { title: '供應商', render: (_, row) => `${row.supplierCode} — ${row.supplierName}` },
    { title: '狀態', dataIndex: 'status', render: (value) => <Tag color={value === 'RECEIVED' ? 'green' : 'gold'}>{displayLabel(value)}</Tag> },
    { title: '預計交期', dataIndex: 'expectedDeliveryDate', sorter: true, render: (value, row) => <Space>{value ?? '—'}{row.isOverdue && <Tag color="red">已逾期</Tag>}</Space> },
    { title: '物料', render: (_, row) => row.items.map((item) => `${item.materialCode}: ${String(item.quantity)} ${item.unit}`).join('；') },
    { title: '下單時間', dataIndex: 'orderedAt', sorter: true, render: formatTimestamp },
    { title: '收貨時間', dataIndex: 'receivedAt', sorter: true, render: formatTimestamp },
    { title: '操作', render: (_, row) => <Link to={`/receiving/purchase-orders/${row.id}`}>檢視</Link> },
  ];
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    <div><Typography.Title level={2} style={{ marginBottom: 0 }}>收貨作業</Typography.Title><Typography.Text type="secondary">檢視受限資料並執行整筆收貨</Typography.Text></div>
    <Space wrap>
      <Select aria-label="收貨狀態" value={query.status} style={{ width: 160 }} options={[{ value: 'ORDERED', label: '待收貨' }, { value: 'RECEIVED', label: '已收貨紀錄' }]} onChange={(value) => setFilter('status', value)} />
      <Input aria-label="收貨物料編號" placeholder="物料編號" style={{ width: 130 }} onChange={(event) => setFilter('materialId', Number(event.target.value) || undefined)} />
      <Input aria-label="預計交期起日" type="date" onChange={(event) => setFilter('expectedDeliveryFrom', event.target.value)} />
      <Input aria-label="預計交期迄日" type="date" onChange={(event) => setFilter('expectedDeliveryTo', event.target.value)} />
      <Input aria-label="下單日期起日" type="date" onChange={(event) => setFilter('orderedFrom', event.target.value)} />
      <Input aria-label="下單日期迄日" type="date" onChange={(event) => setFilter('orderedTo', event.target.value)} />
    </Space>
    {error && <ErrorState title="收貨清單載入失敗" error={error} onRetry={() => void load()} />}
    <Table rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading} locale={{ emptyText: <EmptyState simple description={query.status === 'ORDERED' ? '目前沒有待收貨 PO' : '尚無已收貨紀錄'} /> }}
      pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }}
      onChange={(pagination, _filters, sorter) => { const selected = Array.isArray(sorter) ? sorter[0] : sorter; const field = typeof selected?.field === 'string' && sortable.has(selected.field) ? selected.field : undefined; setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20, ...(field && selected.order ? { sort: `${field},${selected.order === 'ascend' ? 'asc' : 'desc'}` } : {}) })); }} />
  </Space>;
}
