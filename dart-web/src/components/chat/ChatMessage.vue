<script setup lang="ts">
import { computed } from 'vue'
import { useMarkdown } from '@/composables/useMarkdown'
import { useChatStore } from '@/stores/chat'
import type { MessageVO } from '@/types/chat'

const props = defineProps<{ message: MessageVO }>()
const { render } = useMarkdown()
const chatStore = useChatStore()

const isUser = computed(() => props.message.role === 'user')
const renderedContent = computed(() => {
  if (isUser.value) return props.message.content
  return render(props.message.content)
})

const parsedMeta = computed(() => {
  try {
    const raw = props.message.metadata
    if (!raw) return null
    return typeof raw === 'string' ? JSON.parse(raw) : raw
  } catch {
    return null
  }
})

const isClarification = computed(() => parsedMeta.value?.type === 'clarification')

const clarifyingQuestions = computed(() => {
  if (!isClarification.value) return []
  return parsedMeta.value?.clarifyingQuestions || []
})

function handleChipClick(question: string) {
  chatStore.send(question)
}
</script>

<template>
  <div class="message-row" :class="{ user: isUser }">
    <div class="message-bubble" :class="{ user: isUser }">
      <div class="message-role">{{ isUser ? '你' : 'DART' }}</div>
      <div
        v-if="isUser"
        class="message-content"
      >{{ message.content }}</div>
      <div
        v-else
        class="message-content markdown"
        v-html="renderedContent"
      />
      <div v-if="isClarification" class="clarify-chips">
        <button
          v-for="(q, idx) in clarifyingQuestions"
          :key="idx"
          class="clarify-chip"
          @click="handleChipClick(q)"
        >{{ q }}</button>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.message-row {
  display: flex;
  padding: 0 $gap-lg;
  margin-bottom: $gap-md;
  &.user { justify-content: flex-end; }
}
.message-bubble {
  max-width: 75%;
  padding: 14px 18px;
  border-radius: $radius-lg;
  background: $color-surface;
  border: 1px solid $color-border;
  &.user {
    background: $color-accent-soft;
    border-color: $color-accent-soft;
  }
}
.message-role {
  font-size: 12px; font-weight: 600; color: $color-muted;
  margin-bottom: 6px;
}
.message-content {
  font-size: 14px; line-height: 1.6;
  &.markdown :deep(p) { margin: 0 0 8px; }
  &.markdown :deep(pre) {
    background: $color-bg; border-radius: $radius; padding: 12px;
    overflow-x: auto; font-size: 13px;
  }
  &.markdown :deep(code) { font-family: $font-mono; font-size: 13px; }
  &.markdown :deep(ul), &.markdown :deep(ol) { padding-left: 20px; }
  &.markdown :deep(blockquote) {
    border-left: 3px solid $color-accent;
    margin: 8px 0; padding-left: 12px; color: $color-muted;
  }
}
.clarify-chips {
  display: flex; flex-wrap: wrap; gap: 8px;
  margin-top: 12px; padding-top: 12px;
  border-top: 1px solid $color-border;
}
.clarify-chip {
  display: inline-block;
  padding: 6px 14px;
  border: 1px solid $color-accent;
  border-radius: 20px;
  background: white;
  color: $color-accent;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
  &:hover {
    background: $color-accent-soft;
    border-color: $color-accent-dark;
  }
}
</style>
