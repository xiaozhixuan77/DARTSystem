<script setup lang="ts">
interface Scores {
  T: number
  A: number
  D: number
  M: number
}

defineProps<{ scores: Scores; weighted?: number }>()

const dimLabels: Record<string, string> = {
  T: '时效性', A: '权威性', D: '深度', M: '匹配度'
}
</script>

<template>
  <div class="score-card">
    <div class="score-item" v-for="(val, key) in scores" :key="key">
      <span class="dim-label">{{ dimLabels[key] }}</span>
      <span class="dim-bar">
        <span class="bar-fill" :style="{ width: (val / 5) * 100 + '%' }" />
      </span>
      <span class="dim-value">{{ val.toFixed(1) }}</span>
    </div>
    <div class="weighted-score" v-if="weighted !== undefined">
      加权总分: <strong>{{ weighted.toFixed(2) }}</strong>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.score-card {
  padding: 12px 16px;
  background: $color-surface;
  border: 1px solid $color-border;
  border-radius: $radius;
}
.score-item {
  display: flex; align-items: center; gap: 10px;
  margin-bottom: 8px;
  &:last-of-type { margin-bottom: 0; }
}
.dim-label {
  width: 48px;
  font-size: 12px; color: $color-muted;
  text-align: right; flex-shrink: 0;
}
.dim-bar {
  flex: 1; height: 6px;
  background: $color-border; border-radius: 3px;
  overflow: hidden;
}
.bar-fill {
  display: block; height: 100%;
  background: linear-gradient(90deg, $color-accent-light, $color-accent);
  border-radius: 3px;
  transition: width 0.4s ease;
}
.dim-value {
  width: 28px;
  font-size: 12px; font-weight: 600;
  color: $color-fg; text-align: right;
  flex-shrink: 0;
}
.weighted-score {
  margin-top: 10px; padding-top: 10px;
  border-top: 1px solid $color-border;
  font-size: 13px; text-align: center;
  color: $color-muted;
  strong { color: $color-accent; }
}
</style>
