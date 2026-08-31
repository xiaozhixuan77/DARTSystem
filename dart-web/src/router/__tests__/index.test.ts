import { describe, it, expect, vi, beforeEach } from 'vitest'
import router from '../index'

// 路由组件懒加载，全链路断言只需路径与可达性（不渲染真实组件）
describe('router（无登录直通模式）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('根路径 / 重定向到 /chat', async () => {
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/chat')
  })

  it('未设置任何凭据时 /chat 直接可达（无守卫）', async () => {
    await router.push('/chat')
    expect(router.currentRoute.value.name).toBe('Chat')
  })

  it('/knowledge 直接可达', async () => {
    await router.push('/knowledge')
    expect(router.currentRoute.value.name).toBe('Knowledge')
  })

  it('不存在 /login 路由', async () => {
    await router.push('/login')
    expect(router.currentRoute.value.matched).toHaveLength(0)
  })
})
