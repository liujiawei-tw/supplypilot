import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Input, Pagination, Select, Spin, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import { listReceivingPurchaseOrders, type ReceivingPurchaseOrderResponse, type ReceivingQuery } from '../../api/receiving';
import { toApiError } from '../../api/apiError';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import type { ApiErrorResponse, PageResponse } from '../../types/api';
import { formatTimestamp } from '../PurchaseOrders/purchaseOrderUi';
import { displayLabel } from '../../utils/displayLabels';
import { formatDate } from '../../utils/displayFormatters';
import './ReceivingPage.css';

const sortable = new Set(['id', 'poNumber', 'expectedDeliveryDate', 'orderedAt', 'receivedAt']);

function progressLabel(row: ReceivingPurchaseOrderResponse): string {
  if (row.status === 'RECEIVED') return '收貨完成';
  if (row.orderedProgress === 'RECEIVING_INSPECTION') return displayLabel(row.orderedProgress);
  if (row.orderedProgress === 'FOLLOW_UP') return displayLabel(row.orderedProgress);
  return '待進入驗收';
}

function itemSummary(row: ReceivingPurchaseOrderResponse): string {
  if (!row.items.length) return '尚無品項資料';
  const visible = row.items.slice(0, 2).map((item) => `${item.materialCode} · ${String(item.quantity)} ${item.unit}`);
  const remaining = row.items.length - visible.length;
  return remaining > 0 ? `${visible.join('、')}，另 ${remaining} 項` : visible.join('、');
}

function ReceivingStatus({ row }: { row: ReceivingPurchaseOrderResponse }) {
  return <div className="receiving-queue__statuses">
    <Tag color={row.status === 'RECEIVED' ? 'success' : 'gold'}>{displayLabel(row.status)}</Tag>
    <span className={`receiving-queue__progress${row.orderedProgress === 'RECEIVING_INSPECTION' && row.status === 'ORDERED' ? ' is-current' : ''}`}>
      {progressLabel(row)}
    </span>
  </div>;
}

