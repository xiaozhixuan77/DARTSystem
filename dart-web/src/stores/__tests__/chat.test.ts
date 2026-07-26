import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useChatStore } from '../chat'

vi.mock('@/api/conversation', () => ({
  listConversations: vi.fn(),
  createConversation: vi.fn(),
  deleteConversation: vi.fn(),
  getMessages: vi.fn()
}))

vi.mock('@/api/chat', () => ({
  sendMessage: vi.fn()
}))

import { listConversations, createConversation, getMessages } from '@/api/conversation'

describe('useChatStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('初始状态下消息列表为空', () => {
    const store = useChatStore()
    expect(store.messages).toEqual([])
    expect(store.currentId).toBeNull()
    expect(store.isStreaming).toBe(false)
  })

  it('加载会话列表', async () => {
    vi.mocked(listConversations).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: [{ id: 1, title: '测试', tags: [], lastMessage: '', messageCount: 0, updatedAt: '' }]
    })

    const store = useChatStore()
    await store.loadConversations()

    expect(store.conversations).toHaveLength(1)
    expect(store.conversations[0].title).toBe('测试')
  })

  it('创建新会话', async () => {
    vi.mocked(createConversation).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { id: 42, title: '新对话', tags: [], lastMessage: '', messageCount: 0, updatedAt: '' }
    })

    const store = useChatStore()
    const id = await store.newConversation()

    expect(id).toBe(42)
    expect(store.currentId).toBe(42)
    expect(store.conversations).toHaveLength(1)
  })

  it('选择会话并加载消息', async () => {
    vi.mocked(getMessages).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: [{ id: 1, role: 'user', content: 'hi', metadata: null, createdAt: '' }]
    })

    const store = useChatStore()
    await store.selectConversation(1)

    expect(store.currentId).toBe(1)
    expect(store.messages).toHaveLength(1)
  })

  it('发送消息添加用户消息和助手占位', () => {
    const store = useChatStore()
    store.send('Hello')

    expect(store.messages).toHaveLength(2)
    expect(store.messages[0].role).toBe('user')
    expect(store.messages[0].content).toBe('Hello')
    expect(store.messages[1].role).toBe('assistant')
    expect(store.isStreaming).toBe(true)
  })

  it('流式中不重复发送', () => {
    const store = useChatStore()
    store.send('first')
    store.send('second')

    // 第二条消息应被忽略（isStreaming = true）
    expect(store.messages).toHaveLength(2)
  })

  it('取消流式停止状态', () => {
    const store = useChatStore()
    store.send('test')
    store.cancelStreaming()

    expect(store.isStreaming).toBe(false)
    expect(store.streamingStatus).toBe('')
  })
})
