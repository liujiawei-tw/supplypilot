import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Input, Modal, Segmented, Select, Space, Table, Tag, Typography, message } from 'antd';
import type { TableProps } from 'antd';
import { Link } from 'react-router-dom';
import { assignPurchaseOrder, claimPurchaseOrder, createPurchaseOrderDraft, getPurchaseOrder, listPurchaseOrders, type CreatePurchaseOrderRequest, type PurchaseOrderQuery, type PurchaseOrderResponse, type UpdatePurchaseOrderRequest } from '../../api/purchaseOrder';
import { purchaseOrderStageGateway, type PurchaseOrderStageBoardSnapshot } from '../../api/purchaseOrderStage';
import { listAssignableUsers, type AssignableUser } from '../../api/users';
import { requiresMutationVerification, toApiError } from '../../api/apiError';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import VerificationRequiredAlert from '../../components/VerificationRequiredAlert';
import PurchaseOrderForm from './PurchaseOrderForm';
import PurchaseOrderKanban from './PurchaseOrderKanban';
import { defaultPurchaseOrderViewMode, formatPurchaseOrderDate, formatTimestamp, isUnknownMutation, statusColors } from './purchaseOrderUi';
import { displayLabel } from '../../utils/displayLabels';
import type { ApiErrorResponse, PageResponse } from '../../types/api';

const statuses = ['DRAFT', 'APPROVED', 'ORDERED', 'RECEIVED', 'REJECTED', 'CANCELLED'] as const;
const sortable = new Set(['id', 'poNumber', 'status', 'expectedDeliveryDate', 'createdAt', 'updatedAt', 'orderedAt']);
type PurchaseOrderViewMode = 'kanban' | 'list';
type PurchaseOrderBoardResult = {
  query: PurchaseOrderQuery;
  retry: number;
} & (
  | { state: 'ready'; snapshot: PurchaseOrderStageBoardSnapshot }
  | { state: 'incomplete'; reason: string }
  | { state: 'error'; error: ApiErrorResponse }
);

function initialViewMode(): PurchaseOrderViewMode {
  const isMobile = typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(max-width: 767px)').matches;
  return defaultPurchaseOrderViewMode(isMobile);
}

