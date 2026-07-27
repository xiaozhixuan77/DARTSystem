import { describe, it, expect, beforeEach } from 'vitest'
import { ref, nextTick } from 'vue'
import { useScrollToBottom } from '../useScrollToBottom'

function createMockContainer(scrollTop: number, clientHeight: number, scrollHeight: number): HTMLDivElement {
  const el = document.createElement('div')
  // 使用 Object.defineProperty 模拟滚动属性
  let _scrollTop = scrollTop
  Object.defineProperty(el, 'scrollTop', {
    get: () => _scrollTop,
    set: (v: number) => { _scrollTop = v },
    configurable: true
  })
  Object.defineProperty(el, 'clientHeight', {
    get: () => clientHeight,
    configurable: true
  })
  Object.defineProperty(el, 'scrollHeight', {
    get: () => scrollHeight,
    configurable: true
  })
  return el
}

describe('useScrollToBottom', () => {
  it('初始状态 isAtBottom 为 true', () => {
    const containerRef = ref<HTMLDivElement | null>(createMockContainer(0, 400, 1000))
    const trigger = ref(0)
    const { isAtBottom } = useScrollToBottom(containerRef, trigger)
    expect(isAtBottom.value).toBe(true)
  })

  it('trigger 变化时自动滚到底部', async () => {
    const el = createMockContainer(0, 400, 1000)
    const containerRef = ref<HTMLDivElement | null>(el)
    const trigger = ref(0)
    useScrollToBottom(containerRef, trigger)

    trigger.value++
    await nextTick()
    await nextTick() // scrollToBottomSmooth 内部也用了 nextTick

    expect(el.scrollTop).toBe(1000)
  })

  it('用户上滚时 userScrolledUp 为 true', () => {
    const el = createMockContainer(0, 400, 1000)
    const containerRef = ref<HTMLDivElement | null>(el)
    const trigger = ref(0)
    const { onScroll, userScrolledUp } = useScrollToBottom(containerRef, trigger)

    onScroll()
    expect(userScrolledUp.value).toBe(true)
  })

  it('用户在底部时 userScrolledUp 为 false', () => {
    const el = createMockContainer(600, 400, 1000)
    const containerRef = ref<HTMLDivElement | null>(el)
    const trigger = ref(0)
    const { onScroll, userScrolledUp } = useScrollToBottom(containerRef, trigger)

    onScroll()
    expect(userScrolledUp.value).toBe(false)
  })

  it('scrollToBottom 设置 scrollTop', () => {
    const el = createMockContainer(0, 400, 1000)
    const containerRef = ref<HTMLDivElement | null>(el)
    const trigger = ref(0)
    const { scrollToBottom } = useScrollToBottom(containerRef, trigger)

    scrollToBottom()
    expect(el.scrollTop).toBe(1000)
  })

  it('containerRef 为 null 时不报错', () => {
    const trigger = ref(0)
    const nullRef = ref<HTMLDivElement | null>(null)
    const { scrollToBottom, onScroll } = useScrollToBottom(nullRef, trigger)

    expect(() => scrollToBottom()).not.toThrow()
    expect(() => onScroll()).not.toThrow()
  })
})
