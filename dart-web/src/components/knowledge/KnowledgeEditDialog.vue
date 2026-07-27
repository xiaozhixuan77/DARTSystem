<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import type { KnowledgeItemDetail } from '@/types/knowledge'

const props = defineProps<{ visible: boolean; item: KnowledgeItemDetail | null }>()
const emit = defineEmits<{
  save: [data: { title: string; notes: string; tags: string }]
  close: []
}>()

const title = ref('')
const notes = ref('')
const tagInput = ref('')

watch(() => props.item, (val) => {
  if (val) {
    title.value = val.title
    notes.value = val.notes || ''
    tagInput.value = (val.tags || []).join(', ')
  }
}, { immediate: true })

const canSave = computed(() => !!props.item && title.value.trim().length > 0)

function handleSave() {
  if (!canSave.value) return
  emit('save', {
    title: title.value.trim(),
    notes: notes.value.trim(),
    tags: tagInput.value
  })
}

function handleOverlayClick(e: MouseEvent) {
  if ((e.target as HTMLElement).classList.contains('dialog-overlay')) {
    emit('close')
  }
}
</script>

<template>
  <div v-if="visible" class="dialog-overlay" @click="handleOverlayClick">
    <div class="dialog-box">
      <h3 class="dialog-title">编辑知识条目</h3>

      <div class="dialog-field">
        <label for="edit-title">标题</label>
        <input id="edit-title" v-model="title" type="text" />
      </div>

      <div class="dialog-field">
        <label for="edit-tags">标签（逗号分隔）</label>
        <input id="edit-tags" v-model="tagInput" type="text" placeholder="Go, 并发, 后端" />
      </div>

      <div class="dialog-field">
        <label for="edit-notes">备注</label>
        <textarea id="edit-notes" v-model="notes" rows="4" placeholder="添加备注..." />
      </div>

      <div class="dialog-actions">
        <button class="cancel-btn" @click="emit('close')">取消</button>
        <button class="save-btn" :disabled="!canSave" @click="handleSave">保存</button>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.dialog-overlay {
  position: fixed; inset: 0;
  z-index: 900;
  background: rgba(0, 0, 0, 0.4);
  display: flex; align-items: center; justify-content: center;
}
.dialog-box {
  width: 460px; max-height: 80vh;
  background: $color-surface;
  border-radius: $radius-lg;
  padding: 24px;
  overflow-y: auto;
}
.dialog-title {
  font-family: $font-display;
  font-size: 18px; font-weight: 700;
  margin: 0 0 20px;
}
.dialog-field {
  margin-bottom: 16px;
  label {
    display: block;
    font-size: 13px; color: $color-muted;
    margin-bottom: 6px;
  }
  input, textarea {
    width: 100%;
    padding: 10px 12px;
    border: 1px solid $color-border;
    border-radius: $radius;
    font: inherit; font-size: 14px;
    outline: none;
    &:focus { border-color: $color-accent; }
  }
  textarea { resize: vertical; }
}
.dialog-actions {
  display: flex; gap: 12px; justify-content: flex-end;
  margin-top: 8px;
}
.cancel-btn, .save-btn {
  padding: 8px 20px;
  border-radius: $radius;
  font-size: 14px; cursor: pointer;
}
.cancel-btn {
  border: 1px solid $color-border;
  background: $color-surface;
  &:hover { background: $color-bg; }
}
.save-btn {
  border: none;
  background: $color-accent; color: white;
  &:disabled { opacity: 0.4; cursor: default; }
  &:not(:disabled):hover { background: $color-accent-dark; }
}
</style>
