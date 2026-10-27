import { useState } from 'react';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import axiosClient from '../../api/axiosClient';
import { useAuth } from '../../auth/useAuth';
import '../AuthScreens.css';

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

  return <section className="password-screen" aria-labelledby="password-screen-title">
    <section className="password-screen__context">
      <Typography.Text className="auth-screen__eyebrow">帳號安全</Typography.Text>
      <Typography.Title level={2}>完成首次登入設定</Typography.Title>
      <Typography.Paragraph>更新初始密碼後，系統會要求重新登入，再進入採購工作區。</Typography.Paragraph>
    </section>
    <Card className="auth-screen__form-card password-screen__form-card" bordered={false}>
      <Typography.Text className="auth-screen__eyebrow">安全設定</Typography.Text>
      <Typography.Title id="password-screen-title" level={2} className="auth-screen__form-title">設定新密碼</Typography.Title>
      <Typography.Paragraph className="auth-screen__form-description">請設定一組新密碼，完成後需重新登入。</Typography.Paragraph>
      {error && <Alert className="auth-screen__error" type="error" message={error} showIcon />}
      <Form className="auth-screen__form" layout="vertical" onFinish={submit} autoComplete="off">
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
  </section>;
}
