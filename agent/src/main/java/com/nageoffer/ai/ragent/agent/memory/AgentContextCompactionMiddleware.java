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

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.memory.AgentContextTrimmer.TrimResult;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 会话记忆的唯一接线点：每次推理前裁剪持久化上下文，并同步本轮上行的消息列表
 * 裁剪在推理之前提交且不可逆：本轮推理失败也不回滚，被换掉的原文没有任何回捞路径
 * 实例被单例 Agent 共享，不得持有任何 per-call 字段
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentContextCompactionMiddleware implements MiddlewareBase {

    private final AgentContextTrimmer trimmer;

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> next.apply(compact(agent, runtimeContext, input)));
    }

    /**
     * 裁剪失败一律走原列表：省上下文不值得赔上这轮对话
     */
    private ReasoningInput compact(Agent agent, RuntimeContext runtimeContext, ReasoningInput input) {
        try {
            AgentState state = RuntimeContext.resolveAgentState(runtimeContext, agent);
            if (state == null) {
                return input;
            }
            TrimResult result = trimmer.trimInPlace(state.contextMutable());
            if (!result.changed()) {
                return input;
            }
            return new ReasoningInput(syncMessages(input.messages(), result), input.tools(), input.options());
        } catch (Exception e) {
            log.warn("上下文裁剪异常, 本轮按原列表推理, sessionId: {}",
                    runtimeContext == null ? null : runtimeContext.getSessionId(), e);
            return input;
        }
    }

    /**
     * 按引用逐条替换而不是拿 context 重建：上行列表头部还有框架挂上去的人设消息，整体覆盖会把它抹掉
     */
    private List<Msg> syncMessages(List<Msg> messages, TrimResult result) {
        return messages.stream()
                .map(msg -> result.replacements().getOrDefault(msg, msg))
                .toList();
    }
}
