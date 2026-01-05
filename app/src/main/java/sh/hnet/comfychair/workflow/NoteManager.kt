package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.util.DebugLogger

/**
 * Manages workflow note CRUD operations.
 */
object NoteManager {
    private const val TAG = "NoteManager"

    /**
     * Generate a unique note ID (max existing + 1, like groups).
     */
    fun generateNoteId(graph: MutableWorkflowGraph): Int {
        val maxId = graph.notes.maxOfOrNull { it.id } ?: 0
        return maxId + 1
    }

    /**
     * Create a new note and add it to the graph.
     *
     * @param graph The mutable graph to add the note to
     * @param title The note title
     * @param content The note markdown content
     * @return The created note
     */
    fun createNote(
        graph: MutableWorkflowGraph,
        title: String = "New Note",
        content: String = ""
    ): WorkflowNote {
        val id = generateNoteId(graph)
        val note = WorkflowNote(
            id = id,
            title = title,
            content = content,
            height = 0f  // Height will be set by renderer after TextMeasurer measurement
        )
        graph.notes.add(note)
        DebugLogger.i(TAG, "Created note: id=$id, title='$title' (${content.length} chars)")
        return note
    }

    /**
     * Delete a note from the graph.
     * Also removes the note from any groups it belongs to.
     *
     * @param graph The mutable graph
     * @param noteId The ID of the note to delete
     * @return True if the note was found and deleted
     */
    fun deleteNote(graph: MutableWorkflowGraph, noteId: Int): Boolean {
        val memberId = WorkflowNote.noteIdToMemberId(noteId)

        // Remove from any groups
        removeNoteFromGroups(graph, noteId)

        // Remove the note
        val removed = graph.notes.removeIf { it.id == noteId }
        if (removed) {
            DebugLogger.i(TAG, "Deleted note: id=$noteId")
        } else {
            DebugLogger.w(TAG, "Note not found for deletion: id=$noteId")
        }
        return removed
    }

    /**
     * Rename a note.
     *
     * @param graph The mutable graph
     * @param noteId The ID of the note to rename
     * @param newTitle The new title
     * @return True if the note was found and renamed
     */
    fun renameNote(graph: MutableWorkflowGraph, noteId: Int, newTitle: String): Boolean {
        val index = graph.notes.indexOfFirst { it.id == noteId }
        if (index >= 0) {
            val note = graph.notes[index]
            val oldTitle = note.title
            graph.notes[index] = note.copy(title = newTitle)
            DebugLogger.i(TAG, "Renamed note $noteId: '$oldTitle' -> '$newTitle'")
            return true
        }
        DebugLogger.w(TAG, "Note not found for rename: id=$noteId")
        return false
    }

    /**
     * Update a note's content.
     *
     * @param graph The mutable graph
     * @param noteId The ID of the note to update
     * @param newContent The new markdown content
     * @return True if the note was found and updated
     */
    fun updateNoteContent(graph: MutableWorkflowGraph, noteId: Int, newContent: String): Boolean {
        val index = graph.notes.indexOfFirst { it.id == noteId }
        if (index >= 0) {
            val note = graph.notes[index]
            graph.notes[index] = note.copy(content = newContent)  // Keep measured height
            DebugLogger.i(TAG, "Updated note $noteId content (${newContent.length} chars)")
            return true
        }
        DebugLogger.w(TAG, "Note not found for content update: id=$noteId")
        return false
    }

    /**
     * Remove a note from all groups it belongs to.
     * If a group ends up with fewer than 2 members, it is dissolved.
     *
     * @param graph The mutable graph
     * @param noteId The ID of the note to remove from groups
     */
    private fun removeNoteFromGroups(graph: MutableWorkflowGraph, noteId: Int) {
        val memberId = WorkflowNote.noteIdToMemberId(noteId)
        val groupsToRemove = mutableListOf<Int>()

        for (i in graph.groups.indices) {
            val group = graph.groups[i]
            if (memberId in group.memberNodeIds) {
                val newMembers = group.memberNodeIds - memberId
                if (newMembers.size < 2) {
                    // Group would have < 2 members, mark for removal
                    groupsToRemove.add(group.id)
                    DebugLogger.i(TAG, "Dissolving group ${group.id} '${group.title}': only ${newMembers.size} members left")
                } else {
                    // Update group membership
                    graph.groups[i] = group.copy(memberNodeIds = newMembers)
                    DebugLogger.d(TAG, "Removed note $noteId from group ${group.id} '${group.title}'")
                }
            }
        }

        // Remove dissolved groups
        if (groupsToRemove.isNotEmpty()) {
            graph.groups.removeIf { it.id in groupsToRemove }
        }
    }

    /**
     * Get a note by ID.
     *
     * @param graph The graph to search
     * @param noteId The note ID
     * @return The note, or null if not found
     */
    fun getNote(graph: WorkflowGraph, noteId: Int): WorkflowNote? {
        return graph.notes.find { it.id == noteId }
    }

    /**
     * Get a note by ID from a mutable graph.
     *
     * @param graph The mutable graph to search
     * @param noteId The note ID
     * @return The note, or null if not found
     */
    fun getNote(graph: MutableWorkflowGraph, noteId: Int): WorkflowNote? {
        return graph.notes.find { it.id == noteId }
    }
}
