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
          colorPrimary: '#1677ff',
          borderRadius: 6,
        },
      }}
    >
      <AuthProvider><RouterProvider router={router} /></AuthProvider>
    </ConfigProvider>
  );
};

export default App;
