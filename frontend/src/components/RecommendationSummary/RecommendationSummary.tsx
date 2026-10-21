import type { ReactNode } from 'react';
import { Alert, Button, Card, Collapse, Descriptions, Space, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';
import type { DecimalValue, RecommendationResponse } from '../../api/recommendation';
import { useAuth } from '../../auth/useAuth';
import { can } from '../../auth/permissions';
import { displayLabel } from '../../utils/displayLabels';
import './RecommendationSummary.css';

const outcomePresentation = {
  PURCHASE_NOT_REQUIRED: {
    color: 'green',
    alert: 'success',
    alertTitle: '目前沒有淨採購需求',
    description: '已保存快照顯示淨採購需求為零。',
  },
  SUPPLIER_RECOMMENDED: {
    color: 'blue',
    alert: 'info',
    alertTitle: '可依建議評估建立草稿',
    description: '建議供應商與數量取自後端已保存決策。',
  },
  NO_QUALIFIED_SUPPLIER: {
    color: 'orange',
    alert: 'warning',
    alertTitle: '請先檢視供應商排除原因',
    description: '目前有淨採購需求，但後端未回傳合格供應商。',
  },
} as const;

function decimal(value: DecimalValue | null | undefined, suffix?: string): string {
  if (value === null || value === undefined) return '—';
  return suffix ? `${String(value)} ${suffix}` : String(value);
}

function timestamp(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-TW', { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

export default function RecommendationSummary({
  recommendation,
  showDeepLink = false,
  rankingState,
  historicalOnly = false,
}: {
  recommendation: RecommendationResponse;
  showDeepLink?: boolean;
  rankingState: 'loading' | 'error' | 'ready';
  historicalOnly?: boolean;
}) {
  const { user } = useAuth();
  const outcome = outcomePresentation[recommendation.outcome];
  const canCreateDraft = !!user && can(user.role, 'purchaseOrderDraft');
  const unit = recommendation.material.unit;

  let nextStep: ReactNode;
  if (historicalOnly) {
    nextStep = <Typography.Text>此為舊版已保存決策，僅供歷史追溯。</Typography.Text>;
  } else if (recommendation.outcome === 'PURCHASE_NOT_REQUIRED') {
    nextStep = <Typography.Text>此結果不需要建立採購單草稿。</Typography.Text>;
  } else if (recommendation.outcome === 'NO_QUALIFIED_SUPPLIER') {
    nextStep = <Typography.Text>排名區已展開，可查看後端回傳的未符合原因。</Typography.Text>;
  } else if (!canCreateDraft) {
    nextStep = <Typography.Text>請由具有草稿建立權限的採購人員或系統管理員建立草稿。</Typography.Text>;
  } else if (rankingState === 'loading') {
    nextStep = <Typography.Text>正在載入供應商排名；載入完成後可檢視草稿選項。</Typography.Text>;
  } else if (rankingState === 'error') {
    nextStep = <Typography.Text>排名載入失敗。請先重新查詢排名，再檢視草稿選項。</Typography.Text>;
  } else {
    nextStep = <Button type="primary" href="#recommendation-draft">檢視草稿選項</Button>;
  }

  const supplier = recommendation.outcome === 'SUPPLIER_RECOMMENDED'
    ? recommendation.recommendation.supplierName
      ? `${recommendation.recommendation.supplierName}${recommendation.recommendation.supplierId === null ? '' : `（#${recommendation.recommendation.supplierId}）`}`
      : '快照未提供供應商名稱'
    : recommendation.outcome === 'NO_QUALIFIED_SUPPLIER' ? '尚無合格供應商' : '不適用';
  const recommendedQuantity = recommendation.outcome === 'SUPPLIER_RECOMMENDED'
    ? decimal(recommendation.recommendation.quantity, unit)
    : recommendation.outcome === 'NO_QUALIFIED_SUPPLIER' ? '尚無建議數量' : '不適用';

  return <section className="recommendation-summary" aria-labelledby="recommendation-decision-heading">
    <Card className="recommendation-decision-card">
      <Space className="recommendation-decision-title" wrap>
        <Typography.Title level={3} id="recommendation-decision-heading">採購建議 #{recommendation.id}</Typography.Title>
        <Tag color={outcome.color}>{displayLabel(recommendation.outcome)}</Tag>
        {showDeepLink && <Link to={`/recommendations/${recommendation.id}`}>開啟已儲存明細</Link>}
      </Space>
      <Alert
        type={historicalOnly ? 'info' : outcome.alert}
        showIcon
        title={historicalOnly ? '已保存的歷史決策快照' : outcome.alertTitle}
        description={historicalOnly ? '以下推薦內容保留自舊版資料，僅供查閱與追溯。' : outcome.description}
      />

      <div className="recommendation-decision-grid">
        <div className="recommendation-material">
          <Typography.Text type="secondary">物料</Typography.Text>
          <Typography.Text strong className="recommendation-wrap-value">{recommendation.material.code} · {recommendation.material.name}</Typography.Text>
          <Typography.Text type="secondary">基本單位：{unit}</Typography.Text>
        </div>
        <div className="recommendation-net-need">
          <Typography.Text type="secondary">淨採購需求</Typography.Text>
          <Typography.Title level={3} className="recommendation-wrap-value">
            {decimal(recommendation.calculation.netRequirement, unit)}
          </Typography.Title>
        </div>
        <div className="recommendation-supplier">
          <Typography.Text type="secondary">推薦供應商</Typography.Text>
          <Typography.Text strong className="recommendation-wrap-value">{supplier}</Typography.Text>
        </div>
        <div className="recommendation-quantity">
          <Typography.Text type="secondary">建議採購數量</Typography.Text>
          <Typography.Text strong className="recommendation-wrap-value">{recommendedQuantity}</Typography.Text>
        </div>
      </div>

      <div className="recommendation-next-step">
        <Typography.Text strong>下一步</Typography.Text>
        {nextStep}
      </div>
    </Card>

    <Collapse
      className="recommendation-calculation"
      defaultActiveKey={['calculation']}
      items={[
        {
          key: 'calculation',
          label: '決策計算依據',
          children: <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 3 }} items={[
            { key: 'expected', label: '預期需求', children: decimal(recommendation.calculation.expectedDemand, unit) },
            { key: 'safety', label: '安全庫存', children: decimal(recommendation.calculation.safetyStock, unit) },
            { key: 'inventory', label: '目前庫存', children: decimal(recommendation.calculation.currentInventory, unit) },
            { key: 'incoming', label: '在途庫存', children: decimal(recommendation.calculation.incomingInventory, unit) },
            { key: 'net', label: '淨採購需求', children: decimal(recommendation.calculation.netRequirement, unit) },
          ]} />,
        },
        {
          key: 'trace',
          label: '決策期間與追溯資料',
          children: <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
            { key: 'materialId', label: '物料編號', children: recommendation.material.id },
            { key: 'periodStart', label: '期間起日', children: recommendation.period.start },
            { key: 'periodEnd', label: '期間迄日', children: recommendation.period.end },
            { key: 'created', label: '建立時間', children: timestamp(recommendation.createdAt) },
            { key: 'purchaseRequired', label: '是否需要採購', children: recommendation.recommendation.purchaseRequired ? '是' : '否' },
            { key: 'score', label: '後端總分快照', children: decimal(recommendation.recommendation.score) },
          ]} />,
        },
      ]}
    />
  </section>;
}
