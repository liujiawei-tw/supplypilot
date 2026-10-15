import { useRef, useState, type ChangeEvent } from 'react';
import { Alert, Button, Card, Descriptions, Modal, Result, Space, Statistic, Steps, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import { Link } from 'react-router-dom';
import { confirmInventoryImport, validateInventoryImport, type InventoryImportErrorResponse, type InventoryImportValidationResponse } from '../../api/inventoryImport';
import { isUnknownMutationResult, toApiError } from '../../api/apiError';
import EmptyState from '../../components/EmptyState';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import type { ApiErrorResponse } from '../../types/api';
import { parseCsvPreview, type LocalCsvPreview, type LocalCsvRow } from './csvPreview';

type ImportPhase =
  | 'IDLE'
  | 'FILE_SELECTED'
  | 'VALIDATING'
  | 'VALIDATED'
  | 'VALIDATION_FAILED'
  | 'CONFIRMING'
  | 'IMPORTED'
  | 'CONFIRM_FAILED'
  | 'CONFIRM_UNKNOWN';

const MAX_FILE_BYTES = 5 * 1024 * 1024;

function fileSize(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KiB`;
  return `${(size / (1024 * 1024)).toFixed(2)} MiB`;
}

function currentStep(phase: ImportPhase): number {
  if (phase === 'IMPORTED') return 3;
  if (['CONFIRMING', 'CONFIRM_FAILED', 'CONFIRM_UNKNOWN'].includes(phase)) return 2;
  if (['VALIDATED', 'VALIDATION_FAILED'].includes(phase)) return 1;
  return 0;
}

function readFileText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
    reader.onerror = () => reject(reader.error ?? new Error('File read failed'));
    reader.readAsText(file, 'UTF-8');
  });
}

const errorColumns: TableProps<InventoryImportErrorResponse>['columns'] = [
  { title: '列號', dataIndex: 'row', width: 90 },
  { title: '物料代碼', dataIndex: 'materialCode', render: (value: string | null) => value || '—' },
  { title: '錯誤代碼', dataIndex: 'code', render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
  { title: '錯誤訊息', dataIndex: 'message' },
];

export default function InventoryImportPage() {
  const [phase, setPhase] = useState<ImportPhase>('IDLE');
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<LocalCsvPreview | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [validation, setValidation] = useState<InventoryImportValidationResponse | null>(null);
  const [requestError, setRequestError] = useState<ApiErrorResponse | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const revision = useRef(0);
  const validating = useRef(false);
  const confirming = useRef(false);

  const resetForFile = (nextFile: File | null) => {
    revision.current += 1;
    validating.current = false;
    confirming.current = false;
    setFile(nextFile);
    setPreview(null);
    setPreviewError(null);
    setValidation(null);
    setRequestError(null);
    setConfirmOpen(false);
    setPhase(nextFile ? 'FILE_SELECTED' : 'IDLE');
  };

  const selectFile = (event: ChangeEvent<HTMLInputElement>) => {
    const nextFile = event.target.files?.[0] ?? null;
    resetForFile(nextFile);
    if (!nextFile) return;
    const selectedRevision = revision.current;
    void readFileText(nextFile).then((text) => {
      if (revision.current !== selectedRevision) return;
      try {
        setPreview(parseCsvPreview(text));
      } catch (error) {
        setPreviewError(error instanceof Error ? error.message : '無法解析本機 CSV 預覽');
      }
    }).catch(() => {
      if (revision.current === selectedRevision) setPreviewError('無法讀取本機 CSV 預覽');
    });
  };

  const validate = async () => {
    if (!file || validating.current) return;
    const selectedRevision = revision.current;
    validating.current = true;
    setPhase('VALIDATING');
    setRequestError(null);
    setValidation(null);
    try {
      const response = await validateInventoryImport(file);
      if (revision.current !== selectedRevision) return;
      if (response.status !== 'VALIDATED' && response.status !== 'VALIDATION_FAILED') {
        setRequestError({
          code: 'UNEXPECTED_IMPORT_STATUS',
          message: `後端回傳無法用於本頁流程的批次狀態：${response.status}。目前不可確認。`,
          traceId: '', timestamp: '', path: '/inventory-imports/validate',
        });
        setPhase('FILE_SELECTED');
        return;
      }
      setValidation(response);
      setPhase(response.status === 'VALIDATED' ? 'VALIDATED' : 'VALIDATION_FAILED');
    } catch (error) {
      if (revision.current !== selectedRevision) return;
      setRequestError(toApiError(error));
      setPhase('FILE_SELECTED');
    } finally {
      if (revision.current === selectedRevision) validating.current = false;
    }
  };

  const confirm = async () => {
    if (!file || !validation || validation.status !== 'VALIDATED' || phase !== 'VALIDATED' || confirming.current) return;
    confirming.current = true;
    setConfirmOpen(false);
    setRequestError(null);
    setPhase('CONFIRMING');
    try {
      await confirmInventoryImport(validation.batchId);
      setPhase('IMPORTED');
    } catch (error) {
      setRequestError(toApiError(error));
      setPhase(isUnknownMutationResult(error) ? 'CONFIRM_UNKNOWN' : 'CONFIRM_FAILED');
    } finally {
      confirming.current = false;
    }
  };

  const localColumns: TableProps<LocalCsvRow>['columns'] = preview ? [
    { title: 'CSV Line', dataIndex: 'line', width: 100 },
    ...preview.headers.map((header, index) => ({
      title: header,
      key: `${header}-${index}`,
      render: (_value: unknown, row: LocalCsvRow) => row.values[index] ?? '',
    })),
  ] : [];
  const supportedFile = !!file && file.name.toLowerCase().endsWith('.csv') && file.size <= MAX_FILE_BYTES;
  const canValidate = supportedFile && ['FILE_SELECTED', 'VALIDATED', 'VALIDATION_FAILED'].includes(phase);
  const canConfirm = phase === 'VALIDATED' && validation?.status === 'VALIDATED' && Number.isInteger(validation.batchId) && validation.batchId > 0;

  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    <div>
      <Typography.Title level={2} style={{ marginBottom: 0 }}>庫存匯入</Typography.Title>
      <Typography.Text type="secondary">驗證 → 預覽 → 確認；匯入會取代 CSV 所列物料的目前庫存快照。</Typography.Text>
    </div>
    <Steps current={currentStep(phase)} items={[
      { title: '選取 CSV' }, { title: '驗證與預覽' }, { title: '確認匯入' }, { title: '完成' },
    ]} />

    <Card title="1. 選取 CSV">
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <input aria-label="庫存 CSV 檔案" type="file" accept=".csv,text/csv" onChange={selectFile} disabled={phase === 'CONFIRMING' || phase === 'CONFIRM_UNKNOWN'} />
        <Typography.Text type="secondary">支援 UTF-8 CSV（可含 BOM），Header 順序：material_code, quantity, unit；後端上限 5 MiB。</Typography.Text>
        {file ? <Descriptions bordered size="small" column={1} items={[
          { key: 'name', label: '檔案名稱', children: file.name },
          { key: 'size', label: '檔案大小', children: fileSize(file.size) },
          { key: 'state', label: '選取狀態', children: <Tag color="blue">已選取</Tag> },
        ]} /> : <EmptyState simple description="尚未選取 CSV 檔案" />}
        {file && (!file.name.toLowerCase().endsWith('.csv') || file.size > MAX_FILE_BYTES) && <Alert type="warning" showIcon title="檔案不符合後端支援格式" description="請選取副檔名為 .csv 且不超過 5 MiB 的檔案；後端仍會執行最終驗證。" />}
        <Button type="primary" onClick={() => void validate()} loading={phase === 'VALIDATING'} disabled={!canValidate}>驗證並預覽</Button>
      </Space>
    </Card>

    {requestError && <ErrorState title={phase === 'CONFIRM_UNKNOWN' ? '庫存匯入結果待查核' : phase.startsWith('CONFIRM') ? '庫存匯入確認失敗' : '庫存匯入驗證失敗'} error={requestError} operation={phase.startsWith('CONFIRM') ? 'mutation' : 'read'} />}
    {phase === 'VALIDATING' && <LoadingState tip="後端正在驗證 CSV…" />}

    {file && <Card title="本機 CSV 預覽" extra={<Tag>瀏覽器讀取</Tag>}>
      <Alert type="info" showIcon title="這是瀏覽器讀取的原始輸入預覽" description="此表是瀏覽器讀取的本機預覽，不會決定批次狀態或確認資格；後端驗證摘要才是最終依據。" />
      {previewError && <Alert style={{ marginTop: 16 }} type="warning" showIcon title="本機預覽無法解析" description={`${previewError}。仍可送交後端正式解析器驗證。`} />}
      {preview && <Table<LocalCsvRow> style={{ marginTop: 16 }} rowKey="key" columns={localColumns} dataSource={preview.rows}
        locale={{ emptyText: <EmptyState simple description="CSV 沒有可預覽的資料列" /> }} pagination={{ pageSize: 10, showSizeChanger: true }} scroll={{ x: true }} />}
    </Card>}

    {validation && <Card title="後端驗證摘要" extra={<Tag color={validation.status === 'VALIDATED' ? 'success' : 'error'}>{phase === 'IMPORTED' ? '已匯入' : validation.status}</Tag>}>
      <Space wrap size="large">
        <Statistic title="批次編號" value={validation.batchId} />
        <Statistic title="資料列總數" value={validation.totalRows} />
        <Statistic title="有效資料列" value={validation.validRows} />
        <Statistic title="錯誤資料列" value={validation.errorRows} />
      </Space>
      {phase === 'VALIDATION_FAILED' && <Alert style={{ marginTop: 16 }} type="error" showIcon title="驗證失敗，尚未匯入" description="整批採 All-or-Nothing；請更換或修正 CSV 後重新驗證。" />}
      {phase === 'VALIDATED' && <Alert style={{ marginTop: 16 }} type="success" showIcon title="後端驗證通過，尚未匯入" description="只有完成下一步明確確認後，才會以 快照取代 更新庫存。" />}
      {phase === 'CONFIRM_FAILED' && <Alert style={{ marginTop: 16 }} type="error" showIcon title="確認匯入未成功" description="系統不會自動重送。批次狀態可能已改變；請由後端管理途徑核實後再選擇新檔案。" />}
      {phase === 'CONFIRM_FAILED' && requestError?.code === 'CONCURRENT_MODIFICATION' && <Alert style={{ marginTop: 16 }} type="warning" showIcon title="庫存匯入資料已被其他操作更新" description="請重新檢查庫存與匯入資料。目前沒有正式批次狀態查詢端點，因此不會虛構查詢、不會清除鎖定，也不會自動重送確認。" />}
      {phase === 'CONFIRM_UNKNOWN' && <Alert style={{ marginTop: 16 }} type="warning" showIcon title="確認結果未知" description="連線中斷或逾時可能發生在庫存已更新之後。系統已鎖定重送；目前沒有 Batch Status GET，僅查庫存也不能完整證明此 Batch 是否成功，請由後端管理途徑核實。" />}
      {phase === 'CONFIRMING' && <LoadingState tip="正在確認匯入，請勿重複操作..." />}
      {phase === 'IMPORTED' && <Result status="success" title="庫存匯入已完成" subTitle={`批次 #${validation.batchId} 已確認匯入，共 ${validation.totalRows} 筆庫存快照。`} extra={<Link to="/inventory"><Button type="primary">返回庫存</Button></Link>} />}

      <Typography.Title level={4} style={{ marginTop: 24 }}>後端資料列錯誤</Typography.Title>
      {validation.errors.length > 0 ? <Table<InventoryImportErrorResponse> rowKey={(row) => `${row.row}-${row.code}`} columns={errorColumns} dataSource={validation.errors} pagination={{ pageSize: 10, showSizeChanger: true }} />
        : <EmptyState simple description="後端未回傳 Row Error" />}
      <Button type="primary" danger onClick={() => setConfirmOpen(true)} disabled={!canConfirm}>確認匯入</Button>
    </Card>}

    <Modal title="確認庫存匯入" open={confirmOpen} onCancel={() => setConfirmOpen(false)} onOk={() => void confirm()} okText="確認匯入" okButtonProps={{ danger: true, disabled: !canConfirm }} confirmLoading={phase === 'CONFIRMING'}>
      {file && validation && <>
        <Alert type="warning" showIcon title="此操作將實際更新庫存" description="CSV 數量會取代所列物料的現有庫存，不是累加；請確認摘要後再執行。" />
        <Descriptions style={{ marginTop: 16 }} bordered size="small" column={1} items={[
          { key: 'batch', label: '批次編號', children: validation.batchId },
          { key: 'file', label: '檔案名稱', children: file.name },
          { key: 'total', label: '資料列總數', children: validation.totalRows },
          { key: 'valid', label: '有效資料列', children: validation.validRows },
          { key: 'errors', label: '錯誤資料列', children: validation.errorRows },
        ]} />
      </>}
    </Modal>
  </Space>;
}
