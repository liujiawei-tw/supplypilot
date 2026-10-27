import { Button, Typography } from 'antd';
import '../../components/AccessStates.css';
import { useNavigate } from 'react-router-dom';

export default function UnauthorizedPage() {
  const navigate = useNavigate();
  return <section className="access-state access-state--forbidden" aria-labelledby="access-state-title">
    <div className="access-state__code" aria-hidden="true">403</div>
    <div className="access-state__content">
      <Typography.Text className="access-state__eyebrow">存取權限</Typography.Text>
      <Typography.Title id="access-state-title" level={2}>目前帳號無法查看此頁面</Typography.Title>
      <Typography.Paragraph>請從上方導覽選擇已授權的工作，或返回工作總覽。</Typography.Paragraph>
      <Button className="access-state__primary" type="primary" onClick={() => navigate('/dashboard')}>返回工作總覽</Button>
    </div>
  </section>;
}
