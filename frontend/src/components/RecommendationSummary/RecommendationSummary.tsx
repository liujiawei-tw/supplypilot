import { Alert, Card, Descriptions, Space, Statistic, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';
import type { DecimalValue, RecommendationResponse } from '../../api/recommendation';

const outcomePresentation = {
  PURCHASE_NOT_REQUIRED: { color: 'green', label: '不需要採購', description: '目前的 Backend 計算結果沒有採購需求。' },
  SUPPLIER_RECOMMENDED: { color: 'blue', label: '已有推薦供應商', description: '以下供應商、數量與分數均來自已保存的 Backend Snapshot。' },
  NO_QUALIFIED_SUPPLIER: { color: 'orange', label: '沒有合格供應商', description: '存在採購需求，但目前沒有通過限制條件的供應商。' },
} as const;

function decimal(value: DecimalValue | null, suffix?: string): string {
  if (value === null || value === undefined) return 'N/A';
  return suffix ? `${String(value)} ${suffix}` : String(value);
}

function timestamp(value: string): string {
  return new Intl.DateTimeFormat('zh-TW', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

export default function RecommendationSummary({ recommendation, showDeepLink = false }: { recommendation: RecommendationResponse; showDeepLink?: boolean }) {
  const outcome = outcomePresentation[recommendation.outcome];

  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    <Card title={<Space wrap><span>Recommendation #{recommendation.id}</span><Tag color={outcome.color}>{recommendation.outcome}</Tag></Space>}
      extra={showDeepLink ? <Link to={`/recommendations/${recommendation.id}`}>Open permanent detail</Link> : undefined}>
      <Alert type={recommendation.outcome === 'NO_QUALIFIED_SUPPLIER' ? 'warning' : 'info'} showIcon title={outcome.label} description={outcome.description} />
      <Descriptions bordered size="small" column={{ xs: 1, sm: 2, lg: 3 }} style={{ marginTop: 16 }} items={[
        { key: 'code', label: 'Material Code', children: recommendation.material.code },
        { key: 'name', label: 'Material Name', children: recommendation.material.name },
        { key: 'unit', label: 'Base Unit', children: recommendation.material.unit },
        { key: 'start', label: 'Period Start', children: recommendation.period.start },
        { key: 'end', label: 'Period End', children: recommendation.period.end },
        { key: 'created', label: 'Created At', children: timestamp(recommendation.createdAt) },
      ]} />
    </Card>

    <Card title="Decision Calculation Snapshot" extra={<Typography.Text type="secondary">Backend persisted values</Typography.Text>}>
      <Space wrap size="large">
        <Statistic title="Expected Demand" value={decimal(recommendation.calculation.expectedDemand, recommendation.material.unit)} />
        <Statistic title="Safety Stock" value={decimal(recommendation.calculation.safetyStock, recommendation.material.unit)} />
        <Statistic title="Current Inventory" value={decimal(recommendation.calculation.currentInventory, recommendation.material.unit)} />
        <Statistic title="Incoming Inventory" value={decimal(recommendation.calculation.incomingInventory, recommendation.material.unit)} />
        <Statistic title="Net Requirement" value={decimal(recommendation.calculation.netRequirement, recommendation.material.unit)} styles={{ content: { color: recommendation.outcome === 'PURCHASE_NOT_REQUIRED' ? '#389e0d' : '#cf1322' } }} />
      </Space>
      <Typography.Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 0 }}>
        說明：Net Requirement 由 Backend 決策引擎計算；本頁不重新計算或覆寫結果。
      </Typography.Paragraph>
    </Card>

    <Card title="Recommended Supplier Snapshot">
      <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }} items={[
        { key: 'required', label: 'Purchase Required', children: recommendation.recommendation.purchaseRequired ? 'Yes' : 'No' },
        { key: 'supplier', label: 'Supplier', children: recommendation.recommendation.supplierId === null ? 'N/A' : `${recommendation.recommendation.supplierName ?? 'Unknown snapshot name'} (#${recommendation.recommendation.supplierId})` },
        { key: 'quantity', label: 'Recommended Quantity', children: decimal(recommendation.recommendation.quantity, recommendation.material.unit) },
        { key: 'score', label: 'Backend Score', children: decimal(recommendation.recommendation.score) },
      ]} />
    </Card>
  </Space>;
}
