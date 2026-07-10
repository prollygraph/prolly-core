/*
 * Copyright 2026 Earasoft
 * Copyright 2021 Dolthub, Inc.
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
package com.dolthub.prolly;

/**
 * One field's schema in a {@link TupleDescriptor}: its wire {@link Encoding} plus whether NULL is a
 * legal value. That pair is all the comparator needs — nullability decides whether an empty byte
 * range is "NULL, sorts first" or a caller bug, and the encoding picks the compare strategy.
 */
public record Type(Encoding encoding, boolean nullable) {}
