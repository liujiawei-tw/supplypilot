import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Select, Space, Switch, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { createUser, listUsers, updateUser } from '../../api/users';
import { roleLabels } from '../../auth/permissions';
import type { UserRecord, UserStatus } from '../../types/api';

export default function UsersPage() {
  const [users, setUsers] = useState<UserRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [createError, setCreateError] = useState('');
  const [editing, setEditing] = useState<UserRecord | null>(null);
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();

  const load = useCallback(async () => {
    try {
      const page = await listUsers();
      setUsers(page.content);
      setError('');
    } catch {
      setError('無法載入人員清單，請確認管理員權限或稍後重試。');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const saveNew = async (values: { username: string; displayName: string; password: string; canManageUsers: boolean }) => {
    try {
      await createUser({ ...values, username: values.username.trim(), displayName: values.displayName.trim() });
      setCreateOpen(false);
      createForm.resetFields();
      await load();
    } catch (cause) {
      const code = (cause as { response?: { data?: { code?: string } } }).response?.data?.code;
      setCreateError(code === 'DUPLICATE_USERNAME' ? '帳號已存在，請使用其他帳號。' : '新增失敗，請檢查欄位內容後重試。');
    }
  };

  const openEdit = (record: UserRecord) => {
    setEditing(record);
    editForm.setFieldsValue({ displayName: record.displayName, canManageUsers: record.canManageUsers, status: record.status });
  };

  const saveEdit = async (values: { displayName: string; canManageUsers: boolean; status: UserStatus }) => {
    if (!editing) return;
    try {
      await updateUser(editing.id, { ...values, displayName: values.displayName.trim() });
      setEditing(null);
      await load();
    } catch {
      setError('修改失敗，請確認欄位內容及管理員權限。');
    }
  };

  const columns: ColumnsType<UserRecord> = [
    { title: '帳號', dataIndex: 'username', key: 'username' },
    { title: '顯示名稱', dataIndex: 'displayName', key: 'displayName' },
    { title: '業務角色', key: 'role', render: () => roleLabels.ADMIN },
    { title: '人員管理', dataIndex: 'canManageUsers', key: 'canManageUsers', render: (allowed: boolean) => allowed ? '已授權' : '未授權' },
    { title: '狀態', dataIndex: 'status', key: 'status', render: (status: UserStatus) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status === 'ACTIVE' ? '啟用' : '停用'}</Tag> },
    { title: '操作', key: 'action', render: (_, record) => <Button onClick={() => openEdit(record)}>編輯</Button> },
  ];

  return <section>
    <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }}>
      <div><Typography.Title level={2}>人員管理</Typography.Title><Typography.Text type="secondary">管理採購業務帳號與獨立的人員管理授權。</Typography.Text></div>
      <Button type="primary" onClick={() => { setCreateError(''); setCreateOpen(true); }}>新增人員</Button>
    </Space>
    {error && <Alert type="error" showIcon message={error} closable onClose={() => setError('')} style={{ marginBottom: 16 }} />}
    <Table rowKey="id" columns={columns} dataSource={users} loading={loading} pagination={{ pageSize: 20 }} />

    <Modal title="新增人員" open={createOpen} onCancel={() => setCreateOpen(false)} onOk={() => createForm.submit()} okText="建立帳號">
      {createError && <Alert type="error" message={createError} showIcon style={{ marginBottom: 16 }} />}
      <Form form={createForm} layout="vertical" onFinish={saveNew}>
        <Form.Item name="username" label="帳號" rules={[{ required: true, whitespace: true, max: 50, message: '請輸入 1 至 50 個字元的帳號' }]}><Input maxLength={50} /></Form.Item>
        <Form.Item name="displayName" label="顯示名稱" rules={[{ required: true, whitespace: true, max: 100, message: '請輸入顯示名稱' }]}><Input maxLength={100} /></Form.Item>
        <Form.Item name="password" label="初始密碼" rules={[{ required: true, min: 6, max: 100, message: '密碼需為 6 至 100 個字元' }]}><Input.Password autoComplete="new-password" /></Form.Item>
        <Form.Item name="canManageUsers" label="人員管理權限" valuePropName="checked" initialValue={false}><Switch checkedChildren="授權" unCheckedChildren="無" /></Form.Item>
      </Form>
    </Modal>

    <Modal title={`編輯人員：${editing?.username ?? ''}`} open={!!editing} onCancel={() => setEditing(null)} onOk={() => editForm.submit()} okText="儲存">
      <Form form={editForm} layout="vertical" onFinish={saveEdit}>
        <Form.Item name="displayName" label="顯示名稱" rules={[{ required: true, whitespace: true, max: 100, message: '請輸入顯示名稱' }]}><Input maxLength={100} /></Form.Item>
        <Form.Item name="canManageUsers" label="人員管理權限" valuePropName="checked"><Switch checkedChildren="授權" unCheckedChildren="無" /></Form.Item>
        <Form.Item name="status" label="帳號狀態" rules={[{ required: true }]}><Select options={[{ value: 'ACTIVE', label: '啟用' }, { value: 'INACTIVE', label: '停用' }]} /></Form.Item>
      </Form>
    </Modal>
  </section>;
}
