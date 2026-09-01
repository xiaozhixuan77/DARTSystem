import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { listConversations, createConversation, deleteConversation, getMessages } from '@/api/conversation'
import { resumeMessage, sendMessage } from '@/api/chat'
import router from '@/router'
import type { ConversationVO, MessageVO } from '@/types/chat'

// 刷新恢复场景：SSE 断连后后端仍在后台生成完整报告，助手消息在落库后才可见。
// 恢复会话时若最后一条是近期用户消息（回答未生成完），轮询等待助手消息出现
const RESUME_POLL_INTERVAL_MS = 3000
// 后端完整生成（搜索 + 三节流式 + 追问）实测最长 6.5 分钟（LLM 响应波动），
// 超时须覆盖最长生成时间；超时后仅停止自动等待，文案不宣称"失败"——后端可能仍在生成
const RESUME_POLL_TIMEOUT_MS = 600_000
// 仅"近期"用户消息触发轮询：历史会话最后一条 user 无后续是常态（极少），避免误轮询
const RESUME_POLL_WINDOW_MS = 5 * 60 * 1000

// SSE 阶段 → 展示文案（send 与 resume 续流共用同一映射）
const PHASE_TEXT: Record<string, string> = {
  rag_check: '正在检索知识库...',
  searching: '正在搜索...',
  curating: '正在整理结果...'
}

