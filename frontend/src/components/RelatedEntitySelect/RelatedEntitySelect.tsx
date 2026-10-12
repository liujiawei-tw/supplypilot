import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Select, Spin } from 'antd';
import { getMaterial, listMaterials, type MaterialResponse } from '../../api/material';
import { getSupplier, listSuppliers, type SupplierResponse } from '../../api/supplier';
import { can } from '../../auth/permissions';
import { useAuth } from '../../auth/useAuth';

type Entity = Pick<MaterialResponse | SupplierResponse, 'id' | 'code' | 'name'>;

interface RelatedEntitySelectProps {
  entity: 'material' | 'supplier';
  value?: number;
  onChange?: (value: number | undefined) => void;
  disabled?: boolean;
  activeOnly?: boolean;
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

export default function RelatedEntitySelect({ entity, value, onChange, disabled, activeOnly = true, onError }: RelatedEntitySelectProps) {
  const { user } = useAuth();
  const canLoad = entity !== 'supplier' || (!!user && can(user.role, 'suppliers'));
  const [items, setItems] = useState<Entity[]>([]);
  const [entityCache, setEntityCache] = useState<Record<number, Entity>>({});
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);
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
      if (current === requestId.current) onError?.(error);
    } finally {
      if (current === requestId.current) {
        loadingRef.current = false;
        setLoading(false);
      }
    }
  }, [activeOnly, canLoad, entity, onError, remember, search]);

  useEffect(() => {
    if (!canLoad) return;
    const timer = window.setTimeout(() => void load(0, true), 0);
    return () => window.clearTimeout(timer);
  }, [canLoad, load]);

  useEffect(() => {
    if (!canLoad || value === undefined || cacheRef.current[value]) return;
    const current = ++selectionRequestId.current;
    const getSelected = entity === 'material' ? getMaterial(value) : getSupplier(value);
    void getSelected.then((selected) => {
      if (current === selectionRequestId.current) remember([selected]);
    }).catch((error: unknown) => {
      if (current === selectionRequestId.current) onError?.(error);
    });
    return () => { selectionRequestId.current += 1; };
  }, [canLoad, entity, onError, remember, value]);

  useEffect(() => () => {
    requestId.current += 1;
    selectionRequestId.current += 1;
    loadingRef.current = false;
  }, []);

  const options = useMemo(() => {
    const selected = value === undefined ? undefined : entityCache[value];
    const visible = selected && !items.some((item) => item.id === selected.id) ? [selected, ...items] : items;
    return visible.map((item) => ({ value: item.id, label: `${item.code} — ${item.name}` }));
  }, [entityCache, items, value]);

  const handleSearch = (nextSearch: string) => {
    requestId.current += 1;
    loadingRef.current = false;
    setLoading(false);
    setSearch(nextSearch);
  };

  return <Select<number>
    showSearch filterOption={false} value={value} onChange={onChange} disabled={disabled || !canLoad} allowClear
    aria-label={`Select ${entity}`}
    placeholder={`Search ${entity} code; use name: for name`}
    notFoundContent={loading ? <Spin size="small" /> : 'No results'}
    options={options}
    onSearch={handleSearch}
    onPopupScroll={(event) => {
      const target = event.currentTarget;
      if (!loadingRef.current && page + 1 < totalPages && target.scrollTop + target.offsetHeight >= target.scrollHeight - 8) void load(page + 1, false);
    }}
  />;
}
