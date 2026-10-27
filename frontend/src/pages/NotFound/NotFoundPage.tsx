import React from 'react';
import { Button, Typography } from 'antd';
import '../../components/AccessStates.css';
import { useNavigate } from 'react-router-dom';

export const NotFoundPage: React.FC = () => {
  const navigate = useNavigate();

  return (
    <section className="access-state access-state--not-found" aria-labelledby="not-found-title">
      <div className="access-state__code" aria-hidden="true">404</div>
      <div className="access-state__content">
        <Typography.Text className="access-state__eyebrow">頁面不存在</Typography.Text>
        <Typography.Title id="not-found-title" level={2}>找不到這個工作頁面</Typography.Title>
        <Typography.Paragraph>請確認網址，或返回工作總覽繼續已授權的工作。</Typography.Paragraph>
        <Button className="access-state__primary" type="primary" onClick={() => navigate('/dashboard')}>返回工作總覽</Button>
      </div>
    </section>
  );
};

export default NotFoundPage;
