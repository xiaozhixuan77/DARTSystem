import client from './client'
import type { ApiResponse } from '@/types/api'
import type { LoginRequest, RegisterRequest, LoginResponse } from '@/types/user'

export async function login(req: LoginRequest): Promise<ApiResponse<LoginResponse>> {
  const { data } = await client.post<ApiResponse<LoginResponse>>('/auth/login', req)
  return data
}

export async function register(req: RegisterRequest): Promise<ApiResponse<{ userId: number; username: string }>> {
  const { data } = await client.post<ApiResponse<{ userId: number; username: string }>>('/auth/register', req)
  return data
}
