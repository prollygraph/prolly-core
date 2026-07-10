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
 * The wire-format tag for one tuple field — how a field's bytes are to be interpreted (and
 * therefore compared) by {@link TupleDescriptor}/{@link TypeCodec}.
 *
 * <p>The numeric values are the on-disk format (ported from Dolt's encoding table, hence the SQL
 * lineage of the names — {@code Year}, {@code Geometry}, …); {@code IRI}/{@code Hash128} are the
 * port's RDF additions. The {@code *Addr} variants mark out-of-line values: the field holds a
 * content address, not the value itself. <b>Renumbering an existing constant is a format break</b>
 * — pre-1.0 that is allowed but must be a deliberate, coordinated change, never incidental.
 */
public enum Encoding {
    Null((byte) 0),
    Int8((byte) 1),
    Uint8((byte) 2),
    Int16((byte) 3),
    Uint16((byte) 4),
    Int32((byte) 5),
    Uint32((byte) 6),
    Int64((byte) 7),
    Uint64((byte) 8),
    Float32((byte) 9),
    Float64((byte) 10),
    String((byte) 11),
    Bytes((byte) 12),
    JSON((byte) 13),
    Decimal((byte) 14),
    Year((byte) 15),
    Date((byte) 16),
    Time((byte) 17),
    Datetime((byte) 18),
    Enum((byte) 19),
    Set((byte) 20),
    Geometry((byte) 21),
    IRI((byte) 22),
    Hash128((byte) 23),
    Bit64((byte) 24),
    BytesAddr((byte) 25),
    CommitAddr((byte) 26),
    StringAddr((byte) 27),
    JSONAddr((byte) 28),
    GeomAddr((byte) 29),
    ExtendedAddr((byte) 30);

    private final byte value;

    Encoding(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }
}
