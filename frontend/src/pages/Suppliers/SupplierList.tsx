import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
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

export default function SupplierList({ isAdmin, finder = '', finderField = 'code' }: { isAdmin: boolean; finder?: string; finderField?: 'code' | 'name' }) {
  const normalizedFinder = finder.trim();
  const initialQuery = () => ({
    page: 0,
    size: 20,
    sort: 'id,asc',
    code: normalizedFinder && finderField === 'code' ? normalizedFinder : '',
    name: normalizedFinder && finderField === 'name' ? normalizedFinder : '',
    status: undefined as SupplierStatus | undefined,
  });
  const [form] = Form.useForm<SupplierFormValues>();
  const [apiMessage, contextHolder] = message.useMessage();
  const [data, setData] = useState<PageResponse<SupplierResponse> | null>(null);
  const [listError, setListError] = useState<ApiErrorResponse | null>(null);
  const [submitError, setSubmitError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SupplierResponse | null>(null);
  const [query, setQuery] = useState(initialQuery);
  const requestId = useRef(0);

  useEffect(() => {
    const code = normalizedFinder && finderField === 'code' ? normalizedFinder : '';
    const name = normalizedFinder && finderField === 'name' ? normalizedFinder : '';
    setQuery((current) => current.page === 0 && current.code === code && current.name === name && current.status === undefined
      ? current
      : { ...current, page: 0, code, name, status: undefined });
  }, [normalizedFinder, finderField]);

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
    { title: '供應商代碼', dataIndex: 'code', sorter: true, render: (code: string) => <Typography.Text code>{code}</Typography.Text> }, { title: '名稱', dataIndex: 'name', sorter: true },
    { title: '國家／地區', dataIndex: 'country', sorter: true, render: (country: string | null) => country || '—' },
    { title: '可靠度', dataIndex: 'reliabilityScore', sorter: true, render: (value: SupplierResponse['reliabilityScore']) => formatDecimal(value) }, { title: '品質評分', dataIndex: 'qualityScore', sorter: true, render: (value: SupplierResponse['qualityScore']) => formatDecimal(value) },
    { title: '狀態', dataIndex: 'status', sorter: true, render: (status: SupplierStatus) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{displayLabel(status)}</Tag> },
    ...(isAdmin ? [{ title: '操作', key: 'actions', render: (_: unknown, supplier: SupplierResponse) => <Space>
      <Button onClick={() => openEdit(supplier)} style={{ minHeight: 44 }}>編輯</Button>
      <Popconfirm title={`確定要將此供應商設為${supplier.status === 'ACTIVE' ? '停用' : '啟用'}？`} onConfirm={() => void changeStatus(supplier)}><Button style={{ minHeight: 44 }}>{supplier.status === 'ACTIVE' ? '停用' : '啟用'}</Button></Popconfirm>
    </Space> }] : []),
  ];
  return <Space className="supplier-directory sp-section" orientation="vertical" size="middle" style={{ width: '100%' }}>
    {contextHolder}
    <section className="supplier-directory__section" aria-label="供應商目錄">
      <div className="supplier-directory__heading sp-section__heading">
        <div>
          <Typography.Title className="supplier-directory__title" level={3}>供應商目錄</Typography.Title>
          <Typography.Text className="supplier-directory__intro sp-page__lead">依代碼、名稱或狀態查找；結果使用伺服器篩選與分頁。</Typography.Text>
        </div>
        {isAdmin && <Button className="supplier-directory__create" type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增供應商</Button>}
      </div>
    </section>
    <div className="supplier-directory__filters" role="search" aria-label="供應商篩選">
      <Input aria-label="供應商代碼篩選" placeholder="供應商代碼" value={query.code} onChange={(event) => setQuery((current) => ({ ...current, code: event.target.value, page: 0 }))} allowClear style={{ width: 'min(100%, 240px)', minHeight: 44 }} />
      <Input aria-label="供應商名稱篩選" placeholder="供應商名稱" value={query.name} onChange={(event) => setQuery((current) => ({ ...current, name: event.target.value, page: 0 }))} allowClear style={{ width: 'min(100%, 240px)', minHeight: 44 }} />
      <Select aria-label="供應商狀態篩選" placeholder="狀態" value={query.status} options={statuses.map((status) => ({ value: status, label: displayLabel(status) }))} onChange={(status) => setQuery((current) => ({ ...current, status, page: 0 }))} allowClear style={{ width: 150, minHeight: 44 }} />
      {filtered && <Button onClick={() => setQuery((current) => ({ ...current, code: '', name: '', status: undefined, page: 0 }))}>清除篩選</Button>}
    </div>
    {listError && <ErrorState title="供應商查詢失敗" error={listError} onRetry={() => void load()} />}
    {loading && !data ? <LoadingState tip="正在載入供應商…" /> : <>
      <Typography.Text className="supplier-directory__count" type="secondary" aria-live="polite">共 {data?.totalElements ?? 0} 筆供應商</Typography.Text>
      <div className="supplier-directory__table">
      <Table<SupplierResponse> rowKey="id" columns={columns} dataSource={data?.content ?? []} loading={loading} scroll={{ x: 'max-content' }}
        locale={{ emptyText: <EmptyState simple description={filtered ? '沒有符合條件的供應商' : '目前沒有供應商資料'} actionText={isAdmin && !filtered ? '新增供應商' : undefined} onAction={isAdmin && !filtered ? openCreate : undefined} /> }}
        pagination={{ current: (data?.page ?? 0) + 1, pageSize: data?.size ?? query.size, total: data?.totalElements ?? 0, showSizeChanger: true }} onChange={handleTableChange} />
      </div>
    </>}
    <Modal className="supplier-directory__modal" title={editing ? '編輯供應商' : '新增供應商'} open={modalOpen} onCancel={() => !submitting && setModalOpen(false)} onOk={() => void submit()} confirmLoading={submitting} okButtonProps={{ disabled: submitting }} destroyOnHidden>
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