function mapStatusText(status: string, msg: string): string {
  return PHASE_TEXT[status] ?? msg
}

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<ConversationVO[]>([])
  const currentId = ref<number | null>(null)
  const messages = ref<MessageVO[]>([])
  const isStreaming = ref(false)
  const streamingContent = ref('')
  const streamingStatus = ref('')
  const waitingResume = ref(false)
  let abortFn: (() => void) | null = null
  let resumeAbort: (() => void) | null = null
  let pollTimer: ReturnType<typeof setInterval> | null = null
  let pollTimeout: ReturnType<typeof setTimeout> | null = null

  // 会话 id 同步 URL：刷新后 ChatView 依据 route.params.id 自动恢复会话
  watch(currentId, (id) => {
    if (id) router.replace(`/chat/${id}`)
  })

  const currentConversation = computed(() =>
    conversations.value.find((c) => c.id === currentId.value) ?? null
  )

  async function loadConversations() {
    try {
      const res = await listConversations()
      if (res.code === 200) conversations.value = res.data
    } catch { /* ignore */ }
  }

  async function selectConversation(id: number) {
    currentId.value = id
    messages.value = []
    try {
      const res = await getMessages(id)
      if (res.code === 200) messages.value = res.data
    } catch { /* ignore */ }
    startResumeFlow()
  }

  /**
   * 恢复续流：生成中（最后一条是草稿助手消息或近期用户消息）时**先尝试 resume SSE**——
   * 后端会话广播器从已生成位置继续逐 token 推送（真流式，无 5s 粒度）。
   * resume 不可用（连接失败/已结束）时降级轮询兜底（草稿快照拉取）。
   */
  function startResumeFlow() {
    stopPolling()
    const last = messages.value[messages.value.length - 1]
    if (!currentId.value || !isResumeCandidate(last)) return
    const id = currentId.value
    resumeAbort?.()
    resumeAbort = null
    waitingResume.value = true
    // 锁定输入：resume 期间禁止并发发送（onContent 依赖"最后一条助手消息"更新）
    isStreaming.value = true
    streamingContent.value = ''
    streamingStatus.value = ''
    resumeAbort = resumeMessage(id, {
      onStatus(status, msg) {
        streamingStatus.value = mapStatusText(status, msg)
      },
      onContent(chunk) {
        appendStreamingContent(chunk)
      },
      onDone() {
        resumeAbort = null
        isStreaming.value = false
        streamingStatus.value = ''
        waitingResume.value = false
        // 完成后重载消息（快照/增量由 resume 推送，重载取最终完整内容）
        selectConversation(id)
        loadConversations()
      },
      onError() {
        resumeAbort = null
        isStreaming.value = false
        streamingStatus.value = ''
        // resume 不可用 → 降级轮询兜底（草稿每 5s 快照，前端 3s 拉取增量）
        startPollingForResume()
      }
    })
  }

  /**
   * 恢复会话后检测"回答仍在后台生成"：最后一条是草稿助手消息（后端草稿增量持久化）
   * 或近期用户消息（无草稿场景兜底）时，每 3s 轮询拉取消息——草稿内容增量增长显示，
   * 直到完整报告出现且内容连续两次稳定（覆盖追问追加），近似流式无感续流。
   */
  function startPollingForResume() {
    stopPolling()
    const last = messages.value[messages.value.length - 1]
    if (!currentId.value || !isResumeCandidate(last)) return
    waitingResume.value = true
    let lastStableContent = ''
    pollTimer = setInterval(async () => {
      const id = currentId.value
      if (id == null) { stopPolling(); return }
      await reloadMessages(id)
      const tail = messages.value[messages.value.length - 1]
      if (tail && tail.role === 'assistant' && !isDraftMessage(tail)) {
        // 完整报告出现；追问在 afterCommit 后 updateById 追加（SSE 已断收不到），
        // 内容连续两次相同才停止，可捕获追问增量
        if (tail.content === lastStableContent) {
          stopPolling()
        } else {
          lastStableContent = tail.content
        }
      } else {
        lastStableContent = ''
      }
    }, RESUME_POLL_INTERVAL_MS)
    pollTimeout = setTimeout(() => {
      if (waitingResume.value) {
        stopPolling()
      }
    }, RESUME_POLL_TIMEOUT_MS)
  }

  /** 草稿消息判定：后端 metadata 为 JSON 字符串（{"draft":true}），完整消息无 draft 标记 */
  function isDraftMessage(msg: MessageVO): boolean {
    const m = msg.metadata
    if (!m) return false
    try {
      const parsed = typeof m === 'string' ? JSON.parse(m) : m
      return parsed?.draft === true
    } catch {
      return false
    }
  }

  /** 恢复/轮询前置守卫：最后一条是草稿助手消息或近期用户消息（回答生成中） */
  function isResumeCandidate(last: MessageVO | undefined): boolean {
    if (!last) return false
    if (last.role === 'assistant' && !isDraftMessage(last)) return false
    if (last.role === 'user') {
      const createdAt = Date.parse(last.createdAt)
      if (Number.isNaN(createdAt) || Date.now() - createdAt > RESUME_POLL_WINDOW_MS) return false
    }
    return true
  }

  /** 流式增量累积：更新 streamingContent 与最后一条助手消息（send 与 resume 共用） */
  function appendStreamingContent(chunk: string) {
    streamingContent.value += chunk
    const lastMsg = messages.value[messages.value.length - 1]
    if (lastMsg && lastMsg.role === 'assistant') {
      lastMsg.content = streamingContent.value
    }
  }

  function stopPolling() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
    if (pollTimeout) { clearTimeout(pollTimeout); pollTimeout = null }
    waitingResume.value = false
  }

  /**
   * 不清空现有消息，直接覆盖为服务端最新消息。
   * SSE 中断兜底用：报告可能已生成入库，重新拉取即可显示。
   * 返回是否变化——轮询中生成停滞（内容未变）时不整体替换数组，避免无效重渲染。
   */
  async function reloadMessages(id: number): Promise<boolean> {
    try {
      const res = await getMessages(id)
      if (res.code === 200) {
        const oldTail = messages.value[messages.value.length - 1]
        const newTail = res.data[res.data.length - 1]
        if (oldTail && newTail && oldTail.content === newTail.content && oldTail.role === newTail.role) {
          return false
        }
        messages.value = res.data
        return true
      }
    } catch { /* 保留现有消息 */ }
    return false
  }

  async function newConversation(): Promise<number> {
    try {
      const res = await createConversation()
      if (res.code === 200 && res.data.id) {
        conversations.value.unshift(res.data)
        currentId.value = res.data.id
        messages.value = []
        return res.data.id
      }
    } catch { /* ignore */ }
    return 0
  }

  async function removeConversation(id: number): Promise<boolean> {
    try {
      await deleteConversation(id)
      conversations.value = conversations.value.filter((c) => c.id !== id)
      if (currentId.value === id) {
        currentId.value = null
        messages.value = []
        resumeAbort?.()
        resumeAbort = null
        stopPolling()
      }
      return true
    } catch { /* ignore */ }
    return false
  }

  function send(message: string) {
    if (!message.trim() || isStreaming.value) return
    const convId = currentId.value

    // 添加用户消息到本地
    const userMsg: MessageVO = {
      id: Date.now(),
      role: 'user',
      content: message,
      createdAt: new Date().toISOString()
    }
    messages.value.push(userMsg)

    // 添加占位助手消息
    const assistantMsg: MessageVO = {
      id: Date.now() + 1,
      role: 'assistant',
      content: '',
      createdAt: new Date().toISOString()
    }
    messages.value.push(assistantMsg)

    isStreaming.value = true
    streamingContent.value = ''

    abortFn = sendMessage(message, convId, {
      onStatus(status, msg) {
        if (status === 'conversation') {
          // 后端提前告知会话 id，SSE 中断时可据此拉取已生成的消息
          currentId.value = Number(msg)
          return
        }
        streamingStatus.value = mapStatusText(status, msg)
      },
      onContent(chunk) {
        appendStreamingContent(chunk)
      },
      onDone(data) {
        isStreaming.value = false
        streamingStatus.value = ''
        const convId = data.conversationId || currentId.value
        if (convId) {
          // 从服务端重新加载消息，获取 metadata（引导芯片等）
          selectConversation(convId)
        }
        loadConversations()
      },
      onError(code, msg) {
        isStreaming.value = false
        streamingStatus.value = ''
        const last = messages.value[messages.value.length - 1]
        if (last && last.role === 'assistant' && !last.content) {
          // SSE 中断但报告可能已生成入库 → 重新拉取兜底
          if (currentId.value) {
            reloadMessages(currentId.value)
          } else {
            last.content = '错误：' + msg
          }
        }
      }
    })
  }

  function cancelStreaming() {
    abortFn?.()
    isStreaming.value = false
    streamingStatus.value = ''
  }

  return {
    conversations, currentId, currentConversation, messages,
    isStreaming, streamingContent, streamingStatus, waitingResume,
    loadConversations, selectConversation, newConversation,
    removeConversation, send, cancelStreaming, isDraftMessage
  }
})
