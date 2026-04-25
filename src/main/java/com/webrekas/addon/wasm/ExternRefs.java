package com.webrekas.addon.wasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maps long IDs to Java objects, emulating the JS-side object heap used by
 * wasm-bindgen-generated WebAssembly. Every externref value flowing in or out
 * of the WASM module is represented in Chicory as a {@code long}. We store the
 * Java-side object keyed by that long.
 *
 * Reserved IDs (populated by {@link #initTable()} to match wasm-bindgen's
 * convention):
 * <pre>
 *   0  -> UNDEFINED sentinel
 *   1  -> UNDEFINED sentinel (slot-1 duplicate, per JS)
 *   2  -> JS null (Java null in our map)
 *   3  -> Boolean.TRUE
 *   4  -> Boolean.FALSE
 *   5+ -> dynamic allocations
 * </pre>
 */
final class ExternRefs {

    /** Sentinel marker for JS {@code undefined} — distinct from Java {@code null} (= JS null). */
    static final Object UNDEFINED = new Object() {
        @Override public String toString() { return "undefined"; }
    };

    private final Map<Long, Object> slots = new ConcurrentHashMap<>();
    private final AtomicLong next = new AtomicLong(5);

    ExternRefs() {
        // Pre-populate the fixed sentinel slots so every lookup is legal
        // even before __wbindgen_init_externref_table runs.
        slots.put(0L, UNDEFINED);
        slots.put(1L, UNDEFINED);
        // id 2 -> JS null: leave the key absent so get() returns the Java-side null sentinel
        slots.put(3L, Boolean.TRUE);
        slots.put(4L, Boolean.FALSE);
    }

    /** Allocate a new id for {@code o} and return it. */
    long alloc(Object o) {
        long id = next.getAndIncrement();
        // ConcurrentHashMap does not permit null values, so remap Java null to UNDEFINED.
        slots.put(id, o == null ? UNDEFINED : o);
        return id;
    }

    /** Fetch the object for {@code id}. Returns the UNDEFINED sentinel or actual null for the reserved JS-null slot. */
    Object get(long id) {
        if (id == 2L) return null;
        Object v = slots.get(id);
        return v == UNDEFINED ? UNDEFINED : v;
    }

    /** Replace the object at {@code id}. Must only be called for ids we already allocated. */
    void set(long id, Object o) {
        if (id == 2L) return; // JS null slot is immutable in this scheme
        slots.put(id, o == null ? UNDEFINED : o);
    }

    /**
     * Allocate a fresh JS-style Array ({@link ArrayList}) and return its externref id.
     */
    long newArray() { return alloc(new ArrayList<>()); }

    /**
     * Allocate a fresh JS-style Object ({@link LinkedHashMap}) and return its externref id.
     */
    long newObject() { return alloc(new LinkedHashMap<String, Object>()); }

    /** Return {@code arr[idx]} as a Java List element, or null if not a list. */
    @SuppressWarnings("unchecked")
    List<Object> asList(long id) {
        Object v = get(id);
        return v instanceof List ? (List<Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> asMap(long id) {
        Object v = get(id);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }
}
