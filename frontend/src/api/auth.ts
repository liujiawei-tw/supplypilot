import axiosClient from './axiosClient';
import type { AuthenticatedUser, LoginResponse } from '../types/api';

export async function loginRequest(username: string, password: string): Promise<LoginResponse> {
  const { data } = await axiosClient.post<LoginResponse>('/auth/login', { username, password });
  return data;
}

export async function currentUserRequest(): Promise<AuthenticatedUser> {
  const { data } = await axiosClient.get<AuthenticatedUser>('/auth/me');
  return data;
}
