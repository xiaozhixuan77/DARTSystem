import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import AppSidebar from '../AppSidebar.vue'

vi.mock('@/api/conversation', () => ({
  listConversations: vi.fn(),
  createConversation: vi.fn(),
  deleteConversation: vi.fn(),
  getMessages: vi.fn()
}))
vi.mock('@/api/chat', () => ({ sendMessage: vi.fn() }))
vi.mock('@/api/auth', () => ({ login: vi.fn(), register: vi.fn() }))
vi.mock('@/api/client', () => ({
  saveTokens: vi.fn(),
  clearTokens: vi.fn(),
  getAccessToken: vi.fn()
}))
vi.mock('@/api/knowledge', () => ({
  listKnowledge: vi.fn(),
  getKnowledgeDetail: vi.fn(),
  deleteKnowledge: vi.fn()
}))

// 需要 stub 路由
const mockPush = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mockPush })
}))

describe('AppSidebar.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('渲染品牌区域', () => {
    const wrapper = mount(AppSidebar)
    expect(wrapper.find('.brand-name').text()).toBe('DART')
    expect(wrapper.find('.brand-sub').text()).toContain('检索')
  })

  it('渲染"新搜索"按钮', () => {
    const wrapper = mount(AppSidebar)
    expect(wrapper.find('.new-session-btn').text()).toContain('新搜索')
  })

  it('无会话时显示空状态', () => {
    const wrapper = mount(AppSidebar)
    expect(wrapper.find('.session-empty').text()).toBe('暂无会话')
  })

  it('显示会话列表', () => {
    const store = useChatStore()
    store.conversations = [
      { id: 1, title: 'Go 并发', tags: ['Go'], lastMessage: '', messageCount: 3, updatedAt: '2026-01-01' },
      { id: 2, title: 'Vue 响应式', tags: ['Vue'], lastMessage: '', messageCount: 5, updatedAt: '2026-01-02' }
    ]

    const wrapper = mount(AppSidebar)
    const items = wrapper.findAll('.session-item')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toContain('Go 并发')
    expect(items[1].text()).toContain('Vue 响应式')
  })

  it('点击会话触发 selectConversation', async () => {
    const store = useChatStore()
    store.conversations = [{ id: 1, title: 'Test', tags: [], lastMessage: '', messageCount: 0, updatedAt: '' }]
    const selectSpy = vi.spyOn(store, 'selectConversation')

    const wrapper = mount(AppSidebar)
    await wrapper.find('.session-item').trigger('click')

    expect(selectSpy).toHaveBeenCalledWith(1)
  })

  it('点击"新搜索"创建会话', async () => {
    const store = useChatStore()
    const newSpy = vi.spyOn(store, 'newConversation')

    const wrapper = mount(AppSidebar)
    await wrapper.find('.new-session-btn').trigger('click')

    expect(newSpy).toHaveBeenCalled()
  })

  it('显示用户名和头像', () => {
    const authStore = useAuthStore()
    authStore.user = { userId: 1, username: 'Xiao' }

    const wrapper = mount(AppSidebar)
    expect(wrapper.find('.user-name').text()).toBe('Xiao')
    expect(wrapper.find('.user-avatar').text()).toBe('X')
  })

  it('退出登录跳转到 /login', async () => {
    const authStore = useAuthStore()
    authStore.user = { userId: 1, username: 'test' }

    const wrapper = mount(AppSidebar)
    await wrapper.find('.logout-btn').trigger('click')

    expect(mockPush).toHaveBeenCalledWith('/login')
  })
})
