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

vi.mock('@/router', () => ({
  default: { replace: vi.fn() }
}))

import { listConversations, createConversation, deleteConversation, getMessages } from '@/api/conversation'
import { sendMessage } from '@/api/chat'
import router from '@/router'

type Callbacks = {
  onStatus?: (status: string, message: string) => void
  onContent?: (chunk: string) => void
  onDone?: (data: { status: string; conversationId?: number }) => void
  onError?: (code: number, message: string) => void
}

function captureCallbacks(): Callbacks {
  const callbacks: Callbacks = {}
  vi.mocked(sendMessage).mockImplementation((_msg, _convId, cb) => {
    Object.assign(callbacks, cb)
    return () => {}
  })
  return callbacks
}

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

  // === SSE 断流兜底 ===

  it('conversation 状态事件设置当前会话 id', () => {
    const store = useChatStore()
    const cb = captureCallbacks()
    store.send('hello')
    cb.onStatus?.('conversation', '55')
    expect(store.currentId).toBe(55)
  })

  it('onError 时已有会话则拉取最新消息兜底', async () => {
    const store = useChatStore()
    const cb = captureCallbacks()
    store.send('hello')
    cb.onStatus?.('conversation', '55')
    vi.mocked(getMessages).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: [
        { id: 1, role: 'user', content: 'hello', metadata: null, createdAt: '' },
        { id: 2, role: 'assistant', content: '完整报告', metadata: null, createdAt: '' }
      ]
    })

    cb.onError?.(0, '连接中断')
    await vi.waitFor(() => {
      expect(getMessages).toHaveBeenCalledWith(55)
    })
    expect(store.messages).toHaveLength(2)
    expect(store.messages[1].content).toBe('完整报告')
  })

  it('onError 时无会话 id 则显示错误消息', () => {
    const store = useChatStore()
    const cb = captureCallbacks()
    store.send('hello')
    cb.onError?.(0, '连接中断')

    expect(store.isStreaming).toBe(false)
    const last = store.messages[store.messages.length - 1]
    expect(last.content).toContain('错误')
    expect(getMessages).not.toHaveBeenCalled()
  })

  // === URL 同步与会话恢复 ===

  it('conversation 状态事件后当前会话 id 同步到 URL', async () => {
    const store = useChatStore()
    const cb = captureCallbacks()
    store.send('hello')
    cb.onStatus?.('conversation', '55')

    await vi.waitFor(() => {
      expect(router.replace).toHaveBeenCalledWith('/chat/55')
    })
  })

  it('selectConversation 后当前会话 id 同步到 URL', async () => {
    vi.mocked(getMessages).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: [
        { id: 1, role: 'user', content: 'hi', metadata: null, createdAt: '' },
        { id: 2, role: 'assistant', content: 'ok', metadata: null, createdAt: '' }
      ]
    })

    const store = useChatStore()
    await store.selectConversation(1)

    await vi.waitFor(() => {
      expect(router.replace).toHaveBeenCalledWith('/chat/1')
    })
  })

  it('恢复会话最后一条是近期用户消息时轮询等待助手回答', async () => {
    vi.useFakeTimers()
    try {
      const now = new Date().toISOString()
      vi.mocked(getMessages)
        .mockResolvedValueOnce({
          code: 200, message: 'ok', suggestion: null,
          data: [{ id: 1, role: 'user', content: 'hi', metadata: null, createdAt: now }]
        })
        .mockResolvedValueOnce({
          code: 200, message: 'ok', suggestion: null,
          data: [
            { id: 1, role: 'user', content: 'hi', metadata: null, createdAt: now },
            { id: 2, role: 'assistant', content: '完整报告', metadata: null, createdAt: now }
          ]
        })

      const store = useChatStore()
      await store.selectConversation(1)
      expect(store.messages).toHaveLength(1)

      // 3 秒后轮询拉取到完整报告 → 停止轮询
      await vi.advanceTimersByTimeAsync(3000)
      expect(getMessages).toHaveBeenCalledTimes(2)
      expect(store.messages).toHaveLength(2)
      expect(store.messages[1].role).toBe('assistant')
      expect(store.messages[1].content).toBe('完整报告')

      // 已停止：后续不再轮询
      vi.mocked(getMessages).mockClear()
      await vi.advanceTimersByTimeAsync(10_000)
      expect(getMessages).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('轮询等待超时后提示回答生成中断', async () => {
    vi.useFakeTimers()
    try {
      const now = new Date().toISOString()
      vi.mocked(getMessages).mockResolvedValue({
        code: 200, message: 'ok', suggestion: null,
        data: [{ id: 1, role: 'user', content: 'hi', metadata: null, createdAt: now }]
      })

      const store = useChatStore()
      await store.selectConversation(1)

      await vi.advanceTimersByTimeAsync(180_000)
      expect(store.error).toContain('中断')
    } finally {
      vi.useRealTimers()
    }
  })

  // === 删除会话 ===

  function presetConversations(store: ReturnType<typeof useChatStore>) {
    store.conversations.push(
      { id: 1, title: 'A', tags: [], lastMessage: '', messageCount: 0, updatedAt: '' },
      { id: 2, title: 'B', tags: [], lastMessage: '', messageCount: 0, updatedAt: '' }
    )
  }

  it('删除当前会话时调用 API、移除列表并清空状态', async () => {
    vi.mocked(deleteConversation).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null, data: undefined
    })
    const store = useChatStore()
    presetConversations(store)
    store.currentId = 1
    store.messages.push({ id: 1, role: 'user', content: 'x', metadata: null, createdAt: '' })

    const ok = await store.removeConversation(1)

    expect(ok).toBe(true)
    expect(deleteConversation).toHaveBeenCalledWith(1)
    expect(store.conversations).toHaveLength(1)
    expect(store.conversations[0].id).toBe(2)
    expect(store.currentId).toBeNull()
    expect(store.messages).toHaveLength(0)
  })

  it('删除非当前会话不影响当前状态', async () => {
    vi.mocked(deleteConversation).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null, data: undefined
    })
    const store = useChatStore()
    presetConversations(store)
    store.currentId = 2
    store.messages.push({ id: 1, role: 'user', content: 'x', metadata: null, createdAt: '' })

    const ok = await store.removeConversation(1)

    expect(ok).toBe(true)
    expect(store.conversations).toHaveLength(1)
    expect(store.currentId).toBe(2)
    expect(store.messages).toHaveLength(1)
  })

  it('API 失败时返回 false 且本地状态不变', async () => {
    vi.mocked(deleteConversation).mockRejectedValue(new Error('network'))
    const store = useChatStore()
    presetConversations(store)
    store.currentId = 1

    const ok = await store.removeConversation(1)

    expect(ok).toBe(false)
    expect(store.conversations).toHaveLength(2)
    expect(store.currentId).toBe(1)
  })
})
