import { useEffect } from 'react';
import { Alert, Tabs, Typography } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { can } from '../../auth/permissions';

export default function InventoryPage() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const items = [
    user && can(user.role, 'inventoryLevels') && { key: 'stock-levels', label: 'Stock Levels' },
    user && can(user.role, 'stockTransactions') && { key: 'stock-transactions', label: 'Stock Transactions' },
  ].filter((item): item is { key: string; label: string } => !!item)
    .map((item) => ({ ...item, children: <Alert type="info" message={`${item.label} 尚未實作`} description="此容器不會發送資料請求。" /> }));
  const requested = params.get('tab');
  const activeKey = items.some((item) => item.key === requested) ? requested! : items[0]?.key;
  useEffect(() => {
    if (activeKey && requested !== activeKey) {
      setParams((current) => {
        const next = new URLSearchParams(current);
        next.set('tab', activeKey);
        return next;
      }, { replace: true });
    }
  }, [activeKey, requested, setParams]);
  if (!user) return null;
  return <div style={{ padding: 24 }}>
    <Typography.Title level={2}>Inventory</Typography.Title>
    <Tabs items={items} activeKey={activeKey} onChange={(key) => {
      const next = new URLSearchParams(params);
      next.set('tab', key);
      setParams(next);
    }} />
  </div>;
}
