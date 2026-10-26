import { Card, Descriptions, List, Space, Tag, Typography } from 'antd';
import type { AiAnswerData, AiPurchaseOrderFact } from '../../api/ai';
import { displayLabel } from '../../utils/displayLabels';
import { formatAiCurrency, formatDate, formatDateTime, formatDecimal, formatQuantity } from '../../utils/displayFormatters';

const { Text } = Typography;

function hasFacts(data: AiAnswerData): boolean {
  return data.inventory.length + data.quotes.length + data.averagePrices.length + data.suppliers.length
    + data.purchaseOrders.length + data.candidates.length + data.notes.length + data.unmatchedIdentifiers.length > 0
    || data.purchaseOrderTotalCount != null;
}

export default function AiStructuredAnswer({ details, summary }: { details: AiAnswerData; summary?: string | null }) {
  if (!hasFacts(details)) return null;
  const visibleOrders = details.purchaseOrders.slice(0, 5);
  const totalOrders = details.purchaseOrderTotalCount ?? details.purchaseOrders.length;
  const fetchedOrders = details.purchaseOrders.length;
  const renderOrder = (order: AiPurchaseOrderFact) => <List.Item>
    <Space direction="vertical" style={{ width: '100%' }} size="small">
      <Text strong>{order.poNumber}</Text>
      <Space wrap>
        <Tag>{displayLabel(order.status)}</Tag>
        {order.stage && <Tag color="blue">流程階段：{displayLabel(order.stage)}</Tag>}
      </Space>
      <Descriptions size="small" column={1} items={[
        { key: 'supplier', label: '供應商', children: `${order.supplierName}（${order.supplierCode}）` },
        { key: 'expected', label: '預計交貨日', children: formatDate(order.expectedDeliveryDate) },
        { key: 'promised', label: '供應商承諾交貨日', children: formatDate(order.supplierPromisedDeliveryDate) },
        ...order.totals.map((total, index) => ({ key: `total-${index}`, label: `訂單總額（${total.currency}）`, children: formatAiCurrency(total.amount, total.currency) })),
      ]} />
      {order.items.length > 0 && <List size="small" header="品項" dataSource={order.items} renderItem={(item) => <List.Item>
        <Space direction="vertical" size={0}>
          <Text>{item.materialName}（{item.materialCode}）</Text>
          <Text type="secondary">{formatQuantity(item.quantity, item.unit)} × {formatAiCurrency(item.unitPrice, item.currency)}；小計 {formatAiCurrency(item.lineTotal, item.currency)}</Text>
        </Space>
      </List.Item>} />}
      {order.nextStep && <Text type="secondary">下一步：{order.nextStep}</Text>}
    </Space>
  </List.Item>;
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Text>{summary || '以下欄位為後端核實資料。'}</Text>
    {details.inventory.length > 0 && <Card size="small" title="目前庫存">
      <List dataSource={details.inventory} renderItem={(item) => <List.Item>
        <Descriptions size="small" column={1} style={{ width: '100%' }} items={[
          { key: 'material', label: '物料', children: <><Text strong>{item.materialName}</Text>（{item.materialCode}）</> },
          { key: 'quantity', label: '目前數量', children: formatQuantity(item.quantity, item.unit) },
          { key: 'updated', label: '資料更新時間', children: formatDateTime(item.updatedAt) },
        ]} />
      </List.Item>} />
    </Card>}
    {details.averagePrices.length > 0 && <Card size="small" title="有效報價平均價格">
      <List dataSource={details.averagePrices} renderItem={(item) => <List.Item>
        <Descriptions size="small" column={1} style={{ width: '100%' }} items={[
          { key: 'material', label: '物料', children: `${item.materialName}（${item.materialCode}）` },
          { key: 'count', label: '計算範圍', children: `有效報價 ${item.quoteCount} 筆；同幣別、同計價單位` },
          { key: 'average', label: '算術平均單價', children: `${formatAiCurrency(item.averageUnitPrice, item.currency)}／${item.pricingUnit}` },
          { key: 'range', label: '最低至最高', children: `${formatAiCurrency(item.minimumUnitPrice, item.currency)} 至 ${formatAiCurrency(item.maximumUnitPrice, item.currency)}／${item.pricingUnit}` },
        ]} />
      </List.Item>} />
    </Card>}
    {details.quotes.length > 0 && <Card size="small" title="目前有效供應商報價">
      <List dataSource={details.quotes} renderItem={(item) => <List.Item>
        <Descriptions size="small" column={1} style={{ width: '100%' }} items={[
          { key: 'supplier', label: '供應商', children: <Space wrap><Text strong>{item.supplierName}（{item.supplierCode}）</Text><Tag color="green">{displayLabel(item.relationStatus)}</Tag><Tag>{displayLabel(item.supplierStatus)}</Tag></Space> },
          { key: 'material', label: '物料', children: `${item.materialName}（${item.materialCode}）` },
          { key: 'price', label: '單價', children: `${formatAiCurrency(item.unitPrice, item.currency)}／${item.pricingUnit}` },
          { key: 'lead', label: '交期', children: `${item.leadTimeDays} 天` },
        ]} />
      </List.Item>} />
    </Card>}
    {details.suppliers.length > 0 && <Card size="small" title="供應商主檔與評價">
      <List dataSource={details.suppliers} renderItem={(item) => <List.Item>
        <Descriptions size="small" column={1} style={{ width: '100%' }} items={[
          { key: 'supplier', label: '供應商', children: <><Text strong>{item.supplierName}</Text>（{item.supplierCode}）</> },
          { key: 'status', label: '狀態', children: displayLabel(item.status) },
          { key: 'reliability', label: '可靠度評分', children: formatDecimal(item.reliabilityScore) },
          { key: 'quality', label: '品質評分', children: formatDecimal(item.qualityScore) },
        ]} />
      </List.Item>} />
    </Card>}
    {(details.purchaseOrders.length > 0 || details.purchaseOrderTotalCount != null) && <Card size="small" title="採購單">
      <Text type="secondary">符合條件總數 {totalOrders} 筆；本次取得 {fetchedOrders} 筆，目前顯示 {visibleOrders.length} 筆。</Text>
      <List dataSource={visibleOrders} renderItem={renderOrder} />
      {details.purchaseOrders.length > visibleOrders.length && <details>
        <summary style={{ cursor: 'pointer', paddingBlock: 8 }}>展開其餘 {details.purchaseOrders.length - visibleOrders.length} 筆採購單</summary>
        <List dataSource={details.purchaseOrders.slice(visibleOrders.length)} renderItem={renderOrder} />
      </details>}
      {totalOrders > fetchedOrders && <Text type="secondary">尚有 {totalOrders - fetchedOrders} 筆未列出（單次查詢上限為 100 筆）。</Text>}
    </Card>}
    {details.candidates.length > 0 && <Card size="small" title="請確認要查詢的資料">
      <List dataSource={details.candidates} renderItem={(item) => <List.Item>
        <Text>{item.name}（{item.code}{item.unit ? `，${item.unit}` : ''}）</Text>
      </List.Item>} />
    </Card>}
    {(details.notes.length > 0 || details.unmatchedIdentifiers.length > 0) && <Card size="small" title="查詢限制">
      <Space direction="vertical">
        {details.notes.map((note, index) => <Text key={`note-${index}`}>{note}</Text>)}
        {details.unmatchedIdentifiers.length > 0 && <Text type="secondary">未找到資料：{details.unmatchedIdentifiers.join('、')}</Text>}
      </Space>
    </Card>}
  </Space>;
}
