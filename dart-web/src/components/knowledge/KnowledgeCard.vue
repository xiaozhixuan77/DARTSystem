<script setup lang="ts">
import type { KnowledgeItemVO } from '@/types/knowledge'

defineProps<{ item: KnowledgeItemVO }>()
const emit = defineEmits<{ select: [id: number] }>()

function scoreWidth(s: number) {
  return Math.round((s / 5) * 100) + '%'
}
</script>

<template>
  <div class="knowledge-card" @click="emit('select', item.id!)">
    <div class="card-title">{{ item.title }}</div>
    <div class="card-source" v-if="item.sourceUrl">{{ item.sourceUrl }}</div>
    <div class="card-scores">
      <div class="score-row">
        <span class="score-label">T</span>
        <span class="score-bar"><span class="score-fill" :style="{ width: scoreWidth(item.scoreT || 0) }" /></span>
        <span class="score-val">{{ item.scoreT }}</span>
      </div>
      <div class="score-row">
        <span class="score-label">A</span>
        <span class="score-bar"><span class="score-fill" :style="{ width: scoreWidth(item.scoreA || 0) }" /></span>
        <span class="score-val">{{ item.scoreA }}</span>
      </div>
      <div class="score-row">
        <span class="score-label">D</span>
        <span class="score-bar"><span class="score-fill" :style="{ width: scoreWidth(item.scoreD || 0) }" /></span>
        <span class="score-val">{{ item.scoreD }}</span>
      </div>
      <div class="score-row">
        <span class="score-label">M</span>
        <span class="score-bar"><span class="score-fill" :style="{ width: scoreWidth(item.scoreM || 0) }" /></span>
        <span class="score-val">{{ item.scoreM }}</span>
      </div>
      <div class="score-total" v-if="item.weightedScore">
        综合: {{ item.weightedScore }}
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.knowledge-card {
  padding: 14px;
  border: 1px solid $color-border;
  border-radius: $radius;
  background: $color-surface;
  cursor: pointer;
  transition: border-color $duration-fast, box-shadow $duration-fast;
  &:hover {
    border-color: $color-accent;
    box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  }
}
.card-title {
  font-size: 14px; font-weight: 600;
  margin-bottom: 4px;
  overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
}
.card-source {
  font-size: 11px; color: $color-muted;
  margin-bottom: 10px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.score-row {
  display: flex; align-items: center; gap: 6px;
  margin-bottom: 4px;
}
.score-label {
  width: 16px; font-size: 11px; font-weight: 600; color: $color-muted; text-align: center;
}
.score-bar {
  flex: 1; height: 4px; background: $color-border; border-radius: 2px; overflow: hidden;
}
.score-fill {
  display: block; height: 100%;
  background: $color-accent; border-radius: 2px;
  transition: width 0.3s ease;
}
.score-val {
  width: 20px; font-size: 11px; color: $color-muted; text-align: right;
}
.score-total {
  margin-top: 8px; font-size: 12px; font-weight: 600; color: $color-accent;
}
</style>
