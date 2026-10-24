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
import { displayLabel } from '../../utils/displayLabels';
import { formatDecimal } from '../../utils/displayFormatters';

const statuses: SupplierStatus[] = ['ACTIVE', 'INACTIVE'];
type SupplierFormValues = CreateSupplierRequest & { status?: SupplierStatus };

export default function SupplierList({ isAdmin }: { isAdmin: boolean }) {
  const [form] = Form.useForm<SupplierFormValues>();
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<SupplierResponse> | null>(null);
  const [listError, setListError] = useState<ApiErrorResponse | null>(null);
  const [submitError, setSubmitError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SupplierResponse | null>(null);
  const [query, setQuery] = useState({ page: 0, size: 20, sort: 'id,asc', code: '', name: '', status: undefined as SupplierStatus | undefined });
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const current = ++requestId.current;
    setLoading(true); setListError(null);
    try {
      const response = await listSuppliers({ ...query, code: query.code || undefined, name: query.name || undefined });
      if (current === requestId.current) setData(response);
    } catch (loadError) {
      if (current === requestId.current) setListError(toApiError(loadError));
    } finally {
      if (current === requestId.current) setLoading(false);
    }
  }, [query]);
  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  const openCreate = () => {
    setSubmitError(null); setEditing(null); form.resetFields(); form.setFieldsValue({ reliabilityScore: 0, qualityScore: 0 }); setModalOpen(true);
  };
  const openEdit = (supplier: SupplierResponse) => {
    setSubmitError(null);
    setEditing(supplier);
    form.setFieldsValue({ code: supplier.code, name: supplier.name, country: supplier.country ?? undefined, reliabilityScore: supplier.reliabilityScore, qualityScore: supplier.qualityScore, status: supplier.status });
    setModalOpen(true);
  };
  const submit = async () => {
    let values: SupplierFormValues;
    try { values = await form.validateFields(); } catch { return; }
    setSubmitError(null);
    setSubmitting(true);
    try {
      if (editing) {
        const request: UpdateSupplierRequest = { name: values.name.trim(), country: values.country?.trim() ?? '', reliabilityScore: values.reliabilityScore, qualityScore: values.qualityScore, status: values.status };
        await updateSupplier(editing.id, request); apiMessage.success('供應商已更新');
      } else {
        const request: CreateSupplierRequest = { code: values.code.trim(), name: values.name.trim(), country: values.country?.trim() || undefined, reliabilityScore: values.reliabilityScore, qualityScore: values.qualityScore };
        await createSupplier(request); apiMessage.success('供應商已建立');
      }
      setModalOpen(false); form.resetFields(); await load();
    } catch (submitError) {
      setSubmitError(toApiError(submitError));
    } finally { setSubmitting(false); }
  };
  const changeStatus = async (supplier: SupplierResponse) => {
    try {
      await updateSupplier(supplier.id, { status: supplier.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' });
      apiMessage.success('狀態已更新'); await load();
    } catch (statusError) { setListError(toApiError(statusError)); }
  };
  const handleTableChange: TableProps<SupplierResponse>['onChange'] = (pagination, _filters, sorter) => {
    const sortValue = Array.isArray(sorter) ? sorter[0] : sorter;
    setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20, sort: typeof sortValue?.field === 'string' && sortValue.order ? `${sortValue.field},${sortValue.order === 'ascend' ? 'asc' : 'desc'}` : 'id,asc' }));
  };
  const filtered = !!(query.code || query.name || query.status);
  const columns = [
    { title: '供應商代碼', dataIndex: 'code', sorter: true }, { title: '名稱', dataIndex: 'name', sorter: true },
    { title: '國家／地區', dataIndex: 'country', sorter: true, render: (country: string | null) => country || '—' },
    { title: '可靠度', dataIndex: 'reliabilityScore', sorter: true, render: (value: SupplierResponse['reliabilityScore']) => formatDecimal(value) }, { title: '品質評分', dataIndex: 'qualityScore', sorter: true, render: (value: SupplierResponse['qualityScore']) => formatDecimal(value) },
    { title: '狀態', dataIndex: 'status', sorter: true, render: (status: SupplierStatus) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{displayLabel(status)}</Tag> },
    ...(isAdmin ? [{ title: '操作', key: 'actions', render: (_: unknown, supplier: SupplierResponse) => <Space>
      <Button size="small" onClick={() => openEdit(supplier)}>編輯</Button>
      <Popconfirm title={`確定要將此供應商設為${supplier.status === 'ACTIVE' ? '停用' : '啟用'}？`} onConfirm={() => void changeStatus(supplier)}><Button size="small">{supplier.status === 'ACTIVE' ? '停用' : '啟用'}</Button></Popconfirm>
    </Space> }] : []),
  ];
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}
    <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
      <Space wrap>
        <Input aria-label="供應商代碼篩選" placeholder="供應商代碼" value={query.code} onChange={(event) => setQuery((current) => ({ ...current, code: event.target.value, page: 0 }))} allowClear />
        <Input aria-label="供應商名稱篩選" placeholder="供應商名稱" value={query.name} onChange={(event) => setQuery((current) => ({ ...current, name: event.target.value, page: 0 }))} allowClear />
        <Select aria-label="供應商狀態篩選" placeholder="狀態" value={query.status} options={statuses.map((status) => ({ value: status, label: displayLabel(status) }))} onChange={(status) => setQuery((current) => ({ ...current, status, page: 0 }))} allowClear style={{ width: 150 }} />
      </Space>
      {isAdmin && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增供應商</Button>}
    </Space>
    {listError && <ErrorState title="供應商查詢失敗" error={listError} onRetry={() => void load()} />}
    {loading && !data ? <LoadingState tip="正在載入供應商…" /> : <Table<SupplierResponse> rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading} scroll={{ x: 'max-content' }}
      locale={{ emptyText: <EmptyState simple description={filtered ? '沒有符合條件的供應商' : '目前沒有供應商資料'} actionText={isAdmin && !filtered ? '新增供應商' : undefined} onAction={isAdmin && !filtered ? openCreate : undefined} /> }}
      pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }} onChange={handleTableChange} />}
    <Modal title={editing ? '編輯供應商' : '新增供應商'} open={modalOpen} onCancel={() => !submitting && setModalOpen(false)} onOk={() => void submit()} confirmLoading={submitting} okButtonProps={{ disabled: submitting }} destroyOnHidden>
      {submitError && <ErrorState title="供應商送出失敗" error={submitError} />}
      <Form form={form} layout="vertical">
        <Form.Item name="code" label="供應商代碼" rules={[{ required: true, whitespace: true, message: '請輸入供應商代碼' }, { max: 50 }]}><Input disabled={!!editing} /></Form.Item>
        <Form.Item name="name" label="名稱" rules={[{ required: true, whitespace: true, message: '請輸入供應商名稱' }, { max: 100 }]}><Input /></Form.Item>
        <Form.Item name="country" label="國家／地區" rules={[{ max: 100 }]}><Input /></Form.Item>
        <Form.Item name="reliabilityScore" label="可靠度評分" rules={[{ required: true }, { type: 'number', min: 0, max: 100 }]}><InputNumber min={0} max={100} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="qualityScore" label="品質評分" rules={[{ required: true }, { type: 'number', min: 0, max: 100 }]}><InputNumber min={0} max={100} style={{ width: '100%' }} /></Form.Item>
        {editing && <Form.Item name="status" label="狀態" rules={[{ required: true }]}><Select options={statuses.map((status) => ({ value: status, label: displayLabel(status) }))} /></Form.Item>}
      </Form>
    </Modal>
  </Space>;
}
