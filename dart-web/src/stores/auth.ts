import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as loginApi, register as registerApi } from '@/api/auth'
import { saveTokens, clearTokens, getAccessToken } from '@/api/client'
import type { LoginRequest, RegisterRequest } from '@/types/user'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<{ userId: number; username: string } | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  const isLoggedIn = computed(() => !!getAccessToken())
  const username = computed(() => user.value?.username ?? '')

  function initAuth() {
    // 从 localStorage 恢复上次的用户信息
    const saved = localStorage.getItem('userInfo')
    if (saved) {
      try { user.value = JSON.parse(saved) } catch { /* ignore */ }
    }
  }

  async function login(req: LoginRequest) {
    loading.value = true
    error.value = null
    try {
      const res = await loginApi(req)
      if (res.code === 200) {
        saveTokens(res.data.accessToken, res.data.refreshToken)
        user.value = { userId: res.data.userId, username: res.data.username }
        localStorage.setItem('userInfo', JSON.stringify(user.value))
      } else {
        error.value = res.message || '登录失败'
      }
    } catch (e) {
      error.value = '网络错误，请稍后重试'
    } finally {
      loading.value = false
    }
  }

  async function register(req: RegisterRequest) {
    loading.value = true
    error.value = null
    try {
      const res = await registerApi(req)
      if (res.code !== 200) {
        error.value = res.message || '注册失败'
      }
    } catch (e) {
      error.value = '网络错误，请稍后重试'
    } finally {
      loading.value = false
    }
  }

  function logout() {
    user.value = null
    localStorage.removeItem('userInfo')
    clearTokens()
  }

  return { user, loading, error, isLoggedIn, username, initAuth, login, register, logout }
})
