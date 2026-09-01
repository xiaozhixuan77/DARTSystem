<script setup lang="ts">
import { computed, ref, watch, onMounted, nextTick } from 'vue'
import { useMarkdown } from '@/composables/useMarkdown'
import { useChatStore } from '@/stores/chat'
import type { MessageVO } from '@/types/chat'

const props = defineProps<{ message: MessageVO }>()
const chatStore = useChatStore()
const { render, renderMermaidDiagrams } = useMarkdown()

const contentRef = ref<HTMLElement | null>(null)
const isUser = computed(() => props.message.role === 'user')
const isDraft = computed(() => chatStore.isDraftMessage(props.message))
const renderedContent = computed(() => {
  if (isUser.value) return props.message.content
  return render(props.message.content)
})

async function runMermaid() {
  await nextTick()
  if (contentRef.value) {
    await renderMermaidDiagrams(contentRef.value)
  }
}

// 历史消息/消息重建：挂载时渲染（流式中间态不渲染，避免不完整代码块失败）
onMounted(runMermaid)

// 流式结束（内容完整）后渲染一次，覆盖消息原地更新的场景
watch(() => chatStore.isStreaming, async (now, prev) => {
  if (prev === true && now === false) {
    await runMermaid()
  }
})
</script>

<template>
  <div class="message-row" :class="{ user: isUser }">
    <div class="message-bubble" :class="{ user: isUser }">
      <div class="message-role">
        {{ isUser ? '你' : 'DART' }}
        <span v-if="isDraft" class="generating-badge">生成中…</span>
      </div>
      <div
        v-if="isUser"
        class="message-content"
      >{{ message.content }}</div>
      <div
        v-else-if="isDraft && !message.content"
        class="message-content placeholder"
      >正在生成…</div>
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
.generating-badge {
  margin-left: 8px;
  font-size: 11px; font-weight: 400;
  color: $color-muted;
  border: 1px solid $color-border;
  border-radius: $radius;
  padding: 0 6px;
}
// markdown 元素样式（标题/列表/代码块/表格等）统一由全局 styles/markdown.scss 管理
.message-content {
  font-size: 15px; line-height: 1.8;
  &.placeholder {
    color: $color-muted;
    font-style: italic;
  }
}
</style>
