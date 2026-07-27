<script setup lang="ts">
defineProps<{ visible: boolean; x: number; y: number; conversationTitle: string }>()
const emit = defineEmits<{
  rename: []
  delete: []
}>()
</script>

<template>
  <div
    v-if="visible"
    class="context-menu"
    :style="{ left: x + 'px', top: y + 'px' }"
  >
    <div class="menu-item" @click="emit('rename')">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
        <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
        <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
      </svg>
      重命名
    </div>
    <div class="menu-item danger" @click="emit('delete')">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
        <path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
      </svg>
      删除
    </div>
  </div>
</template>

<style lang="scss" scoped>
.context-menu {
  position: fixed;
  z-index: 1000;
  min-width: 140px;
  padding: 6px;
  background: $color-surface;
  border: 1px solid $color-border;
  border-radius: $radius;
  box-shadow: 0 4px 16px rgba(0,0,0,0.12);
}
.menu-item {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  &:hover { background: $color-accent-soft; }
  &.danger {
    color: $color-danger;
    &:hover { background: $color-danger-soft; }
  }
}
</style>
