import React from 'react';
import { Result, Button } from 'antd';
import { useNavigate } from 'react-router-dom';

export const NotFoundPage: React.FC = () => {
  const navigate = useNavigate();

  return (
    <div style={{ padding: '48px 24px' }}>
      <Result
        status="404"
        title="404"
        subTitle="找不到您要瀏覽的頁面。"
        extra={
          <Button type="primary" onClick={() => navigate('/dashboard')}>
            返回儀表板
          </Button>
        }
      />
    </div>
  );
};

export default NotFoundPage;
