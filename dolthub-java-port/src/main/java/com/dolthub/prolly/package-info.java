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

/**
 * The Dolt prolly-tree port — the versioned, content-addressed B-tree substrate: {@link
 * com.dolthub.prolly.Node nodes}, {@link com.dolthub.prolly.Tuple tuples} and {@link
 * com.dolthub.prolly.Cursor cursors}, and the {@link com.dolthub.prolly.TreeMutator structural
 * mutation}, {@link com.dolthub.prolly.DiffEngine diff}, and {@link com.dolthub.prolly.MergeEngine
 * three-way merge} engines.
 *
 * <h2>Null-safety discipline (NullAway)</h2>
 *
 * <p>This package is null-checked by <b>NullAway</b> (scoped through the build's {@code
 * AnnotatedPackages} property — see {@code plans/null-safety-migration.md}). One convention governs
 * every reference-typed declaration here, and it is worth stating because the two tools it uses —
 * {@link org.jspecify.annotations.Nullable @Nullable} and {@link
 * java.util.Objects#requireNonNull(Object) Objects.requireNonNull} — are <b>opposites</b>, each
 * correct only in its own case.
 *
 * <ul>
 *   <li><b>{@code @NonNull} is the default.</b> Every parameter, return, and field is assumed
 *       non-null <em>unless annotated</em>. NullAway proves at compile time that a non-null
 *       reference is never assigned null and never dereferenced when it could be null.
 *   <li><b>{@code @Nullable} is the marked exception</b> — applied <em>only where null genuinely
 *       flows</em>: a real {@code return null}, an absent {@code Map.get}, a sentinel, a
 *       <b>tombstone</b> (a delete, e.g. {@link com.dolthub.prolly.SpillableSortedBuffer}), a
 *       <b>lazily initialized</b> field (e.g. {@link com.dolthub.prolly.Cursor}'s key cache), or an
 *       exhausted iterator's lookahead.
 * </ul>
 *
 * <h3>Why this is <em>not</em> "{@code @Nullable} everywhere"</h3>
 *
 * <p>The net's value comes precisely from {@code @NonNull} being the default and {@code @Nullable}
 * being rare and evidenced. If every reference were {@code @Nullable}, NullAway would accept every
 * dereference as "expected" and <b>catch nothing</b> — the check would be a no-op. So annotating a
 * reference {@code @Nullable} is a <em>contract claim that must be true</em>: null really reaches
 * here. The discipline is self-correcting toward the true contract — <em>under</em>-marking (a
 * {@code return null} from an un-annotated method) is flagged at the source, and
 * <em>over</em>-marking is flagged at the first deref the now-{@code @Nullable} value can no longer
 * safely reach. A clean compile is therefore evidence that every {@code @Nullable} matches how the
 * code actually behaves.
 *
 * <h3>How {@code @Nullable} prevents hiding real bugs</h3>
 *
 * <p>Without nullness annotations a genuine null-dereference bug is <em>invisible</em>: every
 * reference is implicitly "maybe null," so a forgotten null check looks identical to a safe deref
 * and surfaces only as a runtime {@code NullPointerException}, often far from its cause. Marking a
 * genuinely-nullable value {@code @Nullable} forces <b>every</b> caller to handle the null; a
 * forgotten handling becomes a <b>compile-time error</b> instead of a production NPE. The bug moves
 * from runtime-and-distant to compile-time-and-local — that is the entire point.
 *
 * <h3>{@code requireNonNull} — the opposite tool, and why it is not a cop-out</h3>
 *
 * <p>Some dereferences are provably non-null by an invariant the type system cannot express: a key
 * that must belong to one of two sets ({@link com.dolthub.prolly.MergeEngine#mergeMaterialized}), a
 * cursor known valid because a comparison said so ({@link com.dolthub.prolly.DiffEngine}), a
 * lazily-created parent that exists by the time a method runs ({@link com.dolthub.prolly.Cursor}).
 * NullAway cannot trace these, so it flags the deref — and the way that flag is answered <b>is
 * itself a bug-prevention decision</b>:
 *
 * <ul>
 *   <li><b>The bug-hiding answer</b> weakens the contract: mark the upstream source
 *       {@code @Nullable} and suppress, or {@code @SuppressWarnings("NullAway")}. That tells
 *       NullAway "null never matters here" <em>globally</em>, silencing the check at <b>every other
 *       call site too</b> — so a real null flowing through that source is no longer caught. This is
 *       how a nullness net is quietly defeated.
 *   <li><b>The bug-preserving answer (used throughout this package)</b> is {@code requireNonNull}
 *       at the one proven site. It (a) leaves the {@code @Nullable} contract intact everywhere
 *       else, so a real null at any <em>other</em> site is still a compile error; and (b) turns the
 *       invariant into a runtime assertion that <b>fails loudly, with a message, at the exact
 *       site</b> if a future change ever violates it — converting a would-be silent, distant NPE or
 *       data corruption into an immediate, located failure.
 * </ul>
 *
 * <p>So {@code requireNonNull} here is never "ignore the warning"; it is a <em>documented,
 * runtime-checked assertion of an invariant the compiler cannot verify</em> — strictly safer than
 * both the pre-annotation unchecked deref and the contract-weakening suppression. In one line:
 * <b>{@code @Nullable} says "null is real here"; {@code requireNonNull} says "null is provably not
 * here — and prove it again at runtime."</b> Using either where the other belongs is the mistake
 * the per-site choices in this package are made to avoid.
 */
package com.dolthub.prolly;
