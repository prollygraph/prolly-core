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
package com.dolthub.prolly;

import java.util.List;

public class BinaryParityTest {
    public static void main(String[] args) {
        System.out.println("--- Binary Parity Mode Test ---");
        HeapBufferPool pool = new HeapBufferPool();
        {
            TupleDescriptor desc =
                    new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true);
            TupleBuilder tb = new TupleBuilder(pool, desc);

            // Test Case: -1, 0, 1
            // In Binary Parity Mode, these should be sorted correctly by raw bytes.
            tb.putInt64(0, -1);
            Tuple tMinusOne = tb.build();

            tb.putInt64(0, 0);
            Tuple tZero = tb.build();

            tb.putInt64(0, 1);
            Tuple tPlusOne = tb.build();

            // Verify raw byte comparison
            int cmp1 = ByteUtils.compareUnsigned(tMinusOne.segment(), tZero.segment());
            int cmp2 = ByteUtils.compareUnsigned(tZero.segment(), tPlusOne.segment());

            System.out.print("Comparing -1 vs 0 (Raw Bytes)... ");
            if (cmp1 < 0) System.out.println("Passed.");
            else System.err.println("FAILED: " + cmp1);

            System.out.print("Comparing 0 vs 1 (Raw Bytes)... ");
            if (cmp2 < 0) System.out.println("Passed.");
            else System.err.println("FAILED: " + cmp2);

            // Verify TupleDescriptor uses raw bytes in this mode
            System.out.print("Verifying TupleDescriptor uses raw bytes... ");
            int cmp3 = desc.compare(tMinusOne, tZero);
            if (cmp3 < 0) System.out.println("Passed.");
            else System.err.println("FAILED");

            if (cmp1 < 0 && cmp2 < 0 && cmp3 < 0) {
                System.out.println("--- Binary Parity Test PASSED ---");
            } else {
                System.exit(1);
            }
        }
    }
}
