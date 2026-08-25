<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { MessageVO } from '@/types/chat'

const props = defineProps<{
  messages: MessageVO[]
  activeIndex: number
}>()

const emit = defineEmits<{ (e: 'navigate', index: number): void }>()

const open = ref(false)
const rootRef = ref<HTMLElement | null>(null)

// 导航项 = 用户问题；index 为消息数组索引（流式期间 msg.id 是临时值、重载后变化，索引稳定）
const navItems = computed(() => {
  const items: { index: number; content: string }[] = []
  props.messages.forEach((m, index) => {
    if (m.role === 'user') items.push({ index, content: m.content })
  })
  return items
})

function toggle() {
  open.value = !open.value
}

function onItemClick(index: number) {
  emit('navigate', index)
  open.value = false
}

// 点击抽屉外部任意区域收起；按钮在根容器内，contains 判定不误触
function onDocumentClick(e: MouseEvent) {
  if (!open.value) return
  const root = rootRef.value
  if (root && !root.contains(e.target as Node)) {
    open.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocumentClick))
onUnmounted(() => document.removeEventListener('click', onDocumentClick))
</script>

<template>
  <div v-if="navItems.length > 0" ref="rootRef" class="chat-navigator">
    <button
      class="nav-toggle"
      type="button"
      :title="open ? '收起导航' : '对话记录导航'"
      @click="toggle"
    >{{ open ? '◄' : '►' }}</button>
    <div v-if="open" class="nav-panel">
      <div class="nav-panel-title">对话记录</div>
      <div class="nav-list">
        <button
          v-for="item in navItems"
          :key="item.index"
          type="button"
          class="nav-item"
          :class="{ active: item.index === activeIndex }"
          :title="item.content"
          @click="onItemClick(item.index)"
        >{{ item.content }}</button>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.chat-navigator {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 100;
}
// 右半圆凸出按钮：贴右缘垂直居中，灰色细边框
.nav-toggle {
  position: absolute;
  top: 50%;
  right: 0;
  transform: translateY(-50%);
  width: 18px;
  height: 48px;
  padding: 0;
  border: 1px solid $color-border;
  border-left: none;
  border-radius: 0 9px 9px 0;
  background: $color-surface;
  color: $color-muted;
  font-size: 10px;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 1px 1px 4px rgba(0, 0, 0, 0.06);
  &:hover { color: $color-accent; }
}
.nav-panel {
  position: absolute;
  top: 56px; // 避 chat-header
  right: 0;
  bottom: 96px; // 避 ChatInput
  width: $nav-width;
  background: $color-surface;
  border: 1px solid $color-border;
  border-right: none;
  border-radius: $radius 0 0 $radius;
  box-shadow: -4px 0 12px rgba(0, 0, 0, 0.06);
  display: flex;
  flex-direction: column;
  animation: nav-slide-in $duration-fast $ease-out;
}
@keyframes nav-slide-in {
  from { transform: translateX(8px); opacity: 0; }
  to { transform: translateX(0); opacity: 1; }
}
.nav-panel-title {
  padding: 12px $gap-md;
  font-size: 13px;
  font-weight: 600;
  color: $color-muted;
  border-bottom: 1px solid $color-border;
}
.nav-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}
.nav-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 8px 10px;
  margin-bottom: 4px;
  border: none;
  border-radius: $radius;
  background: transparent;
  font-size: 13px;
  line-height: 1.5;
  color: $color-fg;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  &:hover { background: $color-accent-soft; }
  &.active {
    background: $color-accent-soft;
    color: $color-accent-dark;
    font-weight: 600;
  }
}
</style>