export default function ReceivingPage() {
  const [searchParams] = useSearchParams();
  const initialStatus = searchParams.get('status') === 'RECEIVED' ? 'RECEIVED' : 'ORDERED';
  const [data, setData] = useState<PageResponse<ReceivingPurchaseOrderResponse> | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState<ReceivingQuery>(() => ({ status: initialStatus, page: 0, size: 20, sort: 'id,asc' }));
  const requestId = useRef(0);
  const load = useCallback(async () => {
    const current = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const result = await listReceivingPurchaseOrders(query);
      if (current === requestId.current) setData(result);
    } catch (requestError) {
      if (current === requestId.current) {
        setData(null);
        setError(toApiError(requestError));
      }
    } finally {
      if (current === requestId.current) setLoading(false);
    }
  }, [query]);
  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  const updateQuery = (update: (current: ReceivingQuery) => ReceivingQuery) => {
    requestId.current += 1;
    setLoading(true);
    setError(null);
    setQuery(update);
  };

  const setFilter = (key: keyof ReceivingQuery, value: unknown) =>
    updateQuery((current) => ({ ...current, [key]: value || undefined, page: 0 }));

  const handleTableChange: TableProps<ReceivingPurchaseOrderResponse>['onChange'] = (pagination, _filters, sorter) => {
    const selected = Array.isArray(sorter) ? sorter[0] : sorter;
    const field = typeof selected?.field === 'string' && sortable.has(selected.field) ? selected.field : undefined;
    updateQuery((current) => ({
      ...current,
      page: (pagination.current ?? 1) - 1,
      size: pagination.pageSize ?? current.size ?? 20,
      ...(field && selected.order ? { sort: `${field},${selected.order === 'ascend' ? 'asc' : 'desc'}` } : {}),
    }));
  };

  const columns: TableProps<ReceivingPurchaseOrderResponse>['columns'] = [
    {
      title: '採購單',
      dataIndex: 'poNumber',
      sorter: true,
      render: (value, row) => <div className="receiving-queue__identity">
        <Link className="receiving-queue__po-link" to={`/receiving/purchase-orders/${row.id}`}>{value}</Link>
        <span>{row.supplierCode} · {row.supplierName}</span>
      </div>,
    },
    { title: '狀態與進度', dataIndex: 'status', render: (_, row) => <ReceivingStatus row={row} /> },
    {
      title: '預計交期',
      dataIndex: 'expectedDeliveryDate',
      sorter: true,
      render: (value, row) => <div className="receiving-queue__date">
        <strong>預計交期</strong>
        <span>{formatDate(value, '未設定')}</span>
        {row.status === 'ORDERED' && row.isOverdue && <Tag color="error">已逾期</Tag>}
      </div>,
    },
    {
      title: '收貨時間',
      dataIndex: 'receivedAt',
      sorter: true,
      render: (value) => <div className="receiving-queue__date">
        <strong>收貨完成</strong>
        <span>{value ? formatTimestamp(value) : '未設定'}</span>
      </div>,
    },
    { title: '品項摘要', render: (_, row) => <span className="receiving-queue__items">{itemSummary(row)}</span> },
    { title: '下單時間', dataIndex: 'orderedAt', sorter: true, render: (value) => value ? formatTimestamp(value) : '未設定' },
    {
      title: '作業',
      render: (_, row) => <Link className="receiving-queue__open" to={`/receiving/purchase-orders/${row.id}`}>
        {row.status === 'RECEIVED' ? '查看收貨紀錄' : row.orderedProgress === 'RECEIVING_INSPECTION' ? '繼續驗收' : '查看待收貨 PO'}
      </Link>,
    },
  ];

  const pageNumber = (data?.page ?? query.page ?? 0) + 1;
  const pageSize = data?.size ?? query.size ?? 20;
  const total = data?.totalElements ?? 0;
  const records = data?.content ?? [];

  return <section className="receiving-queue sp-page">
    <header className="receiving-queue__heading sp-page__heading">
      <div>
        <Typography.Title level={2} className="receiving-queue__title">收貨作業</Typography.Title>
        <p className="receiving-queue__intro sp-page__lead">依交期與驗收進度處理整筆採購單。短收或品質異常請留在驗收階段，不支援部分收貨。</p>
      </div>
      <span className="receiving-queue__count" aria-live="polite">{loading ? '更新中…' : `共 ${total} 筆`}</span>
    </header>

    <section className="receiving-queue__filters" aria-label="收貨清單篩選條件">
      <label className="receiving-queue__filter">
        <span>採購單狀態</span>
        <Select aria-label="收貨狀態" value={query.status} options={[
          { value: 'ORDERED', label: '待收貨' },
          { value: 'RECEIVED', label: '已收貨紀錄' },
        ]} onChange={(value) => setFilter('status', value)} />
      </label>
      <label className="receiving-queue__filter receiving-queue__filter--material">
        <span>物料編號</span>
        <Input aria-label="收貨物料編號" inputMode="numeric" value={query.materialId ?? ''} placeholder="輸入物料 ID" onChange={(event) => setFilter('materialId', Number(event.target.value) || undefined)} />
      </label>
      <label className="receiving-queue__filter">
        <span>預計交期起日</span>
        <Input aria-label="預計交期起日" type="date" value={query.expectedDeliveryFrom ?? ''} onChange={(event) => setFilter('expectedDeliveryFrom', event.target.value)} />
      </label>
      <label className="receiving-queue__filter">
        <span>預計交期迄日</span>
        <Input aria-label="預計交期迄日" type="date" value={query.expectedDeliveryTo ?? ''} onChange={(event) => setFilter('expectedDeliveryTo', event.target.value)} />
      </label>
      <label className="receiving-queue__filter">
        <span>下單日期起日</span>
        <Input aria-label="下單日期起日" type="date" value={query.orderedFrom ?? ''} onChange={(event) => setFilter('orderedFrom', event.target.value)} />
      </label>
      <label className="receiving-queue__filter">
        <span>下單日期迄日</span>
        <Input aria-label="下單日期迄日" type="date" value={query.orderedTo ?? ''} onChange={(event) => setFilter('orderedTo', event.target.value)} />
      </label>
    </section>

    {error && <ErrorState title="收貨清單載入失敗" error={error} onRetry={() => void load()} />}
    <section className="receiving-queue__table sp-section" aria-label="收貨採購單清單">
      <Table
        rowKey="id"
        columns={columns}
        dataSource={loading ? [] : records}
        loading={loading}
        locale={{ emptyText: error
          ? <span aria-hidden="true" />
          : <EmptyState simple description={query.status === 'ORDERED' ? '目前沒有待收貨 PO' : '尚無已收貨紀錄'} /> }}
        pagination={{ current: pageNumber, pageSize, total, showSizeChanger: true, showTotal: (count) => `共 ${count} 筆` }}
        onChange={handleTableChange}
      />
    </section>

    <section className="receiving-queue__mobile" aria-label="收貨採購單清單">
      {loading
        ? <div className="receiving-queue__loading"><Spin tip="正在載入收貨清單" /></div>
        : error
          ? null
          : records.length === 0
          ? <EmptyState simple description={query.status === 'ORDERED' ? '目前沒有待收貨 PO' : '尚無已收貨紀錄'} />
          : <ul className="receiving-queue__mobile-list">
            {records.map((row) => <li className="receiving-queue__mobile-record" key={row.id}>
              <div className="receiving-queue__mobile-top">
                <Link className="receiving-queue__po-link" to={`/receiving/purchase-orders/${row.id}`}>{row.poNumber}</Link>
                <ReceivingStatus row={row} />
              </div>
              <p className="receiving-queue__supplier">{row.supplierCode} · {row.supplierName}</p>
              <div className="receiving-queue__mobile-facts">
                <div>
                  <span>{row.status === 'RECEIVED' ? '收貨完成' : '預計交期'}</span>
                  <strong>{row.status === 'RECEIVED'
                    ? row.receivedAt ? formatTimestamp(row.receivedAt) : '未設定'
                    : formatDate(row.expectedDeliveryDate, '未設定')}</strong>
                </div>
                {row.status === 'ORDERED' && row.isOverdue && <Tag color="error">已逾期</Tag>}
                <div>
                  <span>下單時間</span>
                  <strong>{row.orderedAt ? formatTimestamp(row.orderedAt) : '未設定'}</strong>
                </div>
              </div>
              <p className="receiving-queue__items">{itemSummary(row)}</p>
              <Link className="receiving-queue__open" to={`/receiving/purchase-orders/${row.id}`}>
                {row.status === 'RECEIVED' ? '查看收貨紀錄' : row.orderedProgress === 'RECEIVING_INSPECTION' ? '繼續驗收' : '查看待收貨 PO'}
              </Link>
            </li>)}
          </ul>}
      <Pagination
        className="receiving-queue__mobile-pagination"
        current={pageNumber}
        pageSize={pageSize}
        total={total}
        showSizeChanger
        showTotal={(count) => `共 ${count} 筆`}
        onChange={(current, size) => updateQuery((previous) => ({ ...previous, page: current - 1, size }))}
      />
    </section>

    {!loading && records.length > 0 && <div className="receiving-queue__footnote">
      清單只呈現收貨所需的採購單、供應商、品項數量與狀態。
      <Button type="link" onClick={() => void load()}>重新整理</Button>
    </div>}
  </section>;
}
