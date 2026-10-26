import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Descriptions, Input, Modal, Space, Tag, Typography, message } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { getReceivingPurchaseOrder, receivePurchaseOrder, type ReceivingPurchaseOrderResponse } from '../../api/receiving';
import { beginReceivingInspection, recordReceivingInspection } from '../../api/purchaseOrder';
import { requiresMutationVerification, toApiError } from '../../api/apiError';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import VerificationRequiredAlert from '../../components/VerificationRequiredAlert';
import type { ApiErrorResponse } from '../../types/api';
import { formatTimestamp } from '../PurchaseOrders/purchaseOrderUi';
import { displayLabel } from '../../utils/displayLabels';
import './ReceivingDetailPage.css';

const emptyReceivingInspection = { allItemsReceived: false, allQuantitiesConfirmed: false, allQualityAccepted: false, note: null };

type VerificationState = 'required' | 'checking' | 'failed' | 'verified';

export default function ReceivingDetailPage() {
  const { id } = useParams(); const poId = Number(id); const { user } = useAuth();
  const [apiMessage, contextHolder] = message.useMessage();
  const [po, setPo] = useState<ReceivingPurchaseOrderResponse | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [receiveError, setReceiveError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [confirming, setConfirming] = useState(false);
  const [inspecting, setInspecting] = useState(false);
  const [allItemsReceived, setAllItemsReceived] = useState(false);
  const [allQuantitiesConfirmed, setAllQuantitiesConfirmed] = useState(false);
  const [allQualityAccepted, setAllQualityAccepted] = useState(false);
  const [inspectionNote, setInspectionNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [verificationRequired, setVerificationRequired] = useState(false);
  const [verificationState, setVerificationState] = useState<VerificationState | null>(null);
  const [verificationMessage, setVerificationMessage] = useState('');
  const [verificationError, setVerificationError] = useState<ApiErrorResponse | null>(null);
  const submittingRef = useRef(false);
  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try { const result = await getReceivingPurchaseOrder(poId); setPo(result); setConfirming(false); }
    catch (requestError) { setError(toApiError(requestError)); }
    finally { setLoading(false); }
  }, [poId]);
  useEffect(() => { const timer = window.setTimeout(() => { if (Number.isSafeInteger(poId) && poId > 0) void load(); else { setError({ code: 'INVALID_ID', message: 'Invalid Purchase Order ID', traceId: '', timestamp: '', path: '' }); setLoading(false); } }, 0); return () => window.clearTimeout(timer); }, [load, poId]);

  const verifyReceivingStatus = async () => {
    if (!verificationRequired || verificationState === 'checking') return;
    setVerificationState('checking'); setVerificationError(null);
    try {
      const latest = await getReceivingPurchaseOrder(poId);
      setPo(latest); setReceiveError(null); setVerificationRequired(false); setVerificationState('verified');
      setVerificationMessage(latest.status === 'RECEIVED'
        ? '此採購單已完成收貨。收貨專用查詢已確認狀態，本頁不會再送出收貨。'
        : `目前仍為待收貨（${displayLabel(latest.status)}），先前操作結果需由使用者重新確認。系統沒有自動重送；如要再操作，必須重新開啟確認流程。`);
    } catch (requestError) {
      setVerificationRequired(true); setVerificationState('failed');
      setVerificationMessage('無法透過 Receiving 專用 GET 取得真實狀態；結果尚未確認，Receive 維持鎖定。');
      setVerificationError(toApiError(requestError));
    }
  };

  const receive = async () => {
    const inspection = po?.receivingInspection ?? emptyReceivingInspection;
    if (!po || po.status !== 'ORDERED' || po.orderedProgress !== 'RECEIVING_INSPECTION'
      || !inspection.allItemsReceived || !inspection.allQuantitiesConfirmed
      || !inspection.allQualityAccepted || submittingRef.current || verificationRequired) return;
    submittingRef.current = true; setSubmitting(true); setReceiveError(null); setVerificationState(null); setVerificationError(null);
    try { const result = await receivePurchaseOrder(po.id); setPo(result); setConfirming(false); setVerificationRequired(false); apiMessage.success('整筆收貨已完成'); }
    catch (requestError) {
      setReceiveError(toApiError(requestError));
      if (requiresMutationVerification(requestError)) {
        setVerificationRequired(true); setVerificationState('required');
        setVerificationMessage('收貨遇到 409、伺服器錯誤或連線結果不明。系統不會自動重送；請先使用 Receiving 專用 GET 查核 後端 真實狀態。');
      }
      setConfirming(false);
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  const saveInspection = async () => {
    if (!po || po.orderedProgress !== 'RECEIVING_INSPECTION' || submittingRef.current || verificationRequired) return;
    submittingRef.current = true; setSubmitting(true); setReceiveError(null);
    try {
      const result = await recordReceivingInspection(po.id, { allItemsReceived, allQuantitiesConfirmed, allQualityAccepted, note: inspectionNote || null });
      setPo({ ...po, orderedProgress: result.orderedProgress, receivingInspection: result.receivingInspection });
      setInspecting(false); apiMessage.success('驗收紀錄已儲存');
    } catch (requestError) {
      setReceiveError(toApiError(requestError));
      if (requiresMutationVerification(requestError)) {
        setVerificationRequired(true); setVerificationState('required');
        setVerificationMessage('驗收紀錄結果未知。系統不會自動重送；請先重新查詢後端採購單狀態。');
      }
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  const startInspection = async () => {
    if (!po || submittingRef.current || verificationRequired) return;
    submittingRef.current = true; setSubmitting(true); setReceiveError(null);
    try {
      const result = await beginReceivingInspection(po.id);
      setPo({ ...po, orderedProgress: result.orderedProgress, receivingInspection: result.receivingInspection });
      apiMessage.success('採購單已移入到貨驗收');
    } catch (requestError) {
      setReceiveError(toApiError(requestError));
      if (requiresMutationVerification(requestError)) {
        setVerificationRequired(true); setVerificationState('required');
        setVerificationMessage('移入驗收階段結果未知。系統不會自動重送；請先重新查詢後端採購單狀態。');
      }
    } finally { submittingRef.current = false; setSubmitting(false); }
  };

  const beginEditingInspection = () => {
    if (verificationRequired || !po) return;
    const inspection = po.receivingInspection ?? emptyReceivingInspection;
    setAllItemsReceived(inspection.allItemsReceived);
    setAllQuantitiesConfirmed(inspection.allQuantitiesConfirmed);
    setAllQualityAccepted(inspection.allQualityAccepted);
    setInspectionNote(inspection.note ?? '');
    setInspecting(true);
  };

  if (loading && !po) return <LoadingState tip="正在載入收貨明細…" />;
  if (error || !po) return <ErrorState title="收貨明細載入失敗" error={error ?? undefined} onRetry={() => void load()} />;
  const canViewTransactions = !!user && can(user.role, 'stockTransactions');
  const canManageInspection = user?.role === 'ADMIN';
  const receivingInspection = po.receivingInspection ?? emptyReceivingInspection;
  const allChecksPassed = receivingInspection.allItemsReceived && receivingInspection.allQuantitiesConfirmed && receivingInspection.allQualityAccepted;
  const progressText = po.orderedProgress ? displayLabel(po.orderedProgress) : '尚未開始驗收';

  return <section className="receiving-detail">
    {contextHolder}
    <header className="receiving-detail__heading">
      <div>
        <Link className="receiving-detail__back" to="/receiving/purchase-orders">← 返回收貨作業</Link>
        <Typography.Title level={2} className="receiving-detail__title">{po.poNumber}</Typography.Title>
        <p className="receiving-detail__supplier">{po.supplierCode} · {po.supplierName}</p>
      </div>
      <div className="receiving-detail__header-actions">
        {canViewTransactions && <Link className="receiving-detail__transaction-link" to={`/inventory?tab=stock-transactions&purchaseOrderId=${po.id}`}>查看庫存異動</Link>}
        <div className="receiving-detail__statuses" aria-label="採購單狀態與收貨進度">
          <div><span>正式狀態</span><Tag color={po.status === 'RECEIVED' ? 'success' : 'gold'}>{displayLabel(po.status)}</Tag></div>
          <div><span>作業階段</span><strong>{progressText}</strong></div>
        </div>
      </div>
    </header>

    {verificationState && <VerificationRequiredAlert
      state={verificationState}
      title={verificationState === 'verified' ? '已取得最新收貨狀態' : verificationState === 'failed' ? '結果尚未確認，收貨維持鎖定' : '收貨結果需要查核，已鎖定再次提交'}
      message={verificationMessage}
      onVerify={verificationRequired ? () => void verifyReceivingStatus() : undefined}
      verifyText="重新查詢收貨狀態"
    />}
    {verificationError && <ErrorState title="收貨狀態查核失敗" error={verificationError} onRetry={() => void verifyReceivingStatus()} retryText="重新查核" />}
    {receiveError && <ErrorState title={verificationRequired ? '收貨結果待查核' : '收貨操作失敗'} error={receiveError} operation="mutation" />}

    <section className="receiving-detail__facts" aria-label="採購單交接資訊">
      <div><span>預計交期</span><strong>{po.expectedDeliveryDate ?? '未設定'}</strong>{po.status === 'ORDERED' && po.isOverdue && <Tag color="error">已逾期</Tag>}</div>
      <div><span>下單時間</span><strong>{formatTimestamp(po.orderedAt)}</strong></div>
      {po.status === 'RECEIVED' && <div><span>收貨完成時間</span><strong>{formatTimestamp(po.receivedAt)}</strong></div>}
      {po.status === 'RECEIVED' && <div><span>收貨經手人</span><strong>{po.receivedByDisplayName || '未提供'}</strong></div>}
    </section>

    {po.status === 'ORDERED' && po.orderedProgress !== 'RECEIVING_INSPECTION' && <section className="receiving-detail__start">
      <div>
        <Typography.Title level={3} className="receiving-detail__section-title">尚未進入到貨驗收</Typography.Title>
        <p>開始驗收只會記錄作業階段；確認全部品項到貨、數量及品質後，才可一次完成整筆收貨。</p>
      </div>
      {canManageInspection
        ? <Button className="receiving-detail__primary-action" loading={submitting} disabled={submitting || verificationRequired}
          onClick={() => Modal.confirm({ title: '開始到貨驗收？', content: '此步驟只記錄進入驗收階段，不會增加庫存。', okText: '開始驗收', onOk: () => startInspection() })}>
          開始到貨驗收
        </Button>
        : <span className="receiving-detail__role-note">由管理員開始到貨驗收</span>}
    </section>}

    {po.status === 'ORDERED' && po.orderedProgress === 'RECEIVING_INSPECTION' && <section className="receiving-detail__section" aria-labelledby="receiving-checks-title">
      <div className="receiving-detail__section-heading">
        <div>
          <Typography.Title level={3} id="receiving-checks-title" className="receiving-detail__section-title">驗收交接</Typography.Title>
          <p>三項檢查全部通過後，才會開放整筆收貨。</p>
        </div>
        {canManageInspection && <Button loading={submitting} disabled={submitting || verificationRequired} onClick={beginEditingInspection}>
          填寫／更新驗收結果
        </Button>}
      </div>

      <ul className="receiving-detail__checks">
        <li className={receivingInspection.allItemsReceived ? 'is-passed' : 'is-pending'}>
          <span>整筆貨品到齊</span>
          <strong>{receivingInspection.allItemsReceived ? '已確認' : '尚未確認'}</strong>
          <small>短收或未到貨時，請保留在驗收階段。</small>
        </li>
        <li className={receivingInspection.allQuantitiesConfirmed ? 'is-passed' : 'is-pending'}>
          <span>全部品項數量</span>
          <strong>{receivingInspection.allQuantitiesConfirmed ? '已確認' : '尚未確認'}</strong>
          <small>逐項核對採購數量後再記錄結果。</small>
        </li>
        <li className={receivingInspection.allQualityAccepted ? 'is-passed' : 'is-pending'}>
          <span>全部品項品質</span>
          <strong>{receivingInspection.allQualityAccepted ? '驗收合格' : '尚未確認合格'}</strong>
          <small>品質異常時請記錄備註，不要完成收貨。</small>
        </li>
      </ul>

      {receivingInspection.note && <Alert className="receiving-detail__inspection-note" type="warning" showIcon title="驗收備註" description={receivingInspection.note} />}
      {!allChecksPassed && <Alert className="receiving-detail__inspection-alert" type="warning" showIcon title="完整收貨尚未開放" description="請先確認整筆到貨、全部數量及全部品質合格；短收或異常須留在驗收階段並記錄備註。" />}
      {allChecksPassed && <Alert className="receiving-detail__ready-alert" type="success" showIcon title="驗收條件已完成" description="可繼續確認並提交整筆收貨。" />}

      <div className="receiving-detail__receive-row">
        <Button type="primary" className="receiving-detail__primary-action" loading={submitting}
          disabled={submitting || verificationRequired || !receivingInspection.allItemsReceived || !receivingInspection.allQuantitiesConfirmed || !receivingInspection.allQualityAccepted}
          onClick={() => setConfirming(true)}>
          確認整筆收貨
        </Button>
        {verificationRequired && <span className="receiving-detail__lock-note">狀態查核完成前，已鎖定再次提交。</span>}
      </div>
    </section>}

    {po.status === 'RECEIVED' && <section className="receiving-detail__completed" aria-live="polite">
      <Typography.Title level={3} className="receiving-detail__section-title">整筆收貨已完成</Typography.Title>
      <p>此採購單已完成收貨，品項與數量如下。庫存異動可由上方授權連結查閱。</p>
    </section>}

    <section className="receiving-detail__section" aria-labelledby="receiving-items-title">
      <div className="receiving-detail__section-heading">
        <div>
          <Typography.Title level={3} id="receiving-items-title" className="receiving-detail__section-title">收貨品項</Typography.Title>
          <p>核對每項品名、物料編號與應收數量。</p>
        </div>
        <span className="receiving-detail__item-count">{po.items.length} 項</span>
      </div>
      <ul className="receiving-detail__items">
        {po.items.map((item) => <li key={item.id}>
          <div className="receiving-detail__item-name">
            <span>物料</span>
            <strong><code>{item.materialCode}</code> · {item.materialName}</strong>
          </div>
          <div className="receiving-detail__item-quantity">
            <span>應收數量</span>
            <strong>{String(item.quantity)} {item.unit}</strong>
          </div>
        </li>)}
      </ul>
    </section>

    <Modal title="確認整筆收貨" open={confirming} okText="確認整筆收貨" confirmLoading={submitting}
      okButtonProps={{ disabled: submitting || verificationRequired || !allChecksPassed }}
      onCancel={() => { if (!submittingRef.current) setConfirming(false); }}
      onOk={() => void receive()}>
      <Alert type="warning" showIcon title="此操作會一次更新庫存，並為所有品項建立庫存異動；不支援部分收貨。" />
      <Descriptions className="receiving-detail__confirm-summary" bordered column={1} items={[
        { key: 'po', label: '採購單編號', children: po.poNumber },
        { key: 'supplier', label: '供應商', children: po.supplierName },
        { key: 'type', label: '收貨方式', children: '整筆收貨' },
        { key: 'items', label: '全部品項', children: po.items.map((item) => item.materialCode + '：' + String(item.quantity) + ' ' + item.unit).join('；') },
      ]} />
    </Modal>

    <Modal title="人工數量與品質驗收" open={inspecting} okText="儲存驗收紀錄" confirmLoading={submitting}
      okButtonProps={{ disabled: submitting || verificationRequired }}
      onCancel={() => { if (!submittingRef.current) setInspecting(false); }}
      onOk={() => void saveInspection()}>
      <Space direction="vertical" className="receiving-detail__inspection-form">
        <Checkbox checked={allItemsReceived} onChange={(event) => setAllItemsReceived(event.target.checked)}>整筆貨品已到齊（不接受部分收貨）</Checkbox>
        <Checkbox checked={allQuantitiesConfirmed} onChange={(event) => setAllQuantitiesConfirmed(event.target.checked)}>全部品項數量已確認</Checkbox>
        <Checkbox checked={allQualityAccepted} onChange={(event) => setAllQualityAccepted(event.target.checked)}>全部品項品質驗收合格</Checkbox>
        <Input.TextArea aria-label="驗收異常備註" maxLength={2000} rows={4} value={inspectionNote} onChange={(event) => setInspectionNote(event.target.value)} placeholder="短收、尚未到貨或品質異常請記錄於此" />
      </Space>
    </Modal>
  </section>;
}
