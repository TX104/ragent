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

package com.nageoffer.ai.ragent.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 运行轨迹块：一轮回复内按事件顺序排列的 reasoning / answer / tool 片段
 * 随消息落库供历史回放还原时间线
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentBlock {

    /**
     * reasoning / answer / tool
     */
    private String kind;

    /**
     * 产生时刻 yyyy-MM-dd'T'HH:mm:ss，不带时区偏移按本地时区解析；展示成什么样由前端定
     * 历史数据是 HH:mm:ss，前端两种都认
     */
    private String at;

    /**
     * reasoning / answer 的正文
     */
    private String text;

    /**
     * tool 名
     */
    private String name;

    /**
     * tool 展示名
     */
    private String displayName;

    /**
     * tool 终态 done / interrupted
     */
    private String status;

    /**
     * tool 结果文本，超长截断
     */
    private String result;
}
