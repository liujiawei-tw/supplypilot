import { Alert, Drawer, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';
import type { ApiErrorResponse } from '../../types/api';
import type { PurchaseOrderResponse, PurchaseOrderStage } from '../../api/purchaseOrder';
import { purchaseOrderStages } from '../../api/purchaseOrderStage';
import { displayLabel } from '../../utils/displayLabels';
import { statusColors } from './purchaseOrderUi';
import { formatCurrency, formatDate, formatQuantity } from '../../utils/displayFormatters';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import './PurchaseOrderWaybill.css';

interface PurchaseOrderWaybillProps { purchaseOrder: PurchaseOrderResponse }

export function PurchaseOrderWaybill({ purchaseOrder }: PurchaseOrderWaybillProps) {
  return (
    <header className="po-waybill" aria-label={`採購單識別 ${purchaseOrder.poNumber}`}>
      <div className="po-waybill__topline">
        <span className="po-waybill__eyebrow">SUPPLYPILOT / 採購交接單</span>
        <span className="po-waybill__record">採購紀錄 · #{purchaseOrder.id}</span>
      </div>
      <div className="po-waybill__identity">
        <Typography.Title level={3} className="po-waybill__number">{purchaseOrder.poNumber}</Typography.Title>
        <div className="po-waybill__tags" aria-label="採購單正式狀態">
          <Tag color={statusColors[purchaseOrder.status]}>
            正式狀態 · {displayLabel(purchaseOrder.status)}
          </Tag>
          {purchaseOrder.isOverdue && <Tag color="red">後端標示已逾期</Tag>}
        </div>
      </div>
      <div className="po-waybill__facts">
        <div><span className="po-waybill__label">供應商</span><span className="po-waybill__value">{purchaseOrder.supplierCode} — {purchaseOrder.supplierName}</span></div>
        <div><span className="po-waybill__label">目前負責人</span><span className="po-waybill__value">{purchaseOrder.assignedToDisplayName ?? '未指派'}</span></div>
        <div><span className="po-waybill__label">公司期望到貨日</span><span className="po-waybill__value">{formatDate(purchaseOrder.expectedDeliveryDate, '未設定')}</span></div>
        <div><span className="po-waybill__label">供應商承諾交期</span><span className="po-waybill__value">{formatDate(purchaseOrder.supplierPromisedDeliveryDate, '尚未提供')}</span></div>
      </div>
    </header>
  );
}

export function PurchaseOrderStageRail({ stage }: { stage: PurchaseOrderStage | null }) {
  const currentIndex = stage === null ? -1 : purchaseOrderStages.findIndex((item) => item.id === stage);
  const nextStage = currentIndex >= 0 ? purchaseOrderStages[currentIndex + 1] : undefined;
  return (
    <section className="po-stage-rail" aria-label="採購單八階段流程">
      <div className="po-stage-rail__heading">
        <Typography.Title level={5}>流程欄位</Typography.Title>
        <Typography.Text type="secondary">目前 Stage 與正式狀態分開記錄</Typography.Text>
      </div>
      {currentIndex < 0 ? (
        <p className="po-stage-rail__unknown" role="status">後端尚未確認此採購單所在階段；不推測舊資料位置，也不提供看板操作。</p>
      ) : (
        <>
          <p className="po-stage-rail__mobile-summary" aria-hidden="true">
            <span>目前：<strong>{purchaseOrderStages[currentIndex].label}</strong></span>
            <span>下一步：<strong>{nextStage?.label ?? '流程已完成'}</strong></span>
            <span className="po-stage-rail__scroll-hint">左右滑動查看完整流程</span>
          </p>
          <ol className="po-stage-rail__list" tabIndex={0} aria-label="採購單階段順序">
            {purchaseOrderStages.map((item, index) => (
              <li key={item.id} className={`po-stage-rail__step${index === currentIndex ? ' is-current' : index < currentIndex ? ' is-before' : ''}`} aria-current={index === currentIndex ? 'step' : undefined}>
                <span className="po-stage-rail__number" aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
                <span className="po-stage-rail__label">{item.label}</span>
                {index === currentIndex && <span className="po-stage-rail__current">目前</span>}
              </li>
            ))}
          </ol>
        </>
      )}
    </section>
  );
}

export function PurchaseOrderInspectorSummary({ purchaseOrder }: PurchaseOrderWaybillProps) {
  return (
    <div className="po-inspector-summary">
      <PurchaseOrderWaybill purchaseOrder={purchaseOrder} />
      <PurchaseOrderStageRail stage={purchaseOrder.stage} />
      <section className="po-inspector-summary__items" aria-labelledby="po-inspector-items">
        <div className="po-inspector-summary__section-heading">
          <Typography.Title id="po-inspector-items" level={5}>採購品項</Typography.Title>
          <span>{purchaseOrder.items.length} 項</span>
        </div>
        <ul>
          {purchaseOrder.items.map((item) => (
            <li key={item.id}>
              <div className="po-inspector-summary__item-main">
                <strong>{item.materialCode} — {item.materialName}</strong><span>{formatQuantity(item.quantity, item.unit)}</span>
              </div>
              <div className="po-inspector-summary__item-price">
                <span>單價 {formatCurrency(item.unitPrice, item.currency)}</span><span>小計 {formatCurrency(item.total, item.currency)}</span>
              </div>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

interface PurchaseOrderInspectorProps {
  open: boolean;
  purchaseOrder: PurchaseOrderResponse | null;
  loading: boolean;
  error: ApiErrorResponse | null;
  onClose: () => void;
  onRetry: () => void;
}

export function PurchaseOrderInspector({ open, purchaseOrder, loading, error, onClose, onRetry }: PurchaseOrderInspectorProps) {
  return (
    <Drawer
      title={purchaseOrder ? `快速檢視 · ${purchaseOrder.poNumber}` : '快速檢視採購單'}
      open={open}
      onClose={onClose}
      placement="right"
      size="large"
      destroyOnHidden
      className="po-inspector"
    >
      <Alert type="info" showIcon title="唯讀快速檢視" description="採購單正式狀態、流程 Stage 與交期以後端資料為準；變更請進入完整明細。" />
      {loading && <LoadingState tip="正在查詢採購單最新資料…" />}
      {error && <ErrorState title="採購單快速檢視載入失敗" error={error} onRetry={onRetry} />}
      {!loading && !error && purchaseOrder && (
        <>
          <PurchaseOrderInspectorSummary purchaseOrder={purchaseOrder} />
          <Link className="po-inspector__detail-link" to={`/purchase-orders/${purchaseOrder.id}`}>
            開啟完整採購單明細
          </Link>
        </>
      )}
    </Drawer>
  );
}
