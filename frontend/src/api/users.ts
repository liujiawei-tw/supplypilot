import axiosClient from './axiosClient';
import type { PageResponse, UserRecord, UserRole, UserStatus } from '../types/api';

export async function listUsers(): Promise<PageResponse<UserRecord>> {
  const { data } = await axiosClient.get<PageResponse<UserRecord>>('/users', { params: { page: 0, size: 100 } });
  return data;
}

export async function createUser(input: { username: string; displayName: string; password: string; role: UserRole }): Promise<UserRecord> {
  const { data } = await axiosClient.post<UserRecord>('/users', input);
  return data;
}

export async function updateUser(id: number, input: { displayName?: string; role?: UserRole; status?: UserStatus }): Promise<UserRecord> {
  const { data } = await axiosClient.patch<UserRecord>(`/users/${id}`, input);
  return data;
}
