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
import { displayLabel } from '../../utils/displayLabels';

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
    { title: '異動編號', dataIndex: 'id', sorter: true }, { title: '物料', dataIndex: 'materialId', sorter: true, render: (_, row) => `${row.materialCode} — ${row.materialName}` },
    { title: '異動類型', dataIndex: 'type', sorter: true, render: displayLabel }, { title: '數量', dataIndex: 'quantity', sorter: true, render: (value, row) => `${String(value)} ${row.unit}` },
    { title: '採購單編號', dataIndex: 'purchaseOrderId', sorter: true, render: (_, row) => row.purchaseOrderId ? poRead ? <Link to={`/purchase-orders/${row.purchaseOrderId}`}>{row.purchaseOrderNumber ?? `PO #${row.purchaseOrderId}`}</Link> : (row.purchaseOrderNumber ?? `PO #${row.purchaseOrderId}`) : '—' },
    { title: '原因', dataIndex: 'reason', render: (value) => value ?? '—' }, { title: '建立者', dataIndex: 'createdByDisplayName' },
    { title: '建立時間', dataIndex: 'createdAt', sorter: true, render: formatTimestamp }, { title: '操作', render: (_, row) => <a onClick={() => void loadDetail(row.id)}>查看</a> },
  ];
  return <Space orientation="vertical" style={{ width: '100%' }}>
    <Space wrap>
      <Input aria-label="物料編號篩選" placeholder="物料編號" style={{ width: 130 }} onChange={(event) => setFilter('materialId', Number(event.target.value) || undefined)} />
      <Select allowClear aria-label="異動類型篩選" placeholder="異動類型" style={{ width: 210 }} options={[{ value: 'PURCHASE_RECEIVE', label: displayLabel('PURCHASE_RECEIVE') }, { value: 'MANUAL_ADJUSTMENT', label: displayLabel('MANUAL_ADJUSTMENT') }]} onChange={(value) => setFilter('type', value)} />
      <Input aria-label="採購單編號篩選" placeholder="採購單編號" style={{ width: 170 }} value={query.purchaseOrderId ?? ''} onChange={(event) => setFilter('purchaseOrderId', Number(event.target.value) || undefined)} />
      <Input aria-label="開始時間篩選" type="datetime-local" onChange={(event) => setFilter('from', event.target.value ? new Date(event.target.value).toISOString() : undefined)} />
      <Input aria-label="結束時間篩選" type="datetime-local" onChange={(event) => setFilter('to', event.target.value ? new Date(event.target.value).toISOString() : undefined)} />
    </Space>
    {error && <ErrorState title="庫存異動載入失敗" error={error} onRetry={() => void load()} />}
    <Table rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading} locale={{ emptyText: <EmptyState simple description="沒有符合條件的庫存異動" /> }} pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }}
      onChange={(pagination, _filters, sorter) => { const selected = Array.isArray(sorter) ? sorter[0] : sorter; const field = typeof selected?.field === 'string' && sortable.has(selected.field) ? selected.field : undefined; setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20, ...(field && selected.order ? { sort: `${field},${selected.order === 'ascend' ? 'asc' : 'desc'}` } : {}) })); }} />
    <Drawer title={detail ? `庫存異動 #${detail.id}` : '庫存異動明細'} size="large" open={detailLoading || !!detail || !!detailError} onClose={() => { detailRequestId.current += 1; setDetail(null); setDetailError(null); setDetailLoading(false); }}>
      {detailLoading && <LoadingState tip="正在載入庫存異動…" />}{detailError && <ErrorState title="庫存異動明細載入失敗" error={detailError} />}
      {detail && <Descriptions bordered column={1} items={[{ key: 'id', label: '異動編號', children: detail.id }, { key: 'material', label: '物料', children: `${detail.materialCode} — ${detail.materialName} (#${detail.materialId})` }, { key: 'type', label: '異動類型', children: displayLabel(detail.type) }, { key: 'quantity', label: '數量', children: `${String(detail.quantity)} ${detail.unit}` }, { key: 'po', label: '採購單', children: detail.purchaseOrderId ? poRead ? <Link to={`/purchase-orders/${detail.purchaseOrderId}`}>{detail.purchaseOrderNumber ?? `PO #${detail.purchaseOrderId}`}</Link> : (detail.purchaseOrderNumber ?? `PO #${detail.purchaseOrderId}`) : '—' }, { key: 'reason', label: '原因', children: detail.reason ?? '—' }, { key: 'createdBy', label: '建立者', children: `${detail.createdByDisplayName} (#${detail.createdBy})` }, { key: 'createdAt', label: '建立時間', children: formatTimestamp(detail.createdAt) }]} />}
    </Drawer>
  </Space>;
}
