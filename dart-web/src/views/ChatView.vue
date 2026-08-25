<script setup lang="ts">
import { onMounted, ref, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import AppSidebar from '@/components/sidebar/AppSidebar.vue'
import ChatMessage from '@/components/chat/ChatMessage.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import ChatNavigator from '@/components/chat/ChatNavigator.vue'
import WelcomeScreen from '@/components/chat/WelcomeScreen.vue'
import StreamingStatus from '@/components/chat/StreamingStatus.vue'

const route = useRoute()
const chatStore = useChatStore()
const authStore = useAuthStore()

const messageListRef = ref<HTMLElement | null>(null)
let userScrolledUp = false
// 消息锚点元素（按消息数组索引收集，定位不用 msg.id——流式/重载下索引稳定）
const messageEls: (HTMLElement | null)[] = []
const activeNavIndex = ref(-1)

function collectAnchor(index: number, el: unknown) {
  messageEls[index] = el as HTMLElement | null
}

function scrollToBottom() {
  const el = messageListRef.value
  if (!el || userScrolledUp) return
  el.scrollTop = el.scrollHeight
}

/** 当前视口顶部对应的消息索引（高亮导航项用）；-1 表示无 */
function computeActiveIndex(): number {
  const list = messageListRef.value
  if (!list) return -1
  const threshold = list.scrollTop + 100
  let active = -1
  for (let i = 0; i < messageEls.length; i++) {
    const el = messageEls[i]
    if (el && el.offsetTop <= threshold) active = i
  }
  return active
}

function onScroll() {
  const el = messageListRef.value
  if (!el) return
  const threshold = 60
  userScrolledUp = el.scrollTop + el.clientHeight < el.scrollHeight - threshold
  activeNavIndex.value = computeActiveIndex()
}

function scrollToMessage(index: number) {
  const el = messageEls[index]
  if (!el) return
  // 置位后流式 streamingContent watch 不会把用户拉回底部
  userScrolledUp = true
  el.scrollIntoView({ behavior: 'smooth' })
}

// 流式内容变化时滚动（flush: 'post' 确保 DOM 更新后执行）
watch(() => chatStore.streamingContent, () => scrollToBottom(), { flush: 'post' })

// 新消息/重载后滚底：本地发送已由 handleSend 复位；外部重载（onDone 重载、轮询追问追加）
// 保持 userScrolledUp 守卫——用户在看旧内容时不被拉回
watch(() => chatStore.messages.length, () => {
  nextTick(scrollToBottom)
})

// 消息数组整体替换（onDone 重载/会话切换）后 scrollTop 复位不触发 scroll 事件，主动重算高亮
watch(() => chatStore.messages, async () => {
  await nextTick()
  activeNavIndex.value = computeActiveIndex()
})

// 切换会话时滚动到底部
watch(() => chatStore.currentId, () => {
  userScrolledUp = false
  nextTick(scrollToBottom)
})

onMounted(() => {
  authStore.initAuth()
  chatStore.loadConversations()
  const id = route.params.id
  if (id) {
    chatStore.selectConversation(Number(id))
  }
})

function handleSend(message: string) {
  userScrolledUp = false
  chatStore.send(message)
}
</script>

<template>
  <div class="chat-layout">
    <AppSidebar />

    <main class="chat-area">
      <div class="chat-header">
        <h2 class="chat-title" v-if="chatStore.currentConversation">
          {{ chatStore.currentConversation.title }}
        </h2>
        <h2 class="chat-title" v-else>新对话</h2>
      </div>

      <StreamingStatus />

      <div ref="messageListRef" class="message-list" v-if="chatStore.messages.length > 0" @scroll="onScroll">
        <div
          v-for="(msg, i) in chatStore.messages" :key="msg.id"
          class="message-anchor"
          :ref="(el) => collectAnchor(i, el)"
        >
          <ChatMessage :message="msg" />
        </div>
      </div>

      <WelcomeScreen v-if="chatStore.messages.length === 0 && !chatStore.isStreaming" />

      <ChatInput @send="handleSend" :disabled="chatStore.isStreaming" />

      <ChatNavigator
        :messages="chatStore.messages"
        :active-index="activeNavIndex"
        @navigate="scrollToMessage"
      />
    </main>
  </div>
</template>

<style lang="scss" scoped>
.chat-layout {
  display: flex;
  height: 100vh;
  background: $color-bg;
  overflow: hidden;
}
.chat-area {
  position: relative; // ChatNavigator 绝对定位参照
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}
.chat-header {
  padding: 14px $gap-lg;
  border-bottom: 1px solid $color-border;
  background: $color-surface;
}
.chat-title {
  font-family: $font-display;
  font-size: 18px;
  font-weight: 600;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.message-list {
  position: relative; // 成为锚点 offsetParent，offsetTop 与 scrollTop 同坐标系
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: $gap-lg 0;
}
.message-anchor {
  scroll-margin-top: 72px; // 避 chat-header + StreamingStatus
}
</style>
