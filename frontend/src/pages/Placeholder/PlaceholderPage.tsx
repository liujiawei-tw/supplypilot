import { Alert, Typography } from 'antd';
import '../../components/AccessStates.css';

export default function PlaceholderPage({ title }: { title: string }) {
  return <section className="feature-placeholder" aria-labelledby="feature-placeholder-title">
    <header className="feature-placeholder__heading">
      <Typography.Text className="access-state__eyebrow">功能狀態</Typography.Text>
      <Typography.Text className="feature-placeholder__status">尚未實作</Typography.Text>
    </header>
    <Typography.Title id="feature-placeholder-title" level={2}>{title}</Typography.Title>
    <Alert className="feature-placeholder__alert" type="info" showIcon
      message="此功能目前僅提供角色授權的路由入口"
      description="資料與操作將由後續 Issue 實作。" />
  </section>;
}
