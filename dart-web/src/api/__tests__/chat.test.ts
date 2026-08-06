import { sendMessage } from '@/api/chat'
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
