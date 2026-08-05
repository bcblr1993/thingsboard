/**
 * Copyright © 2016-2025 The Thingsboard Authors
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
package org.thingsboard.server.common.data.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurePathUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldResolveChildUnderTrustedRoot() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path child = Files.createDirectories(root.resolve("json/system"));

        assertThat(SecurePathUtils.resolveUnderRoot(root, "json", "system")).isEqualTo(child);
    }

    @Test
    void shouldRejectParentTraversalAndAbsoluteChildren() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("root"));

        assertThatThrownBy(() -> SecurePathUtils.resolveUnderRoot(root, "..", "secret.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecurePathUtils.resolveUnderRoot(root, tempDir.resolve("secret.txt").toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecurePathUtils.normalizeConfiguredPath("../secret.txt", "Test path"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectSymbolicLinkEscape() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("link"), outside);

        assertThatThrownBy(() -> SecurePathUtils.resolveUnderRoot(root, "link", "secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link");
    }

}
