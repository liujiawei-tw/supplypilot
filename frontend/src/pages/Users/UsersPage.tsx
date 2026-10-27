import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Select, Space, Switch, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { createUser, deleteUser, listUsers, updateUser } from '../../api/users';
import { roleLabels } from '../../auth/permissions';
import type { UserRecord, UserStatus } from '../../types/api';
import './UsersPage.css';

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

  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);

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

  const confirmDelete = (record: UserRecord) => {
    Modal.confirm({
      title: `永久刪除「${record.username}」？`,
      content: '此操作不可復原。帳號及認證資料會永久刪除；交易與稽核歷史會保留，該帳號目前負責的採購單將變成未指派。',
      okText: '永久刪除', cancelText: '取消', okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteUser(record.id);
          await load();
        } catch (cause) {
          const response = (cause as { response?: { data?: { code?: string } } }).response;
          if (!response) {
            setError(`刪除「${record.username}」結果不明。為避免重複送出，系統不會自動重試；請先重新載入清單確認帳號狀態。`);
          } else {
            const code = response.data?.code;
            setError(code === 'ACCESS_DENIED'
              ? '刪除失敗：不可刪除最後一位有效人員管理者，或目前帳號沒有刪除權限。'
              : code === 'USER_NOT_FOUND' ? '帳號已不存在，請重新載入清單。' : '刪除失敗，請確認錯誤後再操作。');
          }
        }
      },
    });
  };

  const columns: ColumnsType<UserRecord> = [
    { title: '帳號', dataIndex: 'username', key: 'username', render: (username: string) => <Typography.Text strong>{username}</Typography.Text> },
    { title: '顯示名稱', dataIndex: 'displayName', key: 'displayName' },
    { title: '業務角色', key: 'role', render: () => roleLabels.ADMIN },
    { title: '人員管理', dataIndex: 'canManageUsers', key: 'canManageUsers', render: (allowed: boolean) => <Tag color={allowed ? 'green' : 'default'}>{allowed ? '已授權' : '未授權'}</Tag> },
    { title: '狀態', dataIndex: 'status', key: 'status', render: (status: UserStatus) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status === 'ACTIVE' ? '啟用' : '停用'}</Tag> },
    { title: '操作', key: 'action', render: (_, record) => <Space wrap><Button onClick={() => openEdit(record)} style={{ minHeight: 44 }}>編輯</Button><Button danger onClick={() => confirmDelete(record)} style={{ minHeight: 44 }}>永久刪除</Button></Space> },
  ];

  return <section className="sp-page users-workspace" aria-labelledby="users-page-title">
    <header className="users-workspace__heading sp-page__heading">
      <div>
        <Typography.Title id="users-page-title" level={2}>人員管理</Typography.Title>
        <Typography.Text className="users-workspace__intro sp-page__lead">管理業務帳號，並獨立核發人員管理權限。</Typography.Text>
      </div>
      <Button className="users-workspace__create" type="primary" onClick={() => { setCreateError(''); setCreateOpen(true); }}>新增人員</Button>
    </header>
    <Space className="users-workspace__body" orientation="vertical" size="middle" style={{ display: 'flex', width: '100%' }}>
      <Typography.Text className="users-workspace__count" type="secondary" aria-live="polite">{loading ? <span>正在載入人員清單…</span> : <>目前清單共 {users.length} 位人員</>}</Typography.Text>
      {error && <Alert type="error" showIcon message={error} closable onClose={() => setError('')} />}
      <div className="users-workspace__table">
      <Table
        rowKey="id"
        columns={columns}
        dataSource={users}
        loading={loading}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: loading ? '正在載入人員清單…' : '目前沒有人員資料' }}
        pagination={{ pageSize: 20 }}
      />
      </div>
    </Space>

    <Modal className="users-workspace__modal" title="新增人員" open={createOpen} onCancel={() => setCreateOpen(false)} onOk={() => createForm.submit()} okText="建立帳號">
      {createError && <Alert type="error" message={createError} showIcon style={{ marginBottom: 16 }} />}
      <Form form={createForm} layout="vertical" onFinish={saveNew}>
        <Form.Item name="username" label="帳號" rules={[{ required: true, whitespace: true, max: 50, message: '請輸入 1 至 50 個字元的帳號' }]}><Input maxLength={50} /></Form.Item>
        <Form.Item name="displayName" label="顯示名稱" rules={[{ required: true, whitespace: true, max: 100, message: '請輸入顯示名稱' }]}><Input maxLength={100} /></Form.Item>
        <Form.Item name="password" label="初始密碼" rules={[{ required: true, min: 6, max: 100, message: '密碼需為 6 至 100 個字元' }]}><Input.Password autoComplete="new-password" /></Form.Item>
        <Form.Item name="canManageUsers" label="人員管理權限" valuePropName="checked" initialValue={false}><Switch checkedChildren="授權" unCheckedChildren="無" /></Form.Item>
      </Form>
    </Modal>

    <Modal className="users-workspace__modal" title={`編輯人員：${editing?.username ?? ''}`} open={!!editing} onCancel={() => setEditing(null)} onOk={() => editForm.submit()} okText="儲存">
      <Form form={editForm} layout="vertical" onFinish={saveEdit}>
        <Form.Item name="displayName" label="顯示名稱" rules={[{ required: true, whitespace: true, max: 100, message: '請輸入顯示名稱' }]}><Input maxLength={100} /></Form.Item>
        <Form.Item name="canManageUsers" label="人員管理權限" valuePropName="checked"><Switch checkedChildren="授權" unCheckedChildren="無" /></Form.Item>
        <Form.Item name="status" label="帳號狀態" rules={[{ required: true }]}><Select options={[{ value: 'ACTIVE', label: '啟用' }, { value: 'INACTIVE', label: '停用' }]} /></Form.Item>
      </Form>
    </Modal>
  </section>;
}
