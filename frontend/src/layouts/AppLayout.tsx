import React, { useEffect, useMemo, useState } from 'react';
import { Button, Typography } from 'antd';
import { FileSearchOutlined, LogoutOutlined, SearchOutlined } from '@ant-design/icons';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import './AppLayout.css';
import { useAuth } from '../auth/useAuth';
import { canUser, roleLabels } from '../auth/permissions';
import { routeCatalog } from '../router/routeCatalog';
import AiAssistantDrawer from '../components/AiAssistant';
import QuickFinder from '../components/QuickFinder/QuickFinder';

const { Text } = Typography;

const routePriority = [
  'purchase-orders',
  'receiving/purchase-orders',
  'suppliers',
  'materials',
  'inventory',
  'inventory/import',
  'documents',
  'users',
];

function isCurrentRoute(pathname: string, path: string) {
  return pathname === path || pathname.startsWith(path + '/');
}

export const AppLayout: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout } = useAuth();
  const [aiOpen, setAiOpen] = useState(false);
  const [quickFinderOpen, setQuickFinderOpen] = useState(false);

  useEffect(() => {
    const openFinder = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setQuickFinderOpen(true);
      }
    };

    window.addEventListener('keydown', openFinder);
    return () => window.removeEventListener('keydown', openFinder);
  }, []);

  const navigation = useMemo(() => {
    if (!user) return [];

    return routeCatalog
      .filter((entry) => entry.navigation && canUser(user, entry.permission))
      .sort((a, b) => routePriority.indexOf(a.path) - routePriority.indexOf(b.path))
      .map(({ path, label }) => ({ path: '/' + path, label }));
  }, [user]);

  if (!user) return null;

  const selectedRoute = navigation
    .filter((entry) => isCurrentRoute(location.pathname, entry.path))
    .sort((a, b) => b.path.length - a.path.length)[0];
  const mustChangePassword = Boolean(user.mustChangePassword);
  const canQueryAi = canUser(user, 'aiQuery');

  return (
    <div className="app-layout">
      <a className="app-skip-link" href="#main-content">跳至主要內容</a>
      <header className="app-header">
        <div className="app-header__topline">
          <Link className="app-brand" to="/" aria-label="SupplyPilot 採購工作區">
            <img className="app-brand__mark" src="/favicon.svg" alt="" aria-hidden="true" width={40} height={40} />
            <span className="app-brand__wordmark">
              <strong>SupplyPilot</strong>
              <span>採購工作台</span>
            </span>
          </Link>

          <div className="app-header__finder">
            {!mustChangePassword && (
              <>
                <Button
                  className="app-finder-trigger"
                  icon={<SearchOutlined aria-hidden="true" />}
                  onClick={() => setQuickFinderOpen(true)}
                  aria-label="搜尋採購資料"
                  aria-keyshortcuts="Control+K Meta+K"
                >
                  <span>搜尋採購資料</span>
                  <kbd aria-hidden="true">Ctrl K</kbd>
                </Button>
                <Text className="app-finder-hint">採購單、供應商、物料與庫存；文件依範圍查詢</Text>
              </>
            )}
          </div>

          <div className="app-account">
            <div className="app-account__identity">
              <Text className="app-account__name">{user.displayName}</Text>
              <Text className="app-account__role">{roleLabels[user.role]}</Text>
            </div>
            {canQueryAi && !mustChangePassword && (
              <Button className="app-ai-button" icon={<FileSearchOutlined aria-hidden="true" />} onClick={() => setAiOpen(true)}>
                AI 查詢
              </Button>
            )}
            <Button
              className="app-logout-button"
              icon={<LogoutOutlined />}
              onClick={() => { logout(); navigate('/login', { replace: true, state: null }); }}
            >
              登出
            </Button>
          </div>
        </div>

        {!mustChangePassword ? (
          <nav className="app-nav" aria-label="主要工作區">
            <div className="app-nav__inner">
              {navigation.map((entry) => {
                const current = selectedRoute?.path === entry.path;
                return (
                  <Link
                    className={'app-nav__link' + (current ? ' is-active' : '')}
                    key={entry.path}
                    to={entry.path}
                    aria-current={current ? 'page' : undefined}
                  >
                    {entry.label}
                  </Link>
                );
              })}
            </div>
          </nav>
        ) : (
          <div className="app-password-notice" role="status">請完成密碼設定後再進入採購工作區。</div>
        )}
      </header>

      <main className="app-content" id="main-content" tabIndex={-1}>
        <Outlet />
      </main>

      {canQueryAi && !mustChangePassword && (
        <AiAssistantDrawer open={aiOpen} onClose={() => setAiOpen(false)} />
      )}
      {!mustChangePassword && (
        <QuickFinder open={quickFinderOpen} onClose={() => setQuickFinderOpen(false)} />
      )}
    </div>
  );
};

export default AppLayout;
