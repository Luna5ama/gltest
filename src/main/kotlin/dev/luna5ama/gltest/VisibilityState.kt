package dev.luna5ama.gltest

enum class VisibilityState(val index: Int, val defineName: String) {
    ALL(0, "VISIBILITY_STATE_ALL"),
    LAST_VISIBLE(0, "VISIBILITY_STATE_LAST_VISIBLE"),
    NEWLY_VISIBLE(1,"VISIBILITY_STATE_NEWLY_VISIBLE"),
    CURRENT_VISIBLE(3,"VISIBILITY_STATE_CURRENT_VISIBLE")
}