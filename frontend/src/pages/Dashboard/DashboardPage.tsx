import React from 'react';
import { Alert, Card, Typography, Space } from 'antd';
import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { can, roleLabels } from '../../auth/permissions';
import { routeCatalog } from '../../router/routeCatalog';

const { Title, Paragraph } = Typography;

export const DashboardPage: React.FC = () => {
  const { user } = useAuth();
  if (!user) return null;
  const groups = [...new Set(routeCatalog.map((entry) => entry.group))];
  return (
    <div style={{ padding: '24px' }}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <div>
          <Title level={2} style={{ marginBottom: 8 }}>工作總覽</Title>
          <Paragraph type="secondary">
            {user.displayName} · {roleLabels[user.role]}
          </Paragraph>
        </div>

        <Alert type="info" showIcon message="目前沒有待辦資料" description="請從下方入口前往您有權限使用的業務功能。" />
        {groups.map((group) => {
          const entries = routeCatalog.filter((entry) => entry.group === group && entry.navigation && can(user.role, entry.permission));
          return entries.length > 0 && <Card key={group} title={group}>
            <Space wrap>{entries.map((entry) => <Link key={entry.path} to={`/${entry.path}`}>{entry.label}</Link>)}</Space>
          </Card>;
        })}
      </Space>
    </div>
  );
};

export default DashboardPage;
