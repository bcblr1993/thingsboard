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
package org.thingsboard.server.common.data;

import com.google.common.io.Resources;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.util.SecurePathUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class ResourceUtils {

    public static final String CLASSPATH_URL_PREFIX = "classpath:";

    public static boolean resourceExists(Object classLoaderSource, String filePath) {
        return resourceExists(classLoaderSource.getClass().getClassLoader(), filePath);
    }

    public static boolean resourceExists(ClassLoader classLoader, String filePath) {
        boolean classPathResource = false;
        String path = filePath;
        if (path.startsWith(CLASSPATH_URL_PREFIX)) {
            path = path.substring(CLASSPATH_URL_PREFIX.length());
            SecurePathUtils.validateClasspathResource(path);
            classPathResource = true;
        }
        if (!classPathResource) {
            try {
                Path resourceFile = SecurePathUtils.normalizeConfiguredPath(path, "Resource path");
                if (Files.isRegularFile(resourceFile) && Files.isReadable(resourceFile)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                log.debug("Invalid resource path: {}", filePath, e);
                return false;
            }
        }
        InputStream classPathStream = classLoader.getResourceAsStream(path);
        if (classPathStream != null) {
            return true;
        } else {
            try {
                URL url = Resources.getResource(path);
                if (url != null) {
                    return true;
                }
            } catch (IllegalArgumentException e) {}
        }
        return false;
    }

    public static InputStream getInputStream(Object classLoaderSource, String filePath) {
        return getInputStream(classLoaderSource.getClass().getClassLoader(), filePath);
    }

    public static InputStream getInputStream(ClassLoader classLoader, String filePath) {
        boolean classPathResource = false;
        String path = filePath;
        if (path.startsWith(CLASSPATH_URL_PREFIX)) {
            path = path.substring(CLASSPATH_URL_PREFIX.length());
            SecurePathUtils.validateClasspathResource(path);
            classPathResource = true;
        }
        try {
            if (!classPathResource) {
                Path resourceFile = SecurePathUtils.normalizeConfiguredPath(path, "Resource path");
                if (Files.exists(resourceFile)) {
                    resourceFile = SecurePathUtils.requireReadableRegularFile(resourceFile, "Resource path");
                    log.info("Reading resource data from file {}", filePath);
                    return Files.newInputStream(resourceFile);
                }
            }
            InputStream classPathStream = classLoader.getResourceAsStream(path);
            if (classPathStream != null) {
                log.info("Reading resource data from class path {}", filePath);
                return classPathStream;
            } else {
                URL url = Resources.getResource(path);
                if (url != null) {
                    URI uri = url.toURI();
                    log.info("Reading resource data from URI {}", filePath);
                    Path resourceFile = SecurePathUtils.requireReadableRegularFile(Path.of(uri), "Classpath resource");
                    return Files.newInputStream(resourceFile);
                }
            }
        } catch (Exception e) {
            if (e instanceof NullPointerException) {
                log.warn("Unable to find resource: " + filePath);
            } else {
                log.warn("Unable to find resource: " + filePath, e);
            }
        }
        throw new RuntimeException("Unable to find resource: " + filePath);
    }

    public static String getUri(Object classLoaderSource, String filePath) {
        return getUri(classLoaderSource.getClass().getClassLoader(), filePath);
    }

    public static String getUri(ClassLoader classLoader, String filePath) {
        try {
            Path resourceFile = SecurePathUtils.normalizeConfiguredPath(filePath, "Resource path");
            if (Files.exists(resourceFile)) {
                resourceFile = SecurePathUtils.requireReadableRegularFile(resourceFile, "Resource path");
                log.info("Reading resource data from file {}", filePath);
                return resourceFile.toString();
            } else {
                SecurePathUtils.validateClasspathResource(filePath);
                URL url = classLoader.getResource(filePath);
                return url.toURI().toString();
            }
        } catch (Exception e) {
            if (e instanceof NullPointerException) {
                log.warn("Unable to find resource: " + filePath);
            } else {
                log.warn("Unable to find resource: " + filePath, e);
            }
            throw new RuntimeException("Unable to find resource: " + filePath);
        }
    }
}
