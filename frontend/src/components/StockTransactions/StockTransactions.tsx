import { useCallback, useEffect, useRef, useState } from 'react';
import { Descriptions, Drawer, Input, Select, Space, Table } from 'antd';
import type { TableProps } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import { getStockTransaction, listStockTransactions, type StockTransactionQuery, type StockTransactionResponse } from '../../api/stockTransaction';
import { toApiError } from '../../api/apiError';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';
import EmptyState from '../EmptyState';
import ErrorState from '../ErrorState';
import LoadingState from '../LoadingState';
import type { ApiErrorResponse, PageResponse } from '../../types/api';
import { formatTimestamp } from '../../pages/PurchaseOrders/purchaseOrderUi';

const sortable = new Set(['id', 'createdAt', 'materialId', 'type', 'purchaseOrderId', 'quantity']);

export default function StockTransactions() {
  const { user } = useAuth(); const [params, setParams] = useSearchParams();
  const poParam = Number(params.get('purchaseOrderId'));
  const [query, setQuery] = useState<StockTransactionQuery>({ purchaseOrderId: Number.isSafeInteger(poParam) && poParam > 0 ? poParam : undefined, page: 0, size: 20, sort: 'createdAt,desc' });
  const [data, setData] = useState<PageResponse<StockTransactionResponse> | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState<StockTransactionResponse | null>(null);
  const [detailError, setDetailError] = useState<ApiErrorResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const requestId = useRef(0); const detailRequestId = useRef(0);
  const load = useCallback(async () => {
    const current = ++requestId.current; setLoading(true); setError(null);
    try { const result = await listStockTransactions(query); if (current === requestId.current) setData(result); }
    catch (requestError) { if (current === requestId.current) setError(toApiError(requestError)); }
    finally { if (current === requestId.current) setLoading(false); }
  }, [query]);
  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);
  const loadDetail = async (id: number) => {
    const current = ++detailRequestId.current; setDetail(null); setDetailError(null); setDetailLoading(true);
    try { const result = await getStockTransaction(id); if (current === detailRequestId.current) setDetail(result); }
    catch (requestError) { if (current === detailRequestId.current) setDetailError(toApiError(requestError)); }
    finally { if (current === detailRequestId.current) setDetailLoading(false); }
  };
  const setFilter = (key: keyof StockTransactionQuery, value: unknown) => {
    setQuery((current) => ({ ...current, [key]: value || undefined, page: 0 }));
    if (key === 'purchaseOrderId') { const next = new URLSearchParams(params); if (value) next.set('purchaseOrderId', String(value)); else next.delete('purchaseOrderId'); next.set('tab', 'stock-transactions'); setParams(next, { replace: true }); }
  };
  const poRead = !!user && can(user.role, 'purchaseOrderRead');
  const columns: TableProps<StockTransactionResponse>['columns'] = [
    { title: 'ID', dataIndex: 'id', sorter: true }, { title: 'Material', dataIndex: 'materialId', sorter: true, render: (_, row) => `${row.materialCode} — ${row.materialName}` },
    { title: 'Type', dataIndex: 'type', sorter: true }, { title: 'Quantity', dataIndex: 'quantity', sorter: true, render: (value, row) => `${String(value)} ${row.unit}` },
    { title: 'PO Number', dataIndex: 'purchaseOrderId', sorter: true, render: (_, row) => row.purchaseOrderId ? poRead ? <Link to={`/purchase-orders/${row.purchaseOrderId}`}>{row.purchaseOrderNumber ?? `PO #${row.purchaseOrderId}`}</Link> : (row.purchaseOrderNumber ?? `PO #${row.purchaseOrderId}`) : '—' },
    { title: 'Reason', dataIndex: 'reason', render: (value) => value ?? '—' }, { title: 'Created By', dataIndex: 'createdByDisplayName' },
    { title: 'Created At', dataIndex: 'createdAt', sorter: true, render: formatTimestamp }, { title: 'Action', render: (_, row) => <a onClick={() => void loadDetail(row.id)}>Detail</a> },
  ];
  return <Space orientation="vertical" style={{ width: '100%' }}>
    <Space wrap>
      <Input aria-label="Transaction material ID" placeholder="Material ID" style={{ width: 130 }} onChange={(event) => setFilter('materialId', Number(event.target.value) || undefined)} />
      <Select allowClear aria-label="Transaction type" placeholder="Type" style={{ width: 210 }} options={[{ value: 'PURCHASE_RECEIVE' }, { value: 'MANUAL_ADJUSTMENT' }]} onChange={(value) => setFilter('type', value)} />
      <Input aria-label="Transaction PO ID" placeholder="Purchase Order ID" style={{ width: 170 }} value={query.purchaseOrderId ?? ''} onChange={(event) => setFilter('purchaseOrderId', Number(event.target.value) || undefined)} />
      <Input aria-label="Transaction from" type="datetime-local" onChange={(event) => setFilter('from', event.target.value ? new Date(event.target.value).toISOString() : undefined)} />
      <Input aria-label="Transaction to" type="datetime-local" onChange={(event) => setFilter('to', event.target.value ? new Date(event.target.value).toISOString() : undefined)} />
    </Space>
    {error && <ErrorState title="Stock Transactions 載入失敗" error={error} onRetry={() => void load()} />}
    <Table rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading} locale={{ emptyText: <EmptyState simple description="沒有符合條件的 Stock Transaction" /> }} pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }}
      onChange={(pagination, _filters, sorter) => { const selected = Array.isArray(sorter) ? sorter[0] : sorter; const field = typeof selected?.field === 'string' && sortable.has(selected.field) ? selected.field : undefined; setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20, ...(field && selected.order ? { sort: `${field},${selected.order === 'ascend' ? 'asc' : 'desc'}` } : {}) })); }} />
    <Drawer title={detail ? `Stock Transaction #${detail.id}` : 'Stock Transaction Detail'} size="large" open={detailLoading || !!detail || !!detailError} onClose={() => { detailRequestId.current += 1; setDetail(null); setDetailError(null); setDetailLoading(false); }}>
      {detailLoading && <LoadingState tip="Loading transaction..." />}{detailError && <ErrorState title="Stock Transaction Detail 載入失敗" error={detailError} />}
      {detail && <Descriptions bordered column={1} items={[{ key: 'id', label: 'Transaction ID', children: detail.id }, { key: 'material', label: 'Material', children: `${detail.materialCode} — ${detail.materialName} (#${detail.materialId})` }, { key: 'type', label: 'Type', children: detail.type }, { key: 'quantity', label: 'Quantity', children: `${String(detail.quantity)} ${detail.unit}` }, { key: 'po', label: 'Purchase Order', children: detail.purchaseOrderId ? poRead ? <Link to={`/purchase-orders/${detail.purchaseOrderId}`}>{detail.purchaseOrderNumber ?? `PO #${detail.purchaseOrderId}`}</Link> : (detail.purchaseOrderNumber ?? `PO #${detail.purchaseOrderId}`) : '—' }, { key: 'reason', label: 'Reason', children: detail.reason ?? '—' }, { key: 'createdBy', label: 'Created By', children: `${detail.createdByDisplayName} (#${detail.createdBy})` }, { key: 'createdAt', label: 'Created At', children: formatTimestamp(detail.createdAt) }]} />}
    </Drawer>
  </Space>;
}
