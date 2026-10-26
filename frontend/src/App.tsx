import React from 'react';
import { ConfigProvider } from 'antd';
import zhTW from 'antd/locale/zh_TW';
import { RouterProvider } from 'react-router-dom';
import router from './router/routes';
import { AuthProvider } from './auth/AuthContext';

export const App: React.FC = () => {
  return (
    <ConfigProvider
      locale={zhTW}
      theme={{
        token: {
          colorPrimary: '#0d6b67',
          colorInfo: '#245db3',
          colorSuccess: '#137248',
          colorWarning: '#8a4b08',
          colorError: '#b42318',
          colorText: '#16283b',
          colorTextSecondary: '#53697e',
          colorBgBase: '#ffffff',
          colorBgLayout: '#f2f5f8',
          colorBorder: '#dbe4ec',
          borderRadius: 12,
          borderRadiusSM: 8,
          borderRadiusLG: 16,
          controlHeight: 40,
          fontSize: 15,
          lineHeight: 1.55,
        },
        components: {
          Button: { borderRadius: 8, controlHeight: 40, primaryShadow: 'none' },
          Card: { borderRadiusLG: 16, boxShadowTertiary: 'none' },
          Input: { activeShadow: '0 0 0 2px rgba(37, 99, 235, 0.16)' },
          Table: {
            borderColor: '#dbe4ec',
            cellPaddingBlock: 12,
            headerBg: '#f7f9fb',
            headerColor: '#16283b',
            rowHoverBg: '#f2f7f8',
          },
          Modal: { borderRadiusLG: 16 },
        },
      }}
    >
      <AuthProvider><RouterProvider router={router} /></AuthProvider>
    </ConfigProvider>
  );
};

export default App;
