import type { EditorAction } from '@/api/ai'

type Handler = (action: EditorAction) => void

let handler: Handler | null = null
let pendingAction: EditorAction | null = null

export function registerAiEditorHandler(fn: Handler) {
  handler = fn
  if (pendingAction) {
    fn(pendingAction)
    pendingAction = null
  }
}

export function unregisterAiEditorHandler(fn: Handler) {
  if (handler === fn) handler = null
}

export function emitAiEditorAction(action: EditorAction) {
  if (handler) {
    handler(action)
  } else {
    pendingAction = action
  }
}
