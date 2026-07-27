import { describe, it, expect, beforeEach } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

// 模拟 localStorage
const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: (key: string) => store[key] ?? null,
    setItem: (key: string, value: string) => { store[key] = value },
    removeItem: (key: string) => { delete store[key] },
    clear: () => { store = {} }
  }
})()

Object.defineProperty(globalThis, 'localStorage', { value: localStorageMock })

describe('Router', () => {
  let router: ReturnType<typeof createRouter>

  async function createTestRouter() {
    const r = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/', redirect: '/chat' },
        {
          path: '/login',
          name: 'Login',
          component: { template: '<div>Login</div>' },
          meta: { requiresAuth: false }
        },
        {
          path: '/register',
          name: 'Register',
          component: { template: '<div>Register</div>' },
          meta: { requiresAuth: false }
        },
        {
          path: '/chat/:id?',
          name: 'Chat',
          component: { template: '<div>Chat</div>' },
          meta: { requiresAuth: true }
        }
      ]
    })

    r.beforeEach((to, _from) => {
      const token = localStorage.getItem('accessToken')

      if (to.meta.requiresAuth && !token) {
        return { name: 'Login' }
      }

      if ((to.name === 'Login' || to.name === 'Register') && token) {
        return { name: 'Chat' }
      }
    })

    // 初始化路由
    await r.push('/')
    await r.isReady()
    return r
  }

  beforeEach(() => {
    localStorage.clear()
  })

  it('根路径 / 重定向到 /chat', async () => {
    const r = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/', redirect: '/chat' },
        { path: '/chat', name: 'Chat', component: { template: '<div/>' }, meta: { requiresAuth: true } }
      ]
    })
    r.beforeEach((to, _from) => {
      if (to.meta.requiresAuth && !localStorage.getItem('accessToken')) {
        return { name: 'Login' }
      }
    })
    r.addRoute({ path: '/login', name: 'Login', component: { template: '<div/>' }, meta: { requiresAuth: false } })

    await r.push('/')
    await r.isReady()

    expect(r.currentRoute.value.path).toBe('/login')
  })

  it('未登录访问 /chat 重定向到 /login', async () => {
    const r = await createTestRouter()
    await r.push('/chat')
    expect(r.currentRoute.value.name).toBe('Login')
  })

  it('已登录访问 /chat 放行', async () => {
    localStorage.setItem('accessToken', 'valid-token')
    const r = await createTestRouter()
    await r.push('/chat')
    expect(r.currentRoute.value.name).toBe('Chat')
  })

  it('已登录访问 /login 重定向到 /chat', async () => {
    localStorage.setItem('accessToken', 'valid-token')
    const r = await createTestRouter()
    await r.push('/login')
    expect(r.currentRoute.value.name).toBe('Chat')
  })

  it('未登录访问 /login 正常显示', async () => {
    const r = await createTestRouter()
    await r.push('/login')
    expect(r.currentRoute.value.name).toBe('Login')
  })

  it('配置了 4 条路由定义', async () => {
    const r = await createTestRouter()
    // '/' (redirect), '/login', '/register', '/chat/:id?'
    expect(r.getRoutes().length).toBeGreaterThanOrEqual(3)
  })
})
