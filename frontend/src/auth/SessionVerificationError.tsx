import { Button, Result } from 'antd';
import { useAuth } from './useAuth';

export default function SessionVerificationError() {
  const { retryVerification, logout } = useAuth();
  return <Result
    status="warning"
    title="暫時無法驗證登入狀態"
    subTitle="請檢查網路連線或稍後重試。驗證成功前不會顯示受保護內容。"
    extra={<>
      <Button type="primary" onClick={retryVerification}>重試驗證</Button>
      <Button onClick={logout}>登出</Button>
    </>}
  />;
}
