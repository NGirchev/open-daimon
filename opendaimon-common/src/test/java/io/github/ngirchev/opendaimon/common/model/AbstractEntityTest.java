package io.github.ngirchev.opendaimon.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests AbstractEntity equals/hashCode via ConversationThread (concrete subclass).
 */
class AbstractEntityTest {

    @Test
    void equals_sameId_returnsTrue() {
        ConversationThread a = new ConversationThread();
        a.setId(1L);
        ConversationThread b = new ConversationThread();
        b.setId(1L);

        assertTrue(a.equals(b));
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentId_returnsFalse() {
        ConversationThread a = new ConversationThread();
        a.setId(1L);
        ConversationThread b = new ConversationThread();
        b.setId(2L);

        assertFalse(a.equals(b));
        assertNotEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_sameInstance_returnsTrue() {
        ConversationThread a = new ConversationThread();
        a.setId(1L);
        assertTrue(a.equals(a));
    }

    @Test
    void equals_null_returnsFalse() {
        ConversationThread a = new ConversationThread();
        a.setId(1L);
        assertFalse(a.equals(null));
    }

    @Test
    void hashCode_nullId_returnsZero() {
        ConversationThread a = new ConversationThread();
        a.setId(null);
        assertEquals(0, a.hashCode());
    }
}
