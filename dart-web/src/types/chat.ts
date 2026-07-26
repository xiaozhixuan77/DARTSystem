export interface ConversationVO {
  id: number
  title: string
  tags: string[]
  lastMessage: string
  messageCount: number
  updatedAt: string
}

export interface MessageVO {
  id: number
  role: 'user' | 'assistant' | 'system'
  content: string
  metadata?: MessageMetadata | null
  createdAt: string
}

export interface MessageMetadata {
  knowledgeItemIds?: number[]
  searchResults?: SearchResultMeta[]
  scores?: Record<string, ScoreData>
}

export interface SearchResultMeta {
  title: string
  url: string
  snippet: string
}

export interface ScoreData {
  t: number
  a: number
  d: number
  m: number
  total: number
}

export interface SSEEvent {
  event: 'status' | 'search_result' | 'score' | 'content' | 'done' | 'error'
  data?: unknown
  status?: string
  message?: string
  code?: number
}
