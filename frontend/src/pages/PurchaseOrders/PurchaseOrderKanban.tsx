import { useRef, useState } from 'react';
import { Alert, Badge, Button, Card, Empty, Modal, Space, Tag, Typography } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import type { ApiErrorResponse } from '../../types/api';
import type { PurchaseOrderResponse, PurchaseOrderStage } from '../../api/purchaseOrder';
import {
  canUseTransition,
  getPurchaseOrderStageTransition,
  isActivePurchaseOrderStatus,
  purchaseOrderStages,
  PurchaseOrderStageCommittedReadError,
  requiresReceivingDetail,
  type PurchaseOrderStageBoardSnapshot,
  type PurchaseOrderStageGateway,
  type PurchaseOrderStageTransition,
} from '../../api/purchaseOrderStage';
import { requiresMutationVerification, toApiError } from '../../api/apiError';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import VerificationRequiredAlert from '../../components/VerificationRequiredAlert';
import { displayLabel } from '../../utils/displayLabels';
import { formatPurchaseOrderDate, statusColors } from './purchaseOrderUi';
import './PurchaseOrderKanban.css';

type BoardState = 'incomplete' | 'loading' | 'error' | 'ready';

interface PurchaseOrderKanbanProps {
  state: BoardState;
  snapshot?: PurchaseOrderStageBoardSnapshot | null;
  gateway?: PurchaseOrderStageGateway;
  error?: ApiErrorResponse | Error | null;
  reason?: string;
  onRetry?: () => void;
  onRefresh?: () => void;
}

function stageLabel(stage: PurchaseOrderStage | null): string {
  return purchaseOrderStages.find((item) => item.id === stage)?.label ?? '未分類（Stage 尚未確認）';
}

function transitionLabel(transition: PurchaseOrderStageTransition): string {
  const labels: Record<PurchaseOrderStageTransition, string> = {
    BEGIN_SOURCING: '開始詢比議價',
    SUBMIT_FOR_APPROVAL: '送交審核',
    APPROVE: '核准採購單',
    ORDER: '標記已下單',
    BEGIN_RECEIVING_INSPECTION: '開始到貨驗收',
    RECEIVE: '確認整筆收貨',
    CONFIRM_PAYMENT: '標記已付款',
    REVOKE_PAYMENT_CONFIRMATION: '撤銷付款確認',
  };
  return labels[transition];
}

function transitionPermission(transition: PurchaseOrderStageTransition) {
  switch (transition) {
    case 'BEGIN_SOURCING':
    case 'SUBMIT_FOR_APPROVAL': return 'purchaseOrderStageAdvance' as const;
    case 'APPROVE': return 'purchaseOrderApproveReject' as const;
    case 'ORDER': return 'purchaseOrderOrder' as const;
    case 'BEGIN_RECEIVING_INSPECTION': return 'purchaseOrderBeginReceivingInspection' as const;
    case 'RECEIVE': return 'receiving' as const;
    case 'CONFIRM_PAYMENT':
    case 'REVOKE_PAYMENT_CONFIRMATION': return 'purchaseOrderPayment' as const;
  }
}

function confirmationDescription(transition: PurchaseOrderStageTransition): string {
  switch (transition) {
    case 'APPROVE': return '將執行正式核准命令；公司期望到貨日必須已設定。';
    case 'ORDER': return '將執行正式下單命令，不會增加庫存。';
    case 'BEGIN_RECEIVING_INSPECTION': return '只會開始到貨驗收，不會增加庫存。';
    case 'RECEIVE': return '必須先在驗收詳情完成三項人工確認；整筆收貨成功後才會更新庫存。';
    case 'CONFIRM_PAYMENT': return '只記錄人工付款確認，不會執行金流。';
    case 'REVOKE_PAYMENT_CONFIRMATION': return '只撤銷付款確認，不會倒轉已完成的收貨或庫存異動。';
    default: return '將呼叫目前 Stage 對應的受控後端命令。';
  }
}

