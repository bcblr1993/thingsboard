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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class SecurePathUtils {

    private SecurePathUtils() {
    }

    public static Path requireDirectory(String configuredPath, String description) throws IOException {
        Path path = normalizeConfiguredPath(configuredPath, description).toRealPath();
        if (!Files.isDirectory(path)) {
            throw new IOException(description + " is not a directory: " + path);
        }
        return path;
    }

    public static Path requireReadableRegularFile(String configuredPath, String description) throws IOException {
        return requireReadableRegularFile(normalizeConfiguredPath(configuredPath, description), description);
    }

    public static Path requireReadableRegularFile(Path path, String description) throws IOException {
        Path realPath = path.toRealPath();
        if (!Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(realPath)) {
            throw new IOException(description + " is not a readable regular file: " + realPath);
        }
        return realPath;
    }

    public static Path normalizeConfiguredPath(String configuredPath, String description) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        validatePathText(configuredPath, description);
        try {
            Path path = Path.of(configuredPath);
            rejectParentSegments(path, description);
            return path.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(description + " is invalid", e);
        }
    }

    public static Path resolveUnderRoot(Path root, String... relativeParts) {
        if (root == null) {
            throw new IllegalArgumentException("Root path must not be null");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot;
        for (String relativePart : relativeParts) {
            if (relativePart == null || relativePart.isBlank()) {
                throw new IllegalArgumentException("Relative path part must not be blank");
            }
            validatePathText(relativePart, "Relative path part");
            Path part;
            try {
                part = Path.of(relativePart);
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("Relative path part is invalid", e);
            }
            if (part.isAbsolute()) {
                throw new IllegalArgumentException("Absolute child paths are not allowed: " + relativePart);
            }
            rejectParentSegments(part, "Relative path part");
            resolved = resolved.resolve(part);
        }
        resolved = resolved.normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Resolved path escapes the trusted root: " + resolved);
        }
        verifyExistingPathRemainsUnderRoot(normalizedRoot, resolved);
        return resolved;
    }

    public static void validateClasspathResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("Classpath resource must not be blank");
        }
        validatePathText(resourcePath, "Classpath resource");
        Path path = Path.of(resourcePath);
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("Absolute classpath resources are not allowed: " + resourcePath);
        }
        rejectParentSegments(path, "Classpath resource");
    }

    private static void verifyExistingPathRemainsUnderRoot(Path root, Path resolved) {
        if (!Files.exists(root) || !Files.exists(resolved)) {
            return;
        }
        try {
            Path realRoot = root.toRealPath();
            Path realResolved = resolved.toRealPath();
            if (!realResolved.startsWith(realRoot)) {
                throw new IllegalArgumentException("Resolved path escapes the trusted root through a symbolic link: " + resolved);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to validate resolved path: " + resolved, e);
        }
    }

    private static void rejectParentSegments(Path path, String description) {
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException(description + " must not contain parent traversal segments");
            }
        }
    }

    private static void validatePathText(String value, String description) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(description + " must not contain NUL characters");
        }
    }

}
