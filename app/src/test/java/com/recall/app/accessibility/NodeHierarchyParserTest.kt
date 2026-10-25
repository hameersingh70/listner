package com.recall.app.accessibility

import com.recall.app.accessibility.util.NodeHierarchyParser
import org.junit.Assert.*
import org.junit.Test

class NodeHierarchyParserTest {

    @Test
    fun testSha256ComputationIsDeterministic() {
        val input = "Hello|How are you?|Tomorrow 5 PM"
        val hash1 = NodeHierarchyParser.computeSha256(input)
        val hash2 = NodeHierarchyParser.computeSha256(input)

        assertNotNull(hash1)
        assertEquals(64, hash1.length)
        assertEquals(hash1, hash2)
    }

    @Test
    fun testDifferentInputsProduceDifferentHashes() {
        val hashA = NodeHierarchyParser.computeSha256("Hello world")
        val hashB = NodeHierarchyParser.computeSha256("Hello world 2")

        assertNotEquals(hashA, hashB)
    }

    @Test
    fun testBlankInputProducesEmptyHash() {
        assertEquals("", NodeHierarchyParser.computeSha256(""))
        assertEquals("", NodeHierarchyParser.computeSha256("   "))
    }
}
