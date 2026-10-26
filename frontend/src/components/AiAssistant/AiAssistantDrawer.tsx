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
  return <Drawer className="ai-assistant-drawer" title={<div className="ai-assistant-drawer__heading">
    <Typography.Text strong>採購資料助理</Typography.Text>
    <Typography.Text type="secondary">查詢後端資料，並回到來源核對證據。</Typography.Text>
  </div>} open={open} onClose={onClose}
    width="min(100vw, 680px)" destroyOnHidden={false}>
    <Typography.Paragraph className="ai-assistant-drawer__description" type="secondary">AI 回答只作為查詢協助；正式採購與庫存操作仍由受控工作流程完成。</Typography.Paragraph>
    <Tabs items={items} />
  </Drawer>;
}
