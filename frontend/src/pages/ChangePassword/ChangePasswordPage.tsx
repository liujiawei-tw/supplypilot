import { useState } from 'react';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import axiosClient from '../../api/axiosClient';
import { useAuth } from '../../auth/useAuth';

export default function ChangePasswordPage() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const submit = async (values: { currentPassword: string; newPassword: string; confirmPassword: string }) => {
    setSubmitting(true);
    setError('');
    try {
      await axiosClient.post('/auth/change-password', {
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      });
      // The first-login token intentionally remains restricted. Require a fresh login to receive a full token.
      logout();
      navigate('/login', { replace: true, state: { passwordChanged: true } });
    } catch {
      setError('密碼修改失敗。請確認目前密碼，並使用至少 6 個字元的新密碼。');
    } finally {
      setSubmitting(false);
    }
  };

  return <main className="login-page">
    <Card className="login-card">
      <Typography.Title level={3}>設定新密碼</Typography.Title>
      <Typography.Paragraph>首次管理員登入必須先修改初始密碼，完成後請重新登入。</Typography.Paragraph>
      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
      <Form layout="vertical" onFinish={submit} autoComplete="off">
        <Form.Item name="currentPassword" label="目前密碼" rules={[{ required: true, message: '請輸入目前密碼' }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Form.Item name="newPassword" label="新密碼" rules={[{ required: true, min: 6, max: 100, message: '新密碼需為 6 至 100 個字元' }]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item name="confirmPassword" label="再次輸入新密碼" dependencies={['newPassword']} rules={[
          { required: true, message: '請再次輸入新密碼' },
          ({ getFieldValue }) => ({ validator(_, value) {
            return !value || getFieldValue('newPassword') === value ? Promise.resolve() : Promise.reject(new Error('兩次輸入的密碼不一致'));
          } }),
        ]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Button htmlType="submit" type="primary" block loading={submitting}>儲存並重新登入</Button>
      </Form>
    </Card>
  </main>;
}
