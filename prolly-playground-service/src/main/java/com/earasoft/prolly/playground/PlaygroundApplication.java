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
package com.earasoft.prolly.playground;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the real-engine playground backend. */
@SpringBootApplication
public class PlaygroundApplication {
    public static void main(String[] args) {
        // terminal mode: decode a store dir with the real reader, no web server
        if (args.length > 0 && args[0].equals("--inspect")) {
            System.exit(StoreInspector.run(java.util.Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(PlaygroundApplication.class, args);
    }
}
