import { Divider, Space, Tag, Typography } from 'antd';
import { displayLabel } from '../../utils/displayLabels';

const { Paragraph, Text, Title } = Typography;

interface PurchaseOrderItem {
  materialCode: string;
  originalText: string;
  quantity: string;
  unit: string;
  unitPrice: string;
  unitPriceCurrency: string;
  total: string;
  totalCurrency: string;
}

interface PurchaseOrderFact {
  poNumber: string;
  id: string;
  supplier: string;
  status: string;
  expectedDeliveryDate: string;
  currency: string;
  items: string;
}

interface ParsedPurchaseOrderAnswer {
  intro: string;
  totalCount: number;
  orders: PurchaseOrderFact[];
  trailingText: string;
}

const PURCHASE_ORDER_PATTERN =
  /(?<poNumber>[^\s（）;；]+)（ID (?<id>\d+)、supplier=(?<supplier>[\s\S]*?)、status=(?<status>[^、；]+)、expectedDeliveryDate=(?<expectedDeliveryDate>[^、；]+)、currency=(?<currency>[A-Z]{3})、items=\[(?<items>[\s\S]*?)\]）;?/g;
const PURCHASE_ORDER_COUNT_PATTERN = /總計\s*(\d+)\s*筆，本次回傳\s*\d+\s*筆/u;
const MONEY_FIELD_PATTERN = /\b(unitPrice|totalCost|total|amount|price)\s*=\s*(-?\d+(?:\.\d+)?)\s*([A-Z]{3})\b/g;
const ITEM_PATTERN =
  /^(.+?)：quantity=(\S+)\s+(.+?)、unitPrice=(-?\d+(?:\.\d+)?)\s+([A-Z]{3})、total=(-?\d+(?:\.\d+)?)\s+([A-Z]{3})$/u;
const VISIBLE_ORDER_LIMIT = 5;

function formatDecimalForDisplay(value: string): string {
  const [wholePart = '0', fractionPart = ''] = value.split('.');
  const negative = wholePart.startsWith('-');
  const absoluteWhole = negative ? wholePart.slice(1) : wholePart;
  let whole = BigInt(absoluteWhole || '0');
  let hundredths = BigInt(fractionPart.slice(0, 2).padEnd(2, '0') || '0');

  if ((fractionPart[2] ?? '0') >= '5') hundredths += 1n;
  if (hundredths >= 100n) {
    whole += 1n;
    hundredths = 0n;
  }

  const groupedWhole = whole.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const fraction = hundredths.toString().padStart(2, '0').replace(/0+$/u, '');
  const isZero = whole === 0n && hundredths === 0n;
  const sign = negative && !isZero ? '-' : '';
  return sign + groupedWhole + (fraction ? '.' + fraction : '');
}

function formatMoney(amount: string, currency: string): string {
  const formatted = formatDecimalForDisplay(amount);
  return currency === 'TWD' ? 'NT$' + formatted : currency + ' ' + formatted;
}

/** Display-only formatting for amounts in trusted answer text; quantities remain untouched. */
function formatLabeledMoney(text: string): string {
  return text.replace(MONEY_FIELD_PATTERN, (_match, label: string, amount: string, currency: string) => {
    const labelText: Record<string, string> = {
      unitPrice: '單價',
      totalCost: '總成本',
      total: '金額',
      amount: '金額',
      price: '單價',
    };
    return labelText[label] + '：' + formatMoney(amount, currency);
  });
}

function parsePurchaseOrderItems(items: string): PurchaseOrderItem[] {
  return items.split('；').filter(Boolean).map((item) => {
    const originalText = item.trim();
    const match = ITEM_PATTERN.exec(originalText);
    if (!match) {
      return {
        materialCode: '',
        originalText,
        quantity: '',
        unit: '',
        unitPrice: '',
        unitPriceCurrency: '',
        total: '',
        totalCurrency: '',
      };
    }
    return {
      materialCode: match[1],
      originalText,
      quantity: match[2],
      unit: match[3],
      unitPrice: match[4],
      unitPriceCurrency: match[5],
      total: match[6],
      totalCurrency: match[7],
    };
  });
}

