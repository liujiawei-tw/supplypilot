import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Checkbox, Descriptions, Drawer, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { cancelDemand, createDemand, getDemand, listDemands, updateDemand, type DemandResponse, type DemandStatus } from '../../api/demand';
import { toApiError } from '../../api/apiError';
import { getMaterial, type MaterialResponse } from '../../api/material';
import { useAuth } from '../../auth/useAuth';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import RelatedEntitySelect from '../../components/RelatedEntitySelect';
import type { ApiErrorResponse, PageResponse } from '../../types/api';

const statuses: DemandStatus[] = ['ACTIVE', 'CANCELLED'];
const sortableFields = new Set(['id', 'quantity', 'requiredDate', 'status', 'createdAt', 'updatedAt']);

interface DemandFormValues {
  materialId?: number;
  quantity?: number;
  requiredDate?: string;
  note?: string;
  changeNote?: boolean;
}

function formatTimestamp(value: string): string {
  return new Intl.DateTimeFormat('zh-TW', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

export default function DemandsPage() {
  const { user } = useAuth();
  const canWrite = user?.role === 'PRODUCTION_PLANNER' || user?.role === 'ADMIN';
  const [form] = Form.useForm<DemandFormValues>();
  const changeNote = Form.useWatch('changeNote', form);
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<DemandResponse> | null>(null);
  const [materials, setMaterials] = useState<Record<number, MaterialResponse>>({});
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
  const requestedMaterials = useRef(new Set<number>());

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
      .filter((materialId) => !materials[materialId] && !requestedMaterials.current.has(materialId));
    if (!missing.length) return;
    missing.forEach((materialId) => requestedMaterials.current.add(materialId));
    let active = true;
    void Promise.allSettled(missing.map((id) => getMaterial(id))).then((results) => {
      if (!active) return;
      setMaterials((current) => {
        const next = { ...current };
        results.forEach((result) => { if (result.status === 'fulfilled') next[result.value.id] = result.value; });
        return next;
      });
    });
    return () => { active = false; };
  }, [data, materials]);

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
        apiMessage.success('Demand 已更新');
      } else {
        const note = values.note?.trim();
        await createDemand({ materialId: values.materialId!, quantity: values.quantity!, requiredDate: values.requiredDate!, ...(note ? { note } : {}) });
        apiMessage.success('Demand 已建立');
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
      apiMessage.success(`Demand #${demand.id} 已取消`);
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
    { title: 'Demand ID', dataIndex: 'id', sorter: true },
    { title: 'Material', dataIndex: 'materialId', render: (id: number) => materials[id] ? `${materials[id].code} — ${materials[id].name}` : `Material #${id}` },
    { title: 'Quantity', dataIndex: 'quantity', sorter: true },
    { title: 'Required Date', dataIndex: 'requiredDate', sorter: true },
    { title: 'Status', dataIndex: 'status', sorter: true, render: (status: DemandStatus) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status}</Tag> },
    { title: 'Note', dataIndex: 'note', ellipsis: true, render: (note: string | null) => note || '—' },
    { title: 'Created At', dataIndex: 'createdAt', sorter: true, render: formatTimestamp },
    { title: 'Actions', key: 'actions', render: (_value, demand) => <Space>
      <Button size="small" onClick={() => void openDetail(demand.id)}>Detail</Button>
      {canWrite && demand.status === 'ACTIVE' && <>
        <Button size="small" onClick={() => openEdit(demand)}>Edit</Button>
        <Popconfirm title={`取消 Demand #${demand.id}？`} description="此動作會將 Demand 標記為 CANCELLED。" okText="Confirm Cancel" cancelText="Keep Demand" onConfirm={() => void cancel(demand)}>
          <Button danger size="small">Cancel</Button>
        </Popconfirm>
      </>}
    </Space> },
  ];

  const detailMaterial = detail ? materials[detail.materialId] : undefined;
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}
    <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
      <div><Typography.Title level={2} style={{ marginBottom: 0 }}>Demands</Typography.Title><Typography.Text type="secondary">生產需求與狀態管理</Typography.Text></div>
      {canWrite && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>Create Demand</Button>}
    </Space>
    <Space wrap>
      <div style={{ minWidth: 280 }}><RelatedEntitySelect entity="material" activeOnly={false} value={query.materialId} onChange={(materialId) => setQuery((current) => ({ ...current, materialId, page: 0 }))} /></div>
      <Select aria-label="Demand status filter" placeholder="Status" allowClear value={query.status} options={statuses.map((status) => ({ value: status }))} onChange={(status) => setQuery((current) => ({ ...current, status, page: 0 }))} style={{ width: 150 }} />
      <Input aria-label="Required from" type="date" value={query.requiredFrom} onChange={(event) => setQuery((current) => ({ ...current, requiredFrom: event.target.value, page: 0 }))} />
      <Input aria-label="Required to" type="date" value={query.requiredTo} onChange={(event) => setQuery((current) => ({ ...current, requiredTo: event.target.value, page: 0 }))} />
    </Space>
    {listError && <ErrorState title="Demand request failed" error={listError} onRetry={() => void load()} />}
    {loading && !data ? <LoadingState tip="Loading demands..." /> : <Table<DemandResponse>
      rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading}
      locale={{ emptyText: <EmptyState simple description={filtered ? '沒有符合查詢條件的 Demand' : '尚無 Demand 資料'} actionText={canWrite && !filtered ? 'Create Demand' : undefined} onAction={canWrite && !filtered ? openCreate : undefined} /> }}
      pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }} onChange={handleTableChange}
    />}
    <Modal title={editing ? `Edit Demand #${editing.id}` : 'Create Demand'} open={modalOpen} onCancel={() => setModalOpen(false)} onOk={() => void submit()} confirmLoading={submitting} destroyOnHidden>
      {submitError && <ErrorState title="Demand submit failed" error={submitError} />}
      <Form form={form} layout="vertical">
        {!editing && <Form.Item name="materialId" label="Material" rules={[{ required: true, message: 'Material is required' }]}><RelatedEntitySelect entity="material" /></Form.Item>}
        <Form.Item name="quantity" label="Quantity" rules={[{ required: true, message: 'Quantity is required' }, { type: 'number', min: 0.0001, message: 'Quantity must be greater than 0' }]}><InputNumber aria-label="Demand quantity" precision={4} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="requiredDate" label="Required Date" rules={[{ required: true, message: 'Required date is required' }]}><Input aria-label="Demand required date" type="date" /></Form.Item>
        {editing && <Form.Item name="changeNote" valuePropName="checked"><Checkbox>Change note（未勾選時不修改；勾選後留空會清除）</Checkbox></Form.Item>}
        <Form.Item name="note" label="Note" rules={[{ max: 500, message: 'Note must be 500 characters or fewer' }]}><Input.TextArea aria-label="Demand note" maxLength={500} showCount rows={3} disabled={!!editing && !changeNote} /></Form.Item>
      </Form>
    </Modal>
    <Drawer title={detail ? `Demand #${detail.id}` : 'Demand Detail'} open={detailLoading || !!detail || !!detailError} onClose={() => { detailRequestId.current += 1; setDetail(null); setDetailError(null); setDetailLoading(false); }} size="large">
      {detailLoading && <LoadingState tip="Loading demand detail..." />}
      {detailError && <ErrorState title="Demand detail failed" error={detailError} />}
      {detail && <Descriptions column={1} bordered size="small" items={[
        { key: 'material', label: 'Material', children: detailMaterial ? `${detailMaterial.code} — ${detailMaterial.name}` : `Material #${detail.materialId}` },
        { key: 'quantity', label: 'Quantity', children: detail.quantity },
        { key: 'requiredDate', label: 'Required Date', children: detail.requiredDate },
        { key: 'status', label: 'Status', children: <Tag color={detail.status === 'ACTIVE' ? 'green' : 'default'}>{detail.status}</Tag> },
        { key: 'note', label: 'Note', children: detail.note || '—' },
        { key: 'createdBy', label: 'Created By', children: `User #${detail.createdBy}` },
        { key: 'createdAt', label: 'Created At', children: formatTimestamp(detail.createdAt) },
        { key: 'updatedAt', label: 'Updated At', children: formatTimestamp(detail.updatedAt) },
      ]} />}
    </Drawer>
  </Space>;
}
