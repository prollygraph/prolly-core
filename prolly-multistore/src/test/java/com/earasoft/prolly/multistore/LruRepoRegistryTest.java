/*
 * Copyright 2026 Earasoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.earasoft.prolly.multistore;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 Step 1 of the upstream multi-tenant hosting plan — pins the lazy-LRU registry semantics:
 * resolve opens on first access, the LRU evicts the eldest beyond capacity, unregister closes warm
 * Sails, and the {@link RepoLifecycleState#ACTIVE} → {@link RepoLifecycleState#QUIESCING} CAS
 * plumbing works (full in-flight drain is Step 12).
 */
class LruRepoRegistryTest {

    /** Opaque per-repo resource stand-in — the registry is generic over R and never inspects it. */
    private static final class FakeResource {
        final String repoId;
        volatile boolean closed;

        FakeResource(String repoId) {
            this.repoId = repoId;
        }

        void close() {
            closed = true;
        }
    }

    private static Function<String, FakeResource> recordingFactory(
            AtomicInteger calls, List<String> opened) {
        return repoId -> {
            calls.incrementAndGet();
            opened.add(repoId);
            return new FakeResource(repoId);
        };
    }

    // ---- Lazy-open semantics -------------------------------------------

    @Test
    void resolve_throws_repo_not_found_for_unregistered_repo() {
        AtomicInteger calls = new AtomicInteger();
        var registry =
                new LruRepoRegistry<>(
                        recordingFactory(calls, new ArrayList<>()), FakeResource::close, 4);
        RepoNotFoundException ex =
                assertThrows(RepoNotFoundException.class, () -> registry.resolve("missing"));
        assertEquals("missing", ex.repoId());
        assertEquals(0, calls.get(), "factory must NOT be called for unregistered repos");
    }

    @Test
    void register_does_not_open_the_sail() {
        AtomicInteger calls = new AtomicInteger();
        var registry =
                new LruRepoRegistry<>(
                        recordingFactory(calls, new ArrayList<>()), FakeResource::close, 4);
        registry.register("alpha");
        assertEquals(0, calls.get(), "register must NOT trigger a factory open — lazy contract");
        assertTrue(registry.listRepoIds().contains("alpha"));
        assertFalse(registry.isWarm("alpha"), "alpha is registered but not warm until resolve()");
    }

    @Test
    void resolve_opens_on_first_access_and_caches_thereafter() {
        AtomicInteger calls = new AtomicInteger();
        var registry =
                new LruRepoRegistry<>(
                        recordingFactory(calls, new ArrayList<>()), FakeResource::close, 4);
        registry.register("alpha");
        FakeResource first = registry.resolve("alpha");
        FakeResource second = registry.resolve("alpha");
        assertSame(first, second, "second resolve must return the cached Sail instance");
        assertEquals(
                1, calls.get(), "factory must be called exactly once across multiple resolves");
    }

    // ---- LRU eviction --------------------------------------------------

    @Test
    void warm_set_evicts_lru_when_capacity_exceeded() {
        AtomicInteger calls = new AtomicInteger();
        List<String> opened = new ArrayList<>();
        var registry =
                new LruRepoRegistry<>(recordingFactory(calls, opened), FakeResource::close, 2);
        registry.register("a");
        registry.register("b");
        registry.register("c");

        registry.resolve("a");
        registry.resolve("b");
        registry.resolve("c");

        assertTrue(registry.isWarm("c"));
        assertTrue(registry.isWarm("b"));
        assertFalse(
                registry.isWarm("a"), "a was the LRU after resolving b then c — must be evicted");

        // Re-resolving a counts as a fresh open (it was evicted).
        int callsBefore = calls.get();
        registry.resolve("a");
        assertEquals(callsBefore + 1, calls.get(), "evicted Sail must reopen on next resolve");
    }

    @Test
    void resolve_updates_lru_order_on_hit() {
        AtomicInteger calls = new AtomicInteger();
        var registry =
                new LruRepoRegistry<>(
                        recordingFactory(calls, new ArrayList<>()), FakeResource::close, 2);
        registry.register("a");
        registry.register("b");
        registry.register("c");

        registry.resolve("a"); // warm = [a]
        registry.resolve("b"); // warm = [a, b]
        registry.resolve("a"); // touch a → warm = [b, a] (a is MRU)
        registry.resolve("c"); // capacity 2 → evict b (now LRU); warm = [a, c]

        assertTrue(
                registry.isWarm("a"),
                "a was touched after b was inserted — must NOT be the eviction target");
        assertFalse(
                registry.isWarm("b"), "b is the LRU after a-touch — must be evicted when c lands");
        assertTrue(registry.isWarm("c"));
    }

