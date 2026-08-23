/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.agent.memory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 会话记忆配置（agent.memory 段）
 * 这一层只削峰不设上界：消息条数永不减少，开着也只是涨得慢一点
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.memory")
@Validated
public class AgentMemoryProperties {

    /**
     * 记忆总开关，管的是 agent.memory 整段而不只是下面这一层
     */
    private boolean enabled = true;

    /**
     * 嵌套约束必须在字段上级联，漏掉 @Valid 则内层 @Min 一次都不会被求值
     */
    @Valid
    private ToolResult toolResult = new ToolResult();

    /**
     * 历史工具结果清理：占上下文字节的四成，是短期记忆唯一值得动的目标
     */
    @Data
    public static class ToolResult {

        /**
         * 上下文总字符数超过该值才清理；用体量而非消息条数门控，线上最长会话才十余条
         */
        @Min(1000)
        private int triggerChars = 20000;

        /**
         * 保留最近若干个已完成的工具循环，未完成的循环额外全保
         */
        @Min(1)
        private int keepRecentCycles = 2;

        /**
         * 可回收量占当前上下文不足该比例就整次不动，避免每轮都改写前缀把模型侧缓存打废
         * 用比例不用绝对字符：清理总是从最早的未保护循环下手，作废掉的是几乎整份前缀，代价随上下文体量一起涨
         * 取值须 <1，取满等于永不裁剪，关掉这一层是 enabled 的活
         */
        @DecimalMin("0")
        @DecimalMax(value = "1", inclusive = false)
        private double clearAtLeastRatio = 0.2;

        /**
         * 允许清理的工具白名单；MCP 工具集由意图树运行期决定，副作用无法静态判定，不可用黑名单
         * 默认值必须可变，绑定器拿它当容器直接 clear + addAll，写成 List.of 会在启动绑定时抛出
         */
        private List<String> evictableTools = new ArrayList<>(List.of("search_knowledge"));
    }
}
