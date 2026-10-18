import { useRef, useState } from 'react';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { isAllowedDestination } from '../../router/routeCatalog';
import LoadingState from '../../components/LoadingState/LoadingState';
import SessionVerificationError from '../../auth/SessionVerificationError';

export default function LoginPage() {
  const { login, user, isLoading, verificationError } = useAuth();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const pending = useRef(false);
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: { pathname?: string; search?: string } } | null)?.from;

  if (isLoading) return <LoadingState />;
  if (verificationError) return <SessionVerificationError />;
  if (user) return <Navigate to={user.mustChangePassword ? '/change-password' : from?.pathname && isAllowedDestination(from.pathname, user.role) ? `${from.pathname}${from.search ?? ''}` : '/dashboard'} replace />;

  const submit = async (values: { username: string; password: string }) => {
    if (pending.current) return;
    pending.current = true;
    setSubmitting(true);
    setError('');
    try {
      const authenticatedUser = await login(values.username.trim(), values.password);
      if (authenticatedUser.mustChangePassword) {
        navigate('/change-password', { replace: true });
        return;
      }
      const destination = from?.pathname && isAllowedDestination(from.pathname, authenticatedUser.role)
        ? `${from.pathname}${from.search ?? ''}`
        : '/dashboard';
      navigate(destination, { replace: true });
    } catch {
      setError('登入失敗。請確認帳號與密碼，或稍後再試。');
    } finally {
      pending.current = false;
      setSubmitting(false);
    }
  };

  return <main className="login-page">
    <Card className="login-card">
      <Typography.Title level={2}>SupplyPilot</Typography.Title>
      <Typography.Paragraph type="secondary">登入採購與供應鏈決策平台</Typography.Paragraph>
      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
      <Form layout="vertical" onFinish={submit} autoComplete="off">
        <Form.Item name="username" label="帳號" rules={[{ required: true, whitespace: true, message: '請輸入帳號' }]}>
          <Input autoComplete="username" />
        </Form.Item>
        <Form.Item name="password" label="密碼" rules={[{ required: true, message: '請輸入密碼' }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Button htmlType="submit" type="primary" block loading={submitting} disabled={submitting}>登入</Button>
      </Form>
      <Typography.Paragraph type="secondary" style={{ marginTop: 20, marginBottom: 0 }}>
        示範帳號資訊請參閱部署環境提供的說明；此頁不公開密碼。
      </Typography.Paragraph>
    </Card>
  </main>;
}
