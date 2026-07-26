import { getAccessToken } from './client'
import type { SSEEvent } from '@/types/chat'

type SSECallback = {
  onStatus?: (status: string, message: string) => void
  onSearchResult?: (result: unknown) => void
  onContent?: (chunk: string) => void
  onDone?: (data: { status: string; conversationId?: number }) => void
  onError?: (code: number, message: string) => void
}

/**
 * 通过 fetch + ReadableStream 发送聊天消息，解析 SSE 事件流。
 * 返回 abort 函数用于取消请求。
 */
export function sendMessage(
  message: string,
  conversationId: number | null,
  callbacks: SSECallback
): () => void {
  const abortController = new AbortController()
  const token = getAccessToken()

  fetch('/api/chat/send', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: token ? `Bearer ${token}` : ''
    },
    body: JSON.stringify({ message, conversationId }),
    signal: abortController.signal
  }).then(async (response) => {
    if (!response.ok) {
      callbacks.onError?.(response.status, '请求失败')
      return
    }
    const reader = response.body?.getReader()
    if (!reader) return

    const decoder = new TextDecoder()
    let buffer = ''

    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            buffer = line + '\n' + buffer
            continue
          }
          if (line.startsWith('data:')) {
            const jsonStr = line.slice(5).trim()
            if (!jsonStr) continue
            try {
              const event: SSEEvent = JSON.parse(jsonStr)
              switch (event.event) {
                case 'status':
                  callbacks.onStatus?.(event.status || '', event.message || '')
                  break
                case 'search_result':
                  callbacks.onSearchResult?.(event.data)
                  break
                case 'content':
                  callbacks.onContent?.(event.data as string)
                  break
                case 'done':
                  callbacks.onDone?.(event.data as { status: string; conversationId?: number })
                  break
                case 'error':
                  callbacks.onError?.(event.code || 500, event.message || '未知错误')
                  break
              }
            } catch {
              // 非 JSON 数据行（如 [DONE] 等），忽略
            }
          }
        }
      }
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        callbacks.onError?.(0, '连接中断')
      }
    }
  }).catch((err) => {
    if ((err as Error).name !== 'AbortError') {
      callbacks.onError?.(0, '网络错误')
    }
  })

  return () => abortController.abort()
}
