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
}