export default function PurchaseOrderKanban({ state, snapshot, gateway, error, reason, onRetry, onRefresh }: PurchaseOrderKanbanProps) {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [items, setItems] = useState<PurchaseOrderResponse[]>(snapshot?.purchaseOrders ?? []);
  const [draggedId, setDraggedId] = useState<number | null>(null);
  const [dropTarget, setDropTarget] = useState<PurchaseOrderStage | null>(null);
  const [pending, setPending] = useState<{ po: PurchaseOrderResponse; transition: PurchaseOrderStageTransition } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const [operationError, setOperationError] = useState<ApiErrorResponse | null>(null);
  const [verificationId, setVerificationId] = useState<number | null>(null);
  const [verificationState, setVerificationState] = useState<'required' | 'checking' | 'failed' | 'verified' | null>(null);
  const [verificationError, setVerificationError] = useState<ApiErrorResponse | null>(null);
  const [announcement, setAnnouncement] = useState('');

  const canUse = (po: PurchaseOrderResponse, transition: PurchaseOrderStageTransition) =>
    !!user
    && can(user.role, transitionPermission(transition))
    && canUseTransition(po.status, transition, !!po.expectedDeliveryDate);

  const transitionTo = (po: PurchaseOrderResponse, target: PurchaseOrderStage) =>
    getPurchaseOrderStageTransition(po.stage, target);

  const openTransition = (po: PurchaseOrderResponse, transition: PurchaseOrderStageTransition) => {
    if (!gateway || submittingRef.current || verificationId !== null || !canUse(po, transition)) return;
    if (requiresReceivingDetail(transition)) {
      setAnnouncement('收貨需要先完成三項人工驗收確認，已開啟收貨詳情；看板不會略過驗收表單。');
      navigate(`/receiving/purchase-orders/${po.id}`);
      return;
    }
    setOperationError(null);
    setPending({ po, transition });
  };

  const runTransition = async () => {
    if (!pending || !gateway || submittingRef.current || verificationId !== null) return;
    const { po, transition } = pending;
    submittingRef.current = true;
    setSubmitting(true);
    setOperationError(null);
    setVerificationState(null);
    setVerificationError(null);
    try {
      const latest = await gateway.executeTransition(po.id, transition);
      setItems((current) => current.map((item) => item.id === latest.id ? latest : item));
      setPending(null);
      setAnnouncement(`${latest.poNumber} 已重新查詢後端狀態：${stageLabel(latest.stage)}。`);
      onRefresh?.();
    } catch (cause) {
      const committedReadFailure = cause instanceof PurchaseOrderStageCommittedReadError;
      const errorForDisplay = committedReadFailure ? toApiError(cause.originalError) : toApiError(cause);
      setOperationError(errorForDisplay);
      // A failed or ambiguous attempt must never leave its confirmation dialog
      // available for accidental resubmission after verification unlocks it.
      setPending(null);
      if (committedReadFailure || requiresMutationVerification(cause)) {
        setVerificationId(po.id);
        setVerificationState('required');
        setVerificationError(null);
        setAnnouncement('操作結果尚未確認；已鎖定此看板的重複操作。請先重新查詢後端狀態。');
      }
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
      setDraggedId(null);
      setDropTarget(null);
    }
  };

  const verifyLatestState = async () => {
    if (!gateway || verificationId === null || verificationState === 'checking') return;
    const id = verificationId;
    setVerificationState('checking');
    setVerificationError(null);
    try {
      const latest = await gateway.readPurchaseOrder(id);
      setItems((current) => current.map((item) => item.id === latest.id ? latest : item));
      setVerificationId(null);
      setVerificationState('verified');
      setOperationError(null);
      setAnnouncement(`已重新查詢 ${latest.poNumber}：${stageLabel(latest.stage)}。請重新審閱後再開始新的操作。`);
      onRefresh?.();
    } catch (cause) {
      setVerificationState('failed');
      setVerificationError(toApiError(cause));
      setAnnouncement('查詢失敗；相關操作仍維持鎖定。');
    }
  };

  const handleDrop = (target: PurchaseOrderStage) => {
    const po = items.find((item) => item.id === draggedId);
    setDropTarget(null);
    if (!po || !gateway || submittingRef.current || verificationId !== null) return;
    const transition = transitionTo(po, target);
    if (!transition) {
      setAnnouncement('此 Stage 轉移不合法，沒有送出任何寫入。');
      setDraggedId(null);
      return;
    }
    if (!canUse(po, transition)) {
      setAnnouncement('目前帳號不可執行此命令，沒有送出任何寫入。');
      setDraggedId(null);
      return;
    }
    openTransition(po, transition);
    setDraggedId(null);
  };

  if (state === 'loading') return <section className="purchase-order-kanban--state"><LoadingState tip="正在載入完整採購單看板…" /></section>;
  if (state === 'error') return <section className="purchase-order-kanban--state"><ErrorState title="採購單看板載入失敗" error={error} onRetry={onRetry} /></section>;

  if (state === 'incomplete' || !snapshot) {
    return <section className="purchase-order-kanban" aria-label="採購單八階段看板">
      <Alert
        type="warning"
        showIcon
        message="看板資料不完整，已隱藏卡片"
        description={reason ?? '尚未取得全部採購單資料。為避免將部分資料當成完整看板，請重新載入或切換至列表查詢。'}
      />
      <div className="purchase-order-kanban__columns" role="region" aria-label="採購單八階段流程欄位" tabIndex={0}>
        {purchaseOrderStages.map((stage) => <section className="purchase-order-kanban__column" key={stage.id} aria-labelledby={`stage-${stage.id}`}>
          <div className="purchase-order-kanban__column-heading">
            <Typography.Title level={5} id={`stage-${stage.id}`}>{stage.label}</Typography.Title>
            <Badge count="—" aria-label={`${stage.label}數量待載入`} />
          </div>
          <div className="purchase-order-kanban__pending" role="status">完整資料尚未載入</div>
        </section>)}
      </div>
    </section>;
  }

  if (!snapshot.complete) {
    return <section className="purchase-order-kanban--state"><Alert type="warning" showIcon message="看板資料不完整，已隱藏卡片" description="後端尚未確認所有符合條件的採購單均已載入。為避免部分資料造成錯誤操作，請先使用列表模式。" /></section>;
  }

  const activePurchaseOrders = items.filter((po) => isActivePurchaseOrderStatus(po.status));
  const unknownStageCount = activePurchaseOrders.filter((po) => po.stage === null).length;
  const locked = verificationId !== null || submitting;

  return <section className="purchase-order-kanban" aria-label="採購單八階段看板">
    {unknownStageCount > 0 && <Alert type="warning" showIcon message={`${unknownStageCount} 筆採購單的 Stage 為 NULL`} description="後端無法確認這些舊資料目前所在欄位，因此不放入八階段欄位，也不提供看板操作；請由採購單明細確認資料。" />}
    {announcement && <div role="status" aria-live="polite" className="purchase-order-kanban__announcement">{announcement}</div>}
    {verificationState && <VerificationRequiredAlert
      state={verificationState}
      title={verificationState === 'verified' ? '已取得最新採購單狀態' : verificationState === 'failed' ? '結果尚未確認，操作維持鎖定' : '操作結果待查核，已鎖定重複提交'}
      message={verificationState === 'verified' ? announcement : '409、伺服器錯誤或連線中斷可能發生在後端完成操作之後。系統不會自動重送；請先查詢後端真實狀態。'}
      onVerify={verificationId !== null ? () => void verifyLatestState() : undefined}
      verifyText="重新查詢採購單"
    />}
    {verificationError && <ErrorState title="採購單狀態查核失敗" error={verificationError} onRetry={() => void verifyLatestState()} retryText="重新查核" />}
    {operationError && <ErrorState title={verificationId !== null ? '操作結果待查核' : '看板操作失敗'} error={operationError} operation="mutation" />}

    <div className="purchase-order-kanban__columns" role="region" aria-label="採購單八階段流程欄位" tabIndex={0}>
      {purchaseOrderStages.map((stage) => {
        const stageItems = activePurchaseOrders.filter((po) => po.stage === stage.id);
        return <section
          className={`purchase-order-kanban__column${dropTarget === stage.id ? ' purchase-order-kanban__column--drop-target' : ''}`}
          key={stage.id}
          aria-labelledby={`stage-${stage.id}`}
          onDragOver={(event) => {
            const po = items.find((item) => item.id === draggedId);
            const transition = po ? transitionTo(po, stage.id) : null;
            if (transition && canUse(po!, transition) && !requiresReceivingDetail(transition) && !locked) {
              event.preventDefault();
              setDropTarget(stage.id);
            }
          }}
          onDragLeave={() => setDropTarget((current) => current === stage.id ? null : current)}
          onDrop={(event) => {
            event.preventDefault();
            if (!requiresTargetConfirmation(stage.id, draggedId, items)) {
              handleDrop(stage.id);
              return;
            }
            const po = items.find((item) => item.id === draggedId);
            if (po) {
              setAnnouncement('收貨須由驗收詳情完成表單與整筆收貨，未從看板送出命令。');
              navigate(`/receiving/purchase-orders/${po.id}`);
              setDraggedId(null);
              setDropTarget(null);
            }
          }}
        >
          <div className="purchase-order-kanban__column-heading">
            <Typography.Title level={5} id={`stage-${stage.id}`}>{stage.label}</Typography.Title>
            <Badge count={stageItems.length} aria-label={`${stage.label}${stageItems.length} 張採購單`} />
          </div>
          <div className="purchase-order-kanban__cards">
            {stageItems.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="此階段目前沒有採購單" /> : stageItems.map((po) => {
              const nextStage = purchaseOrderStages[purchaseOrderStages.findIndex((candidate) => candidate.id === stage.id) + 1]?.id;
              const nextTransition = nextStage ? transitionTo(po, nextStage) : null;
              const canDrag = !!gateway && !!nextTransition && !requiresReceivingDetail(nextTransition) && canUse(po, nextTransition) && !locked;
              const isReceivingStage = po.stage === 'RECEIVING_INSPECTION';
              const canOpenReceiving = isReceivingStage && !!user && can(user.role, 'receiving');
              const reverseTransition = stage.id === 'COMPLETED' ? transitionTo(po, 'PAYMENT_RECONCILIATION') : null;
              return <Card
                key={po.id}
                size="small"
                className={`purchase-order-kanban__card${draggedId === po.id ? ' purchase-order-kanban__card--dragging' : ''}`}
                draggable={canDrag}
                onDragStart={(event) => {
                  if (!canDrag) { event.preventDefault(); return; }
                  setDraggedId(po.id);
                  event.dataTransfer.effectAllowed = 'move';
                  event.dataTransfer.setData('text/plain', String(po.id));
                }}
                onDragEnd={() => { setDraggedId(null); setDropTarget(null); }}
              >
                <Space orientation="vertical" size={8} style={{ width: '100%' }} className="purchase-order-kanban__card-content">
                  <Typography.Text strong className="purchase-order-kanban__po-number">{po.poNumber}</Typography.Text>
                  <Typography.Text className="purchase-order-kanban__supplier">{po.supplierCode} — {po.supplierName}</Typography.Text>
                  <div className="purchase-order-kanban__date-row">
                    <Typography.Text type="secondary" className="purchase-order-kanban__date-label">公司期望到貨日：</Typography.Text>
                    <Typography.Text className="purchase-order-kanban__date-value">{formatPurchaseOrderDate(po.expectedDeliveryDate)}</Typography.Text>
                  </div>
                  {po.supplierPromisedDeliveryDate && <div className="purchase-order-kanban__date-row">
                    <Typography.Text type="secondary" className="purchase-order-kanban__date-label">供應商承諾交期：</Typography.Text>
                    <Typography.Text className="purchase-order-kanban__date-value">{formatPurchaseOrderDate(po.supplierPromisedDeliveryDate)}</Typography.Text>
                  </div>}
                  {po.isOverdue && <Tag className="purchase-order-kanban__overdue" color="red">後端標示已逾期</Tag>}
                  <Space wrap className="purchase-order-kanban__badges">
                    <Tag color={statusColors[po.status]}>{displayLabel(po.status)}</Tag>
                    <Tag>{stageLabel(po.stage)}</Tag>
                  </Space>
                  <Link className="purchase-order-kanban__details-link" to={`/purchase-orders/${po.id}`} aria-label={`檢視採購單 ${po.poNumber}`}>檢視採購單</Link>
                  {nextTransition && canUse(po, nextTransition) && !requiresReceivingDetail(nextTransition) && <Button
                    className="purchase-order-kanban__transition-action"
                    size="small"
                    disabled={!gateway || locked}
                    loading={submitting && pending?.po.id === po.id}
                    onClick={() => openTransition(po, nextTransition)}
                  >
                    {transitionLabel(nextTransition)}
                  </Button>}
                  {canOpenReceiving && <Link className="purchase-order-kanban__details-link" to={`/receiving/purchase-orders/${po.id}`}>前往驗收詳情</Link>}
                  {reverseTransition && canUse(po, reverseTransition) && <Button className="purchase-order-kanban__transition-action" size="small" disabled={!gateway || locked} onClick={() => openTransition(po, reverseTransition)}>
                    {transitionLabel(reverseTransition)}
                  </Button>}
                </Space>
              </Card>;
            })}
          </div>
        </section>;
      })}
    </div>
    <Modal
      title={pending ? `確認${transitionLabel(pending.transition)}` : '確認採購單操作'}
      open={!!pending}
      okText={pending ? transitionLabel(pending.transition) : '確認'}
      confirmLoading={submitting}
      okButtonProps={{ disabled: !gateway || locked }}
      onCancel={() => { if (!submittingRef.current) setPending(null); }}
      onOk={() => void runTransition()}
    >
      {pending && <Space orientation="vertical" size={12}>
        <Typography.Text>採購單：{pending.po.poNumber}</Typography.Text>
        <Typography.Text>{stageLabel(pending.po.stage)} → {transitionLabel(pending.transition)}</Typography.Text>
        <Alert type="warning" showIcon message={confirmationDescription(pending.transition)} />
      </Space>}
    </Modal>
  </section>;
}

function requiresTargetConfirmation(target: PurchaseOrderStage, draggedId: number | null, items: PurchaseOrderResponse[]): boolean {
  const source = items.find((item) => item.id === draggedId)?.stage;
  return source === 'RECEIVING_INSPECTION' && target === 'PAYMENT_RECONCILIATION';
}
