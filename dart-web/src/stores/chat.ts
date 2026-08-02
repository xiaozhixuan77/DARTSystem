import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { listConversations, createConversation, deleteConversation, getMessages } from '@/api/conversation'
import { sendMessage } from '@/api/chat'
import type { ConversationVO, MessageVO } from '@/types/chat'

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<ConversationVO[]>([])
  const currentId = ref<number | null>(null)
  const messages = ref<MessageVO[]>([])
  const isStreaming = ref(false)
  const streamingContent = ref('')
  const streamingStatus = ref('')
  const error = ref<string | null>(null)
  let abortFn: (() => void) | null = null

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
    isStreaming, streamingContent, streamingStatus, error,
    loadConversations, selectConversation, newConversation,
    removeConversation, send, cancelStreaming
  }
})
