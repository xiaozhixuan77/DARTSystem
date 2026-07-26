<script setup lang="ts">
import { useChatStore } from '@/stores/chat'

const chatStore = useChatStore()
</script>

<template>
  <div v-if="chatStore.isStreaming" class="streaming-bar">
    <div class="streaming-indicator">
      <span class="spinner" />
      <span class="streaming-text">{{ chatStore.streamingStatus || '处理中...' }}</span>
    </div>
    <button class="cancel-btn" @click="chatStore.cancelStreaming">取消</button>
  </div>
</template>

<style lang="scss" scoped>
.streaming-bar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px $gap-lg;
  background: $color-accent-soft;
  border-bottom: 1px solid $color-border;
}
.streaming-indicator {
  display: flex; align-items: center; gap: 10px;
}
.spinner {
  width: 16px; height: 16px;
  border: 2px solid $color-border;
  border-top-color: $color-accent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.streaming-text { font-size: 13px; color: $color-fg; }
.cancel-btn {
  padding: 4px 12px;
  border: 1px solid $color-border;
  border-radius: $radius;
  background: $color-surface;
  font-size: 12px; cursor: pointer;
  &:hover { border-color: $color-danger; color: $color-danger; }
}
</style>
