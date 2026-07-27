import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useSSE } from '../useSSE'

describe('useSSE', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('初始状态为 idle', () => {
    const { status, error } = useSSE()
    expect(status.value).toBe('idle')
    expect(error.value).toBeNull()
  })

  it('connect 设置状态为 connecting', () => {
    // 用永不 resolve 的 promise 保证状态停留在 connecting
    vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => {}) as Promise<Response>)

    const { status, connect } = useSSE()
    connect('/api/chat/send', { message: 'hi' }, {})

    expect(status.value).toBe('connecting')
  })

  it('连接成功接收 SSE 事件', async () => {
    const mockReader = {
      read: vi.fn()
        .mockResolvedValueOnce({ done: false, value: new TextEncoder().encode('event: status\ndata: 正在检索知识库...\n\n') })
        .mockResolvedValueOnce({ done: true, value: undefined })
    }

    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      body: { getReader: () => mockReader }
    } as unknown as Response)

    const { status, connect } = useSSE()

    const events: Array<{ event: string; data: string }> = []
    await connect('/api/test', {}, {
      onEvent(event, data) { events.push({ event, data }) }
    })

    expect(status.value).toBe('idle')
    expect(events).toHaveLength(1)
    expect(events[0]).toEqual({ event: 'status', data: '正在检索知识库...' })
  })

  it('HTTP 错误时设置 error 状态', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: false,
      status: 500,
      body: null
    } as Response)

    const { status, error, connect } = useSSE()
    let errMsg = ''

    await connect('/api/test', {}, {
      onError(err) { errMsg = err }
    })

    expect(status.value).toBe('error')
    expect(error.value).toBe('HTTP 500')
    expect(errMsg).toBe('HTTP 500')
  })

  it('网络错误时设置 error 状态', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('Network down'))

    const { status, error, connect } = useSSE()
    let errMsg = ''

    await connect('/api/test', {}, {
      onError(err) { errMsg = err }
    })

    expect(status.value).toBe('error')
    expect(error.value).toBe('Network down')
  })

  it('disconnect 中断连接并重置状态', () => {
    vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => {}) as Promise<Response>)

    const { status, connect, disconnect } = useSSE()
    connect('/api/test', {}, {})
    expect(status.value).toBe('connecting')

    disconnect()
    expect(status.value).toBe('idle')
  })

  it('AbortError 静默处理不视为错误', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new DOMException('Aborted', 'AbortError'))

    const { status, error, connect } = useSSE()

    await connect('/api/test', {}, {})

    expect(status.value).toBe('idle')
    expect(error.value).toBeNull()
  })

  it('发送请求时自动携带 Authorization header', async () => {
    localStorage.setItem('access_token', 'test-jwt-token')

    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: vi.fn().mockResolvedValue({ done: true, value: undefined })
        })
      }
    } as unknown as Response)

    const { connect } = useSSE()
    await connect('/api/test', { q: 'test' }, {})

    const callArgs = fetchSpy.mock.calls[0]
    const headers = (callArgs[1] as RequestInit).headers as Record<string, string>
    expect(headers['Authorization']).toBe('Bearer test-jwt-token')
    expect(headers['Content-Type']).toBe('application/json')
  })

  it('无 token 时不发送 Authorization header', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: vi.fn().mockResolvedValue({ done: true, value: undefined })
        })
      }
    } as unknown as Response)

    const { connect } = useSSE()
    await connect('/api/test', {}, {})

    const callArgs = fetchSpy.mock.calls[0]
    const headers = (callArgs[1] as RequestInit).headers as Record<string, string>
    expect(headers['Authorization']).toBeUndefined()
  })
})
