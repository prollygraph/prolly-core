// Copyright 2026 Dolt Java Port Authors

package serial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the auto-generated {@link ItemType} Flatbuffers enum. Even
 * though this file is generated, drift in the underlying schema
 * (prolly.fbs) would change these constants and orphan every chunk
 * persisted under the previous numbering.
 */
class ItemTypeTest {

    @Test
    void unknown_constant_pinned() {
        assertEquals(0, ItemType.Unknown,
            "Unknown=0 — wire-format constant; drift orphans existing chunks");
    }

    @Test
    void tuple_format_alpha_constant_pinned() {
        assertEquals(1, ItemType.TupleFormatAlpha,
            "TupleFormatAlpha=1 — wire-format constant");
    }

    @Test
    void names_array_size_matches_constants() {
        // The names array is indexed by constant value — must cover all defined values.
        assertEquals(2, ItemType.names.length,
            "names array must have one entry per ItemType constant");
    }

    @Test
    void names_array_contents_pinned() {
        assertEquals("Unknown", ItemType.names[0]);
        assertEquals("TupleFormatAlpha", ItemType.names[1]);
    }

    @Test
    void name_lookup_for_each_value() {
        assertEquals("Unknown", ItemType.name(0));
        assertEquals("TupleFormatAlpha", ItemType.name(1));
    }

    @Test
    void name_throws_on_out_of_range() {
        // The auto-generated code does a direct array index — out-of-range
        // surfaces as ArrayIndexOutOfBoundsException, not a graceful Optional.
        assertThrows(ArrayIndexOutOfBoundsException.class,
            () -> ItemType.name(2));
        assertThrows(ArrayIndexOutOfBoundsException.class,
            () -> ItemType.name(-1));
    }

    @Test
    void class_is_final_and_uninstantiable() {
        // Pin: ItemType has a private no-arg ctor — defensive against
        // someone trying to instantiate a constants-holder class.
        assertTrue(java.lang.reflect.Modifier.isFinal(ItemType.class.getModifiers()),
            "ItemType must be final — auto-generated holder class");
        assertEquals(0, ItemType.class.getConstructors().length,
            "ItemType must have no public constructors");
    }
}
