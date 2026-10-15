import React, { useState } from 'react';
import { Button, Layout, Menu, Space, Typography, theme } from 'antd';
import { DashboardOutlined, LogoutOutlined, RobotOutlined } from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { can, roleLabels } from '../auth/permissions';
import { routeCatalog } from '../router/routeCatalog';
import AiAssistantDrawer from '../components/AiAssistant';

const { Header, Sider, Content } = Layout;
const { Title, Text } = Typography;

export const AppLayout: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout } = useAuth();
  const [aiOpen, setAiOpen] = useState(false);
  const {
    token: { colorBgContainer, borderRadiusLG },
  } = theme.useToken();

  if (!user) return null;
  const groups = [...new Set(routeCatalog.map((entry) => entry.group))];
  const menuItems = [
    { key: '/dashboard', icon: <DashboardOutlined />, label: '儀表板' },
    ...groups.map((group) => ({
      key: group,
      label: group,
      children: routeCatalog.filter((entry) => entry.group === group && entry.navigation && can(user.role, entry.permission))
        .map((entry) => ({ key: `/${entry.path}`, label: entry.label })),
    })).filter((group) => group.children.length > 0),
  ];
  const selected = routeCatalog.filter((entry) => entry.navigation && location.pathname.startsWith(`/${entry.path}`))
    .sort((a, b) => b.path.length - a.path.length)[0];

  const handleMenuClick = ({ key }: { key: string }) => {
    navigate(key);
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsedWidth="0" theme="dark">
        <div style={{ padding: '16px', textAlign: 'center' }}>
          <Title level={4} style={{ color: '#fff', margin: 0 }}>
            SupplyPilot
          </Title>
          <Text style={{ color: '#8c8c8c', fontSize: '11px', display: 'block', marginTop: '4px' }}>
            採購決策平台
          </Text>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selected ? `/${selected.path}` : '/dashboard']}
          items={menuItems}
          onClick={handleMenuClick}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            padding: '0 24px',
            background: colorBgContainer,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          <div>
            <Text strong style={{ fontSize: '16px' }}>
              SupplyPilot — AI 採購與供應鏈決策平台
            </Text>
          </div>
          <Space wrap>
            {can(user.role, 'aiQuery') && <Button icon={<RobotOutlined />} onClick={() => setAiOpen(true)}>AI 助理</Button>}
            <Text>{user.displayName}（{user.username}）</Text>
            <Text type="secondary">{roleLabels[user.role]}</Text>
            <Button icon={<LogoutOutlined />} onClick={() => { logout(); navigate('/login', { replace: true, state: null }); }}>登出</Button>
          </Space>
        </Header>
        <Content style={{ margin: '24px 16px 0' }}>
          <div
            style={{
              padding: 24,
              minHeight: 360,
              background: colorBgContainer,
              borderRadius: borderRadiusLG,
            }}
          >
            <Outlet />
          </div>
        </Content>
      </Layout>
      {can(user.role, 'aiQuery') && <AiAssistantDrawer open={aiOpen} onClose={() => setAiOpen(false)} />}
    </Layout>
  );
};

export default AppLayout;
