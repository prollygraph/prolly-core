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
package com.earasoft.prolly;

/**
 * One collection's outcome: how many chunks the mark phase claimed live, and how many the sweep
 * deleted. What an operator endpoint reports, and what a test asserts.
 *
 * @param reachableChunks size of the union mark set (engine walk + every contributor's claim)
 * @param sweptChunks 20-byte keys deleted by the sweep
 */
public record GcResult(int reachableChunks, int sweptChunks) {}
