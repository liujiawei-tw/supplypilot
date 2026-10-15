import { Drawer, Tabs, Typography } from 'antd';
import { useAuth } from '../../auth/useAuth';
import { can } from '../../auth/permissions';
import AiQueryPanel from './AiQueryPanel';
import AiDraftProposalPanel from './AiDraftProposalPanel';

export default function AiAssistantDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { user } = useAuth();
  if (!user || !can(user.role, 'aiQuery')) return null;
  const items = [{ key: 'query', label: '查詢與來源', children: <AiQueryPanel /> }];
  if (can(user.role, 'aiProposal')) items.push({ key: 'draft', label: '採購草稿提案', children: <AiDraftProposalPanel /> });
  return <Drawer title="SupplyPilot AI 助理" open={open} onClose={onClose}
    width="min(100vw, 520px)" destroyOnHidden={false}>
    <Typography.Paragraph type="secondary">AI 功能與核心頁面隔離；關閉 Drawer 不會改變目前路由。</Typography.Paragraph>
    <Tabs items={items} />
  </Drawer>;
}
