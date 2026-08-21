import { describe, it, expect, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteConversation } from '@/api/conversation'
import AppSidebar from '../AppSidebar.vue'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn() },
  ElMessageBox: { confirm: vi.fn() }
}))

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

// 需要 stub 路由；vi.mock factory 被 hoist，共享 mock 用 vi.hoisted 声明
const { mockPush } = vi.hoisted(() => ({ mockPush: vi.fn() }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mockPush, replace: mockPush })
}))
// chat store 会 import '@/router'（真实模块依赖 vue-router.createRouter），此处一并 mock，
// 避免与上面 vue-router 的局部 mock 冲突
vi.mock('@/router', () => ({
  default: { push: mockPush, replace: mockPush }
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

  it('点击知识库按钮跳转 /knowledge', async () => {
    const wrapper = mount(AppSidebar)
    await wrapper.find('.panel-toggle').trigger('click')

    expect(mockPush).toHaveBeenCalledWith('/knowledge')
  })

  // === 会话删除 ===

  const conv = (id: number, title: string) => ({
    id, title, tags: [], lastMessage: '', messageCount: 0, updatedAt: ''
  })

  async function rightClickFirstSession(wrapper: ReturnType<typeof mount>) {
    await wrapper.find('.session-item').trigger('contextmenu', { clientX: 100, clientY: 200 })
  }

  it('右键会话显示上下文菜单', async () => {
    const store = useChatStore()
    store.conversations = [conv(1, 'Go 并发')]
    const wrapper = mount(AppSidebar)

    await rightClickFirstSession(wrapper)

    expect(wrapper.find('.context-menu').exists()).toBe(true)
    expect(wrapper.find('.context-menu').attributes('style')).toContain('left: 100px')
    expect(wrapper.find('.context-menu').attributes('style')).toContain('top: 200px')
  })

  it('点击删除触发确认框且携带会话标题', async () => {
    const store = useChatStore()
    store.conversations = [conv(1, 'Go 并发')]
    const wrapper = mount(AppSidebar)

    await rightClickFirstSession(wrapper)
    await wrapper.find('.menu-item.danger').trigger('click')

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(String(vi.mocked(ElMessageBox.confirm).mock.calls[0][0])).toContain('Go 并发')
  })

  it('确认后删除会话并移除列表项', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as any)
    vi.mocked(deleteConversation).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null, data: undefined
    })
    const store = useChatStore()
    store.conversations = [conv(1, 'A'), conv(2, 'B')]
    const wrapper = mount(AppSidebar)

    await rightClickFirstSession(wrapper)
    await wrapper.find('.menu-item.danger').trigger('click')
    await flushPromises()

    expect(deleteConversation).toHaveBeenCalledWith(1)
    expect(wrapper.findAll('.session-item')).toHaveLength(1)
    expect(ElMessage.success).toHaveBeenCalledWith('会话已删除')
  })

  it('取消确认时不删除', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValue('cancel')
    const store = useChatStore()
    store.conversations = [conv(1, 'A'), conv(2, 'B')]
    const wrapper = mount(AppSidebar)

    await rightClickFirstSession(wrapper)
    await wrapper.find('.menu-item.danger').trigger('click')
    await flushPromises()

    expect(deleteConversation).not.toHaveBeenCalled()
    expect(wrapper.findAll('.session-item')).toHaveLength(2)
  })

  it('删除当前会话后跳转 /chat', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as any)
    vi.mocked(deleteConversation).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null, data: undefined
    })
    const store = useChatStore()
    store.conversations = [conv(1, 'A'), conv(2, 'B')]
    store.currentId = 1
    const wrapper = mount(AppSidebar)

    await rightClickFirstSession(wrapper)
    await wrapper.find('.menu-item.danger').trigger('click')
    await flushPromises()

    expect(mockPush).toHaveBeenCalledWith('/chat')
  })

  it('删除非当前会话不跳转', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as any)
    vi.mocked(deleteConversation).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null, data: undefined
    })
    const store = useChatStore()
    store.conversations = [conv(1, 'A'), conv(2, 'B')]
    store.currentId = 2
    const wrapper = mount(AppSidebar)

    await rightClickFirstSession(wrapper)
    await wrapper.find('.menu-item.danger').trigger('click')
    await flushPromises()

    expect(deleteConversation).toHaveBeenCalledWith(1)
    // 删除非当前会话不跳转回 /chat（currentId 赋值触发的 URL 同步 replace 仅反映当前会话）
    expect(mockPush).not.toHaveBeenCalledWith('/chat')
    expect(store.currentId).toBe(2)
  })

  it('点击窗口其他位置关闭菜单', async () => {
    const store = useChatStore()
    store.conversations = [conv(1, 'A')]
    const wrapper = mount(AppSidebar)

    await rightClickFirstSession(wrapper)
    expect(wrapper.find('.context-menu').exists()).toBe(true)

    window.dispatchEvent(new Event('click'))
    await nextTick()

    expect(wrapper.find('.context-menu').exists()).toBe(false)
  })

  it('滚动会话列表关闭菜单', async () => {
    const store = useChatStore()
    store.conversations = [conv(1, 'A')]
    const wrapper = mount(AppSidebar)

    await rightClickFirstSession(wrapper)
    expect(wrapper.find('.context-menu').exists()).toBe(true)

    await wrapper.find('.session-list').trigger('scroll')

    expect(wrapper.find('.context-menu').exists()).toBe(false)
  })

  // === 会话项删除按钮 ===

  it('会话项右侧渲染删除按钮', () => {
    const store = useChatStore()
    store.conversations = [conv(1, 'A')]
    const wrapper = mount(AppSidebar)

    expect(wrapper.find('.session-delete-btn').exists()).toBe(true)
  })

  it('点击删除按钮不选中会话', async () => {
    const store = useChatStore()
    store.conversations = [conv(1, 'A')]
    const selectSpy = vi.spyOn(store, 'selectConversation')
    const wrapper = mount(AppSidebar)

    await wrapper.find('.session-delete-btn').trigger('click')

    expect(selectSpy).not.toHaveBeenCalled()
  })

  it('点击删除按钮触发确认框且携带会话标题', async () => {
    const store = useChatStore()
    store.conversations = [conv(1, 'Go 并发')]
    const wrapper = mount(AppSidebar)

    await wrapper.find('.session-delete-btn').trigger('click')

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(String(vi.mocked(ElMessageBox.confirm).mock.calls[0][0])).toContain('Go 并发')
  })

  it('确认后删除会话并移除列表项', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as any)
    vi.mocked(deleteConversation).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null, data: undefined
    })
    const store = useChatStore()
    store.conversations = [conv(1, 'A'), conv(2, 'B')]
    const wrapper = mount(AppSidebar)

    await wrapper.find('.session-delete-btn').trigger('click')
    await flushPromises()

    expect(deleteConversation).toHaveBeenCalledWith(1)
    expect(wrapper.findAll('.session-item')).toHaveLength(1)
  })

  it('取消确认时不删除', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValue('cancel')
    const store = useChatStore()
    store.conversations = [conv(1, 'A'), conv(2, 'B')]
    const wrapper = mount(AppSidebar)

    await wrapper.find('.session-delete-btn').trigger('click')
    await flushPromises()

    expect(deleteConversation).not.toHaveBeenCalled()
    expect(wrapper.findAll('.session-item')).toHaveLength(2)
  })

  it('删除当前会话后跳转 /chat', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as any)
    vi.mocked(deleteConversation).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null, data: undefined
    })
    const store = useChatStore()
    store.conversations = [conv(1, 'A'), conv(2, 'B')]
    store.currentId = 1
    const wrapper = mount(AppSidebar)

    await wrapper.find('.session-delete-btn').trigger('click')
    await flushPromises()

    expect(mockPush).toHaveBeenCalledWith('/chat')
  })
})
