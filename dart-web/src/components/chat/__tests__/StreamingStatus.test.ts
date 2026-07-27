import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '@/stores/chat'
import StreamingStatus from '../StreamingStatus.vue'

vi.mock('@/api/conversation', () => ({
  listConversations: vi.fn(),
  createConversation: vi.fn(),
  deleteConversation: vi.fn(),
  getMessages: vi.fn()
}))
vi.mock('@/api/chat', () => ({ sendMessage: vi.fn() }))

describe('StreamingStatus.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('流式状态未激活时不渲染', () => {
    const wrapper = mount(StreamingStatus)
    expect(wrapper.find('.streaming-bar').exists()).toBe(false)
  })

  it('流式状态激活时渲染状态栏', () => {
    const store = useChatStore()
    store.isStreaming = true
    store.streamingStatus = '正在搜索...'

    const wrapper = mount(StreamingStatus)
    expect(wrapper.find('.streaming-bar').exists()).toBe(true)
    expect(wrapper.find('.streaming-text').text()).toBe('正在搜索...')
  })

  it('无 streamingStatus 时显示默认文字', () => {
    const store = useChatStore()
    store.isStreaming = true
    store.streamingStatus = ''

    const wrapper = mount(StreamingStatus)
    expect(wrapper.find('.streaming-text').text()).toBe('处理中...')
  })

  it('点击取消按钮调用 cancelStreaming', async () => {
    const store = useChatStore()
    store.isStreaming = true
    const cancelSpy = vi.spyOn(store, 'cancelStreaming')

    const wrapper = mount(StreamingStatus)
    await wrapper.find('.cancel-btn').trigger('click')

    expect(cancelSpy).toHaveBeenCalled()
  })
})
