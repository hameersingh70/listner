package com.recall.app.accessibility.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

object NodeHierarchyParser {

    data class ParsedHierarchy(
        val chatTitle: String,
        val contentHash: String,
        val messageSnippets: List<String>,
        val contentBounds: Rect?
    )

    /**
     * Traverses the node tree to find the chat header title and message snippets.
     */
    fun parse(rootNode: AccessibilityNodeInfo?): ParsedHierarchy {
        if (rootNode == null) {
            return ParsedHierarchy(
                chatTitle = "Unknown Chat",
                contentHash = "",
                messageSnippets = emptyList(),
                contentBounds = null
            )
        }

        val allTexts = mutableListOf<String>()
        val nodeBounds = mutableListOf<Rect>()
        var detectedTitle = ""

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            if (!text.isNullOrBlank()) {
                allTexts.add(text)
                val rect = Rect()
                node.getBoundsInScreen(rect)
                nodeBounds.add(rect)

                // The top-most text in a chat view is often the conversation title / contact name
                if (detectedTitle.isBlank() && depth in 1..4 && rect.top < 300 && text.length < 50) {
                    detectedTitle = text
                }
            } else if (!desc.isNullOrBlank() && desc.length < 50 && detectedTitle.isBlank() && depth in 1..4) {
                detectedTitle = desc
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
                child.recycle()
            }
        }

        traverse(rootNode, 0)

        val title = if (detectedTitle.isNotBlank()) detectedTitle else "Chat"
        val hash = computeSha256(allTexts.joinToString("|"))

        // Compute enclosing bounding box for text nodes
        val enclosingRect = if (nodeBounds.isNotEmpty()) {
            val left = nodeBounds.minOf { it.left }
            val top = nodeBounds.minOf { it.top }
            val right = nodeBounds.maxOf { it.right }
            val bottom = nodeBounds.maxOf { it.bottom }
            Rect(left, top, right, bottom)
        } else {
            null
        }

        return ParsedHierarchy(
            chatTitle = title,
            contentHash = hash,
            messageSnippets = allTexts.takeLast(10), // Keep latest 10 items as representative snippet
            contentBounds = enclosingRect
        )
    }

    fun computeSha256(input: String): String {
        if (input.isBlank()) return ""
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
