import { Descriptions, Space, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import type { DecimalValue, DisqualificationReason, SupplierRecommendationResultResponse } from '../../api/recommendation';
import EmptyState from '../EmptyState';

const reasonLabels: Record<string, string> = {
  SUPPLIER_INACTIVE: '供應商目前停用',
  SUPPLIER_MATERIAL_NOT_FOUND: '找不到供應商與物料的供應關係',
  SUPPLIER_MATERIAL_INACTIVE: '供應商物料關係目前停用',
  DELIVERY_DATE_NOT_FEASIBLE: '交期無法滿足需求日期',
};

function value(raw: DecimalValue | number | null, suffix?: string): string {
  if (raw === null || raw === undefined) return 'N/A';
  return suffix ? `${String(raw)} ${suffix}` : String(raw);
}

function reason(raw: DisqualificationReason | null): React.ReactNode {
  if (raw === null) return 'N/A';
  return <Space orientation="vertical" size={0}><span>{reasonLabels[raw] ?? '未知排除原因'}</span><Typography.Text code>{raw}</Typography.Text></Space>;
}

export default function SupplierRankingTable({ rows, unit }: { rows: SupplierRecommendationResultResponse[]; unit: string }) {
  const columns: TableProps<SupplierRecommendationResultResponse>['columns'] = [
    { title: 'Rank', dataIndex: 'rank', render: (rank: number | null) => rank === null ? 'N/A' : `#${rank}` },
    { title: 'Supplier', key: 'supplier', render: (_value, row) => <Space orientation="vertical" size={0}><strong>{row.supplierCode}</strong><span>{row.supplierName}</span></Space> },
    { title: 'Qualification', dataIndex: 'qualified', render: (qualified: boolean) => <Tag color={qualified ? 'green' : 'red'}>{qualified ? 'Qualified' : 'Disqualified'}</Tag> },
    { title: 'Recommended Qty', dataIndex: 'recommendedQuantity', render: (raw: DecimalValue | null) => value(raw, unit) },
    { title: 'Unit Price', dataIndex: 'unitPrice', render: (raw: DecimalValue | null) => value(raw, 'TWD') },
    { title: 'Total Cost', dataIndex: 'totalCost', render: (raw: DecimalValue | null) => value(raw, 'TWD') },
    { title: 'Lead Time', dataIndex: 'leadTimeDays', render: (days: number | null) => value(days, 'days') },
    { title: 'Disqualification Reason', dataIndex: 'disqualifiedReason', render: reason },
  ];

  return <Table<SupplierRecommendationResultResponse>
    aria-label="Supplier ranking"
    rowKey="id"
    columns={columns}
    dataSource={rows}
    scroll={{ x: 1100 }}
    pagination={rows.length > 10 ? { pageSize: 10, showSizeChanger: false } : false}
    locale={{ emptyText: <EmptyState simple description="此 Recommendation 沒有 Supplier Ranking 資料" /> }}
    expandable={{
      expandedRowRender: (row) => <Descriptions size="small" bordered column={{ xs: 1, sm: 2, lg: 4 }} items={[
        { key: 'reliabilitySnapshot', label: 'Reliability Snapshot', children: value(row.reliabilityScoreSnapshot) },
        { key: 'qualitySnapshot', label: 'Quality Snapshot', children: value(row.qualityScoreSnapshot) },
        { key: 'priceScore', label: 'Price Score', children: value(row.priceScore) },
        { key: 'leadScore', label: 'Lead Time Score', children: value(row.leadTimeScore) },
        { key: 'reliabilityScore', label: 'Reliability Score', children: value(row.reliabilityScore) },
        { key: 'qualityScore', label: 'Quality Score', children: value(row.qualityScore) },
        { key: 'totalScore', label: 'Total Score', children: value(row.totalScore) },
        { key: 'resultId', label: 'Backend Result ID', children: row.id },
        { key: 'supplierId', label: 'Supplier ID', children: row.supplierId },
      ]} />,
      rowExpandable: () => true,
    }}
  />;
}
