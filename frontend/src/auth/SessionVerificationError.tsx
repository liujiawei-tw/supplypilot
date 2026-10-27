import { Button, Typography } from 'antd';
import '../components/AccessStates.css';
import { useAuth } from './useAuth';

export default function SessionVerificationError() {
  const { retryVerification, logout } = useAuth();
  return <section className="access-state access-state--session sp-error-state" role="alert" aria-labelledby="session-error-title">
    <div className="access-state__code access-state__code--warning" aria-hidden="true">!</div>
    <div className="access-state__content">
      <Typography.Text className="access-state__eyebrow">登入檢查</Typography.Text>
      <Typography.Title id="session-error-title" level={2}>暫時無法驗證登入狀態</Typography.Title>
      <Typography.Paragraph>請檢查網路連線或稍後重試。驗證成功前不會顯示受保護內容。</Typography.Paragraph>
      <div className="access-state__actions">
        <Button className="access-state__primary" type="primary" onClick={retryVerification}>重試驗證</Button>
        <Button onClick={logout}>登出</Button>
      </div>
    </div>
  </section>;
}
