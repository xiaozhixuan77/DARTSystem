import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SettingsDialog from '../SettingsDialog.vue'

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    put: vi.fn(),
    post: vi.fn(),
    interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } }
  },
  getPoolUserId: vi.fn(() => 3),
  setPoolUserId: vi.fn()
}))

import client from '@/api/client'

describe('SettingsDialog.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockGet({
      '/settings/api': { data: { baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-flash', apiKey: 'sk-ab****2345' } },
      '/settings/balance': { data: null }
    })
  })

  function mockGet(routes: Record<string, any>) {
    ;(client.get as any).mockImplementation((url: string) =>
      Promise.resolve({ data: { code: 200, ...(routes[url] ?? { data: null }) } })
    )
  }

  function mountDialog() {
    return mount(SettingsDialog, { props: { visible: true } })
  }

  it('渲染两个区块：余额/服务配置 + footer 按钮', () => {
    const wrapper = mountDialog()
    expect(wrapper.find('.balance-section').exists()).toBe(true)
    expect(wrapper.find('.config-section').exists()).toBe(true)
    expect(wrapper.find('.pool-section').exists()).toBe(false)
    expect(wrapper.find('.btn-primary').text()).toContain('保存设置')
    expect(wrapper.find('.btn-secondary').text()).toContain('取消')
  })

  it('打开时加载 API 配置并展示脱敏 key', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    expect((wrapper.find('#apiUrl').element as HTMLInputElement).value)
      .toBe('https://api.deepseek.com')
    expect((wrapper.find('#modelName').element as HTMLInputElement).value)
      .toBe('deepseek-v4-flash')
    expect((wrapper.find('#apiKey').element as HTMLInputElement).value)
      .toBe('sk-ab****2345')
  })

  it('保存设置先测连接成功再调 PUT', async () => {
    ;(client.post as any).mockResolvedValue({
      data: { code: 200, data: { ok: true, latencyMs: 112, error: null } }
    })
    ;(client.put as any).mockResolvedValue({ data: { code: 200 } })

    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.find('.btn-primary').trigger('click')
    await flushPromises()

    // 先 POST /settings/test，成功后才 PUT
    expect(client.post).toHaveBeenCalledWith('/settings/test', expect.any(Object))
    await new Promise((r) => setTimeout(r, 900))
    await flushPromises()
    expect(client.put).toHaveBeenCalled()
    expect(wrapper.find('.test-result.success').text()).toContain('连接成功')
  })

  it('连接测试失败时不保存并展示错误', async () => {
    ;(client.post as any).mockResolvedValue({
      data: { code: 200, data: { ok: false, latencyMs: 0, error: '连接失败：API Key 无效（HTTP 401）' } }
    })

    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.find('.btn-primary').trigger('click')
    await flushPromises()

    expect(client.put).not.toHaveBeenCalled()
    expect(wrapper.find('.test-result.error').text()).toContain('连接失败')
  })

  it('URL 非 HTTPS 时校验失败不发请求', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.find('#apiUrl').setValue('http://plain-http.com')
    await wrapper.find('.btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('.form-group.has-error').exists()).toBe(true)
    expect(client.put).not.toHaveBeenCalled()
  })

  it('刷新余额展示余额大字', async () => {
    mockGet({
      '/settings/api': { data: { baseUrl: 'x', model: 'y', apiKey: 'z****' } },
      '/settings/balance': { data: { isAvailable: true, currency: 'CNY', totalBalance: '30.13', error: null } }
    })

    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.find('.refresh-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.balance-value .num').text()).toContain('30.13')
    expect(wrapper.find('.balance-unit').text()).toContain('CNY')
  })

  it('余额查询失败展示错误', async () => {
    mockGet({
      '/settings/api': { data: { baseUrl: 'x', model: 'y', apiKey: 'z****' } },
      '/settings/balance': { data: { isAvailable: false, currency: null, totalBalance: null, error: '余额查询失败：401' } }
    })

    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.find('.refresh-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.balance-error').text()).toContain('查询失败')
  })
})
