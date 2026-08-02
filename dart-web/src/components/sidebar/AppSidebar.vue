<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import { useKnowledgeStore } from '@/stores/knowledge'
import SessionContextMenu from '@/components/common/SessionContextMenu.vue'
import type { ConversationVO } from '@/types/chat'

const chatStore = useChatStore()
const authStore = useAuthStore()
const router = useRouter()
const knowledgeStore = useKnowledgeStore()

const menuVisible = ref(false)
const menuX = ref(0)
const menuY = ref(0)
const menuConversationId = ref<number | null>(null)
const menuConversationTitle = ref('')

function openContextMenu(conv: ConversationVO, e: MouseEvent) {
  e.preventDefault()
  menuConversationId.value = conv.id ?? null
  menuConversationTitle.value = conv.title
  menuX.value = Math.min(e.clientX, window.innerWidth - 160)
  menuY.value = Math.min(e.clientY, window.innerHeight - 96)
  menuVisible.value = true
}

function closeContextMenu() {
  menuVisible.value = false
}

async function confirmAndDeleteConversation(id: number, title: string) {
  const isCurrent = chatStore.currentId === id
  try {
    await ElMessageBox.confirm(
      `确定删除会话「${title}」吗？删除后不可恢复。`,
      '删除会话',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  if (isCurrent) {
    // 删除流式生成中的会话，避免消息写入已删除会话
    chatStore.cancelStreaming()
  }
  const ok = await chatStore.removeConversation(id)
  if (ok) {
    ElMessage.success('会话已删除')
    if (isCurrent) router.replace('/chat')
  }
}

async function handleDeleteConversation() {
  const id = menuConversationId.value
  if (id == null) return
  closeContextMenu()
  await confirmAndDeleteConversation(id, menuConversationTitle.value)
}

onMounted(() => {
  chatStore.loadConversations()
  window.addEventListener('click', closeContextMenu)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', closeContextMenu)
})

function handleNewSession() {
  chatStore.newConversation()
}

function handleLogout() {
  authStore.logout()
  router.push('/login')
}
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar-brand" @click="router.push('/chat')">
      <div class="brand-mark">D</div>
      <div class="brand-text">
        <div class="brand-name">DART</div>
        <div class="brand-sub">双 Agent 检索系统</div>
      </div>
    </div>

    <button class="new-session-btn" @click="handleNewSession">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
        width="18" height="18" style="margin-right:6px">
        <path d="M12 5v14m-7-7h14"/>
      </svg>
      新搜索
    </button>

    <div class="session-list" @scroll="closeContextMenu">
      <div
        v-for="conv in chatStore.conversations"
        :key="conv.id"
        class="session-item"
        :class="{ active: conv.id === chatStore.currentId }"
        @click="chatStore.selectConversation(conv.id!)"
        @contextmenu="openContextMenu(conv, $event)"
      >
        <div class="session-title">{{ conv.title }}</div>
        <div class="session-meta">
          <span class="session-tags" v-if="conv.tags">{{ conv.tags }}</span>
          <span class="session-time" v-if="conv.updatedAt">
            {{ new Date(conv.updatedAt).toLocaleDateString('zh-CN') }}
          </span>
        </div>
        <button
          class="session-delete-btn"
          title="删除会话"
          @click.stop="confirmAndDeleteConversation(conv.id!, conv.title)"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
            <path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
          </svg>
        </button>
      </div>
      <div v-if="chatStore.conversations.length === 0" class="session-empty">
        暂无会话
      </div>
    </div>

    <SessionContextMenu
      :visible="menuVisible"
      :x="menuX"
      :y="menuY"
      :conversation-title="menuConversationTitle"
      @delete="handleDeleteConversation"
      @rename="closeContextMenu"
    />

    <div class="sidebar-footer">
      <button class="panel-toggle" @click="knowledgeStore.togglePanel">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          width="18" height="18" style="margin-right:6px">
          <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20M4 4v15.5A2.5 2.5 0 0 1 6.5 22H20V4a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2z"/>
        </svg>
        知识库
      </button>
      <div class="user-area">
        <span class="user-avatar">{{ authStore.username.charAt(0).toUpperCase() }}</span>
        <span class="user-name">{{ authStore.username }}</span>
        <button class="logout-btn" @click="handleLogout" title="退出登录">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            width="16" height="16">
            <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>
          </svg>
        </button>
      </div>
    </div>
  </aside>
</template>

<style lang="scss" scoped>
.sidebar {
  width: $sidebar-width;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: $color-surface;
  border-right: 1px solid $color-border;
  flex-shrink: 0;
}
.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: $gap-lg;
  cursor: pointer;
}
.brand-mark {
  width: 44px; height: 44px;
  display: grid; place-items: center;
  background: $color-accent; color: white;
  border-radius: $radius-lg;
  font-family: $font-mono; font-size: 20px; font-weight: 700;
  flex-shrink: 0;
}
.brand-name {
  font-family: $font-display;
  font-size: 20px; font-weight: 700;
  color: $color-fg;
}
.brand-sub {
  font-size: 11px; color: $color-muted;
}
.new-session-btn {
  margin: 0 $gap-md $gap-md;
  padding: 10px;
  border: 1px dashed $color-border;
  border-radius: $radius;
  background: $color-accent-soft;
  color: $color-accent;
  font-size: 14px; font-weight: 500;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer;
  transition: background $duration-fast ease;
  &:hover { background: $color-accent-light; color: white; }
}
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 $gap-md;
}
.session-item {
  position: relative;
  padding: 12px 28px 12px 12px;
  border-radius: $radius;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background $duration-fast ease;
  &:hover { background: $color-accent-soft; }
  &.active { background: $color-accent-soft; }
  &:hover .session-delete-btn { opacity: 1; }
}
.session-delete-btn {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  opacity: 0;
  border: none;
  background: none;
  color: $color-muted;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  transition: opacity $duration-fast ease, color $duration-fast ease;
  &:hover { color: $color-danger; }
}
.session-title {
  font-size: 14px; font-weight: 500;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.session-meta {
  display: flex; gap: 8px; margin-top: 4px;
  font-size: 12px; color: $color-muted;
}
.session-empty {
  text-align: center;
  color: $color-muted;
  padding: $gap-xl;
  font-size: 13px;
}
.sidebar-footer {
  padding: $gap-md;
  border-top: 1px solid $color-border;
}
.panel-toggle {
  width: 100%;
  padding: 8px;
  border: 1px solid $color-border;
  border-radius: $radius;
  background: $color-surface;
  color: $color-fg;
  font-size: 13px;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer;
  margin-bottom: $gap-md;
  transition: background $duration-fast ease;
  &:hover { background: $color-accent-soft; }
}
.user-area {
  display: flex; align-items: center; gap: 8px;
}
.user-avatar {
  width: 32px; height: 32px;
  border-radius: 50%;
  background: $color-accent-soft;
  color: $color-accent;
  display: grid; place-items: center;
  font-size: 14px; font-weight: 600;
}
.user-name {
  flex: 1;
  font-size: 13px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.logout-btn {
  border: none; background: none; cursor: pointer;
  color: $color-muted;
  padding: 4px;
  &:hover { color: $color-danger; }
}
</style>
