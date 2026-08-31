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
    ;(client.get as any).mockResolvedValue({ data: { code: 200, data: null } })
  })

  function mountDialog() {
    return mount(SettingsDialog, { props: { visible: true } })
  }

  it('渲染三个区块：数据池/API 配置/余额', () => {
    const wrapper = mountDialog()
    expect(wrapper.find('.pool-section').exists()).toBe(true)
    expect(wrapper.find('.api-section').exists()).toBe(true)
    expect(wrapper.find('.balance-section').exists()).toBe(true)
  })

  it('打开时加载 API 配置并展示脱敏 key', async () => {
    ;(client.get as any).mockResolvedValue({
      data: {
        code: 200,
        data: { baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-flash', apiKey: 'sk-ab****2345' }
      }
    })

    const wrapper = mountDialog()
    await flushPromises()

    expect((wrapper.find('.api-url input').element as HTMLInputElement).value)
      .toBe('https://api.deepseek.com')
    expect((wrapper.find('.api-model input').element as HTMLInputElement).value)
      .toBe('deepseek-v4-flash')
    expect((wrapper.find('.api-key input').element as HTMLInputElement).value)
      .toBe('sk-ab****2345')
  })

  it('保存 API 配置调用 PUT', async () => {
    ;(client.get as any).mockResolvedValue({
      data: { code: 200, data: { baseUrl: 'x', model: 'y', apiKey: 'z****' } }
    })
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

    expect(wrapper.find('.balance-text').text()).toContain('30.13')
  })

  it('余额查询失败展示错误', async () => {
    ;(client.get as any).mockResolvedValue({
      data: { code: 200, data: { isAvailable: false, currency: null, totalBalance: null, error: '余额查询失败：401' } }
    })

    const wrapper = mountDialog()
    await flushPromises()
    await wrapper.find('.balance-refresh').trigger('click')
    await flushPromises()

    expect(wrapper.find('.balance-error').text()).toContain('查询失败')
  })
})