    @Test
    void eviction_closes_the_evicted_resource() {
        List<FakeResource> created = new ArrayList<>();
        Function<String, FakeResource> factory =
                repoId -> {
                    var r = new FakeResource(repoId);
                    created.add(r);
                    return r;
                };
        var registry = new LruRepoRegistry<>(factory, FakeResource::close, 1);
        registry.register("a");
        registry.register("b");

        registry.resolve("a");
        registry.resolve("b"); // capacity 1 → a evicted + closed

        assertTrue(created.get(0).closed, "evicted resource must have close() called");
        assertFalse(created.get(1).closed, "warm resource must stay open");
    }

    // ---- listRepoIds ---------------------------------------------------

    @Test
    void list_repo_ids_returns_registered_set() {
        var registry =
                new LruRepoRegistry<>(repo -> new FakeResource(repo), FakeResource::close, 4);
        registry.register("a");
        registry.register("b");
        registry.register("c");
        assertEquals(Set.of("a", "b", "c"), registry.listRepoIds());
    }

    @Test
    void list_repo_ids_is_immutable() {
        var registry =
                new LruRepoRegistry<>(repo -> new FakeResource(repo), FakeResource::close, 4);
        registry.register("a");
        Set<String> ids = registry.listRepoIds();
        assertThrows(UnsupportedOperationException.class, () -> ids.add("b"));
    }

    // ---- register ------------------------------------------------------

    @Test
    void register_throws_on_duplicate_repo_id() {
        var registry =
                new LruRepoRegistry<>(repo -> new FakeResource(repo), FakeResource::close, 4);
        registry.register("alpha");
        assertThrows(IllegalStateException.class, () -> registry.register("alpha"));
    }

    @Test
    void register_requires_non_null_id() {
        var registry =
                new LruRepoRegistry<>(repo -> new FakeResource(repo), FakeResource::close, 4);
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    // ---- unregister ----------------------------------------------------

    @Test
    void unregister_closes_warm_sail_and_removes_from_registered() {
        var registry =
                new LruRepoRegistry<>(repo -> new FakeResource(repo), FakeResource::close, 4);
        registry.register("alpha");
        registry.resolve("alpha");
        assertTrue(registry.isWarm("alpha"));
        registry.unregister("alpha");
        assertFalse(registry.listRepoIds().contains("alpha"));
        assertFalse(registry.isWarm("alpha"));
        assertNull(registry.stateOf("alpha"));
    }

    @Test
    void unregister_is_no_op_when_not_registered() {
        var registry =
                new LruRepoRegistry<>(repo -> new FakeResource(repo), FakeResource::close, 4);
        assertDoesNotThrow(() -> registry.unregister("never-registered"));
    }

    // ---- quiesce -------------------------------------------------------

    @Test
    void quiesce_transitions_active_to_quiescing_then_unregisters() {
        var registry =
                new LruRepoRegistry<>(repo -> new FakeResource(repo), FakeResource::close, 4);
        registry.register("alpha");
        assertEquals(RepoLifecycleState.ACTIVE, registry.stateOf("alpha"));
        registry.quiesce("alpha", Duration.ofSeconds(30));
        // Step 1 stub: CAS → QUIESCING then immediate unregister.
        // Step 12 will leave the repo in QUIESCING during the drain window.
        assertNull(
                registry.stateOf("alpha"),
                "Step 1 stub: quiesce immediately unregisters; state map clears");
        assertFalse(registry.listRepoIds().contains("alpha"));
    }

    @Test
    void quiesce_throws_repo_not_found_when_unregistered() {
        var registry =
                new LruRepoRegistry<>(repo -> new FakeResource(repo), FakeResource::close, 4);
        assertThrows(
                RepoNotFoundException.class,
                () -> registry.quiesce("missing", Duration.ofSeconds(30)));
    }

    @Test
    void quiesce_rejects_null_timeout() {
        var registry =
                new LruRepoRegistry<>(repo -> new FakeResource(repo), FakeResource::close, 4);
        registry.register("alpha");
        assertThrows(IllegalArgumentException.class, () -> registry.quiesce("alpha", null));
    }

    // ---- constructor validation ----------------------------------------

    @Test
    void warm_set_size_must_be_positive() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new LruRepoRegistry<>(
                                repo -> new FakeResource(repo), FakeResource::close, 0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new LruRepoRegistry<>(
                                repo -> new FakeResource(repo), FakeResource::close, -1));
    }

    @Test
    void factory_must_not_be_null() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LruRepoRegistry<>(null, FakeResource::close, 4));
    }

    @Test
    void warm_set_size_is_introspectable() {
        var registry =
                new LruRepoRegistry<>(repo -> new FakeResource(repo), FakeResource::close, 7);
        assertEquals(7, registry.warmSetSize());
    }
}
