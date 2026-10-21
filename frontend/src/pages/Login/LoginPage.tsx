import { useRef, useState } from 'react';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { isAllowedDestination } from '../../router/routeCatalog';
import LoadingState from '../../components/LoadingState/LoadingState';
import SessionVerificationError from '../../auth/SessionVerificationError';
import { isAxiosError } from 'axios';
import { PostLoginVerificationError } from '../../auth/authErrors';

function loginErrorMessage(error: unknown): string {
  if (error instanceof PostLoginVerificationError) {
    return '登入成功，但暫時無法驗證登入狀態，請重新登入或稍後再試。';
  }
  if (isAxiosError(error)) {
    const status = error.response?.status;
    if (status === 401) return '帳號或密碼錯誤。';
    if (status === 429) return '登入請求過於頻繁，請稍後再試。';
    if (!error.response || (status !== undefined && status >= 500)) return '無法連線或登入服務暫時異常，請稍後再試。';
  }
  return '登入服務暫時無法處理請求，請稍後再試。';
}

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
  if (user) return <Navigate to={user.mustChangePassword ? '/change-password' : from?.pathname && isAllowedDestination(from.pathname, user) ? `${from.pathname}${from.search ?? ''}` : '/dashboard'} replace />;

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
      const destination = from?.pathname && isAllowedDestination(from.pathname, authenticatedUser)
        ? `${from.pathname}${from.search ?? ''}`
        : '/dashboard';
      navigate(destination, { replace: true });
    } catch (error) {
      setError(loginErrorMessage(error));
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
