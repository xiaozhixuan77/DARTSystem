import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { listConversations, createConversation, deleteConversation, getMessages } from '@/api/conversation'
import { sendMessage } from '@/api/chat'
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

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<ConversationVO[]>([])
  const currentId = ref<number | null>(null)
  const messages = ref<MessageVO[]>([])
  const isStreaming = ref(false)
  const streamingContent = ref('')
  const streamingStatus = ref('')
  const error = ref<string | null>(null)
  const waitingResume = ref(false)
  let abortFn: (() => void) | null = null
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
    error.value = null
    try {
      const res = await getMessages(id)
      if (res.code === 200) messages.value = res.data
    } catch { /* ignore */ }
    startPollingForResume()
  }

  /**
   * 恢复会话后检测"回答仍在后台生成"：最后一条是近期用户消息且无助手回复时，
   * 每 3s 轮询拉取消息，直到助手消息出现（后端断连后仍会生成完并落库）。
   */
  function startPollingForResume() {
    stopPolling()
    const last = messages.value[messages.value.length - 1]
    if (!currentId.value || !last || last.role !== 'user') return
    const createdAt = Date.parse(last.createdAt)
    if (Number.isNaN(createdAt) || Date.now() - createdAt > RESUME_POLL_WINDOW_MS) return
    waitingResume.value = true
    pollTimer = setInterval(async () => {
      const id = currentId.value
      if (id == null) { stopPolling(); return }
      await reloadMessages(id)
      const tail = messages.value[messages.value.length - 1]
      if (tail && tail.role === 'assistant') stopPolling()
    }, RESUME_POLL_INTERVAL_MS)
    pollTimeout = setTimeout(() => {
      if (waitingResume.value) {
        stopPolling()
        error.value = '上次回答仍在生成中，请稍后刷新查看'
      }
    }, RESUME_POLL_TIMEOUT_MS)
  }

  function stopPolling() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
    if (pollTimeout) { clearTimeout(pollTimeout); pollTimeout = null }
    waitingResume.value = false
  }

  /**
   * 不清空现有消息，直接覆盖为服务端最新消息。
   * SSE 中断兜底用：报告可能已生成入库，重新拉取即可显示。
   */
  async function reloadMessages(id: number) {
    try {
      const res = await getMessages(id)
      if (res.code === 200) messages.value = res.data
    } catch { /* 保留现有消息 */ }
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
    error.value = null

    abortFn = sendMessage(message, convId, {
      onStatus(status, msg) {
        if (status === 'conversation') {
          // 后端提前告知会话 id，SSE 中断时可据此拉取已生成的消息
          currentId.value = Number(msg)
          return
        }
        streamingStatus.value = status === 'rag_check' ? '正在检索知识库...'
          : status === 'searching' ? '正在搜索...'
          : status === 'curating' ? '正在整理结果...'
          : msg
      },
      onContent(chunk) {
        streamingContent.value += chunk
        // 实时更新最后一条助手消息
        const last = messages.value[messages.value.length - 1]
        if (last && last.role === 'assistant') {
          last.content = streamingContent.value
        }
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
        error.value = msg
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
    isStreaming, streamingContent, streamingStatus, error, waitingResume,
    loadConversations, selectConversation, newConversation,
    removeConversation, send, cancelStreaming
  }
})
