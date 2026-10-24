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
import { displayLabel } from '../../utils/displayLabels';
import { formatQuantity } from '../../utils/displayFormatters';

const units: BaseUnit[] = ['G', 'KG', 'ML', 'L', 'PCS'];
const statuses: MaterialStatus[] = ['ACTIVE', 'INACTIVE'];
type MaterialFormValues = CreateMaterialRequest & { status?: MaterialStatus };

export default function MaterialsPage() {
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';
  const [form] = Form.useForm<MaterialFormValues>();
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<MaterialResponse> | null>(null);
  const [listError, setListError] = useState<ApiErrorResponse | null>(null);
  const [submitError, setSubmitError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<MaterialResponse | null>(null);
  const [query, setQuery] = useState({ page: 0, size: 20, sort: 'id,asc', code: '', name: '', status: undefined as MaterialStatus | undefined });
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const current = ++requestId.current;
    setLoading(true);
    setListError(null);
    try {
      const response = await listMaterials({ ...query, code: query.code || undefined, name: query.name || undefined });
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
    setSubmitError(null);
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ baseUnit: 'PCS', safetyStock: 0 });
    setModalOpen(true);
  };

  const openEdit = (material: MaterialResponse) => {
    setSubmitError(null);
    setEditing(material);
    form.setFieldsValue({ code: material.code, name: material.name, baseUnit: material.baseUnit, safetyStock: material.safetyStock, status: material.status });
    setModalOpen(true);
  };

  const submit = async () => {
    let values: MaterialFormValues;
    try { values = await form.validateFields(); } catch { return; }
    setSubmitError(null);
    setSubmitting(true);
    try {
      if (editing) {
        const request: UpdateMaterialRequest = { name: values.name.trim(), safetyStock: values.safetyStock, status: values.status };
        await updateMaterial(editing.id, request);
        apiMessage.success('物料已更新');
      } else {
        const request: CreateMaterialRequest = { code: values.code.trim(), name: values.name.trim(), baseUnit: values.baseUnit, safetyStock: values.safetyStock };
        await createMaterial(request);
        apiMessage.success('物料已建立');
      }
      setModalOpen(false);
      form.resetFields();
      await load();
    } catch (submitError) {
      setSubmitError(toApiError(submitError));
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
      setListError(toApiError(statusError));
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
  const columns: TableProps<MaterialResponse>['columns'] = [
    { title: '物料代碼', dataIndex: 'code', sorter: true },
    { title: '名稱', dataIndex: 'name', sorter: true },
    { title: '安全庫存', dataIndex: 'safetyStock', sorter: true, render: (value, row) => formatQuantity(value, row.baseUnit) },
    { title: '狀態', dataIndex: 'status', sorter: true, render: (status: MaterialStatus) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{displayLabel(status)}</Tag> },
    ...(isAdmin ? [{ title: '操作', key: 'actions', render: (_: unknown, material: MaterialResponse) => <Space>
      <Button size="small" onClick={() => openEdit(material)}>編輯</Button>
      <Popconfirm title={`確定要將此物料設為${material.status === 'ACTIVE' ? '停用' : '啟用'}？`} onConfirm={() => void changeStatus(material)}>
        <Button size="small">{material.status === 'ACTIVE' ? '停用' : '啟用'}</Button>
      </Popconfirm>
    </Space> }] : []),
  ];

  return <div>
    {contextHolder}
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
        <div><Typography.Title level={2} style={{ marginBottom: 0 }}>物料管理</Typography.Title><Typography.Text type="secondary">維護物料主檔與安全庫存</Typography.Text></div>
        {isAdmin && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增物料</Button>}
      </Space>
      <Space wrap style={{ maxWidth: '100%' }}>
        <Input aria-label="物料代碼篩選" placeholder="物料代碼" value={query.code} onChange={(event) => setQuery((current) => ({ ...current, code: event.target.value, page: 0 }))} allowClear />
        <Input aria-label="物料名稱篩選" placeholder="物料名稱" value={query.name} onChange={(event) => setQuery((current) => ({ ...current, name: event.target.value, page: 0 }))} allowClear />
        <Select aria-label="物料狀態篩選" placeholder="狀態" value={query.status} options={statuses.map((status) => ({ value: status, label: displayLabel(status) }))} onChange={(status) => setQuery((current) => ({ ...current, status, page: 0 }))} allowClear style={{ width: 150 }} />
      </Space>
      {listError && <ErrorState title="物料查詢失敗" error={listError} onRetry={() => void load()} />}
      {loading && !data ? <LoadingState tip="正在載入物料…" /> : <Table<MaterialResponse>
        rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading} scroll={{ x: 'max-content' }}
        locale={{ emptyText: <EmptyState simple description={filtered ? '沒有符合條件的物料' : '目前沒有物料資料'} actionText={isAdmin && !filtered ? '新增物料' : undefined} onAction={isAdmin && !filtered ? openCreate : undefined} /> }}
        pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }}
        onChange={handleTableChange}
      />}
    </Space>
    <Modal title={editing ? '編輯物料' : '新增物料'} open={modalOpen} onCancel={() => !submitting && setModalOpen(false)} onOk={() => void submit()} confirmLoading={submitting} okButtonProps={{ disabled: submitting }} destroyOnHidden>
      {submitError && <ErrorState title="物料送出失敗" error={submitError} />}
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item name="code" label="物料代碼" rules={[{ required: true, whitespace: true, message: '請輸入物料代碼' }, { max: 50 }]}><Input disabled={!!editing} /></Form.Item>
        <Form.Item name="name" label="名稱" rules={[{ required: true, whitespace: true, message: '請輸入物料名稱' }, { max: 100 }]}><Input /></Form.Item>
        <Form.Item name="baseUnit" label="基本單位" rules={[{ required: true, message: '請選擇基本單位' }]}><Select disabled={!!editing} options={units.map((unit) => ({ value: unit, label: unit }))} /></Form.Item>
        <Form.Item name="safetyStock" label="安全庫存" rules={[{ required: true, message: '請輸入安全庫存' }, { type: 'number', min: 0 }]}><InputNumber style={{ width: '100%' }} /></Form.Item>
        {editing && <Form.Item name="status" label="狀態" rules={[{ required: true }]}><Select options={statuses.map((status) => ({ value: status, label: displayLabel(status) }))} /></Form.Item>}
      </Form>
    </Modal>
  </div>;
}
