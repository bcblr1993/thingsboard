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
package org.thingsboard.server.service.relations;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 相应对象
 * @param <T> 泛型
 */
@Data
public class ApiResponse<T> {
    // 状态码
    private int status;
    // 消息
    private String message;
    // 具体数据
    private T data;
    // 响应时间戳
    private LocalDateTime timestamp;

    // 构造方法
    public ApiResponse(int status, String message, T data) {
        this.status = status;
        this.message = message;
        this.data = data;
        // 自动设置当前时间
        this.timestamp = LocalDateTime.now();
    }


    /**
     * 静态方法用于快速构建响应对象
     * @param data 数据
     * @return 相应对象
     * @param <T> 泛型
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "Success", data);
    }

    /**
     * 静态方法用于快速构建响应对象
     * @param status 状态码
     * @param message 信息
     * @return 相应对象
     * @param <T> 泛型
     */
    public static <T> ApiResponse<T> error(int status, String message) {
        return new ApiResponse<>(status, message, null);
    }

}
