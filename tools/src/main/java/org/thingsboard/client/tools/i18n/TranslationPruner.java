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
package org.thingsboard.client.tools.i18n;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.core.util.Separators.Spacing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.thingsboard.server.common.data.util.SecurePathUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;


public class TranslationPruner {

    /**
     * Recursively collect all JSON keys in dot notation from the given node.
     */
    private static void collectKeys(JsonNode node, String prefix, Set<String> keys) {
        if (!node.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            keys.add(fullKey);
            collectKeys(entry.getValue(), fullKey, keys);
        }
    }

    /**
     * Prune the translation ObjectNode, keeping only fields whose dot-keys are in the valid set.
     */
    private static ObjectNode pruneNode(ObjectNode node, Set<String> keys, String prefix, ObjectMapper mapper) {
        ObjectNode pruned = mapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (keys.contains(fullKey)) {
                if (value.isObject()) {
                    ObjectNode child = pruneNode((ObjectNode) value, keys, fullKey, mapper);
                    pruned.set(key, child);
                } else {
                    pruned.set(key, value);
                }
            }
        }
        return pruned;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: `java TranslationPruner <source folder> <dest folder>`, where dest folder must contain the locale.constant-en_US.json for reference structure.");
            System.exit(1);
        }
        try {
            Path sourceFolder = SecurePathUtils.requireDirectory(args[0], "Source translation directory");
            Path destFolder = SecurePathUtils.requireDirectory(args[1], "Destination translation directory");

            Path referenceFile = SecurePathUtils.requireReadableRegularFile(
                    SecurePathUtils.resolveUnderRoot(destFolder, "locale.constant-en_US.json"),
                    "Reference translation file");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode usRoot = mapper.readTree(referenceFile.toFile());
            Set<String> validKeys = new HashSet<>();
            collectKeys(usRoot, "", validKeys);
            try (Stream<Path> sourceFiles = Files.list(sourceFolder)) {
                for (Path sourceFile : sourceFiles.filter(Files::isRegularFile).toList()) {
                    sourceFile = SecurePathUtils.resolveUnderRoot(sourceFolder, sourceFile.getFileName().toString());
                    sourceFile = SecurePathUtils.requireReadableRegularFile(sourceFile, "Source translation file");
                    Path destFile = SecurePathUtils.resolveUnderRoot(destFolder, sourceFile.getFileName().toString());
                    JsonNode sourceRoot = mapper.readTree(sourceFile.toFile());
                    if (!sourceRoot.isObject()) {
                        throw new IllegalArgumentException("Source JSON must be an object at root");
                    }
                    ObjectNode pruned = pruneNode((ObjectNode) sourceRoot, validKeys, "", mapper);
                    Separators seps = Separators.createDefaultInstance()
                            .withObjectFieldValueSpacing(Spacing.AFTER);
                    mapper.writer(new DefaultPrettyPrinter().withSeparators(seps)).writeValue(destFile.toFile(), pruned);
                    System.out.println("Pruned translation written to " + destFile);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

}
