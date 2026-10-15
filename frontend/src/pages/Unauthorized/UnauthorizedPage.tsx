import { Button, Result } from 'antd';
import { useNavigate } from 'react-router-dom';

export default function UnauthorizedPage() {
  const navigate = useNavigate();
  return <Result status="403" title="403" subTitle="您沒有權限查看此頁面。" extra={<Button onClick={() => navigate('/dashboard')}>返回儀表板</Button>} />;
}
