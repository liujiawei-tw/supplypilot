import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { createMaterial, listMaterials, updateMaterial, type BaseUnit, type CreateMaterialRequest, type MaterialResponse, type MaterialStatus, type UpdateMaterialRequest } from '../../api/material';
import { toApiError } from '../../api/apiError';
import { useAuth } from '../../auth/useAuth';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import type { ApiErrorResponse, PageResponse } from '../../types/api';

const units: BaseUnit[] = ['G', 'KG', 'ML', 'L', 'PCS'];
const statuses: MaterialStatus[] = ['ACTIVE', 'INACTIVE'];
type MaterialFormValues = CreateMaterialRequest & { status?: MaterialStatus };

export default function MaterialsPage() {
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';
  const [form] = Form.useForm<MaterialFormValues>();
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<MaterialResponse> | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<MaterialResponse | null>(null);
  const [query, setQuery] = useState({ page: 0, size: 20, sort: 'id,asc', code: '', name: '', status: undefined as MaterialStatus | undefined });
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const current = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const response = await listMaterials({ ...query, code: query.code || undefined, name: query.name || undefined });
      if (current === requestId.current) setData(response);
    } catch (loadError) {
      if (current === requestId.current) setError(toApiError(loadError));
    } finally {
      if (current === requestId.current) setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ baseUnit: 'PCS', safetyStock: 0 });
    setModalOpen(true);
  };

  const openEdit = (material: MaterialResponse) => {
    setEditing(material);
    form.setFieldsValue({ code: material.code, name: material.name, baseUnit: material.baseUnit, safetyStock: material.safetyStock, status: material.status });
    setModalOpen(true);
  };

  const submit = async () => {
    let values: MaterialFormValues;
    try { values = await form.validateFields(); } catch { return; }
    setSubmitting(true);
    try {
      if (editing) {
        const request: UpdateMaterialRequest = { name: values.name.trim(), safetyStock: values.safetyStock, status: values.status };
        await updateMaterial(editing.id, request);
        apiMessage.success('Material 已更新');
      } else {
        const request: CreateMaterialRequest = { code: values.code.trim(), name: values.name.trim(), baseUnit: values.baseUnit, safetyStock: values.safetyStock };
        await createMaterial(request);
        apiMessage.success('Material 已建立');
      }
      setModalOpen(false);
      form.resetFields();
      await load();
    } catch (submitError) {
      setError(toApiError(submitError));
    } finally {
      setSubmitting(false);
    }
  };

  const changeStatus = async (material: MaterialResponse) => {
    try {
      await updateMaterial(material.id, { status: material.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' });
      apiMessage.success('狀態已更新');
      await load();
    } catch (statusError) {
      setError(toApiError(statusError));
    }
  };

  const handleTableChange: TableProps<MaterialResponse>['onChange'] = (pagination, _filters, sorter) => {
    const sortValue = Array.isArray(sorter) ? sorter[0] : sorter;
    setQuery((current) => ({
      ...current,
      page: (pagination.current ?? 1) - 1,
      size: pagination.pageSize ?? 20,
      sort: typeof sortValue?.field === 'string' && sortValue.order ? `${sortValue.field},${sortValue.order === 'ascend' ? 'asc' : 'desc'}` : 'id,asc',
    }));
  };

  const filtered = !!(query.code || query.name || query.status);
  const columns = [
    { title: 'Code', dataIndex: 'code', sorter: true },
    { title: 'Name', dataIndex: 'name', sorter: true },
    { title: 'Base Unit', dataIndex: 'baseUnit', sorter: true },
    { title: 'Safety Stock', dataIndex: 'safetyStock', sorter: true },
    { title: 'Status', dataIndex: 'status', sorter: true, render: (status: MaterialStatus) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status}</Tag> },
    ...(isAdmin ? [{ title: 'Actions', key: 'actions', render: (_: unknown, material: MaterialResponse) => <Space>
      <Button size="small" onClick={() => openEdit(material)}>Edit</Button>
      <Popconfirm title={`確定要將此 Material 設為 ${material.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'}？`} onConfirm={() => void changeStatus(material)}>
        <Button size="small">{material.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}</Button>
      </Popconfirm>
    </Space> }] : []),
  ];

  return <div>
    {contextHolder}
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
        <div><Typography.Title level={2} style={{ marginBottom: 0 }}>Materials</Typography.Title><Typography.Text type="secondary">物料主檔與安全庫存</Typography.Text></div>
        {isAdmin && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>Create Material</Button>}
      </Space>
      <Space wrap>
        <Input aria-label="Material code filter" placeholder="Code" value={query.code} onChange={(event) => setQuery((current) => ({ ...current, code: event.target.value, page: 0 }))} allowClear />
        <Input aria-label="Material name filter" placeholder="Name" value={query.name} onChange={(event) => setQuery((current) => ({ ...current, name: event.target.value, page: 0 }))} allowClear />
        <Select aria-label="Material status filter" placeholder="Status" value={query.status} options={statuses.map((status) => ({ value: status, label: status }))} onChange={(status) => setQuery((current) => ({ ...current, status, page: 0 }))} allowClear style={{ width: 150 }} />
      </Space>
      {error && <ErrorState title="Material request failed" error={error} onRetry={() => void load()} />}
      {loading && !data ? <LoadingState tip="Loading materials..." /> : <Table<MaterialResponse>
        rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading}
        locale={{ emptyText: <EmptyState simple description={filtered ? '沒有符合查詢條件的 Material' : '尚無 Material 資料'} actionText={isAdmin && !filtered ? 'Create Material' : undefined} onAction={isAdmin && !filtered ? openCreate : undefined} /> }}
        pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }}
        onChange={handleTableChange}
      />}
    </Space>
    <Modal title={editing ? 'Edit Material' : 'Create Material'} open={modalOpen} onCancel={() => !submitting && setModalOpen(false)} onOk={() => void submit()} confirmLoading={submitting} okButtonProps={{ disabled: submitting }} destroyOnHidden>
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item name="code" label="Code" rules={[{ required: true, whitespace: true }, { max: 50 }]}><Input disabled={!!editing} /></Form.Item>
        <Form.Item name="name" label="Name" rules={[{ required: true, whitespace: true }, { max: 100 }]}><Input /></Form.Item>
        <Form.Item name="baseUnit" label="Base Unit" rules={[{ required: true }]}><Select disabled={!!editing} options={units.map((unit) => ({ value: unit, label: unit }))} /></Form.Item>
        <Form.Item name="safetyStock" label="Safety Stock" rules={[{ required: true }, { type: 'number', min: 0 }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
        {editing && <Form.Item name="status" label="Status" rules={[{ required: true }]}><Select options={statuses.map((status) => ({ value: status, label: status }))} /></Form.Item>}
      </Form>
    </Modal>
  </div>;
}
