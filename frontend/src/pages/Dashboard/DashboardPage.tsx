import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { Alert, List, Space, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { can, roleLabels } from '../../auth/permissions';
import { routeCatalog } from '../../router/routeCatalog';
import { listDemands, type DemandResponse } from '../../api/demand';
import { listPurchaseOrders, type PurchaseOrderResponse, type PurchaseOrderStatus } from '../../api/purchaseOrder';
import { listReceivingPurchaseOrders, type ReceivingPurchaseOrderResponse } from '../../api/receiving';
import { toApiError } from '../../api/apiError';
import type { ApiErrorResponse, PageResponse, UserRole } from '../../types/api';
import { displayLabel } from '../../utils/displayLabels';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import './DashboardPage.css';

const { Paragraph, Text, Title } = Typography;

type WorkKind = 'purchase-order' | 'receiving' | 'demand';
type WorkRow = PurchaseOrderResponse | ReceivingPurchaseOrderResponse | DemandResponse;
type WorkPanelStatus = 'LOADING' | 'READY' | 'EMPTY' | 'FORBIDDEN' | 'ERROR';

interface WorkPanelDefinition {
  key: string;
  title: string;
  description: string;
  kind: WorkKind;
  permission: 'purchaseOrderRead' | 'receiving' | 'demands';
  status?: PurchaseOrderStatus | 'ORDERED' | 'ACTIVE';
  destination: string;
  emptyDescription: string;
}

interface WorkPanelState {
  status: WorkPanelStatus;
  rows: WorkRow[];
  totalElements: number;
  error: ApiErrorResponse | null;
}

interface DashboardState {
  role: UserRole;
  epoch: number;
  panels: Record<string, WorkPanelState>;
}

interface QuickAction {
  path: string;
  label: string;
  description: string;
}

const emptyPanel = (): WorkPanelState => ({ status: 'LOADING', rows: [], totalElements: 0, error: null });

function panelsForRole(role: UserRole): WorkPanelDefinition[] {
  if (role === 'PROCUREMENT_STAFF') return [
    { key: 'purchase-draft', title: '採購草稿', description: '後端狀態為 DRAFT 的採購單。', kind: 'purchase-order', permission: 'purchaseOrderRead', status: 'DRAFT', destination: '/purchase-orders', emptyDescription: '目前沒有 DRAFT 採購單。' },
    { key: 'purchase-approval', title: '待下單採購單', description: '已核准、可由採購人員接續下單的採購單。', kind: 'purchase-order', permission: 'purchaseOrderRead', status: 'APPROVED', destination: '/purchase-orders', emptyDescription: '目前沒有等待下單的已核准採購單。' },
    { key: 'purchase-ordered', title: '已下單採購單', description: '追蹤已送出的採購單與收貨進度。', kind: 'purchase-order', permission: 'purchaseOrderRead', status: 'ORDERED', destination: '/purchase-orders', emptyDescription: '目前沒有 ORDERED 採購單。' },
  ];

  if (role === 'PROCUREMENT_MANAGER') return [
    { key: 'purchase-review', title: '等待核准', description: '僅列出後端狀態為 DRAFT 的採購單。', kind: 'purchase-order', permission: 'purchaseOrderRead', status: 'DRAFT', destination: '/purchase-orders', emptyDescription: '目前沒有等待審核的 DRAFT 採購單。' },
  ];

  if (role === 'WAREHOUSE_STAFF') return [
    { key: 'receiving-ordered', title: '待收貨採購單', description: '使用倉管專用受限資料；逾期狀態由後端提供。', kind: 'receiving', permission: 'receiving', status: 'ORDERED', destination: '/receiving/purchase-orders', emptyDescription: '目前沒有待收貨採購單。' },
  ];

  if (role === 'ADMIN') return [
    { key: 'admin-purchase-review', title: '待處理採購單', description: '供管理員檢視草稿與已核准採購單。', kind: 'purchase-order', permission: 'purchaseOrderRead', status: 'DRAFT', destination: '/purchase-orders', emptyDescription: '目前沒有 DRAFT 採購單。' },
    { key: 'admin-purchase-ordering', title: '已核准待下單', description: '已核准、可繼續下單作業的採購單。', kind: 'purchase-order', permission: 'purchaseOrderRead', status: 'APPROVED', destination: '/purchase-orders', emptyDescription: '目前沒有已核准待下單的採購單。' },
    { key: 'admin-receiving', title: '待收貨採購單', description: '以收貨專用資料檢視到貨作業。', kind: 'receiving', permission: 'receiving', status: 'ORDERED', destination: '/receiving/purchase-orders', emptyDescription: '目前沒有待收貨採購單。' },
  ];

  return [
    { key: 'planner-demands', title: '進行中的需求', description: '依需求日期查看 ACTIVE Demand；數量不跨物料或單位加總。', kind: 'demand', permission: 'demands', status: 'ACTIVE', destination: '/demands', emptyDescription: '目前沒有 ACTIVE 需求。' },
  ];
}

function quickActionsForRole(role: UserRole): QuickAction[] {
  const actions: Record<UserRole, QuickAction[]> = {
    PROCUREMENT_STAFF: [
      { path: 'recommendations', label: '建立採購建議', description: '依期間與物料取得後端推薦結果。' },
      { path: 'purchase-orders', label: '查看採購單', description: '查詢草稿、核准、下單與歷史狀態。' },
    ],
    PROCUREMENT_MANAGER: [
      { path: 'purchase-orders', label: '審核採購單', description: '檢視 DRAFT 並執行授權的核准或退回。' },
      { path: 'recommendations', label: '查看採購建議', description: '依既有採購建議編號查詢決策快照。' },
    ],
    WAREHOUSE_STAFF: [
      { path: 'receiving/purchase-orders', label: '開啟收貨作業', description: '查看待收貨採購單並執行整筆收貨。' },
      { path: 'receiving/purchase-orders?status=RECEIVED', label: '查看已收貨紀錄', description: '查詢 Backend 保存的收貨結果。' },
    ],
    ADMIN: [
      { path: 'purchase-orders', label: '管理採購單', description: '查看授權的採購單與處理流程。' },
      { path: 'receiving/purchase-orders', label: '開啟收貨作業', description: '使用收貨專用資料檢視待收貨項目。' },
      { path: 'recommendations', label: '建立採購建議', description: '依期間與物料取得後端推薦結果。' },
    ],
    PRODUCTION_PLANNER: [
      { path: 'demands', label: '管理需求', description: '查看與維護生產需求。' },
      { path: 'inventory', label: '查看庫存', description: '依物料查詢已授權的庫存資料。' },
    ],
  };

  return actions[role].flatMap((action) => {
    const route = routeCatalog.find((entry) => entry.path === action.path.split('?')[0] && entry.navigation);
    return route && can(role, route.permission) ? [{ ...action, path: `/${action.path}` }] : [];
  });
}

async function fetchPanel(definition: WorkPanelDefinition): Promise<PageResponse<WorkRow>> {
  if (definition.kind === 'purchase-order') {
    return await listPurchaseOrders({ status: definition.status as PurchaseOrderStatus, page: 0, size: 5, sort: 'id,desc' }) as PageResponse<WorkRow>;
  }
  if (definition.kind === 'receiving') {
    return await listReceivingPurchaseOrders({ status: 'ORDERED', page: 0, size: 5, sort: 'expectedDeliveryDate,asc' }) as PageResponse<WorkRow>;
  }
  return await listDemands({ status: 'ACTIVE', page: 0, size: 5, sort: 'requiredDate,asc' }) as PageResponse<WorkRow>;
}

function isPageResponse(value: unknown): value is PageResponse<WorkRow> {
  if (typeof value !== 'object' || value === null) return false;
  const page = value as Partial<PageResponse<WorkRow>>;
  return Array.isArray(page.content) && typeof page.totalElements === 'number';
}

function isPurchaseOrder(row: WorkRow): row is PurchaseOrderResponse {
  return 'poNumber' in row && 'createdBy' in row;
}

function isReceivingOrder(row: WorkRow): row is ReceivingPurchaseOrderResponse {
  return 'poNumber' in row && !('createdBy' in row);
}

function rowDescription(row: WorkRow): ReactNode {
  if (isPurchaseOrder(row)) {
    return <Space wrap size={[8, 4]}>
      <Text>{row.supplierCode} · {row.supplierName}</Text>
      <Text type="secondary">預計交期：{row.expectedDeliveryDate ?? '未設定'}</Text>
      {row.isOverdue && <Tag color="red">已逾期</Tag>}
    </Space>;
  }
  if (isReceivingOrder(row)) {
    return <Space wrap size={[8, 4]}>
      <Text>{row.supplierCode} · {row.supplierName}</Text>
      <Text type="secondary">預計交期：{row.expectedDeliveryDate ?? '未設定'}</Text>
      {row.isOverdue && <Tag color="red">已逾期</Tag>}
    </Space>;
  }
  return <Space wrap size={[8, 4]}>
    <Text>物料 ID {row.materialId}</Text>
    <Text type="secondary">需求日：{row.requiredDate}</Text>
  </Space>;
}

function rowTitle(row: WorkRow): ReactNode {
  if (isPurchaseOrder(row) || isReceivingOrder(row)) {
    return <Space wrap>
      <Text strong>{row.poNumber}</Text>
      <Tag color={row.status === 'DRAFT' ? 'default' : row.status === 'APPROVED' ? 'blue' : 'gold'}>{displayLabel(row.status)}</Tag>
    </Space>;
  }
  return <Space wrap>
    <Text strong>需求 #{row.id}</Text>
    <Tag color="blue">{displayLabel(row.status)}</Tag>
  </Space>;
}

function nextStep(row: WorkRow, role: UserRole): string {
  if (isPurchaseOrder(row)) {
    if (row.status === 'DRAFT') return role === 'PROCUREMENT_MANAGER' ? '檢視內容並核准或退回' : '檢視草稿內容';
    if (row.status === 'APPROVED') return '檢視交期並接續下單';
    return '追蹤採購單與收貨進度';
  }
  if (isReceivingOrder(row)) return '核對物料與數量後進入收貨';
  return '開啟需求清單檢視完整資料';
}

function DashboardWorkPanel({
  definition,
  state,
  role,
  onRetry,
}: {
  definition: WorkPanelDefinition;
  state: WorkPanelState;
  role: UserRole;
  onRetry: () => void;
}) {
  return <section className="dashboard-panel" aria-labelledby={`${definition.key}-heading`}>
    <div className="dashboard-panel-heading">
      <div>
        <Title level={4} id={`${definition.key}-heading`}>{definition.title}</Title>
        <Paragraph type="secondary">{definition.description}</Paragraph>
      </div>
      {(state.status === 'READY' || state.status === 'EMPTY') && (
        <Tag color={state.status === 'EMPTY' ? 'default' : 'blue'}>
          {state.status === 'EMPTY' ? '目前 0 筆' : `顯示 ${state.rows.length} 筆／符合條件共 ${state.totalElements} 筆`}
        </Tag>
      )}
    </div>

    {state.status === 'LOADING' && <LoadingState tip={`正在查詢「${definition.title}」…`} skeleton rows={2} />}
    {state.status === 'FORBIDDEN' && <Alert
      type="warning"
      showIcon
      title="此工作資料無權限顯示"
      description="查詢被拒絕，不能將此狀態當成沒有待辦。請使用下方其他已授權入口。"
    />}
    {state.status === 'ERROR' && <ErrorState
      title={`${definition.title}查詢失敗`}
      error={state.error}
      onRetry={onRetry}
      retryText="重新查詢"
    />}
    {state.status === 'EMPTY' && <EmptyState simple description={definition.emptyDescription} />}
    {state.status === 'READY' && state.rows.length === 0 && (
      <Alert type="warning" showIcon title="查詢頁面沒有項目" description={`後端回報符合條件共 ${state.totalElements} 筆，請開啟清單查看。`} />
    )}
    {state.status === 'READY' && state.rows.length > 0 && <List
      className="dashboard-work-list"
      dataSource={state.rows}
      rowKey={(row) => String(row.id)}
      renderItem={(row) => <List.Item
        actions={[<Link
          key="open"
          aria-label={isPurchaseOrder(row) || isReceivingOrder(row) ? `開啟 ${row.poNumber} 明細` : `查看需求 ${row.id}`}
          to={isReceivingOrder(row) ? `/receiving/purchase-orders/${row.id}` : isPurchaseOrder(row) ? `/purchase-orders/${row.id}` : definition.destination}
        >{isPurchaseOrder(row) || isReceivingOrder(row) ? '開啟明細' : '查看需求'}</Link>]}
      >
        <List.Item.Meta
          title={rowTitle(row)}
          description={<Space orientation="vertical" size={2}>
            {rowDescription(row)}
            <Text className="dashboard-next-step">下一步：{nextStep(row, role)}</Text>
          </Space>}
        />
      </List.Item>}
    />}
    <Link className="dashboard-panel-link" to={definition.destination}>前往{definition.kind === 'receiving' ? '收貨作業' : definition.kind === 'demand' ? '需求管理' : '採購單清單'}</Link>
  </section>;
}

export const DashboardPage = () => {
  const { user } = useAuth();
  const [dashboard, setDashboard] = useState<DashboardState | null>(null);
  const epochRef = useRef(0);
  const panelRequestRef = useRef<Record<string, number>>({});

  const loadPanel = useCallback(async (definition: WorkPanelDefinition, role: UserRole, epoch: number) => {
    const request = (panelRequestRef.current[definition.key] ?? 0) + 1;
    panelRequestRef.current[definition.key] = request;
    setDashboard((current) => current?.epoch === epoch ? {
      ...current,
      panels: { ...current.panels, [definition.key]: emptyPanel() },
    } : current);

    if (!can(role, definition.permission)) {
      if (epochRef.current === epoch && panelRequestRef.current[definition.key] === request) {
        setDashboard((current) => current?.epoch === epoch ? {
          ...current,
          panels: { ...current.panels, [definition.key]: { ...emptyPanel(), status: 'FORBIDDEN' } },
        } : current);
      }
      return;
    }

    try {
      const result = await fetchPanel(definition);
      if (epochRef.current !== epoch || panelRequestRef.current[definition.key] !== request) return;
      if (!isPageResponse(result)) throw new Error('Invalid work queue response');
      setDashboard((current) => current?.epoch === epoch ? {
        ...current,
        panels: { ...current.panels, [definition.key]: {
          status: result.content.length === 0 && result.totalElements === 0 ? 'EMPTY' : 'READY',
          rows: result.content,
          totalElements: result.totalElements,
          error: null,
        } },
      } : current);
    } catch (error) {
      if (epochRef.current !== epoch || panelRequestRef.current[definition.key] !== request) return;
      const normalized = toApiError(error);
      const forbidden = normalized.httpStatus === 403 || normalized.code === 'ACCESS_DENIED';
      setDashboard((current) => current?.epoch === epoch ? {
        ...current,
        panels: { ...current.panels, [definition.key]: {
          ...emptyPanel(),
          status: forbidden ? 'FORBIDDEN' : 'ERROR',
          error: normalized,
        } },
      } : current);
    }
  }, []);

  useEffect(() => {
    if (!user) return;
    const epoch = ++epochRef.current;
    const definitions = panelsForRole(user.role);
    panelRequestRef.current = {};
    const timer = window.setTimeout(() => {
      if (epochRef.current !== epoch) return;
      setDashboard({
        role: user.role,
        epoch,
        panels: Object.fromEntries(definitions.map((definition) => [definition.key, emptyPanel()])),
      });
      definitions.forEach((definition) => { void loadPanel(definition, user.role, epoch); });
    }, 0);
    return () => {
      window.clearTimeout(timer);
      epochRef.current += 1;
    };
  }, [user, loadPanel]);

  if (!user) return null;

  const roleData = dashboard?.role === user.role ? dashboard : null;
  const definitions = panelsForRole(user.role);
  const states = roleData ? Object.values(roleData.panels) : [];
  const hasLoadedPanel = states.some((panel) => panel.status === 'READY' || panel.status === 'EMPTY');
  const hasUnavailablePanel = states.some((panel) => panel.status === 'ERROR' || panel.status === 'FORBIDDEN');
  const partial = hasLoadedPanel && hasUnavailablePanel;
  const actions = quickActionsForRole(user.role);

  return <main className="dashboard-workspace">
    <header className="dashboard-header">
      <div>
        <Title level={2}>工作總覽</Title>
        <Paragraph type="secondary">{user.displayName} · {roleLabels[user.role]}</Paragraph>
      </div>
      <Text type="secondary">依目前登入角色顯示已授權的工作資料</Text>
    </header>

    <section className="dashboard-primary" aria-labelledby="dashboard-work-heading">
      <Title level={3} id="dashboard-work-heading">優先工作</Title>
      {partial && <Alert
        className="dashboard-partial-alert"
        type="warning"
        showIcon
        title="部分工作資料無法載入"
        description="已成功取得的工作仍保留在畫面中；失敗區塊可各自重新查詢。"
      />}
      {roleData ? <div className="dashboard-work-grid">
        {definitions.map((definition) => <DashboardWorkPanel
          key={definition.key}
          definition={definition}
          state={roleData.panels[definition.key] ?? emptyPanel()}
          role={user.role}
          onRetry={() => void loadPanel(definition, user.role, roleData.epoch)}
        />)}
      </div> : <LoadingState tip="正在準備角色工作區…" skeleton rows={3} />}
    </section>

    <section className="dashboard-quick-actions" aria-labelledby="dashboard-actions-heading">
      <Title level={3} id="dashboard-actions-heading">常用工作入口</Title>
      <List
        className="dashboard-action-list"
        grid={{ gutter: 24, xs: 1, sm: 2, lg: 3 }}
        dataSource={actions}
        rowKey="path"
        renderItem={(action) => <List.Item>
          <div className="dashboard-action">
            <Link to={action.path}>{action.label}</Link>
            <Text type="secondary">{action.description}</Text>
          </div>
        </List.Item>}
      />
      {actions.length === 0 && <Paragraph type="secondary">此角色目前沒有額外的工作入口。</Paragraph>}
    </section>
  </main>;
};

export default DashboardPage;
