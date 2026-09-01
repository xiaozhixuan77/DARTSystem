import { getPoolUserId } from './client'

type SSECallback = {
  onStatus?: (status: string, message: string) => void
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
  return sseRequest('/api/chat/send', { message, conversationId }, callbacks, '请求失败')
}

/**
 * 恢复续流：刷新后重新订阅正在生成的会话，从已生成位置继续接收 SSE 增量。
 * 后端返回：快照重放 + 实时增量（生成中），或 done(completed)（已结束/无广播器）。
 * 返回 abort 函数。
 */
export function resumeMessage(
  conversationId: number,
  callbacks: SSECallback
): () => void {
  return sseRequest('/api/chat/resume', { conversationId }, callbacks, '恢复连接失败')
}

/**
 * 公共 SSE 请求：fetch POST + ReadableStream 解析，返回 abort 函数。
 * sendMessage 与 resumeMessage 共用（URL/body/错误文案不同）。
 */
function sseRequest(
  path: string,
  body: Record<string, unknown>,
  callbacks: SSECallback,
  errorFallback: string
): () => void {
  const abortController = new AbortController()
  const userId = getPoolUserId()

  fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': String(userId)
    },
    body: JSON.stringify(body),
    signal: abortController.signal
  }).then(async (response) => {
    if (!response.ok) {
      // 非 200 响应是 JSON 错误
      const errBody = await response.json().catch(() => null)
      callbacks.onError?.(response.status, errBody?.message || errorFallback)
      return
    }
    await parseSSEStream(response, callbacks)
  }).catch((err) => {
    if ((err as Error).name !== 'AbortError') {
      callbacks.onError?.(0, '网络错误')
    }
  })

  return () => abortController.abort()
}

/**
 * 解析 SSE 响应流（fetch + ReadableStream）：逐行拆分事件，路由到 callbacks。
 * 流正常结束但未收到 done → 连接被服务器提前关闭（报告可能已生成）。
 */
async function parseSSEStream(
  response: Response,
  callbacks: SSECallback
): Promise<void> {
  let receivedDone = false
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

  function dispatch(event: string, payload: string) {
    try {
      const data = JSON.parse(payload)
      switch (event) {
        case 'status':
          callbacks.onStatus?.(data.phase || '', data.message || '')
          break
        case 'content':
          // content 事件：data 是裸字符串，不是 JSON
          callbacks.onContent?.(payload)
          break
        case 'done':
          receivedDone = true
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
          // Spring SseEmitter 输出 "data:" + 内容（无分隔空格），不能剥除前导空格，
          // 否则流式推送的纯空格 delta（markdown 标题/列表的空格）会被吞掉。
          // 空 data 行（"data:" 无内容）是内容换行的一部分：即使事件内只有空
          // data 行（纯换行 delta），也必须还原为换行，否则前端缺换行
          if (line === 'data:') {
            dataBuffer += '\n'
          } else {
            dataBuffer += (dataBuffer ? '\n' : '') + line.slice(5)
          }
        } else if (line.trim() === '') {
          // SSE spec: 空行表示事件结束
          flushEvent()
        }
      }
    }
    flushEvent()
    // 流正常结束但未收到 done → 连接被服务器提前关闭，报告可能已生成
    if (!receivedDone) {
      callbacks.onError?.(0, '连接中断')
    }
  } catch (err) {
    if ((err as Error).name !== 'AbortError') {
      callbacks.onError?.(0, '连接中断')
    }
  }
}
