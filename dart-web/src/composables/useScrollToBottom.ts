import { ref, watch, nextTick, type Ref } from 'vue'

export function useScrollToBottom(containerRef: Ref<HTMLElement | null>, trigger: Ref<number>) {
  const isAtBottom = ref(true)
  const userScrolledUp = ref(false)

  function onScroll() {
    const el = containerRef.value
    if (!el) return
    const threshold = 60
    userScrolledUp.value = el.scrollTop + el.clientHeight < el.scrollHeight - threshold
  }

  function scrollToBottom() {
    const el = containerRef.value
    if (!el) return
    el.scrollTop = el.scrollHeight
  }

  function scrollToBottomSmooth() {
    nextTick(() => {
      if (!userScrolledUp.value) {
        scrollToBottom()
      }
    })
  }

  watch(trigger, () => {
    scrollToBottomSmooth()
  })

  return { isAtBottom, userScrolledUp, onScroll, scrollToBottom }
}
