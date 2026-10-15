import { describe, expect, it } from 'vitest';
import { ConfigProvider, Pagination } from 'antd';
import zhTW from 'antd/locale/zh_TW';
import { render, screen } from '@testing-library/react';

describe('Ant Design zh-TW locale', () => {
  it('localizes built-in pagination controls', () => {
    render(<ConfigProvider locale={zhTW}><Pagination current={1} pageSize={10} total={30} /></ConfigProvider>);
    expect(screen.getByTitle('下一頁')).toBeTruthy();
  });
});
