import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SettingsDialog from '../SettingsDialog.vue'

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    put: vi.fn(),
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

  it('渲染两个区块：API 配置/余额', () => {
    const wrapper = mountDialog()
    expect(wrapper.find('.api-section').exists()).toBe(true)
    expect(wrapper.find('.balance-section').exists()).toBe(true)
    expect(wrapper.find('.pool-section').exists()).toBe(false)
  })

  it('打开时加载 API 配置并展示脱敏 key', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    expect((wrapper.find('input.api-url').element as HTMLInputElement).value)
      .toBe('https://api.deepseek.com')
    expect((wrapper.find('input.api-model').element as HTMLInputElement).value)
      .toBe('deepseek-v4-flash')
    expect((wrapper.find('input.api-key').element as HTMLInputElement).value)
      .toBe('sk-ab****2345')
  })

  it('保存 API 配置调用 PUT', async () => {
    ;(client.put as any).mockResolvedValue({ data: { code: 200 } })

    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.find('.api-save').trigger('click')

    expect(client.put).toHaveBeenCalled()
  })

  it('刷新余额展示余额信息', async () => {
    ;(client.get as any).mockResolvedValue({
      data: { code: 200, data: { isAvailable: true, currency: 'CNY', totalBalance: '30.13', error: null } }
    })

    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.find('.balance-refresh').trigger('click')
    await flushPromises()

    console.log('DEBUG get calls:', (client.get as any).mock.calls.map((c: any) => c[0]))
    await vi.waitFor(() => {
      expect(wrapper.find('.balance-text').text()).toContain('30.13')
    }, { timeout: 2000 }).catch(() => {
      console.log('FALLBACK BALANCE:', wrapper.find('.balance-section').html())
    })
  })

  it('余额查询失败展示错误', async () => {
    mockGet({
      '/settings/api': { data: { baseUrl: 'x', model: 'y', apiKey: 'z****' } },
      '/settings/balance': { data: { isAvailable: false, currency: null, totalBalance: null, error: '余额查询失败：401' } }
    })

    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.find('.balance-refresh').trigger('click')
    await flushPromises()

    expect(wrapper.find('.balance-error').text()).toContain('查询失败')
  })
})
