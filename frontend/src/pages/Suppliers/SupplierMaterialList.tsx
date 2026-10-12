import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { getMaterial, type MaterialResponse } from '../../api/material';
import { getSupplier, type SupplierResponse } from '../../api/supplier';
import { createSupplierMaterial, listSupplierMaterials, updateSupplierMaterial, type CreateSupplierMaterialRequest, type SupplierMaterialResponse, type SupplierMaterialStatus, type UpdateSupplierMaterialRequest } from '../../api/supplierMaterial';
import { toApiError } from '../../api/apiError';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import RelatedEntitySelect from '../../components/RelatedEntitySelect';
import type { ApiErrorResponse, PageResponse } from '../../types/api';

const statuses: SupplierMaterialStatus[] = ['ACTIVE', 'INACTIVE'];
type SupplierMaterialFormValues = CreateSupplierMaterialRequest & { status?: SupplierMaterialStatus };

export default function SupplierMaterialList({ isAdmin }: { isAdmin: boolean }) {
  const [form] = Form.useForm<SupplierMaterialFormValues>();
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<SupplierMaterialResponse> | null>(null);
  const [suppliers, setSuppliers] = useState<Record<number, SupplierResponse>>({});
  const [materials, setMaterials] = useState<Record<number, MaterialResponse>>({});
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SupplierMaterialResponse | null>(null);
  const [query, setQuery] = useState({ page: 0, size: 20, sort: 'id,asc', supplierId: undefined as number | undefined, materialId: undefined as number | undefined, status: undefined as SupplierMaterialStatus | undefined });
  const requestId = useRef(0);
  const handleRelatedError = useCallback((relatedError: unknown) => setError(toApiError(relatedError)), []);

  const load = useCallback(async () => {
    const current = ++requestId.current;
    setLoading(true); setError(null);
    try {
      const response = await listSupplierMaterials(query);
      const supplierIds = [...new Set(response.content.map((item) => item.supplierId))];
      const materialIds = [...new Set(response.content.map((item) => item.materialId))];
      const [supplierValues, materialValues] = await Promise.all([
        Promise.all(supplierIds.map((id) => getSupplier(id))),
        Promise.all(materialIds.map((id) => getMaterial(id))),
      ]);
      if (current !== requestId.current) return;
      setData(response);
      setSuppliers(Object.fromEntries(supplierValues.map((supplier) => [supplier.id, supplier])));
      setMaterials(Object.fromEntries(materialValues.map((material) => [material.id, material])));
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
    setEditing(null); form.resetFields(); form.setFieldsValue({ currency: 'TWD', unitPrice: 0, leadTimeDays: 0 }); setModalOpen(true);
  };
  const openEdit = (item: SupplierMaterialResponse) => {
    setEditing(item);
    form.setFieldsValue({ supplierId: item.supplierId, materialId: item.materialId, unitPrice: item.unitPrice, moq: item.moq, packageMultiple: item.packageMultiple ?? undefined, leadTimeDays: item.leadTimeDays, currency: item.currency, status: item.status });
    setModalOpen(true);
  };
  const submit = async () => {
    let values: SupplierMaterialFormValues;
    try { values = await form.validateFields(); } catch { return; }
    setSubmitting(true);
    try {
      if (editing) {
        const request: UpdateSupplierMaterialRequest = { unitPrice: values.unitPrice, moq: values.moq, packageMultiple: values.packageMultiple, leadTimeDays: values.leadTimeDays, status: values.status };
        await updateSupplierMaterial(editing.id, request); apiMessage.success('SupplierMaterial 已更新');
      } else {
        const request: CreateSupplierMaterialRequest = { supplierId: values.supplierId, materialId: values.materialId, unitPrice: values.unitPrice, moq: values.moq, packageMultiple: values.packageMultiple, leadTimeDays: values.leadTimeDays, currency: values.currency };
        await createSupplierMaterial(request); apiMessage.success('SupplierMaterial 已建立');
      }
      setModalOpen(false); form.resetFields(); await load();
    } catch (submitError) {
      setError(toApiError(submitError));
    } finally { setSubmitting(false); }
  };
  const changeStatus = async (item: SupplierMaterialResponse) => {
    try {
      await updateSupplierMaterial(item.id, { status: item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' });
      apiMessage.success('狀態已更新'); await load();
    } catch (statusError) { setError(toApiError(statusError)); }
  };
  const handleTableChange: TableProps<SupplierMaterialResponse>['onChange'] = (pagination, _filters, sorter) => {
    const sortValue = Array.isArray(sorter) ? sorter[0] : sorter;
    setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20, sort: typeof sortValue?.field === 'string' && sortValue.order ? `${sortValue.field},${sortValue.order === 'ascend' ? 'asc' : 'desc'}` : 'id,asc' }));
  };
  const label = (entity: SupplierResponse | MaterialResponse | undefined, id: number) => entity ? `${entity.code} — ${entity.name}` : `#${id}`;
  const filtered = !!(query.supplierId || query.materialId || query.status);
  const columns = [
    { title: 'Supplier', dataIndex: 'supplierId', render: (id: number) => label(suppliers[id], id) },
    { title: 'Material', dataIndex: 'materialId', render: (id: number) => label(materials[id], id) },
    { title: 'Unit Price', dataIndex: 'unitPrice', sorter: true }, { title: 'Currency', dataIndex: 'currency' },
    { title: 'MOQ', dataIndex: 'moq', sorter: true }, { title: 'Package Multiple', dataIndex: 'packageMultiple', sorter: true, render: (value: number | null) => value ?? '—' },
    { title: 'Lead Time Days', dataIndex: 'leadTimeDays', sorter: true },
    { title: 'Status', dataIndex: 'status', sorter: true, render: (status: SupplierMaterialStatus) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status}</Tag> },
    ...(isAdmin ? [{ title: 'Actions', key: 'actions', render: (_: unknown, item: SupplierMaterialResponse) => <Space>
      <Button size="small" onClick={() => openEdit(item)}>Edit</Button>
      <Popconfirm title={`確定要將此 SupplierMaterial 設為 ${item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'}？`} onConfirm={() => void changeStatus(item)}><Button size="small">{item.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}</Button></Popconfirm>
    </Space> }] : []),
  ];

  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}
    <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
      <Space wrap>
        <div style={{ width: 240 }}><RelatedEntitySelect entity="supplier" activeOnly={false} value={query.supplierId} onChange={(supplierId) => setQuery((current) => ({ ...current, supplierId, page: 0 }))} onError={handleRelatedError} /></div>
        <div style={{ width: 240 }}><RelatedEntitySelect entity="material" activeOnly={false} value={query.materialId} onChange={(materialId) => setQuery((current) => ({ ...current, materialId, page: 0 }))} onError={handleRelatedError} /></div>
        <Select aria-label="SupplierMaterial status filter" placeholder="Status" value={query.status} options={statuses.map((status) => ({ value: status, label: status }))} onChange={(status) => setQuery((current) => ({ ...current, status, page: 0 }))} allowClear style={{ width: 150 }} />
        {filtered && <Button onClick={() => setQuery((current) => ({ ...current, supplierId: undefined, materialId: undefined, status: undefined, page: 0 }))}>Clear filters</Button>}
      </Space>
      {isAdmin && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>Create SupplierMaterial</Button>}
    </Space>
    {error && <ErrorState title="SupplierMaterial request failed" error={error} onRetry={() => void load()} />}
    {loading && !data ? <LoadingState tip="Loading supplier materials..." /> : <Table<SupplierMaterialResponse> rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading}
      locale={{ emptyText: <EmptyState simple description={filtered ? '沒有符合查詢條件的 SupplierMaterial' : '尚無 SupplierMaterial 資料'} actionText={isAdmin && !filtered ? 'Create SupplierMaterial' : undefined} onAction={isAdmin && !filtered ? openCreate : undefined} /> }}
      pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }} onChange={handleTableChange} />}
    <Modal title={editing ? 'Edit SupplierMaterial' : 'Create SupplierMaterial'} open={modalOpen} onCancel={() => !submitting && setModalOpen(false)} onOk={() => void submit()} confirmLoading={submitting} okButtonProps={{ disabled: submitting }} destroyOnHidden>
      <Form form={form} layout="vertical" preserve={false}>
        {editing ? <>
          <Form.Item label="Supplier"><Input disabled value={label(suppliers[editing.supplierId], editing.supplierId)} /></Form.Item>
          <Form.Item label="Material"><Input disabled value={label(materials[editing.materialId], editing.materialId)} /></Form.Item>
          <Form.Item label="Currency"><Input disabled value={editing.currency} /></Form.Item>
        </> : <>
          <Form.Item name="supplierId" label="Supplier" rules={[{ required: true }]}><RelatedEntitySelect entity="supplier" onError={handleRelatedError} /></Form.Item>
          <Form.Item name="materialId" label="Material" rules={[{ required: true }]}><RelatedEntitySelect entity="material" onError={handleRelatedError} /></Form.Item>
          <Form.Item name="currency" label="Currency" rules={[{ required: true }]}><Select options={[{ value: 'TWD', label: 'TWD' }]} /></Form.Item>
        </>}
        <Form.Item name="unitPrice" label="Unit Price" rules={[{ required: true }, { type: 'number', min: 0 }]}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="moq" label="MOQ" rules={[{ required: true }, { type: 'number', min: Number.MIN_VALUE, message: 'MOQ 必須大於 0' }]}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="packageMultiple" label="Package Multiple" rules={[{ type: 'number', min: Number.MIN_VALUE, message: 'Package Multiple 必須大於 0' }]}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="leadTimeDays" label="Lead Time Days" rules={[{ required: true }, { type: 'integer', min: 0 }]}><InputNumber min={0} precision={0} style={{ width: '100%' }} /></Form.Item>
        {editing && <Form.Item name="status" label="Status" rules={[{ required: true }]}><Select options={statuses.map((status) => ({ value: status, label: status }))} /></Form.Item>}
        {!editing && <Typography.Text type="secondary">Supplier 與 Material 的啟用狀態及重複關聯由 Backend 最終驗證。</Typography.Text>}
      </Form>
    </Modal>
  </Space>;
}
