<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ send: [message: string] }>()
const input = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

watch(input, autoResize)

function handleSend() {
  const trimmed = input.value.trim()
  if (props.disabled || !trimmed) return
  emit('send', trimmed)
  input.value = ''
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <div class="chat-input-area">
    <div class="input-wrapper">
      <textarea
        ref="textareaRef"
        v-model="input"
        class="input-field"
        placeholder="输入技术问题，DART 帮你检索与整理..."
        rows="1"
        @keydown="handleKeydown"
      />
      <button class="send-btn" @click="handleSend" :disabled="!input.trim() || disabled">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          width="20" height="20">
          <path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"/>
        </svg>
      </button>
    </div>
    <div class="input-hint">Enter 发送 · Shift+Enter 换行</div>
  </div>
</template>

<style lang="scss" scoped>
.chat-input-area {
  padding: $gap-md $gap-lg;
  border-top: 1px solid $color-border;
  background: $color-surface;
}
.input-wrapper {
  display: flex; align-items: flex-end; gap: 8px;
  background: $color-bg;
  border: 1px solid $color-border;
  border-radius: $radius-lg;
  padding: 8px 12px;
  &:focus-within { border-color: $color-accent; box-shadow: 0 0 0 3px $color-accent-soft; }
}
.input-field {
  flex: 1;
  border: none; background: none;
  font: inherit; font-size: 14px;
  resize: none; outline: none;
  min-height: 24px; max-height: 160px;
  line-height: 1.5;
}
.send-btn {
  width: 36px; height: 36px;
  display: grid; place-items: center;
  border: none; border-radius: 50%;
  background: $color-accent; color: white;
  cursor: pointer;
  flex-shrink: 0;
  transition: opacity $duration-fast;
  &:disabled { opacity: 0.4; cursor: default; }
  &:not(:disabled):hover { background: $color-accent-dark; }
}
.input-hint {
  text-align: center;
  font-size: 11px; color: $color-muted;
  margin-top: 6px;
}
</style>
