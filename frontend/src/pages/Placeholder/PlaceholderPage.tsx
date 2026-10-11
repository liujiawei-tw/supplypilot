import { Alert, Typography } from 'antd';

export default function PlaceholderPage({ title }: { title: string }) {
  return <div style={{ padding: 24 }}>
    <Typography.Title level={2}>{title}</Typography.Title>
    <Alert type="info" showIcon message="此功能尚未實作" description="目前只提供角色授權的路由入口；資料與操作將由後續 Issue 完成。" />
  </div>;
}
