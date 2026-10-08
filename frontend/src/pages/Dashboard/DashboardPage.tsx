import React from 'react';
import { Card, Typography, Space } from 'antd';

const { Title, Paragraph, Text } = Typography;

export const DashboardPage: React.FC = () => {
  return (
    <div style={{ padding: '24px' }}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <div>
          <Title level={2} style={{ marginBottom: 8 }}>SupplyPilot Dashboard</Title>
          <Paragraph type="secondary">
            AI Procurement & Supply Chain Decision Platform
          </Paragraph>
        </div>

        <Card title="System Overview" bordered={false}>
          <Paragraph>
            Welcome to the SupplyPilot platform shell baseline.
          </Paragraph>
          <Paragraph type="secondary">
            Business dashboard, procurement KPIs, inventory alerts, and recommendation workflows will be implemented in subsequent frontend issues.
          </Paragraph>
          <div>
            <Text type="secondary">Baseline Environment Status: </Text>
            <Text code>Frontend Shell Active</Text>
          </div>
        </Card>
      </Space>
    </div>
  );
};

export default DashboardPage;