export default function PurchaseOrdersPage() {
  const { user } = useAuth();
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<PurchaseOrderResponse> | null>(null);
  const [dataQuery, setDataQuery] = useState<PurchaseOrderQuery | null>(null);
  const [assignableUsers, setAssignableUsers] = useState<AssignableUser[]>([]);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [viewMode, setViewMode] = useState<PurchaseOrderViewMode>(initialViewMode);
  const [boardResult, setBoardResult] = useState<PurchaseOrderBoardResult | null>(null);
  const [boardRetry, setBoardRetry] = useState(0);
  const [query, setQuery] = useState<PurchaseOrderQuery>({ page: 0, size: 20, sort: 'createdAt,desc' });
  const [createOpen, setCreateOpen] = useState(false);
  const [pending, setPending] = useState<CreatePurchaseOrderRequest | null>(null);
  const [createError, setCreateError] = useState<ApiErrorResponse | null>(null);
  const [createUnknown, setCreateUnknown] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const [ownerSubmitting, setOwnerSubmitting] = useState(false);
  const ownerSubmittingRef = useRef(false);
  const [ownerError, setOwnerError] = useState<ApiErrorResponse | null>(null);
  const [ownerVerificationId, setOwnerVerificationId] = useState<number | null>(null);
  const [ownerVerificationState, setOwnerVerificationState] = useState<'required' | 'checking' | 'failed' | 'verified' | null>(null);
  const [ownerVerificationError, setOwnerVerificationError] = useState<ApiErrorResponse | null>(null);
  const requestId = useRef(0);
  const boardRequestId = useRef(0);

  const load = useCallback(async () => {
    const current = ++requestId.current; setLoading(true); setError(null);
    try {
      const result = await listPurchaseOrders(query);
      if (current === requestId.current) {
        setData(result);
        setDataQuery(query);
      }
    }
    catch (requestError) {
      if (current === requestId.current) {
        setData(null);
        setDataQuery(query);
        setError(toApiError(requestError));
      }
    }
    finally { if (current === requestId.current) setLoading(false); }
  }, [query]);
  useEffect(() => {
    if (viewMode !== 'list') return;
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load, viewMode]);
  useEffect(() => {
    if (viewMode !== 'kanban') {
      boardRequestId.current += 1;
      return;
    }

    const current = ++boardRequestId.current;
    void purchaseOrderStageGateway.readCompleteBoard(query)
      .then((result) => {
        if (current !== boardRequestId.current) return;
        if (result.kind === 'ready') {
          setBoardResult({ query, retry: boardRetry, state: 'ready', snapshot: result.snapshot });
        } else {
          setBoardResult({ query, retry: boardRetry, state: 'incomplete', reason: result.reason });
        }
      })
      .catch((cause) => {
        if (current !== boardRequestId.current) return;
        setBoardResult({ query, retry: boardRetry, state: 'error', error: toApiError(cause) });
      });

    return () => {
      if (current === boardRequestId.current) boardRequestId.current += 1;
    };
  }, [boardRetry, query, viewMode]);
  useEffect(() => { void listAssignableUsers().then(setAssignableUsers).catch(() => setAssignableUsers([])); }, []);

  const currentBoardResult = boardResult?.query === query && boardResult.retry === boardRetry ? boardResult : null;
  const currentListData = dataQuery === query ? data : null;
  const listLoading = loading || dataQuery !== query;
  const boardState = currentBoardResult?.state ?? 'loading';
  const boardSnapshot = currentBoardResult?.state === 'ready' ? currentBoardResult.snapshot : null;
  const boardError = currentBoardResult?.state === 'error' ? currentBoardResult.error : null;
  const boardReason = currentBoardResult?.state === 'incomplete' ? currentBoardResult.reason : undefined;
  const boardViewKey = JSON.stringify([query, boardRetry, boardState]);

  const changeOwner = async (po: PurchaseOrderResponse, value: number | 'claim') => {
    if (ownerSubmittingRef.current || ownerVerificationId !== null) return;
    ownerSubmittingRef.current = true; setOwnerSubmitting(true); setOwnerError(null);
    try {
      if (value === 'claim') await claimPurchaseOrder(po.id);
      else await assignPurchaseOrder(po.id, value);
      apiMessage.success('採購單負責人已更新');
      await load();
    } catch (cause) {
      setOwnerError(toApiError(cause));
      if (requiresMutationVerification(cause)) {
        setOwnerVerificationId(po.id); setOwnerVerificationState('required');
      }
    } finally { ownerSubmittingRef.current = false; setOwnerSubmitting(false); }
  };

  const verifyOwnerState = async () => {
    if (ownerVerificationId === null || ownerVerificationState === 'checking') return;
    const id = ownerVerificationId;
    setOwnerVerificationState('checking'); setOwnerVerificationError(null);
    try {
      const latest = await getPurchaseOrder(id);
      setData((current) => current && ({ ...current, content: current.content.map((item) => item.id === id ? latest : item) }));
      setOwnerVerificationId(null); setOwnerVerificationState('verified'); setOwnerError(null);
    } catch (cause) {
      setOwnerVerificationState('failed'); setOwnerVerificationError(toApiError(cause));
    }
  };

  const confirmCreate = async () => {
    if (!pending || submittingRef.current || createUnknown) return;
    submittingRef.current = true; setSubmitting(true); setCreateError(null);
    try {
      const created = await createPurchaseOrderDraft(pending);
      apiMessage.success(`採購單草稿 ${created.poNumber} 已建立`);
      setPending(null); setCreateOpen(false);
      if (viewMode === 'kanban') setBoardRetry((current) => current + 1);
      else await load();
    } catch (requestError) {
      const unknown = isUnknownMutation(requestError); setCreateUnknown(unknown); setCreateError(toApiError(requestError)); setPending(null);
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  const columns: TableProps<PurchaseOrderResponse>['columns'] = [
    { title: '採購單編號', dataIndex: 'poNumber', sorter: true, sortOrder: query.sort?.startsWith('poNumber,') ? (query.sort.endsWith(',asc') ? 'ascend' : 'descend') : null },
    { title: '供應商', render: (_, row) => `${row.supplierCode} — ${row.supplierName}` },
    { title: '目前負責人', responsive: ['md'], render: (_, row) => <Space wrap>
      <span>{row.assignedToDisplayName ?? '未指派'}</span>
      {!row.assignedTo && <Button aria-label={`Claim ${row.poNumber}`} size="small" loading={ownerSubmitting} disabled={ownerVerificationId !== null} onClick={() => Modal.confirm({ title: '認領此採購單？', content: `確認將目前登入帳號設為 ${row.poNumber} 的負責人。`, onOk: () => changeOwner(row, 'claim') })}>認領</Button>}
      <Select aria-label={`${row.poNumber} 指派負責人`} size="small" placeholder="指派" style={{ width: 140 }} value={row.assignedTo ?? undefined} disabled={ownerSubmitting || ownerVerificationId !== null}
        options={assignableUsers.map((item) => ({ value: item.id, label: item.displayName }))}
        onChange={(value: number) => Modal.confirm({ title: '變更採購單負責人？', content: `確認將 ${row.poNumber} 指派給 ${assignableUsers.find((item) => item.id === value)?.displayName ?? '所選帳號'}。`, onOk: () => changeOwner(row, value) })} />
    </Space> },
    { title: '狀態', dataIndex: 'status', sorter: true, responsive: ['md'], sortOrder: query.sort?.startsWith('status,') ? (query.sort.endsWith(',asc') ? 'ascend' : 'descend') : null, render: (value) => <Tag color={statusColors[value as PurchaseOrderResponse['status']]}>{displayLabel(value)}</Tag> },
    { title: '預計交期', dataIndex: 'expectedDeliveryDate', sorter: true, sortOrder: query.sort?.startsWith('expectedDeliveryDate,') ? (query.sort.endsWith(',asc') ? 'ascend' : 'descend') : null, render: (value, row) => <Space>{formatPurchaseOrderDate(value)}{row.isOverdue && <Tag color="red">已逾期</Tag>}</Space> },
    { title: '供應商承諾交期', dataIndex: 'supplierPromisedDeliveryDate', responsive: ['md'], render: formatPurchaseOrderDate },
    { title: '建立時間', dataIndex: 'createdAt', sorter: true, responsive: ['md'], sortOrder: query.sort?.startsWith('createdAt,') ? (query.sort.endsWith(',asc') ? 'ascend' : 'descend') : null, render: formatTimestamp },
    { title: '下單時間', dataIndex: 'orderedAt', sorter: true, responsive: ['md'], sortOrder: query.sort?.startsWith('orderedAt,') ? (query.sort.endsWith(',asc') ? 'ascend' : 'descend') : null, render: formatTimestamp },
    { title: '操作', render: (_, row) => <Link to={`/purchase-orders/${row.id}`}>檢視</Link> },
  ];

  const setFilter = (key: keyof PurchaseOrderQuery, value: unknown) => setQuery((current) => ({ ...current, [key]: value || undefined, page: 0 }));
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}
    <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}><div><Typography.Title level={2} style={{ marginBottom: 0 }}>採購單管理</Typography.Title><Typography.Text type="secondary">查詢採購單流程與稽核紀錄</Typography.Text></div>{user && can(user.role, 'purchaseOrderCreate') && <Button type="primary" onClick={() => { setCreateOpen(true); setCreateError(null); }}>建立手動草稿</Button>}</Space>
    <Space wrap>
      <Select allowClear placeholder="狀態" style={{ width: 150 }} options={statuses.map((value) => ({ value, label: displayLabel(value) }))} onChange={(value) => setFilter('status', value)} />
      <Input aria-label="採購單號搜尋" placeholder="採購單號" maxLength={100} allowClear style={{ width: 190 }} value={query.poNumber ?? ''} onChange={(event) => setFilter('poNumber', event.target.value)} />
      <Input aria-label="供應商代碼搜尋" placeholder="供應商代碼" maxLength={100} allowClear style={{ width: 170 }} value={query.supplierCode ?? ''} onChange={(event) => setFilter('supplierCode', event.target.value)} />
      <Input aria-label="供應商名稱搜尋" placeholder="供應商名稱" maxLength={100} allowClear style={{ width: 190 }} value={query.supplierName ?? ''} onChange={(event) => setFilter('supplierName', event.target.value)} />
      <Input aria-label="物料代碼搜尋" placeholder="物料代碼" maxLength={100} allowClear style={{ width: 170 }} value={query.materialCode ?? ''} onChange={(event) => setFilter('materialCode', event.target.value)} />
      <Input aria-label="供應商編號篩選" placeholder="供應商編號" style={{ width: 130 }} onChange={(event) => setFilter('supplierId', Number(event.target.value) || undefined)} />
      <Input aria-label="物料編號篩選" placeholder="物料編號" style={{ width: 130 }} onChange={(event) => setFilter('materialId', Number(event.target.value) || undefined)} />
      <Input aria-label="建立日期起日" type="date" onChange={(event) => setFilter('createdFrom', event.target.value)} />
      <Input aria-label="建立日期迄日" type="date" onChange={(event) => setFilter('createdTo', event.target.value)} />
      <Input aria-label="預計交期起日" type="date" onChange={(event) => setFilter('expectedDeliveryFrom', event.target.value)} />
      <Input aria-label="預計交期迄日" type="date" onChange={(event) => setFilter('expectedDeliveryTo', event.target.value)} />
      <Select allowClear placeholder="逾期狀態" style={{ width: 140 }} options={[{ value: true, label: '只看逾期' }, { value: false, label: '排除逾期' }]} onChange={(value) => setQuery((current) => ({ ...current, overdue: value, page: 0 }))} />
    </Space>
    <div className="purchase-orders__view-switch" aria-label="採購單檢視模式">
      <Segmented
        aria-label="採購單檢視模式"
        value={viewMode}
        options={[{ label: '看板', value: 'kanban' }, { label: '列表', value: 'list' }]}
        onChange={(value) => {
          const nextMode = value as PurchaseOrderViewMode;
          if (nextMode === 'kanban' && viewMode !== 'kanban') setBoardRetry((current) => current + 1);
          setViewMode(nextMode);
        }}
      />
    </div>
    {viewMode === 'list' && error && <ErrorState title="採購單清單載入失敗" error={error} onRetry={() => void load()} />}
    {viewMode === 'list' && ownerVerificationState && <VerificationRequiredAlert
      state={ownerVerificationState}
      title={ownerVerificationState === 'verified' ? '已取得最新採購單狀態' : ownerVerificationState === 'failed' ? '結果尚未確認，負責人操作維持鎖定' : '負責人操作結果待查核'}
      message={ownerVerificationState === 'verified' ? '畫面已更新為後端最新資料。請重新審閱後，再次明確確認認領或指派。' : '操作遇到 409、伺服器錯誤或連線結果不明。系統不會自動重送；請先查詢後端真實狀態。'}
      onVerify={ownerVerificationId !== null ? () => void verifyOwnerState() : undefined}
      verifyText="重新查詢採購單"
    />}
    {viewMode === 'list' && ownerVerificationError && <ErrorState title="採購單狀態查核失敗" error={ownerVerificationError} onRetry={() => void verifyOwnerState()} retryText="重新查核" />}
    {viewMode === 'list' && ownerError && <ErrorState title={ownerVerificationId !== null ? '負責人操作結果待查核' : '負責人操作失敗'} error={ownerError} operation="mutation" />}

    {viewMode === 'kanban' ? <PurchaseOrderKanban
      key={boardViewKey}
      state={boardState}
      snapshot={boardSnapshot}
      gateway={purchaseOrderStageGateway}
      error={boardError}
      reason={boardReason}
      onRetry={() => setBoardRetry((current) => current + 1)}
      onRefresh={() => setBoardRetry((current) => current + 1)}
    /> : <Table rowKey="id" columns={columns} dataSource={currentListData?.content ?? []} loading={listLoading}
      locale={{ emptyText: <EmptyState simple description="沒有符合條件的 Purchase Order" /> }}
      scroll={{ x: true }}
      pagination={{ current: (currentListData?.page ?? query.page ?? 0) + 1, pageSize: currentListData?.size ?? query.size ?? 20, total: currentListData?.totalElements ?? 0, showSizeChanger: true }}
      onChange={(pagination, _filters, sorter) => {
        const selected = Array.isArray(sorter) ? sorter[0] : sorter;
        const field = typeof selected?.field === 'string' && sortable.has(selected.field) ? selected.field : undefined;
        setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20, ...(field && selected.order ? { sort: `${field},${selected.order === 'ascend' ? 'asc' : 'desc'}` } : {}) }));
      }} />}
    <Modal width={900} title="建立手動採購單草稿" open={createOpen} footer={null} destroyOnHidden onCancel={() => { if (!submittingRef.current) setCreateOpen(false); }}>
      {createUnknown && <Alert type="warning" showIcon title="建立結果不明，已鎖定再次送出" description="請由採購單清單人工核實是否已建立；建立 API 沒有可安全使用的唯一查核鍵，本頁不會自動重送。重新載入頁面後才可重新操作。" />}
      {createError && <ErrorState title={createUnknown ? '採購單草稿建立結果待確認' : '採購單草稿建立失敗'} error={createError} />}
      {!createUnknown && <PurchaseOrderForm disabled={submitting} onReview={(request: CreatePurchaseOrderRequest | UpdatePurchaseOrderRequest) => setPending(request as CreatePurchaseOrderRequest)} />}
    </Modal>
    <Modal title="確認建立手動草稿" open={!!pending} okText="確認建立草稿" confirmLoading={submitting} okButtonProps={{ disabled: submitting }} onOk={() => void confirmCreate()} onCancel={() => { if (!submittingRef.current) setPending(null); }}>
      {pending && <><Alert type="warning" showIcon title="只會建立草稿，不會自動核准或下單。" /><Descriptions style={{ marginTop: 16 }} bordered column={1} items={[
        { key: 'supplier', label: '供應商編號', children: pending.supplierId },
        { key: 'delivery', label: '預計交期', children: pending.expectedDeliveryDate ?? '未設定' },
        { key: 'items', label: '品項', children: pending.items.map((item) => `物料 #${item.materialId}：${item.quantity} @ ${item.unitPrice}`).join('；') },
      ]} /></>}
    </Modal>
  </Space>;
}
