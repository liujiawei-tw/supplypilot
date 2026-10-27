import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Descriptions, Drawer, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { cancelDemand, createDemand, getDemand, listDemands, updateDemand, type DemandResponse, type DemandStatus } from '../../api/demand';
import { toApiError } from '../../api/apiError';
import { getMaterial, type MaterialResponse } from '../../api/material';
import { useAuth } from '../../auth/useAuth';
import { can } from '../../auth/permissions';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import RelatedEntitySelect from '../../components/RelatedEntitySelect';
import type { ApiErrorResponse, PageResponse } from '../../types/api';
import { displayLabel } from '../../utils/displayLabels';
import { formatDate, formatDateTime, formatQuantity } from '../../utils/displayFormatters';
import './DemandsPage.css';

const statuses: DemandStatus[] = ['ACTIVE', 'CANCELLED'];
const sortableFields = new Set(['id', 'quantity', 'requiredDate', 'status', 'createdAt', 'updatedAt']);

interface DemandFormValues {
  materialId?: number;
  quantity?: number;
  requiredDate?: string;
  note?: string;
  changeNote?: boolean;
}

export default function DemandsPage() {
  const { user } = useAuth();
  const canWrite = user?.role === 'PRODUCTION_PLANNER' || user?.role === 'ADMIN';
  const canReadMaterials = !!user && can(user.role, 'materials');
  const [form] = Form.useForm<DemandFormValues>();
  const changeNote = Form.useWatch('changeNote', form);
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<DemandResponse> | null>(null);
  const [materials, setMaterials] = useState<Record<number, MaterialResponse>>({});
  const materialsRef = useRef<Record<number, MaterialResponse>>({});
  const [failedMaterialIds, setFailedMaterialIds] = useState<number[]>([]);
  const [listError, setListError] = useState<ApiErrorResponse | null>(null);
  const [submitError, setSubmitError] = useState<ApiErrorResponse | null>(null);
  const [detailError, setDetailError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<DemandResponse | null>(null);
  const [detail, setDetail] = useState<DemandResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [query, setQuery] = useState({ page: 0, size: 20, sort: 'id,asc', materialId: undefined as number | undefined, status: undefined as DemandStatus | undefined, requiredFrom: '', requiredTo: '' });
  const requestId = useRef(0);
  const detailRequestId = useRef(0);
  const materialRequests = useRef(new Map<number, Promise<MaterialResponse>>());
  const materialResolutionId = useRef(0);

  const resolveMaterials = useCallback(async (materialIds: number[]) => {
    if (!canReadMaterials) return;
    const current = ++materialResolutionId.current;
    const requests = materialIds.map((materialId) => {
      const existing = materialRequests.current.get(materialId);
      if (existing) return existing;
      const request = getMaterial(materialId);
      materialRequests.current.set(materialId, request);
      void request.finally(() => {
        if (materialRequests.current.get(materialId) === request) materialRequests.current.delete(materialId);
      }).catch(() => undefined);
      return request;
    });
    const results = await Promise.allSettled(requests);
    const loaded: MaterialResponse[] = [];
    const failed: number[] = [];
    results.forEach((result, index) => {
      if (result.status === 'fulfilled') loaded.push(result.value);
      else failed.push(materialIds[index]);
    });
    if (loaded.length) {
      setMaterials((cached) => {
        const next = { ...cached };
        loaded.forEach((material) => { next[material.id] = material; });
        materialsRef.current = next;
        return next;
      });
    }
    if (current === materialResolutionId.current) setFailedMaterialIds(failed);
  }, [canReadMaterials]);

  const load = useCallback(async () => {
    const current = ++requestId.current;
    setLoading(true);
    setListError(null);
    try {
      const response = await listDemands({ ...query, requiredFrom: query.requiredFrom || undefined, requiredTo: query.requiredTo || undefined });
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

  useEffect(() => {
    const missing = [...new Set((data?.content ?? []).map(({ materialId }) => materialId))]
      .filter((materialId) => !materialsRef.current[materialId]);
    materialResolutionId.current += 1;
    if (missing.length && canReadMaterials) void resolveMaterials(missing);
  }, [canReadMaterials, data, resolveMaterials]);

  const currentPageMaterialIds = new Set((data?.content ?? []).map(({ materialId }) => materialId));
  const currentFailedMaterialIds = failedMaterialIds.filter((materialId) => currentPageMaterialIds.has(materialId) && !materials[materialId]);

  const retryMaterials = () => {
    setFailedMaterialIds([]);
    if (currentFailedMaterialIds.length) void resolveMaterials(currentFailedMaterialIds);
  };

  const openCreate = () => {
    setEditing(null);
    setSubmitError(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (demand: DemandResponse) => {
    setEditing(demand);
    setSubmitError(null);
    form.setFieldsValue({ quantity: demand.quantity, requiredDate: demand.requiredDate, note: demand.note ?? '', changeNote: false });
    setModalOpen(true);
  };

  const openDetail = async (id: number) => {
    const current = ++detailRequestId.current;
    setDetail(null);
    setDetailError(null);
    setDetailLoading(true);
    try {
      const response = await getDemand(id);
      if (current === detailRequestId.current) setDetail(response);
    } catch (error) {
      if (current === detailRequestId.current) setDetailError(toApiError(error));
    } finally {
      if (current === detailRequestId.current) setDetailLoading(false);
    }
  };

  const submit = async () => {
    let values: DemandFormValues;
    try { values = await form.validateFields(); } catch { return; }
    setSubmitError(null);
    setSubmitting(true);
    try {
      if (editing) {
        const request = { quantity: values.quantity!, requiredDate: values.requiredDate!, ...(values.changeNote ? { note: values.note?.trim() || null } : {}) };
        await updateDemand(editing.id, request);
        apiMessage.success('需求資料已更新');
      } else {
        const note = values.note?.trim();
        await createDemand({ materialId: values.materialId!, quantity: values.quantity!, requiredDate: values.requiredDate!, ...(note ? { note } : {}) });
        apiMessage.success('需求資料已建立');
      }
      setModalOpen(false);
      form.resetFields();
      await load();
    } catch (error) {
      setSubmitError(toApiError(error));
    } finally {
      setSubmitting(false);
    }
  };

  const cancel = async (demand: DemandResponse) => {
    try {
      await cancelDemand(demand.id);
      apiMessage.success(`需求 #${demand.id} 已取消`);
      await load();
    } catch (error) {
      setListError(toApiError(error));
    }
  };

  const handleTableChange: TableProps<DemandResponse>['onChange'] = (pagination, _filters, sorter) => {
    const value = Array.isArray(sorter) ? sorter[0] : sorter;
    const field = typeof value?.field === 'string' && sortableFields.has(value.field) ? value.field : 'id';
    const direction = value?.order === 'descend' ? 'desc' : 'asc';
    setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20, sort: `${field},${direction}` }));
  };

  const filtered = !!(query.materialId || query.status || query.requiredFrom || query.requiredTo);
  const columns: TableProps<DemandResponse>['columns'] = [
    { title: '需求編號', dataIndex: 'id', sorter: true },
    { title: '物料', dataIndex: 'materialId', render: (id: number) => materials[id] ? `${materials[id].code} — ${materials[id].name}` : `物料 #${id}` },
    { title: '數量', dataIndex: 'quantity', sorter: true, render: (quantity: number, row: DemandResponse) => formatQuantity(quantity, materials[row.materialId]?.baseUnit) },
    { title: '需求日期', dataIndex: 'requiredDate', sorter: true, render: (value: string | null | undefined) => formatDate(value) },
    { title: '狀態', dataIndex: 'status', sorter: true, render: (status: DemandStatus) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{displayLabel(status)}</Tag> },
    { title: '備註', dataIndex: 'note', ellipsis: true, render: (note: string | null) => note || '—' },
    { title: '建立時間', dataIndex: 'createdAt', sorter: true, render: (value: string | null | undefined) => formatDateTime(value) },
    { title: '操作', key: 'actions', render: (_value, demand) => <Space>
      <Button size="small" onClick={() => void openDetail(demand.id)}>檢視</Button>
      {canWrite && demand.status === 'ACTIVE' && <>
        <Button size="small" onClick={() => openEdit(demand)}>編輯</Button>
        <Popconfirm title={`確定取消需求 #${demand.id}？`} description="此動作會將需求標記為「已取消」。" okText="確認取消" cancelText="保留需求" onConfirm={() => void cancel(demand)}>
          <Button danger size="small">取消需求</Button>
        </Popconfirm>
      </>}
    </Space> },
  ];

  const detailMaterial = detail ? materials[detail.materialId] : undefined;
  return <section className="sp-page demand-workspace" aria-labelledby="demand-page-title">
    {contextHolder}
    <header className="demand-workspace__heading sp-page__heading">
      <div>
        <Typography.Title id="demand-page-title" level={2}>需求管理</Typography.Title>
        <Typography.Text className="demand-workspace__intro sp-page__lead">保留既有需求資料維護；V2 採購流程請由採購單工作台建立草稿。</Typography.Text>
      </div>
      {canWrite && <Button className="demand-workspace__create" type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增需求</Button>}
    </header>
    <Space className="demand-workspace__filters" wrap role="search" aria-label="需求篩選">
      <div style={{ minWidth: 280 }}><RelatedEntitySelect entity="material" activeOnly={false} value={query.materialId} onChange={(materialId) => setQuery((current) => ({ ...current, materialId, page: 0 }))} /></div>
      <Select aria-label="需求狀態篩選" placeholder="狀態" allowClear value={query.status} options={statuses.map((status) => ({ value: status, label: displayLabel(status) }))} onChange={(status) => setQuery((current) => ({ ...current, status, page: 0 }))} style={{ width: 150 }} />
      <Input aria-label="需求日期起日" type="date" value={query.requiredFrom} onChange={(event) => setQuery((current) => ({ ...current, requiredFrom: event.target.value, page: 0 }))} />
      <Input aria-label="需求日期迄日" type="date" value={query.requiredTo} onChange={(event) => setQuery((current) => ({ ...current, requiredTo: event.target.value, page: 0 }))} />
    </Space>
    {listError && <ErrorState title="需求清單載入失敗" error={listError} onRetry={() => void load()} />}
    {currentFailedMaterialIds.length > 0 && <Alert type="warning" showIcon title="部分物料資訊載入失敗" description={<Space orientation="vertical"><span>需求資料仍可使用；可重新載入本頁的物料代碼與名稱。</span><Button size="small" onClick={retryMaterials}>重新載入物料資訊</Button></Space>} />}
    {loading && !data ? <LoadingState tip="正在載入需求…" /> : <div className="demand-workspace__table"><Table<DemandResponse>
      rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading}
      locale={{ emptyText: <EmptyState simple description={filtered ? '沒有符合查詢條件的需求' : '目前沒有需求資料'} actionText={canWrite && !filtered ? '新增需求' : undefined} onAction={canWrite && !filtered ? openCreate : undefined} /> }}
      pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }} onChange={handleTableChange}
    /></div>}
    <Modal className="demand-workspace__modal" title={editing ? `編輯需求 #${editing.id}` : '新增需求'} open={modalOpen} onCancel={() => setModalOpen(false)} onOk={() => void submit()} okText={editing ? '儲存變更' : '建立需求'} confirmLoading={submitting} destroyOnHidden>
      {submitError && <ErrorState title="需求送出失敗" error={submitError} />}
      <Form form={form} layout="vertical">
        {!editing && <Form.Item name="materialId" label="物料" rules={[{ required: true, message: '請選擇物料' }]}><RelatedEntitySelect entity="material" /></Form.Item>}
        <Form.Item name="quantity" label="數量" rules={[{ required: true, message: '請輸入數量' }, { type: 'number', min: 0.0001, message: '數量必須大於 0' }]}><InputNumber aria-label="需求數量" precision={4} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="requiredDate" label="需求日期" rules={[{ required: true, message: '請選擇需求日期' }]}><Input aria-label="需求日期" type="date" /></Form.Item>
        {editing && <Form.Item name="changeNote" valuePropName="checked"><Checkbox>修改備註（未勾選時不修改；勾選後留空會清除）</Checkbox></Form.Item>}
        <Form.Item name="note" label="備註" rules={[{ max: 500, message: '備註不可超過 500 個字元' }]}><Input.TextArea aria-label="需求備註" maxLength={500} showCount rows={3} disabled={!!editing && !changeNote} /></Form.Item>
      </Form>
    </Modal>
    <Drawer className="demand-workspace__drawer" title={detail ? `需求 #${detail.id}` : '需求明細'} open={detailLoading || !!detail || !!detailError} onClose={() => { detailRequestId.current += 1; setDetail(null); setDetailError(null); setDetailLoading(false); }} size="large">
      {detailLoading && <LoadingState tip="正在載入需求明細…" />}
      {detailError && <ErrorState title="需求詳細資料載入失敗" error={detailError} />}
      {detail && <Descriptions column={1} bordered size="small" items={[
        { key: 'material', label: '物料', children: detailMaterial ? `${detailMaterial.code} — ${detailMaterial.name}` : `物料 #${detail.materialId}` },
        { key: 'quantity', label: '數量', children: formatQuantity(detail.quantity, detailMaterial?.baseUnit) },
        { key: 'requiredDate', label: '需求日期', children: formatDate(detail.requiredDate) },
        { key: 'status', label: '狀態', children: <Tag color={detail.status === 'ACTIVE' ? 'green' : 'default'}>{displayLabel(detail.status)}</Tag> },
        { key: 'note', label: '備註', children: detail.note || '—' },
        { key: 'createdBy', label: '建立人員', children: `人員 #${detail.createdBy}` },
        { key: 'createdAt', label: '建立時間', children: formatDateTime(detail.createdAt) },
        { key: 'updatedAt', label: '更新時間', children: formatDateTime(detail.updatedAt) },
      ]} />}
    </Drawer>
  </section>;
}
