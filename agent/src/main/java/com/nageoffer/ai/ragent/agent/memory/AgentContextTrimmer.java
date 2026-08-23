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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话上下文裁剪：把过老的工具结果换成等长占位说明，不碰 IO 也不改列表长度
 * 只替换 tool_result 而不动 tool_use，因此永远不会产生孤儿结果块
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentContextTrimmer {

    /**
     * 纯陈述不带祈使句：任何"需要请重新调用"都是我们往上下文里塞指令
     */
    private static final String EVICTED_PREFIX = "[历史工具结果已省略，原长 ";
    private static final String EVICTED_SUFFIX = " 字符]";

    private final AgentMemoryProperties memoryProperties;

    /**
     * 就地裁剪并返回替换映射，调用方据此同步本轮真正发给模型的那份列表
     */
    public TrimResult trimInPlace(List<Msg> context) {
        if (!memoryProperties.isEnabled() || context == null || context.isEmpty()) {
            return TrimResult.UNCHANGED;
        }
        AgentMemoryProperties.ToolResult config = memoryProperties.getToolResult();
        int totalChars = totalChars(context);
        if (totalChars <= config.getTriggerChars()) {
            return TrimResult.UNCHANGED;
        }

        List<Cycle> cycles = splitCycles(context);
        Set<Integer> protectedCycles = protectedCycles(context, cycles, config.getKeepRecentCycles());
        List<Candidate> candidates = collectCandidates(context, cycles, protectedCycles, config.getEvictableTools());
        int reclaimable = candidates.stream().mapToInt(Candidate::reclaimable).sum();
        // 够不着最小回收量就整次放弃：宁可这轮不省，也不为几百字符把前缀改一遍
        // 下限按当前体量折算，日志里打折算后的绝对值，比例配置才有得对账
        int clearAtLeast = (int) Math.ceil(totalChars * config.getClearAtLeastRatio());
        if (reclaimable < clearAtLeast) {
            log.debug("上下文裁剪跳过, 总字符: {}, 可回收: {}, 下限: {}", totalChars, reclaimable, clearAtLeast);
            return TrimResult.UNCHANGED;
        }

        Map<Msg, Msg> replacements = apply(context, candidates);
        log.info("上下文裁剪完成, 总字符: {} -> {}, 命中消息: {}, 工具结果: {}",
                totalChars, totalChars - reclaimable, replacements.size(), candidates.size());
        return new TrimResult(reclaimable, replacements);
    }

    /**
     * 按工具循环切分：一条带 tool_use 的 assistant 消息开启一个循环，遇到用户消息或纯文本回答即闭合
     */
    private List<Cycle> splitCycles(List<Msg> context) {
        List<Cycle> cycles = new ArrayList<>();
        Cycle current = null;
        for (int i = 0; i < context.size(); i++) {
            Msg msg = context.get(i);
            MsgRole role = msg.getRole();
            if (role == MsgRole.TOOL) {
                if (current != null) {
                    current.toolIndexes().add(i);
                    for (ToolResultBlock block : blocks(msg, ToolResultBlock.class)) {
                        current.pendingIds().remove(block.getId());
                    }
                }
                continue;
            }
            List<ToolUseBlock> toolUses = blocks(msg, ToolUseBlock.class);
            if (role == MsgRole.ASSISTANT && !toolUses.isEmpty()) {
                current = new Cycle(i, new ArrayList<>(), new HashSet<>());
                for (ToolUseBlock block : toolUses) {
                    current.pendingIds().add(block.getId());
                }
                cycles.add(current);
                continue;
            }
            current = null;
        }
        return cycles;
    }

    /**
     * 本轮开出的循环与未闭合的循环一律保护且不占配额，keepRecentCycles 只在本轮之前计数
     * 一个用户轮最多跑 maxIters 次推理，按「最近 N 个」计数会在模型合成答案前清掉它本轮自己查到的证据
     */
    private Set<Integer> protectedCycles(List<Msg> context, List<Cycle> cycles, int keepRecentCycles) {
        int turnStart = lastUserIndex(context);
        Set<Integer> result = new HashSet<>();
        int kept = 0;
        for (int i = cycles.size() - 1; i >= 0; i--) {
            Cycle cycle = cycles.get(i);
            if (cycle.startIndex() > turnStart || !cycle.pendingIds().isEmpty()) {
                result.add(i);
                continue;
            }
            if (kept < keepRecentCycles) {
                result.add(i);
                kept++;
            }
        }
        return result;
    }

    /**
     * 末条用户消息即本轮起点；摘要消息虽然也是 USER 但挂在头部，取最后一条不会认错
     * 取不到用户消息就返回 -1，此时全部循环落进保护区，宁可不省也不猜边界
     */
    private int lastUserIndex(List<Msg> context) {
        for (int i = context.size() - 1; i >= 0; i--) {
            if (context.get(i).getRole() == MsgRole.USER) {
                return i;
            }
        }
        return -1;
    }

    private List<Candidate> collectCandidates(List<Msg> context, List<Cycle> cycles,
                                              Set<Integer> protectedCycles, List<String> evictableTools) {
        List<Candidate> candidates = new ArrayList<>();
        for (int c = 0; c < cycles.size(); c++) {
            if (protectedCycles.contains(c)) {
                continue;
            }
            for (int msgIndex : cycles.get(c).toolIndexes()) {
                for (ToolResultBlock block : blocks(context.get(msgIndex), ToolResultBlock.class)) {
                    // 框架的 CallExecution.buildErrorToolResult 只 set id/output/state，工具名为空即框架级错误结果，判空顺带把它排除在外
                    if (block.getName() == null || !evictableTools.contains(block.getName()) || isEvicted(block)) {
                        continue;
                    }
                    int originChars = outputChars(block);
                    int reclaimable = originChars - previewChars(originChars);
                    if (reclaimable > 0) {
                        candidates.add(new Candidate(msgIndex, block, originChars, reclaimable));
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * 等长原位替换：只 set 不删不加，破坏性写留给真正做压缩的那一层
     * 先全部重建再统一提交，重建阶段抛异常时 context 一个字节都没动，不会留下「库里是占位、模型看到的是原文」
     */
    private Map<Msg, Msg> apply(List<Msg> context, List<Candidate> candidates) {
        Map<ToolResultBlock, Candidate> hit = new IdentityHashMap<>();
        Set<Integer> touched = new HashSet<>();
        for (Candidate candidate : candidates) {
            hit.put(candidate.block(), candidate);
            touched.add(candidate.msgIndex());
        }
        Map<Integer, Msg> staged = new LinkedHashMap<>();
        Map<Msg, Msg> replacements = new IdentityHashMap<>();
        for (int msgIndex : touched) {
            Msg origin = context.get(msgIndex);
            List<ContentBlock> rebuilt = new ArrayList<>(origin.getContent().size());
            for (ContentBlock block : origin.getContent()) {
                Candidate candidate = block instanceof ToolResultBlock result ? hit.get(result) : null;
                rebuilt.add(candidate == null ? block : evict(candidate));
            }
            Msg replaced = origin.withContent(rebuilt);
            staged.put(msgIndex, replaced);
            replacements.put(origin, replaced);
        }
        staged.forEach(context::set);
        return replacements;
    }

    /**
     * 重建必须带全 id / name / metadata / state，漏掉 state 会把工具的挂起与失败状态洗成默认值
     */
    private ToolResultBlock evict(Candidate candidate) {
        ToolResultBlock origin = candidate.block();
        return ToolResultBlock.builder()
                .id(origin.getId())
                .name(origin.getName())
                .output(TextBlock.builder().text(preview(candidate.originChars())).build())
                .metadata(origin.getMetadata())
                .state(origin.getState())
                .build();
    }

    private String preview(int originChars) {
        return EVICTED_PREFIX + originChars + EVICTED_SUFFIX;
    }

    private int previewChars(int originChars) {
        return preview(originChars).length();
    }

    /**
     * 靠占位文案自身识别已清理块：换成自定义 metadata 就要赌它能穿过状态序列化，前缀判定不依赖任何外部约定
     */
    private boolean isEvicted(ToolResultBlock block) {
        List<ContentBlock> output = block.getOutput();
        return output != null && output.size() == 1
                && output.get(0) instanceof TextBlock text
                && text.getText() != null && text.getText().startsWith(EVICTED_PREFIX);
    }

    private int totalChars(List<Msg> context) {
        int sum = 0;
        for (Msg msg : context) {
            if (msg.getContent() == null) {
                continue;
            }
            for (ContentBlock block : msg.getContent()) {
                sum += charsOf(block);
            }
        }
        return sum;
    }

    /**
     * 字符数只作为 token 的粗代理，非文本块按零计，图片音视频不在裁剪目标内
     */
    private int charsOf(ContentBlock block) {
        if (block instanceof TextBlock text) {
            return length(text.getText());
        }
        if (block instanceof ThinkingBlock thinking) {
            return length(thinking.getThinking());
        }
        if (block instanceof ToolUseBlock toolUse) {
            return length(toolUse.getName())
                    + (toolUse.getInput() == null ? 0 : toolUse.getInput().toString().length());
        }
        if (block instanceof ToolResultBlock result) {
            return outputChars(result);
        }
        return 0;
    }

    private int outputChars(ToolResultBlock block) {
        if (block.getOutput() == null) {
            return 0;
        }
        int sum = 0;
        for (ContentBlock nested : block.getOutput()) {
            sum += charsOf(nested);
        }
        return sum;
    }

    private <T extends ContentBlock> List<T> blocks(Msg msg, Class<T> type) {
        return msg.getContent() == null ? List.of() : msg.getContentBlocks(type);
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    /**
     * 替换映射按引用比对，供调用方把同一批 Msg 换进本轮上行列表
     */
    public record TrimResult(int reclaimedChars, Map<Msg, Msg> replacements) {

        public static final TrimResult UNCHANGED = new TrimResult(0, Map.of());

        public boolean changed() {
            return reclaimedChars > 0 && !replacements.isEmpty();
        }
    }

    private record Cycle(int startIndex, List<Integer> toolIndexes, Set<String> pendingIds) {
    }

    private record Candidate(int msgIndex, ToolResultBlock block, int originChars, int reclaimable) {
    }
}
