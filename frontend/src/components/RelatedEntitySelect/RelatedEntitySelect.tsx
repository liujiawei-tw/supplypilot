import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Select, Space, Spin, Typography } from 'antd';
import { toApiError } from '../../api/apiError';
import { getMaterial, listMaterials, type MaterialResponse } from '../../api/material';
import { getSupplier, listSuppliers, type SupplierResponse } from '../../api/supplier';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';
import ErrorState from '../ErrorState';

type Entity = Pick<MaterialResponse | SupplierResponse, 'id' | 'code' | 'name'>;
const SEARCH_DEBOUNCE_MS = 250;

interface RelatedEntitySelectProps {
  entity: 'material' | 'supplier';
  value?: number;
  onChange?: (value: number | undefined, option?: unknown) => void;
  id?: string;
  'aria-describedby'?: string;
  'aria-invalid'?: boolean;
  'aria-required'?: boolean;
  disabled?: boolean;
  activeOnly?: boolean;
  showMaterialUnit?: boolean;
  onError?: (error: unknown) => void;
}

function parseSearch(search: string): { code?: string; name?: string } {
  const trimmed = search.trim();
  if (!trimmed) return {};
  if (trimmed.toLowerCase().startsWith('name:')) {
    const name = trimmed.slice(5).trim();
    return name ? { name } : {};
  }
  return { code: trimmed };
}

export default function RelatedEntitySelect({ entity, value, onChange, id, disabled, activeOnly = true, showMaterialUnit = false, onError, ...fieldAccessibility }: RelatedEntitySelectProps) {
  const { user } = useAuth();
  const canLoad = entity !== 'supplier' || (!!user && can(user.role, 'suppliers'));
  const [items, setItems] = useState<Entity[]>([]);
  const [entityCache, setEntityCache] = useState<Record<number, Entity>>({});
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<ReturnType<typeof toApiError> | null>(null);
  const [selectedError, setSelectedError] = useState<ReturnType<typeof toApiError> | null>(null);
  const requestId = useRef(0);
  const selectionRequestId = useRef(0);
  const loadingRef = useRef(false);
  const cacheRef = useRef<Record<number, Entity>>({});

  const remember = useCallback((entities: Entity[]) => {
    if (!entities.length) return;
    setEntityCache((current) => {
      const next = { ...current };
      for (const item of entities) next[item.id] = item;
      cacheRef.current = next;
      return next;
    });
  }, []);

  const load = useCallback(async (nextPage: number, replace: boolean) => {
    if (!canLoad || loadingRef.current) return;
    const current = ++requestId.current;
    loadingRef.current = true;
    setLoadError(null);
    setLoading(true);
    try {
      const common = { page: nextPage, size: 20, sort: 'code,asc', ...parseSearch(search) };
      const response = entity === 'material'
        ? await listMaterials({ ...common, status: activeOnly ? 'ACTIVE' : undefined })
        : await listSuppliers({ ...common, status: activeOnly ? 'ACTIVE' : undefined });
      if (current !== requestId.current) return;
      remember(response.content);
      setItems((existing) => replace ? response.content : [...existing, ...response.content.filter((candidate) => !existing.some((item) => item.id === candidate.id))]);
      setPage(response.page);
      setTotalPages(response.totalPages);
    } catch (error) {
      if (current === requestId.current) {
        setLoadError(toApiError(error));
        onError?.(error);
      }
    } finally {
      if (current === requestId.current) {
        loadingRef.current = false;
        setLoading(false);
      }
    }
  }, [activeOnly, canLoad, entity, onError, remember, search]);

  const loadSelected = useCallback(async (selectedId: number) => {
    const current = ++selectionRequestId.current;
    setSelectedError(null);
    try {
      const selected = entity === 'material' ? await getMaterial(selectedId) : await getSupplier(selectedId);
      if (current === selectionRequestId.current) remember([selected]);
    } catch (error) {
      if (current === selectionRequestId.current) {
        setSelectedError(toApiError(error));
        onError?.(error);
      }
    }
  }, [entity, onError, remember]);

  useEffect(() => {
    if (!canLoad) return;
    const timer = window.setTimeout(() => void load(0, true), search.trim() ? SEARCH_DEBOUNCE_MS : 0);
    return () => window.clearTimeout(timer);
  }, [canLoad, load, search]);

  useEffect(() => {
    if (!canLoad || value === undefined || cacheRef.current[value]) return;
    void loadSelected(value);
    return () => { selectionRequestId.current += 1; };
  }, [canLoad, loadSelected, value]);

  useEffect(() => () => {
    requestId.current += 1;
    selectionRequestId.current += 1;
    loadingRef.current = false;
  }, []);

  const options = useMemo(() => {
    const selected = value === undefined ? undefined : entityCache[value];
    const visible = selected && !items.some((item) => item.id === selected.id) ? [selected, ...items] : items;
    return visible.map((item) => ({
      value: item.id,
      label: `${item.code} — ${item.name}${showMaterialUnit && entity === 'material' && 'baseUnit' in item ? ` (${item.baseUnit})` : ''}`,
    }));
  }, [entity, entityCache, items, showMaterialUnit, value]);

  const handleSearch = (nextSearch: string) => {
    if (nextSearch === search) return;
    requestId.current += 1;
    loadingRef.current = false;
    setLoading(false);
    setLoadError(null);
    setItems([]);
    setPage(0);
    setTotalPages(1);
    setSearch(nextSearch);
  };

  const handleChange = (nextValue: number | undefined, option: unknown) => {
    setSelectedError(null);
    onChange?.(nextValue, option);
  };

  return <Space orientation="vertical" size={4} style={{ width: '100%' }}>
    <Select<number>
    id={id} {...fieldAccessibility} showSearch filterOption={false} value={value} onChange={handleChange} disabled={disabled || !canLoad} allowClear
    aria-label={entity === 'supplier' ? '選取供應商' : '選取物料'}
    placeholder={`搜尋${entity === 'supplier' ? '供應商' : '物料'}代碼或名稱`}
    notFoundContent={loading ? <Spin size="small" /> : loadError ? '搜尋失敗，請查看錯誤訊息並重試' : '沒有符合的項目'}
    options={options}
    onSearch={handleSearch}
    onPopupScroll={(event) => {
      const target = event.currentTarget;
      if (!loadingRef.current && page + 1 < totalPages && target.scrollTop + target.offsetHeight >= target.scrollHeight - 8) void load(page + 1, false);
    }}
    />
    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      依代碼搜尋請直接輸入；依名稱搜尋請輸入 <Typography.Text code>name:名稱</Typography.Text>。
    </Typography.Text>
    {loadError && <ErrorState title="主檔搜尋失敗" error={loadError} message="無法取得搜尋結果。已選取項目會保留，請重試或調整搜尋條件。" onRetry={() => void load(0, true)} retryText="重試搜尋" />}
    {selectedError && value !== undefined && <ErrorState title="已選主檔資訊載入失敗" error={selectedError} message="目前選取值仍保留；無法確認其完整代碼與名稱，請重新載入。" onRetry={() => void loadSelected(value)} retryText="重新載入已選項目" />}
  </Space>;
}
