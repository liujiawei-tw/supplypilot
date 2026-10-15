import { Descriptions, Space, Table, Tag } from 'antd';
import type { TableProps } from 'antd';
import type { DecimalValue, DisqualificationReason, SupplierRecommendationResultResponse } from '../../api/recommendation';
import { displayLabel } from '../../utils/displayLabels';
import EmptyState from '../EmptyState';

const reasonLabels: Record<string, string> = {
  SUPPLIER_INACTIVE: '供應商目前停用',
  SUPPLIER_MATERIAL_NOT_FOUND: '找不到供應商與物料的供應關係',
  SUPPLIER_MATERIAL_INACTIVE: '供應商物料關係目前停用',
  DELIVERY_DATE_NOT_FEASIBLE: '交期無法滿足需求日期',
};

function value(raw: DecimalValue | number | null, suffix?: string): string {
  if (raw === null || raw === undefined) return '—';
  return suffix ? `${String(raw)} ${suffix}` : String(raw);
}

function reason(raw: DisqualificationReason | null): React.ReactNode {
  if (raw === null) return '—';
  return reasonLabels[raw] ?? displayLabel(raw);
}

export default function SupplierRankingTable({ rows, unit }: { rows: SupplierRecommendationResultResponse[]; unit: string }) {
  const columns: TableProps<SupplierRecommendationResultResponse>['columns'] = [
    { title: '排名', dataIndex: 'rank', render: (rank: number | null) => rank === null ? '不適用' : `第 ${rank} 名` },
    { title: '供應商', key: 'supplier', render: (_value, row) => <Space orientation="vertical" size={0}><strong>{row.supplierCode}</strong><span>{row.supplierName}</span></Space> },
    { title: '資格', dataIndex: 'qualified', render: (qualified: boolean) => <Tag color={qualified ? 'green' : 'red'}>{qualified ? '符合資格' : '不符合資格'}</Tag> },
    { title: '建議數量', dataIndex: 'recommendedQuantity', render: (raw: DecimalValue | null) => value(raw, unit) },
    { title: '單價', dataIndex: 'unitPrice', render: (raw: DecimalValue | null) => value(raw, 'TWD') },
    { title: '總成本', dataIndex: 'totalCost', render: (raw: DecimalValue | null) => value(raw, 'TWD') },
    { title: '交期', dataIndex: 'leadTimeDays', render: (days: number | null) => days === null ? '—' : `${days} 天` },
    { title: '不符合原因', dataIndex: 'disqualifiedReason', render: reason },
  ];

  return <Table<SupplierRecommendationResultResponse>
    aria-label="供應商排名"
    rowKey="id"
    columns={columns}
    dataSource={rows}
    scroll={{ x: 1100 }}
    pagination={rows.length > 10 ? { pageSize: 10, showSizeChanger: false } : false}
    locale={{ emptyText: <EmptyState simple description="此採購建議沒有供應商排名資料" /> }}
    expandable={{
      expandedRowRender: (row) => <Descriptions size="small" bordered column={{ xs: 1, sm: 2, lg: 4 }} items={[
        { key: 'reliabilitySnapshot', label: '可靠度快照', children: value(row.reliabilityScoreSnapshot) },
        { key: 'qualitySnapshot', label: '品質評分快照', children: value(row.qualityScoreSnapshot) },
        { key: 'priceScore', label: '價格評分', children: value(row.priceScore) },
        { key: 'leadScore', label: '交期評分', children: value(row.leadTimeScore) },
        { key: 'reliabilityScore', label: '可靠度評分', children: value(row.reliabilityScore) },
        { key: 'qualityScore', label: '品質評分', children: value(row.qualityScore) },
        { key: 'totalScore', label: '總分', children: value(row.totalScore) },
        { key: 'resultId', label: '後端結果編號', children: row.id },
        { key: 'supplierId', label: '供應商編號', children: row.supplierId },
      ]} />,
      rowExpandable: () => true,
    }}
  />;
}
