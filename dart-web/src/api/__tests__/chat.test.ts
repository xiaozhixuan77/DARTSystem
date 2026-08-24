import { resumeMessage, sendMessage } from '@/api/chat'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * api/chat.ts SSE 流解析测试：
 * Spring SseEmitter 输出 "data:" + 内容（无分隔空格），
 * 流式推送的纯空格/换行 delta 必须原样保留，否则 markdown 格式被破坏。
 */

function sseStream(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  let i = 0
  return new ReadableStream({
    pull(controller) {
      if (i < chunks.length) {
        controller.enqueue(encoder.encode(chunks[i++]))
      } else {
        controller.close()
      }
    }
  })
}

describe('sendMessage SSE 解析', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    vi.stubGlobal('localStorage', { getItem: () => 'test-token' } as any)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('content 事件保留空格与换行 delta（markdown 格式完整）', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      body: sseStream([
        'event:content\ndata:## \ndata:\ndata:📖 核心概念\n\n',
        'event:content\ndata:### \ndata:1. 概念名\n\n',
        'event:done\ndata:{"status":"complete","conversationId":1}\n\n'
      ])
    } as any)

    const contents: string[] = []
    await new Promise<void>((resolve) => {
      sendMessage('test', null, {
        onContent: (chunk) => contents.push(chunk),
        onDone: () => resolve()
      })
    })

    // 纯空格 delta（"## " 尾空格、独立 " "）原样保留；空 data 行还原为换行
    expect(contents[0]).toBe('## \n\n📖 核心概念')
    // 内容内换行以 \n 连接，标题格式完整
    expect(contents[1]).toBe('### \n1. 概念名')
  })

  it('纯换行 delta（空 data 行事件）也推送给前端', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      body: sseStream([
        'event:content\ndata:### 1. 标题\n\n',
        // 纯换行 delta：Spring 输出 "data:" 空行 + 事件结束空行
        'event:content\ndata:\n\n',
        'event:content\ndata:- 列表项\n\n',
        'event:done\ndata:{"status":"complete"}\n\n'
      ])
    } as any)

    const contents: string[] = []
    await new Promise<void>((resolve) => {
      sendMessage('test', null, {
        onContent: (chunk) => contents.push(chunk),
        onDone: () => resolve()
      })
    })

    // 纯换行事件不被丢弃，列表项前的换行保留
    expect(contents).toEqual(['### 1. 标题', '\n', '- 列表项'])
  })

  it('多个 data 行（内容内换行）以换行连接', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      body: sseStream([
        'event:content\ndata:行一\ndata:行二\n\n',
        'event:done\ndata:{"status":"complete"}\n\n'
      ])
    } as any)

    const contents: string[] = []
    await new Promise<void>((resolve) => {
      sendMessage('test', null, {
        onContent: (chunk) => contents.push(chunk),
        onDone: () => resolve()
      })
    })

    expect(contents[0]).toBe('行一\n行二')
  })
})

describe('resumeMessage 恢复续流解析', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    vi.stubGlobal('localStorage', { getItem: () => 'test-token' } as any)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('请求 /api/chat/resume 并携带会话 id', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      body: sseStream(['event:done\ndata:{"status":"completed"}\n\n'])
    } as any)

    await new Promise<void>((resolve) => {
      resumeMessage(55, { onDone: () => resolve() })
    })

    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(url).toBe('/api/chat/resume')
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ conversationId: 55 })
    expect((init as RequestInit).headers).toHaveProperty('Authorization', 'Bearer test-token')
  })

  it('快照 + 实时增量 content 事件路由到 onContent', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      body: sseStream([
        // 快照（已推送内容合并为一次 content）
        'event:content\ndata:## 部分内容\n\n',
        // 实时增量逐 token
        'event:content\ndata:增量1\n\n',
        'event:content\ndata:增量2\n\n',
        'event:done\ndata:{"status":"complete"}\n\n'
      ])
    } as any)

    const contents: string[] = []
    await new Promise<void>((resolve) => {
      resumeMessage(55, {
        onContent: (chunk) => contents.push(chunk),
        onDone: () => resolve()
      })
    })

    expect(contents).toEqual(['## 部分内容', '增量1', '增量2'])
  })

  it('resume 失败（非 200）路由到 onError', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ message: '无权限' })
    } as any)

    const errors: Array<[number, string]> = []
    await new Promise<void>((resolve) => {
      resumeMessage(55, {
        onError: (code, msg) => { errors.push([code, msg]); resolve() }
      })
    })

    expect(errors).toEqual([[403, '无权限']])
  })
})
