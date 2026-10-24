import { Divider, Space, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';
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
  totalCount: number;
  content: PurchaseOrderAnswerBlock[];
  unparsedOrderCount: number;
}

type PurchaseOrderAnswerBlock =
  | { type: 'text'; text: string }
  | { type: 'purchase-order'; order: PurchaseOrderFact };

const PURCHASE_ORDER_START_PATTERN = /[^\s（）;；]+（ID \d+、supplier=/g;
const PURCHASE_ORDER_PATTERN =
  /^(?<poNumber>[^\s（）;；]+)（ID (?<id>\d+)、supplier=(?<supplier>[\s\S]*?)、status=(?<status>[^、；]+)、expectedDeliveryDate=(?<expectedDeliveryDate>[^、；]+)、currency=(?<currency>[A-Z]{3})、items=\[(?<items>[\s\S]*?)\]）[;；]?/u;
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

/** Display-only formatting for labeled amounts in plain answer text; quantities remain untouched. */
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
  const countMatch = PURCHASE_ORDER_COUNT_PATTERN.exec(answer);
  const starts = [...answer.matchAll(PURCHASE_ORDER_START_PATTERN)];
  const content: PurchaseOrderAnswerBlock[] = [];
  let cursor = 0;
  let unparsedOrderCount = 0;

  starts.forEach((start, index) => {
    const startOffset = start.index ?? 0;
    const endOffset = starts[index + 1]?.index ?? answer.length;
    if (startOffset > cursor) {
      content.push({ type: 'text', text: answer.slice(cursor, startOffset) });
    }

    const candidate = answer.slice(startOffset, endOffset);
    const match = PURCHASE_ORDER_PATTERN.exec(candidate);
    const fields = match?.groups;
    if (!match || !fields) {
      content.push({ type: 'text', text: candidate });
      unparsedOrderCount += 1;
    } else {
      content.push({
        type: 'purchase-order',
        order: {
          poNumber: fields.poNumber,
          id: fields.id,
          supplier: fields.supplier,
          status: fields.status,
          expectedDeliveryDate: fields.expectedDeliveryDate,
          currency: fields.currency,
          items: fields.items,
        },
      });
      const remaining = candidate.slice(match[0].length);
      if (remaining) content.push({ type: 'text', text: remaining });
    }

    cursor = endOffset;
  });

  if (cursor < answer.length) content.push({ type: 'text', text: answer.slice(cursor) });

  const orderCount = content.filter((block) => block.type === 'purchase-order').length;
  if (!countMatch && orderCount === 0) return null;

  return {
    totalCount: countMatch ? Number(countMatch[1]) : orderCount,
    content,
    unparsedOrderCount,
  };
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
    <Text type="secondary">採購單編號：{order.id}　幣別：{order.currency}</Text>
  </section>;
}

export default function AiAnswerDisplay({ answer }: { answer: string }) {
  const parsed = parsePurchaseOrderAnswer(answer);
  if (!parsed) {
    return <Paragraph style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', marginBottom: 0 }}>
      {formatLabeledMoney(answer)}
    </Paragraph>;
  }

  const orderCount = parsed.content.filter((block) => block.type === 'purchase-order').length;
  const renderedContent: ReactNode[] = [];
  let renderedOrderCount = 0;
  let extraOrders: PurchaseOrderFact[] = [];

  const flushExtraOrders = () => {
    if (extraOrders.length === 0) return;
    const orders = extraOrders;
    extraOrders = [];
    renderedContent.push(
      <div key={'extra-orders-' + renderedOrderCount}>
        {renderedOrderCount > 0 && <Divider style={{ marginBlock: 4 }} />}
        <details>
          <summary style={{ cursor: 'pointer', paddingBlock: 8 }}>
            展開其餘 {orders.length} 筆採購單
          </summary>
          {orders.map((order) =>
            <div key={order.id}>
              <Divider style={{ marginBlock: 4 }} />
              <PurchaseOrderSummary order={order} />
            </div>
          )}
        </details>
      </div>,
    );
  };

  parsed.content.forEach((block, index) => {
    if (block.type === 'text') {
      if (!block.text.trim()) return;
      flushExtraOrders();
      renderedContent.push(
        <Paragraph key={'text-' + index} style={{ whiteSpace: 'pre-wrap', marginBlock: 12 }}>
          {formatLabeledMoney(block.text)}
        </Paragraph>,
      );
      return;
    }

    if (renderedOrderCount >= VISIBLE_ORDER_LIMIT) {
      extraOrders.push(block.order);
      renderedOrderCount += 1;
      return;
    }

    flushExtraOrders();
    renderedContent.push(
      <div key={'order-' + block.order.id + '-' + index}>
        {renderedOrderCount > 0 && <Divider style={{ marginBlock: 4 }} />}
        <PurchaseOrderSummary order={block.order} />
      </div>,
    );
    renderedOrderCount += 1;
  });
  flushExtraOrders();

  return <div style={{ overflowWrap: 'anywhere' }}>
    <Text strong>
      已下單採購單：共 {parsed.totalCount} 筆，以下列出 {orderCount} 筆。
    </Text>
    {parsed.unparsedOrderCount > 0 && <Paragraph type="secondary" style={{ marginBlock: 8 }}>
      部分採購單格式無法解析，已保留原始回答內容。
    </Paragraph>}
    {renderedContent}
    {orderCount === 0 && parsed.unparsedOrderCount === 0 &&
      <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>目前沒有可列出的採購單明細。</Paragraph>}
  </div>;
}
