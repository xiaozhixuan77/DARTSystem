<script setup lang="ts">
import { onMounted, ref, computed, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { useScrollToBottom } from '@/composables/useScrollToBottom'
import AppSidebar from '@/components/sidebar/AppSidebar.vue'
import ChatMessage from '@/components/chat/ChatMessage.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import WelcomeScreen from '@/components/chat/WelcomeScreen.vue'
import StreamingStatus from '@/components/chat/StreamingStatus.vue'
import KnowledgePanel from '@/components/knowledge/KnowledgePanel.vue'

const route = useRoute()
const chatStore = useChatStore()
const authStore = useAuthStore()

const messageListRef = ref<HTMLElement | null>(null)
const scrollTrigger = computed(() =>
  chatStore.messages.length * 1000 + chatStore.streamingContent.length
)
const { onScroll } = useScrollToBottom(messageListRef, scrollTrigger)

// 切换会话时立即滚动到底部
watch(() => chatStore.currentId, () => {
  nextTick(() => {
    if (messageListRef.value) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    }
  })
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
        <ChatMessage
          v-for="msg in chatStore.messages" :key="msg.id"
          :message="msg"
        />
      </div>

      <WelcomeScreen v-if="chatStore.messages.length === 0 && !chatStore.isStreaming" />

      <ChatInput @send="handleSend" :disabled="chatStore.isStreaming" />
    </main>

    <KnowledgePanel />
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
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
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
  flex: 1;
  overflow-y: auto;
  padding: $gap-lg 0;
}
</style>
