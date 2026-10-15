import { Alert, Card, Descriptions, Space, Statistic, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';
import type { DecimalValue, RecommendationResponse } from '../../api/recommendation';
import { displayLabel } from '../../utils/displayLabels';

const outcomePresentation = {
  PURCHASE_NOT_REQUIRED: { color: 'green', label: '不需要採購', description: '目前的 後端 計算結果沒有採購需求。' },
  SUPPLIER_RECOMMENDED: { color: 'blue', label: '已有推薦供應商', description: '以下供應商、數量與分數均來自已保存的 後端 Snapshot。' },
  NO_QUALIFIED_SUPPLIER: { color: 'orange', label: '沒有合格供應商', description: '存在採購需求，但目前沒有通過限制條件的供應商。' },
} as const;

function decimal(value: DecimalValue | null, suffix?: string): string {
  if (value === null || value === undefined) return '—';
  return suffix ? `${String(value)} ${suffix}` : String(value);
}

function timestamp(value: string): string {
  return new Intl.DateTimeFormat('zh-TW', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

export default function RecommendationSummary({ recommendation, showDeepLink = false }: { recommendation: RecommendationResponse; showDeepLink?: boolean }) {
  const outcome = outcomePresentation[recommendation.outcome];

  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    <Card title={<Space wrap><span>採購建議 #{recommendation.id}</span><Tag color={outcome.color}>{displayLabel(recommendation.outcome)}</Tag></Space>}
      extra={showDeepLink ? <Link to={`/recommendations/${recommendation.id}`}>開啟已儲存明細</Link> : undefined}>
      <Alert type={recommendation.outcome === 'NO_QUALIFIED_SUPPLIER' ? 'warning' : 'info'} showIcon title={outcome.label} description={outcome.description} />
      <Descriptions bordered size="small" column={{ xs: 1, sm: 2, lg: 3 }} style={{ marginTop: 16 }} items={[
        { key: 'code', label: '物料代碼', children: recommendation.material.code },
        { key: 'name', label: '物料名稱', children: recommendation.material.name },
        { key: 'unit', label: '基本單位', children: recommendation.material.unit },
        { key: 'start', label: '期間起日', children: recommendation.period.start },
        { key: 'end', label: '期間迄日', children: recommendation.period.end },
        { key: 'created', label: '建立時間', children: timestamp(recommendation.createdAt) },
      ]} />
    </Card>

    <Card title="採購建議計算快照" extra={<Typography.Text type="secondary">數值以 後端 回應為準</Typography.Text>}>
      <Space wrap size="large">
        <Statistic title="預期需求" value={decimal(recommendation.calculation.expectedDemand, recommendation.material.unit)} />
        <Statistic title="安全庫存" value={decimal(recommendation.calculation.safetyStock, recommendation.material.unit)} />
        <Statistic title="目前庫存" value={decimal(recommendation.calculation.currentInventory, recommendation.material.unit)} />
        <Statistic title="在途庫存" value={decimal(recommendation.calculation.incomingInventory, recommendation.material.unit)} />
        <Statistic title="淨需求" value={decimal(recommendation.calculation.netRequirement, recommendation.material.unit)} styles={{ content: { color: recommendation.outcome === 'PURCHASE_NOT_REQUIRED' ? '#389e0d' : '#cf1322' } }} />
      </Space>
      <Typography.Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 0 }}>
        說明：淨需求由後端決策引擎計算；本頁不重新計算或覆寫結果。
      </Typography.Paragraph>
    </Card>

    <Card title="建議供應商快照">
      <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }} items={[
        { key: 'required', label: '是否需要採購', children: recommendation.recommendation.purchaseRequired ? '是' : '否' },
        { key: 'supplier', label: '供應商', children: recommendation.recommendation.supplierId === null ? '—' : `${recommendation.recommendation.supplierName ?? '快照未提供供應商名稱'} (#${recommendation.recommendation.supplierId})` },
        { key: 'quantity', label: '建議數量', children: decimal(recommendation.recommendation.quantity, recommendation.material.unit) },
        { key: 'score', label: '後端評分', children: decimal(recommendation.recommendation.score) },
      ]} />
    </Card>
  </Space>;
}
