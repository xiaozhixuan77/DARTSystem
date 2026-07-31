import { getAccessToken } from './client'

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
      // 非 200 响应是 JSON 错误
      const errBody = await response.json().catch(() => null)
      callbacks.onError?.(response.status, errBody?.message || '请求失败')
      return
    }
    const reader = response.body?.getReader()
    if (!reader) return

    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = ''
    let dataBuffer = ''

    function flushEvent() {
      if (!dataBuffer) return
      const event = currentEvent || 'message'
      dispatch(event, dataBuffer)
      currentEvent = ''
      dataBuffer = ''
    }

    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            flushEvent()
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            // SSE spec: data 以单个空格开头时去掉空格，否则保留
            const raw = line.slice(5)
            const data = raw.startsWith(' ') ? raw.slice(1) : raw
            dataBuffer += (dataBuffer ? '\n' : '') + data
          } else if (line.trim() === '') {
            // SSE spec: 空行表示事件结束
            flushEvent()
          }
        }
      }
      flushEvent()
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

  function dispatch(event: string, payload: string) {
    try {
      const data = JSON.parse(payload)
      switch (event) {
        case 'status':
          callbacks.onStatus?.(data.phase || '', data.message || '')
          break
        case 'search_result':
          callbacks.onSearchResult?.(data)
          break
        case 'curated_report':
          // curated_report 由后端直接发送，作为 search_result 处理
          callbacks.onSearchResult?.(data)
          break
        case 'content':
          // content 事件：data 是裸字符串，不是 JSON
          callbacks.onContent?.(payload)
          break
        case 'done':
          callbacks.onDone?.(data)
          break
        case 'error':
          callbacks.onError?.(data.code || 500, data.message || '未知错误')
          break
      }
    } catch {
      // 非 JSON（如 content 中的纯文本），直接当内容处理
      if (event === 'content') {
        callbacks.onContent?.(payload)
      }
    }
  }

  return () => abortController.abort()
}
