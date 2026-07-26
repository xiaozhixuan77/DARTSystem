import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '../auth'

// Mock API
vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn()
}))

vi.mock('@/api/client', () => ({
  saveTokens: vi.fn(),
  clearTokens: vi.fn(),
  getAccessToken: vi.fn()
}))

import { login as loginApi, register as registerApi } from '@/api/auth'
import { getAccessToken } from '@/api/client'

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('初始状态下 isLoggedIn 为 false', () => {
    vi.mocked(getAccessToken).mockReturnValue(null)
    const store = useAuthStore()
    expect(store.isLoggedIn).toBe(false)
  })

  it('有 Token 时 isLoggedIn 为 true', () => {
    vi.mocked(getAccessToken).mockReturnValue('fake-token')
    const store = useAuthStore()
    expect(store.isLoggedIn).toBe(true)
  })

  it('登录成功设置用户信息', async () => {
    vi.mocked(loginApi).mockResolvedValue({
      code: 200,
      message: 'ok',
      suggestion: null,
      data: { accessToken: 'at', refreshToken: 'rt', expiresIn: 7200, userId: 1, username: 'test' }
    })
    vi.mocked(getAccessToken).mockReturnValue('at')

    const store = useAuthStore()
    await store.login({ username: 'test', password: 'pass' })

    expect(store.isLoggedIn).toBe(true)
    expect(store.username).toBe('test')
    expect(store.error).toBeNull()
  })

  it('登录失败设置 error', async () => {
    vi.mocked(loginApi).mockResolvedValue({
      code: 401,
      message: '密码错误',
      suggestion: null,
      data: null as any
    })

    const store = useAuthStore()
    await store.login({ username: 'test', password: 'wrong' })

    expect(store.error).toBe('密码错误')
  })

  it('网络异常时设置 error', async () => {
    vi.mocked(loginApi).mockRejectedValue(new Error('Network Error'))

    const store = useAuthStore()
    await store.login({ username: 'test', password: 'pass' })

    expect(store.error).toBe('网络错误，请稍后重试')
  })

  it('注册成功无 error', async () => {
    vi.mocked(registerApi).mockResolvedValue({
      code: 200,
      message: 'ok',
      suggestion: null,
      data: { userId: 1, username: 'newuser' }
    })

    const store = useAuthStore()
    await store.register({ username: 'newuser', password: 'pass' })

    expect(store.error).toBeNull()
  })

  it('注册失败设置 error', async () => {
    vi.mocked(registerApi).mockResolvedValue({
      code: 409,
      message: '用户名已存在',
      suggestion: null,
      data: null as any
    })

    const store = useAuthStore()
    await store.register({ username: 'taken', password: 'pass' })

    expect(store.error).toBe('用户名已存在')
  })

  it('登出清除状态', () => {
    vi.mocked(getAccessToken).mockReturnValue('token')
    const store = useAuthStore()
    store.logout()

    expect(store.user).toBeNull()
  })
})
