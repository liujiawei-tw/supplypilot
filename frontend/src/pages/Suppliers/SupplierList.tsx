import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Table, Tag } from 'antd';
import type { TableProps } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { createSupplier, listSuppliers, updateSupplier, type CreateSupplierRequest, type SupplierResponse, type SupplierStatus, type UpdateSupplierRequest } from '../../api/supplier';
import { toApiError } from '../../api/apiError';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import type { ApiErrorResponse, PageResponse } from '../../types/api';

const statuses: SupplierStatus[] = ['ACTIVE', 'INACTIVE'];
type SupplierFormValues = CreateSupplierRequest & { status?: SupplierStatus };

export default function SupplierList({ isAdmin }: { isAdmin: boolean }) {
  const [form] = Form.useForm<SupplierFormValues>();
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<SupplierResponse> | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SupplierResponse | null>(null);
  const [query, setQuery] = useState({ page: 0, size: 20, sort: 'id,asc', code: '', name: '', status: undefined as SupplierStatus | undefined });
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const current = ++requestId.current;
    setLoading(true); setError(null);
    try {
      const response = await listSuppliers({ ...query, code: query.code || undefined, name: query.name || undefined });
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
    setEditing(null); form.resetFields(); form.setFieldsValue({ reliabilityScore: 0, qualityScore: 0 }); setModalOpen(true);
  };
  const openEdit = (supplier: SupplierResponse) => {
    setEditing(supplier);
    form.setFieldsValue({ code: supplier.code, name: supplier.name, country: supplier.country ?? undefined, reliabilityScore: supplier.reliabilityScore, qualityScore: supplier.qualityScore, status: supplier.status });
    setModalOpen(true);
  };
  const submit = async () => {
    let values: SupplierFormValues;
    try { values = await form.validateFields(); } catch { return; }
    setSubmitting(true);
    try {
      if (editing) {
        const request: UpdateSupplierRequest = { name: values.name.trim(), country: values.country?.trim() ?? '', reliabilityScore: values.reliabilityScore, qualityScore: values.qualityScore, status: values.status };
        await updateSupplier(editing.id, request); apiMessage.success('Supplier 已更新');
      } else {
        const request: CreateSupplierRequest = { code: values.code.trim(), name: values.name.trim(), country: values.country?.trim() || undefined, reliabilityScore: values.reliabilityScore, qualityScore: values.qualityScore };
        await createSupplier(request); apiMessage.success('Supplier 已建立');
      }
      setModalOpen(false); form.resetFields(); await load();
    } catch (submitError) {
      setError(toApiError(submitError));
    } finally { setSubmitting(false); }
  };
  const changeStatus = async (supplier: SupplierResponse) => {
    try {
      await updateSupplier(supplier.id, { status: supplier.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' });
      apiMessage.success('狀態已更新'); await load();
    } catch (statusError) { setError(toApiError(statusError)); }
  };
  const handleTableChange: TableProps<SupplierResponse>['onChange'] = (pagination, _filters, sorter) => {
    const sortValue = Array.isArray(sorter) ? sorter[0] : sorter;
    setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20, sort: typeof sortValue?.field === 'string' && sortValue.order ? `${sortValue.field},${sortValue.order === 'ascend' ? 'asc' : 'desc'}` : 'id,asc' }));
  };
  const filtered = !!(query.code || query.name || query.status);
  const columns = [
    { title: 'Code', dataIndex: 'code', sorter: true }, { title: 'Name', dataIndex: 'name', sorter: true },
    { title: 'Country', dataIndex: 'country', sorter: true, render: (country: string | null) => country || '—' },
    { title: 'Reliability', dataIndex: 'reliabilityScore', sorter: true }, { title: 'Quality', dataIndex: 'qualityScore', sorter: true },
    { title: 'Status', dataIndex: 'status', sorter: true, render: (status: SupplierStatus) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status}</Tag> },
    ...(isAdmin ? [{ title: 'Actions', key: 'actions', render: (_: unknown, supplier: SupplierResponse) => <Space>
      <Button size="small" onClick={() => openEdit(supplier)}>Edit</Button>
      <Popconfirm title={`確定要將此 Supplier 設為 ${supplier.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'}？`} onConfirm={() => void changeStatus(supplier)}><Button size="small">{supplier.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}</Button></Popconfirm>
    </Space> }] : []),
  ];
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}
    <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
      <Space wrap>
        <Input aria-label="Supplier code filter" placeholder="Code" value={query.code} onChange={(event) => setQuery((current) => ({ ...current, code: event.target.value, page: 0 }))} allowClear />
        <Input aria-label="Supplier name filter" placeholder="Name" value={query.name} onChange={(event) => setQuery((current) => ({ ...current, name: event.target.value, page: 0 }))} allowClear />
        <Select aria-label="Supplier status filter" placeholder="Status" value={query.status} options={statuses.map((status) => ({ value: status, label: status }))} onChange={(status) => setQuery((current) => ({ ...current, status, page: 0 }))} allowClear style={{ width: 150 }} />
      </Space>
      {isAdmin && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>Create Supplier</Button>}
    </Space>
    {error && <ErrorState title="Supplier request failed" error={error} onRetry={() => void load()} />}
    {loading && !data ? <LoadingState tip="Loading suppliers..." /> : <Table<SupplierResponse> rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading}
      locale={{ emptyText: <EmptyState simple description={filtered ? '沒有符合查詢條件的 Supplier' : '尚無 Supplier 資料'} actionText={isAdmin && !filtered ? 'Create Supplier' : undefined} onAction={isAdmin && !filtered ? openCreate : undefined} /> }}
      pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }} onChange={handleTableChange} />}
    <Modal title={editing ? 'Edit Supplier' : 'Create Supplier'} open={modalOpen} onCancel={() => !submitting && setModalOpen(false)} onOk={() => void submit()} confirmLoading={submitting} okButtonProps={{ disabled: submitting }} destroyOnHidden>
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item name="code" label="Code" rules={[{ required: true, whitespace: true }, { max: 50 }]}><Input disabled={!!editing} /></Form.Item>
        <Form.Item name="name" label="Name" rules={[{ required: true, whitespace: true }, { max: 100 }]}><Input /></Form.Item>
        <Form.Item name="country" label="Country" rules={[{ max: 100 }]}><Input /></Form.Item>
        <Form.Item name="reliabilityScore" label="Reliability Score" rules={[{ required: true }, { type: 'number', min: 0, max: 100 }]}><InputNumber min={0} max={100} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="qualityScore" label="Quality Score" rules={[{ required: true }, { type: 'number', min: 0, max: 100 }]}><InputNumber min={0} max={100} style={{ width: '100%' }} /></Form.Item>
        {editing && <Form.Item name="status" label="Status" rules={[{ required: true }]}><Select options={statuses.map((status) => ({ value: status, label: status }))} /></Form.Item>}
      </Form>
    </Modal>
  </Space>;
}
