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
package org.thingsboard.server.controller;

import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.exception.InvalidParametersException;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.cache.CacheValueQueryResponse;
import org.thingsboard.server.service.cache.CacheValueQueryService;

@RestController
@TbCoreComponent
@RequestMapping("/api/cache/values")
@RequiredArgsConstructor
public class CacheValueQueryController extends BaseController {

    private final CacheValueQueryService cacheValueQueryService;

    @ApiOperation(value = "Get a registered cache value",
            notes = "Returns a tenant-isolated value for the specified result type and key. " +
                    "A missing value is returned as {\"found\":false,\"value\":null}.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping("/{resultType}/{key}")
    public CacheValueQueryResponse getCacheValue(
            @Parameter(description = "Registered cache result type", required = true)
            @PathVariable String resultType,
            @Parameter(description = "Cache lookup key", required = true)
            @PathVariable String key) throws InvalidParametersException, ThingsboardException {
        try {
            return cacheValueQueryService.query(getCurrentUser(), resultType, key);
        } catch (IllegalArgumentException e) {
            throw new InvalidParametersException(e.getMessage());
        }
    }

}
