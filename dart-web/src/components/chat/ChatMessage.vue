<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue'
import { useMarkdown } from '@/composables/useMarkdown'
import type { MessageVO } from '@/types/chat'

const props = defineProps<{ message: MessageVO }>()
const { render, renderMermaidDiagrams } = useMarkdown()

const contentRef = ref<HTMLElement | null>(null)
const isUser = computed(() => props.message.role === 'user')
const renderedContent = computed(() => {
  if (isUser.value) return props.message.content
  return render(props.message.content)
})

watch(renderedContent, async () => {
  await nextTick()
  if (contentRef.value) {
    await renderMermaidDiagrams(contentRef.value)
  }
})
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
        ref="contentRef"
        class="message-content markdown"
        v-html="renderedContent"
      />
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
  &.markdown :deep(.mermaid) {
    display: flex; justify-content: center;
    padding: 16px 0; margin: 12px 0;
    background: white; border-radius: $radius;
    svg { max-width: 100%; }
  }
}
</style>
