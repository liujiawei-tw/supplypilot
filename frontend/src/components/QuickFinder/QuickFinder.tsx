import { useMemo, useRef, useState } from 'react';
import { Alert, Button, Empty, Input, List, Modal, Select, Space, Spin, Tag, Typography } from 'antd';
import { FileSearchOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { listPurchaseOrders, type PurchaseOrderResponse } from '../../api/purchaseOrder';
import { listSuppliers, type SupplierResponse } from '../../api/supplier';
import { listMaterials, type MaterialResponse } from '../../api/material';
import { listInventory, type InventoryResponse } from '../../api/inventory';
import { toApiError } from '../../api/apiError';
import { useAuth } from '../../auth/useAuth';
import { canUser, type Permission } from '../../auth/permissions';
import { displayLabel } from '../../utils/displayLabels';
import { formatDate, formatQuantity } from '../../utils/displayFormatters';
import type { PageResponse } from '../../types/api';
import './QuickFinder.css';

const { Text } = Typography;
type ScopeKey = 'purchase-orders' | 'suppliers' | 'materials' | 'inventory' | 'documents';
interface ScopeOption { key: ScopeKey; label: string; permission: Permission; }
const scopeOptions: ScopeOption[] = [
  { key: 'purchase-orders', label: '採購單', permission: 'purchaseOrderRead' },
  { key: 'suppliers', label: '供應商', permission: 'suppliers' },
  { key: 'materials', label: '物料', permission: 'materials' },
  { key: 'inventory', label: '庫存', permission: 'inventoryLevels' },
  { key: 'documents', label: '文件', permission: 'documentRead' },
];
type QuickResult =
  | { kind: 'purchase-order'; item: PurchaseOrderResponse }
  | { kind: 'supplier'; item: SupplierResponse; finderField: 'code' | 'name' }
  | { kind: 'material'; item: MaterialResponse; finderField: 'code' | 'name' }
  | { kind: 'inventory'; item: InventoryResponse };
interface QuickFinderProps { open: boolean; onClose: () => void; }

function uniqueById<T extends { id: number }>(pages: PageResponse<T>[]): T[] {
  return [...new Map(pages.flatMap((page) => page.content).map((item) => [item.id, item])).values()];
}
function resultTitle(result: QuickResult): string {
  if (result.kind === 'purchase-order') return result.item.poNumber;
  if (result.kind === 'supplier' || result.kind === 'material') return result.item.code;
  return result.item.materialCode;
}
function resultDescription(result: QuickResult): string {
  if (result.kind === 'purchase-order') return `${result.item.supplierCode} · ${result.item.supplierName} · ${result.item.items.length} 項物料`;
  if (result.kind === 'supplier') return result.item.name;
  if (result.kind === 'material') return `${result.item.name} · ${result.item.baseUnit}`;
  return `${result.item.materialName} · ${formatQuantity(result.item.quantity, result.item.unit)}`;
}
function resultTags(result: QuickResult): string[] {
  if (result.kind === 'purchase-order') return [
    `正式狀態：${displayLabel(result.item.status)}`, `目前階段：${displayLabel(result.item.stage)}`,
    result.item.isOverdue ? '逾期' : '', `公司期望：${formatDate(result.item.expectedDeliveryDate)}`,
    `供應承諾：${formatDate(result.item.supplierPromisedDeliveryDate)}`,
  ].filter(Boolean);
  if (result.kind === 'supplier') return [displayLabel(result.item.status), result.item.country ?? ''].filter(Boolean);
  if (result.kind === 'material') return [displayLabel(result.item.status), `安全庫存 ${formatQuantity(result.item.safetyStock, result.item.baseUnit)}`];
  return [`目前庫存 ${formatQuantity(result.item.quantity, result.item.unit)}`, `更新 ${formatDate(result.item.updatedAt)}`];
}

export default function QuickFinder({ open, onClose }: QuickFinderProps) {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [scope, setScope] = useState<ScopeKey>('purchase-orders');
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<QuickResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [hasSearched, setHasSearched] = useState(false);
  const sequence = useRef(0);
  const availableScopes = useMemo(() => user ? scopeOptions.filter((option) => canUser(user, option.permission)) : [], [user]);
  const effectiveScope = availableScopes.some((option) => option.key === scope) ? scope : availableScopes[0]?.key ?? 'documents';
  const selectedScope = availableScopes.find((option) => option.key === effectiveScope);

  const close = () => {
    sequence.current += 1;
    setLoading(false);
    setQuery('');
    setResults([]);
    setError('');
    setHasSearched(false);
    onClose();
  };
  const changeScope = (next: ScopeKey) => {
    sequence.current += 1; setLoading(false); setScope(next); setResults([]); setError(''); setHasSearched(false);
  };
  const changeQuery = (value: string) => {
    sequence.current += 1; setLoading(false); setQuery(value); setResults([]); setError(''); setHasSearched(false);
  };

  const search = async () => {
    if (effectiveScope === 'documents') { close(); navigate('/documents'); return; }
    const value = query.trim();
    if (!value || !selectedScope) return;
    const current = ++sequence.current;
    setLoading(true); setError(''); setResults([]); setHasSearched(false);
    try {
      if (effectiveScope === 'purchase-orders') {
        const pages = await Promise.all([
          listPurchaseOrders({ poNumber: value, page: 0, size: 6 }),
          listPurchaseOrders({ supplierCode: value, page: 0, size: 6 }),
          listPurchaseOrders({ supplierName: value, page: 0, size: 6 }),
          listPurchaseOrders({ materialCode: value, page: 0, size: 6 }),
        ]);
        if (current === sequence.current) setResults(uniqueById(pages).map((item) => ({ kind: 'purchase-order', item })));
      } else if (effectiveScope === 'suppliers') {
        const pages = await Promise.all([
          listSuppliers({ code: value, page: 0, size: 6 }), listSuppliers({ name: value, page: 0, size: 6 }),
        ]);
        if (current === sequence.current) {
          const matches = [
            ...pages[0].content.map((item) => ({ item, finderField: 'code' as const })),
            ...pages[1].content.map((item) => ({ item, finderField: 'name' as const })),
          ];
          const unique = new Map<number, typeof matches[number]>();
          for (const match of matches) if (!unique.has(match.item.id)) unique.set(match.item.id, match);
          setResults([...unique.values()].map(({ item, finderField }) => ({ kind: 'supplier', item, finderField })));
        }
      } else if (effectiveScope === 'materials') {
        const pages = await Promise.all([
          listMaterials({ code: value, page: 0, size: 6 }), listMaterials({ name: value, page: 0, size: 6 }),
        ]);
        if (current === sequence.current) {
          const matches = [
            ...pages[0].content.map((item) => ({ item, finderField: 'code' as const })),
            ...pages[1].content.map((item) => ({ item, finderField: 'name' as const })),
          ];
          const unique = new Map<number, typeof matches[number]>();
          for (const match of matches) if (!unique.has(match.item.id)) unique.set(match.item.id, match);
          setResults([...unique.values()].map(({ item, finderField }) => ({ kind: 'material', item, finderField })));
        }
      } else {
        const page = await listInventory({ materialCode: value, page: 0, size: 6 });
        if (current === sequence.current) setResults(page.content.map((item) => ({ kind: 'inventory', item })));
      }
      if (current === sequence.current) setHasSearched(true);
    } catch (searchError) {
      if (current === sequence.current) setError(toApiError(searchError).message);
    } finally {
      if (current === sequence.current) setLoading(false);
    }
  };

  const openResult = (result: QuickResult) => {
    const encoded = encodeURIComponent(query.trim());
    close();
    if (result.kind === 'purchase-order') navigate(`/purchase-orders/${result.item.id}`);
    else if (result.kind === 'supplier') navigate(`/suppliers?finder=${encoded}&tab=suppliers&finderField=${result.finderField}`);
    else if (result.kind === 'material') navigate(`/materials?finder=${encoded}&finderField=${result.finderField}`);
    else navigate(`/inventory?tab=stock-levels&finder=${encoded}`);
  };

  if (!user) return null;
  return (
    <Modal className="quick-finder" title="查找採購資料" open={open} onCancel={close} footer={null}
      width="min(760px, calc(100vw - 24px))" destroyOnHidden aria-label="查找採購資料">
      <Space className="quick-finder__controls" align="start" wrap>
        <Select aria-label="查找範圍" value={effectiveScope} options={availableScopes.map(({ key, label }) => ({ value: key, label }))} onChange={changeScope} />
        {effectiveScope !== 'documents' && <Input autoFocus aria-label={`${selectedScope?.label ?? '資料'}查詢文字`}
          placeholder={effectiveScope === 'purchase-orders' ? '輸入單號、供應商代碼／名稱或物料代碼'
            : effectiveScope === 'inventory' ? '輸入物料代碼' : `輸入${selectedScope?.label ?? '資料'}代碼或名稱`}
          prefix={<SearchOutlined aria-hidden="true" />} value={query} onChange={(event) => changeQuery(event.target.value)}
          onPressEnter={() => void search()} maxLength={120} allowClear />}
        <Button type="primary" icon={<SearchOutlined aria-hidden="true" />} onClick={() => void search()} disabled={effectiveScope !== 'documents' && !query.trim()}>
          {effectiveScope === 'documents' ? '開啟文件篩選' : '查找'}
        </Button>
      </Space>
      {effectiveScope === 'documents' && <Alert className="quick-finder__note" type="info" showIcon icon={<FileSearchOutlined />}
        title="文件依類型、供應商與狀態篩選"
        description="目前文件 API 沒有標題文字搜尋。開啟文件管理後可使用實際支援的篩選條件，避免顯示不完整的全文搜尋結果。" />}
      {error && <Alert className="quick-finder__state" type="error" showIcon title={error} />}
      {loading && <div className="quick-finder__state"><Spin tip="正在查詢後端資料…" /></div>}
      {!loading && !error && hasSearched && results.length === 0 && query.trim() && effectiveScope !== 'documents' &&
        <Empty className="quick-finder__state" image={Empty.PRESENTED_IMAGE_SIMPLE} description="沒有找到符合條件的資料" />}
      {!loading && results.length > 0 && <List className="quick-finder__results" dataSource={results} renderItem={(result) =>
        <List.Item key={`${result.kind}-${resultTitle(result)}`} actions={[<Button key="open" type="link" onClick={() => openResult(result)}>開啟</Button>]}>
          <List.Item.Meta
            title={<Button type="text" className="quick-finder__result-title" onClick={() => openResult(result)}>{resultTitle(result)}</Button>}
            description={<Space orientation="vertical" size={4}>
              <Text>{resultDescription(result)}</Text>
              <Space className="quick-finder__tags" wrap size={[8, 4]}>{resultTags(result).map((tag) => <Tag key={tag}>{tag}</Tag>)}</Space>
            </Space>}
          />
        </List.Item>
      } />}
      {!loading && results.length > 0 && <Text className="quick-finder__footnote">結果來自所選範圍的現有後端條件；顯示前幾筆，完整篩選與分頁請進入對應頁面。</Text>}
      {!query.trim() && effectiveScope !== 'documents' && <Text className="quick-finder__footnote">按 Enter 開始查詢。不同資料類型會分別使用其現有後端條件。</Text>}
    </Modal>
  );
}





