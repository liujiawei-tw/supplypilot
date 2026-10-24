import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { getMaterial, type MaterialResponse } from '../../api/material';
import { getSupplier, type SupplierResponse } from '../../api/supplier';
import { createSupplierMaterial, listSupplierMaterials, updateSupplierMaterial, type CreateSupplierMaterialRequest, type DecimalValue, type SupplierMaterialResponse, type SupplierMaterialStatus, type UpdateSupplierMaterialRequest } from '../../api/supplierMaterial';
import { toApiError } from '../../api/apiError';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import RelatedEntitySelect from '../../components/RelatedEntitySelect';
import type { ApiErrorResponse, PageResponse } from '../../types/api';
import { displayLabel } from '../../utils/displayLabels';
import { formatCurrency, formatQuantity } from '../../utils/displayFormatters';

const statuses: SupplierMaterialStatus[] = ['ACTIVE', 'INACTIVE'];
type SupplierMaterialFormValues = CreateSupplierMaterialRequest & { status?: SupplierMaterialStatus };

export default function SupplierMaterialList({ isAdmin }: { isAdmin: boolean }) {
  const [form] = Form.useForm<SupplierMaterialFormValues>();
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<SupplierMaterialResponse> | null>(null);
  const [suppliers, setSuppliers] = useState<Record<number, SupplierResponse>>({});
  const [materials, setMaterials] = useState<Record<number, MaterialResponse>>({});
  const [listError, setListError] = useState<ApiErrorResponse | null>(null);
  const [submitError, setSubmitError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SupplierMaterialResponse | null>(null);
  const [query, setQuery] = useState({ page: 0, size: 20, sort: 'id,asc', supplierId: undefined as number | undefined, materialId: undefined as number | undefined, status: undefined as SupplierMaterialStatus | undefined });
  const requestId = useRef(0);
  const handleListRelatedError = useCallback((relatedError: unknown) => setListError(toApiError(relatedError)), []);
  const handleSubmitRelatedError = useCallback((relatedError: unknown) => setSubmitError(toApiError(relatedError)), []);

  const load = useCallback(async () => {
    const current = ++requestId.current;
    setLoading(true); setListError(null);
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
    setSubmitError(null); setEditing(null); form.resetFields(); form.setFieldsValue({ currency: 'TWD', unitPrice: 0, leadTimeDays: 0 }); setModalOpen(true);
  };
  const openEdit = (item: SupplierMaterialResponse) => {
    setSubmitError(null);
    setEditing(item);
    form.setFieldsValue({ supplierId: item.supplierId, materialId: item.materialId, unitPrice: item.unitPrice, moq: item.moq, packageMultiple: item.packageMultiple ?? undefined, leadTimeDays: item.leadTimeDays, currency: item.currency, status: item.status });
    setModalOpen(true);
  };
  const submit = async () => {
    let values: SupplierMaterialFormValues;
    try { values = await form.validateFields(); } catch { return; }
    setSubmitError(null);
    setSubmitting(true);
    try {
      if (editing) {
        const request: UpdateSupplierMaterialRequest = { unitPrice: values.unitPrice, moq: values.moq, packageMultiple: values.packageMultiple, leadTimeDays: values.leadTimeDays, status: values.status };
        await updateSupplierMaterial(editing.id, request); apiMessage.success('供應商物料關聯已更新');
      } else {
        const request: CreateSupplierMaterialRequest = { supplierId: values.supplierId, materialId: values.materialId, unitPrice: values.unitPrice, moq: values.moq, packageMultiple: values.packageMultiple, leadTimeDays: values.leadTimeDays, currency: values.currency };
        await createSupplierMaterial(request); apiMessage.success('供應商物料關聯已建立');
      }
      setModalOpen(false); form.resetFields(); await load();
    } catch (submitError) {
      setSubmitError(toApiError(submitError));
    } finally { setSubmitting(false); }
  };
  const changeStatus = async (item: SupplierMaterialResponse) => {
    try {
      await updateSupplierMaterial(item.id, { status: item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' });
      apiMessage.success('狀態已更新'); await load();
    } catch (statusError) { setListError(toApiError(statusError)); }
  };
  const handleTableChange: TableProps<SupplierMaterialResponse>['onChange'] = (pagination, _filters, sorter) => {
    const sortValue = Array.isArray(sorter) ? sorter[0] : sorter;
    setQuery((current) => ({ ...current, page: (pagination.current ?? 1) - 1, size: pagination.pageSize ?? 20, sort: typeof sortValue?.field === 'string' && sortValue.order ? `${sortValue.field},${sortValue.order === 'ascend' ? 'asc' : 'desc'}` : 'id,asc' }));
  };
  const label = (entity: SupplierResponse | MaterialResponse | undefined, id: number) => entity ? `${entity.code} — ${entity.name}` : `#${id}`;
  const filtered = !!(query.supplierId || query.materialId || query.status);
  const columns = [
    { title: '供應商', dataIndex: 'supplierId', render: (id: number) => label(suppliers[id], id) },
    { title: '物料', dataIndex: 'materialId', render: (id: number) => label(materials[id], id) },
    { title: '單價', dataIndex: 'unitPrice', sorter: true, render: (value: DecimalValue, row: SupplierMaterialResponse) => formatCurrency(value, row.currency) },
    { title: '最低訂購量（MOQ）', dataIndex: 'moq', sorter: true, render: (value: DecimalValue, row: SupplierMaterialResponse) => formatQuantity(value, materials[row.materialId]?.baseUnit) },
    { title: '包裝倍數', dataIndex: 'packageMultiple', sorter: true, render: (value: DecimalValue | null, row: SupplierMaterialResponse) => formatQuantity(value, materials[row.materialId]?.baseUnit) },
    { title: '交期（天）', dataIndex: 'leadTimeDays', sorter: true },
    { title: '狀態', dataIndex: 'status', sorter: true, render: (status: SupplierMaterialStatus) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{displayLabel(status)}</Tag> },
    ...(isAdmin ? [{ title: '操作', key: 'actions', render: (_: unknown, item: SupplierMaterialResponse) => <Space>
      <Button size="small" onClick={() => openEdit(item)}>編輯</Button>
      <Popconfirm title={`確定要將此供應商物料關聯設為${item.status === 'ACTIVE' ? '停用' : '啟用'}？`} onConfirm={() => void changeStatus(item)}><Button size="small">{item.status === 'ACTIVE' ? '停用' : '啟用'}</Button></Popconfirm>
    </Space> }] : []),
  ];

  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    {contextHolder}
    <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
      <Space wrap>
        <div style={{ width: 240 }}><RelatedEntitySelect entity="supplier" activeOnly={false} value={query.supplierId} onChange={(supplierId) => setQuery((current) => ({ ...current, supplierId, page: 0 }))} onError={handleListRelatedError} /></div>
        <div style={{ width: 240 }}><RelatedEntitySelect entity="material" activeOnly={false} value={query.materialId} onChange={(materialId) => setQuery((current) => ({ ...current, materialId, page: 0 }))} onError={handleListRelatedError} /></div>
        <Select aria-label="供應商物料狀態篩選" placeholder="狀態" value={query.status} options={statuses.map((status) => ({ value: status, label: displayLabel(status) }))} onChange={(status) => setQuery((current) => ({ ...current, status, page: 0 }))} allowClear style={{ width: 150 }} />
        {filtered && <Button onClick={() => setQuery((current) => ({ ...current, supplierId: undefined, materialId: undefined, status: undefined, page: 0 }))}>清除篩選</Button>}
      </Space>
        {isAdmin && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增供應商物料關聯</Button>}
    </Space>
    {listError && <ErrorState title="供應商物料查詢失敗" error={listError} onRetry={() => void load()} />}
    {loading && !data ? <LoadingState tip="正在載入供應商物料關聯…" /> : <Table<SupplierMaterialResponse> rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading} scroll={{ x: 'max-content' }}
      locale={{ emptyText: <EmptyState simple description={filtered ? '沒有符合條件的供應商物料關聯' : '目前沒有供應商物料關聯'} actionText={isAdmin && !filtered ? '新增供應商物料關聯' : undefined} onAction={isAdmin && !filtered ? openCreate : undefined} /> }}
      pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }} onChange={handleTableChange} />}
    <Modal title={editing ? '編輯供應商物料關聯' : '新增供應商物料關聯'} open={modalOpen} onCancel={() => !submitting && setModalOpen(false)} onOk={() => void submit()} confirmLoading={submitting} okButtonProps={{ disabled: submitting }} destroyOnHidden>
      {submitError && <ErrorState title="供應商物料資料送出失敗" error={submitError} />}
      <Form form={form} layout="vertical" preserve={false}>
        {editing ? <>
          <Form.Item label="供應商"><Input disabled value={label(suppliers[editing.supplierId], editing.supplierId)} /></Form.Item>
          <Form.Item label="物料"><Input disabled value={label(materials[editing.materialId], editing.materialId)} /></Form.Item>
          <Form.Item label="幣別"><Input disabled value={editing.currency} /></Form.Item>
        </> : <>
          <Form.Item name="supplierId" label="供應商" rules={[{ required: true }]}><RelatedEntitySelect entity="supplier" onError={handleSubmitRelatedError} /></Form.Item>
          <Form.Item name="materialId" label="物料" rules={[{ required: true }]}><RelatedEntitySelect entity="material" onError={handleSubmitRelatedError} /></Form.Item>
          <Form.Item name="currency" label="幣別" rules={[{ required: true }]}><Select options={[{ value: 'TWD', label: 'TWD' }]} /></Form.Item>
        </>}
        <Form.Item name="unitPrice" label="單價" rules={[{ required: true }, { type: 'number', min: 0 }]}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="moq" label="最低訂購量（MOQ）" rules={[{ required: true }, { type: 'number', min: Number.MIN_VALUE, message: 'MOQ 必須大於 0' }]}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="packageMultiple" label="包裝倍數" rules={[{ type: 'number', min: Number.MIN_VALUE, message: '包裝倍數必須大於 0' }]}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="leadTimeDays" label="交期（天）" rules={[{ required: true }, { type: 'integer', min: 0 }]}><InputNumber min={0} precision={0} style={{ width: '100%' }} /></Form.Item>
        {editing && <Form.Item name="status" label="狀態" rules={[{ required: true }]}><Select options={statuses.map((status) => ({ value: status, label: displayLabel(status) }))} /></Form.Item>}
        {!editing && <Typography.Text type="secondary">供應商、物料啟用狀態及重複關聯由 後端 最終驗證。</Typography.Text>}
      </Form>
    </Modal>
  </Space>;
}