function parsePurchaseOrderAnswer(answer: string): ParsedPurchaseOrderAnswer | null {
  const matches = [...answer.matchAll(PURCHASE_ORDER_PATTERN)];
  const countMatch = PURCHASE_ORDER_COUNT_PATTERN.exec(answer);
  if (!countMatch && matches.length === 0) return null;

  const firstOrderOffset = matches[0]?.index ?? answer.length;
  const prefix = answer.slice(0, firstOrderOffset);
  const intro = prefix
    .replace(/Open PO 依既有定義只包含 ORDERED 狀態；[\s\S]*?本次回傳\s*\d+\s*筆：?/u, '')
    .trim();
  const totalCount = countMatch ? Number(countMatch[1]) : matches.length;
  const orders = matches.map((match) => {
    const fields = match.groups;
    if (!fields) return null;
    return {
      poNumber: fields.poNumber,
      id: fields.id,
      supplier: fields.supplier,
      status: fields.status,
      expectedDeliveryDate: fields.expectedDeliveryDate,
      currency: fields.currency,
      items: fields.items,
    };
  }).filter((order): order is PurchaseOrderFact => order !== null);

  const lastMatch = matches[matches.length - 1];
  const trailingText = lastMatch
    ? answer.slice(lastMatch.index! + lastMatch[0].length).replace(/^[；。\s]+/u, '').trim()
    : answer.slice(countMatch!.index! + countMatch![0].length).replace(/^[：；。\s]+/u, '').trim();

  return { intro, totalCount, orders, trailingText };
}

function PurchaseOrderSummary({ order }: { order: PurchaseOrderFact }) {
  const items = parsePurchaseOrderItems(order.items);
  return <section aria-label={'採購單 ' + order.poNumber} style={{ paddingBlock: 12, overflowWrap: 'anywhere' }}>
    <Title level={5} style={{ margin: '0 0 8px', overflowWrap: 'anywhere' }}>{order.poNumber}</Title>
    <Space wrap size={[8, 4]} style={{ marginBottom: 8, maxWidth: '100%' }}>
      <Text><Text type="secondary">供應商：</Text>{order.supplier}</Text>
      <Tag>{displayLabel(order.status)}</Tag>
      <Text><Text type="secondary">預計交期：</Text>{order.expectedDeliveryDate === 'null' ? '—' : order.expectedDeliveryDate}</Text>
    </Space>
    <ul style={{ margin: 0, paddingInlineStart: 20 }}>
      {items.map((item, index) => <li key={item.materialCode + '-' + index} style={{ paddingBlock: 3, overflowWrap: 'anywhere' }}>
        {item.materialCode
          ? <>
            <Text strong>{item.materialCode}</Text>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 12px', marginTop: 4 }}>
              <Text style={{ whiteSpace: 'nowrap' }}>數量 {item.quantity} {item.unit}</Text>
              <Text style={{ whiteSpace: 'nowrap' }}>單價 {formatMoney(item.unitPrice, item.unitPriceCurrency)}</Text>
              <Text style={{ whiteSpace: 'nowrap' }}>小計 {formatMoney(item.total, item.totalCurrency)}</Text>
            </div>
          </>
          : <Text>{formatLabeledMoney(item.originalText)}</Text>}
      </li>)}
    </ul>
    <Text type="secondary">PO ID：{order.id}　幣別：{order.currency}</Text>
  </section>;
}

export default function AiAnswerDisplay({ answer }: { answer: string }) {
  const parsed = parsePurchaseOrderAnswer(answer);
  if (!parsed) {
    return <Paragraph style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', marginBottom: 0 }}>
      {formatLabeledMoney(answer)}
    </Paragraph>;
  }

  return <div style={{ overflowWrap: 'anywhere' }}>
    {parsed.intro && <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 12 }}>{parsed.intro}</Paragraph>}
    <Text strong>
      已下單採購單：共 {parsed.totalCount} 筆，以下列出 {parsed.orders.length} 筆。
    </Text>
    {parsed.orders.length === 0
      ? <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>目前沒有可列出的採購單明細。</Paragraph>
      : <div aria-label="採購單明細">
        {parsed.orders.slice(0, VISIBLE_ORDER_LIMIT).map((order, index) =>
          <div key={order.id}>
            {index > 0 && <Divider style={{ marginBlock: 4 }} />}
            <PurchaseOrderSummary order={order} />
          </div>,
        )}
        {parsed.orders.length > VISIBLE_ORDER_LIMIT && <details>
          <summary style={{ cursor: 'pointer', paddingBlock: 8 }}>
            展開其餘 {parsed.orders.length - VISIBLE_ORDER_LIMIT} 筆採購單
          </summary>
          {parsed.orders.slice(VISIBLE_ORDER_LIMIT).map((order) =>
            <div key={order.id}>
              <Divider style={{ marginBlock: 4 }} />
              <PurchaseOrderSummary order={order} />
            </div>,
          )}
        </details>}
      </div>}
    {parsed.trailingText && <Paragraph style={{ whiteSpace: 'pre-wrap', marginTop: 12, marginBottom: 0 }}>
      {formatLabeledMoney(parsed.trailingText)}
    </Paragraph>}
  </div>;
}