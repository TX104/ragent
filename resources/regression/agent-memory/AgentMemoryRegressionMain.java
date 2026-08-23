/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package com.nageoffer.ai.ragent.initializer;

import com.nageoffer.ai.ragent.initializer.AgentChatClient.AgentTurnResult;
import com.nageoffer.ai.ragent.initializer.AgentStateProbe.Snapshot;
import com.nageoffer.ai.ragent.initializer.MemoryTurnScript.Turn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent 记忆回归主入口：登录后按剧本串行提问，每轮结束读一次 t_agent_state，最后出判定与校准两张表
 * 判定只针对已实现的记忆层，未实现的层答不出算符合预期，实现之后同一份剧本自动转为硬断言
 */
final class AgentMemoryRegressionMain {

    private AgentMemoryRegressionMain() {
    }

    public static void main(String[] args) {
        RegressionContext.run(args, AgentMemoryRegressionMain::execute);
    }

    private static void execute(RegressionContext context) throws Exception {
        MemoryTurnScript script = MemoryTurnScript.load(context.suiteDir().resolve("turns.properties"));
        String userId = context.login();
        printEnvironment(context, script, userId);

        List<TurnRecord> records = runTurns(context, script);
        printTurnTable(records);
        printCalibration(context, records);

        List<Check> checks = evaluate(context, script, records);
        printChecks(checks);
        printSessions(records);

        long failed = checks.stream().filter(check -> check.status() == Status.FAIL).count();
        long uncovered = checks.stream().filter(check -> check.status() == Status.UNCOVERED).count();
        if (failed > 0) {
            throw new IllegalStateException("记忆回归未通过，失败项 " + failed + " 条，详见上方判定表");
        }
        System.out.println();
        System.out.println("[regression] SUCCESS"
                + (uncovered > 0 ? "（有 " + uncovered + " 项未被本次覆盖，见判定表 UNCOVERED）" : ""));
    }

    // ---------------------------------------------------------------- 执行

    private static List<TurnRecord> runTurns(RegressionContext context, MemoryTurnScript script) throws Exception {
        long interval = Math.max(0, context.config().getInt("turn.interval-seconds", 2)) * 1000L;
        boolean verbose = Boolean.parseBoolean(context.argument("verbose", "false"));
        List<TurnRecord> records = new ArrayList<>();
        String mainSession = null;
        String freshSession = null;

        for (Turn turn : script.turns()) {
            String requested = turn.fresh() ? freshSession : mainSession;
            System.out.printf("[regression] %s (%s/%s) %s%n", turn.ref(), turn.session(), turn.tier(), turn.purpose());
            TurnRecord record;
            try {
                AgentTurnResult result = context.chat().ask(turn.text(), requested, context.turnTimeout());
                if (turn.fresh()) {
                    freshSession = result.conversationId();
                } else {
                    mainSession = result.conversationId();
                }
                Snapshot snapshot = context.probeWithRetry(result.conversationId(), script.anchor());
                record = TurnRecord.of(turn, result, snapshot);
                if (verbose) {
                    System.out.println("    回答: " + abbreviate(result.answer(), 400));
                }
            } catch (Exception ex) {
                // 单轮失败不中断：后续轮次的表现本身就是诊断信息，最终判定统一在报告里给
                System.out.println("    本轮失败: " + ex.getMessage());
                record = TurnRecord.failed(turn, ex.getMessage());
            }
            records.add(record);
            System.out.println("    " + record.oneLine());
            Thread.sleep(interval);
        }
        return List.copyOf(records);
    }

    // ---------------------------------------------------------------- 判定

    private static List<Check> evaluate(RegressionContext context, MemoryTurnScript script,
                                        List<TurnRecord> records) {
        List<Check> checks = new ArrayList<>();
        Snapshot mainPeak = peak(records, false);
        Snapshot mainLast = last(records, false);

        checks.add(new Check("链路", "每一轮都拿到了完整回答", "short-term",
                records.stream().noneMatch(TurnRecord::failedTurn)
                        ? Status.PASS : Status.FAIL,
                records.stream().filter(TurnRecord::failedTurn).count() + " 轮失败"));

        checks.add(new Check("结构", "上下文里没有孤儿 tool_use / tool_result", "short-term",
                records.stream().allMatch(TurnRecord::structurallySound) ? Status.PASS : Status.FAIL,
                "P0 只做等长原位替换，出现孤儿说明有人改了消息条数"));

        checks.add(new Check("短期", "锚点原文始终留在上下文里", "short-term",
                mainLast != null && mainLast.anchorPresent() ? Status.PASS : Status.FAIL,
                "锚点 " + script.anchor() + "，P0 从不删消息，丢了就是压缩层越界"));

        for (TurnRecord record : records) {
            if (record.turn().expectAny().isEmpty()) {
                continue;
            }
            checks.add(checkRecall(record));
        }

        // 撑量轮不检索，上下文就涨不起来，后面的阈值校准全是空跑；这不是记忆回归而是覆盖度问题
        List<String> missedTools = new ArrayList<>();
        for (TurnRecord record : records) {
            String expected = record.turn().expectTool();
            if (!expected.isBlank() && !record.calledTool(expected)) {
                missedTools.add(record.turn().ref());
            }
        }
        checks.add(new Check("撑量", "撑量轮确实触发了知识检索", "short-term",
                missedTools.isEmpty() ? Status.PASS : Status.UNCOVERED,
                missedTools.isEmpty() ? "全部命中 expect-tool"
                        : "未检索的轮次 " + String.join(",", missedTools) + "，多半是知识库没初始化，上下文撑不起来"));

        int evicted = mainLast == null ? 0 : mainLast.evictedToolResults();
        int trigger = context.config().getInt("agent.memory.tool-result.trigger-chars", 0);
        int peakChars = mainPeak == null ? 0 : mainPeak.contextChars();
        checks.add(new Check("短期", "工具结果清理已实际触发", "short-term",
                evicted > 0 ? Status.PASS : Status.UNCOVERED,
                evicted > 0 ? "已清理 " + evicted + " 块"
                        : "峰值 ≈" + peakChars + " 字符未到阈值 " + trigger + "，按 README 调低阈值重跑才能覆盖清理逻辑"));

        boolean midHit = mainLast != null && (!mainLast.summary().isBlank() || mainLast.summaryMessages() > 0);
        checks.add(tierCheck("中期", "会话摘要资产已生成", "mid-term", midHit,
                "读 payload 的 summary 字段与 __compaction_summary__ 消息"));

        TurnRecord fresh = records.stream().filter(record -> record.turn().fresh()).findFirst().orElse(null);
        boolean longHit = fresh != null && fresh.matched();
        checks.add(tierCheck("长期", "新会话里仍认得锚点", "long-term", longHit,
                "跨会话命中只能来自长期记忆，上下文在新会话里是空的"));

        return List.copyOf(checks);
    }

    private static Check checkRecall(TurnRecord record) {
        Turn turn = record.turn();
        String detail = "期望命中 " + String.join(" / ", turn.expectAny());
        if (record.failedTurn()) {
            return new Check(tierLabel(turn.tier()), turn.ref() + " " + turn.purpose(), turn.tier(),
                    turn.enforced() ? Status.FAIL : Status.PENDING, "本轮未拿到回答");
        }
        return tierCheck(tierLabel(turn.tier()), turn.ref() + " " + turn.purpose(), turn.tier(),
                record.matched(), detail);
    }

    /**
     * 未实现的层不判死：答不出是当前的正确行为，答得出反而要人复核走的是不是别的路径
     */
    private static Check tierCheck(String scope, String name, String tier, boolean hit, String detail) {
        boolean implemented = MemoryTurnScript.IMPLEMENTED_TIERS.contains(tier);
        Status status;
        if (implemented) {
            status = hit ? Status.PASS : Status.FAIL;
        } else {
            status = hit ? Status.UNEXPECTED : Status.PENDING;
        }
        String suffix = implemented ? "" : "（" + tier + " 尚未实现）";
        return new Check(scope, name, tier, status, detail + suffix);
    }

    private static String tierLabel(String tier) {
        return switch (tier) {
            case "short-term" -> "短期";
            case "mid-term" -> "中期";
            case "long-term" -> "长期";
            default -> tier;
        };
    }

    // ---------------------------------------------------------------- 输出

    private static void printEnvironment(RegressionContext context, MemoryTurnScript script, String userId) {
        InitializerConfig config = context.config();
        System.out.println("=== 环境 ===");
        System.out.println("  服务地址        " + config.require("server.base-url"));
        System.out.println("  执行架构        " + config.get("execution.engine-type", "(未知)")
                + "（必须是 agent，workflow 档位没有 Agent 记忆）");
        System.out.println("  登录用户        " + config.require("auth.username") + " / userId=" + userId);
        System.out.println("  记忆开关        " + config.get("agent.memory.enabled", "(未知)"));
        System.out.println("  trigger-chars   " + config.get("agent.memory.tool-result.trigger-chars", "(未知)"));
        System.out.println("  keep-cycles     " + config.get("agent.memory.tool-result.keep-recent-cycles", "(未知)"));
        System.out.println("  clear-at-least  " + config.get("agent.memory.tool-result.clear-at-least-ratio", "(未知)")
                + "（占当前上下文的比例）");
        System.out.println("  剧本            " + script.turns().size() + " 轮，锚点 " + script.anchor());
        System.out.println("  已实现记忆层    " + String.join(", ", MemoryTurnScript.IMPLEMENTED_TIERS));
        System.out.println();
    }

    private static void printTurnTable(List<TurnRecord> records) {
        System.out.println();
        System.out.println("=== 逐轮观测 ===");
        System.out.printf("  %-5s %-6s %-10s %7s %6s %6s %7s %6s %7s %9s  %s%n",
                "轮次", "会话", "层", "回答字数", "消息数", "循环数", "≈字符", "结果块", "已清理", "payload", "命中");
        for (TurnRecord record : records) {
            Snapshot snapshot = record.snapshot();
            System.out.printf("  %-5s %-6s %-10s %7s %6s %6s %7s %6s %7s %9s  %s%n",
                    record.turn().ref(), record.turn().session(), record.turn().tier(),
                    record.failedTurn() ? "-" : record.answerLength(),
                    number(snapshot == null ? -1 : snapshot.messageCount()),
                    number(snapshot == null ? -1 : snapshot.toolCycles()),
                    number(snapshot == null ? -1 : snapshot.contextChars()),
                    number(snapshot == null ? -1 : snapshot.toolResultBlocks()),
                    number(snapshot == null ? -1 : snapshot.evictedToolResults()),
                    number(snapshot == null ? -1 : snapshot.payloadBytes()),
                    record.hitLabel());
        }
        System.out.println("  说明：≈字符按 AgentContextTrimmer 的口径在 SQL 侧复算，tool_use 入参长度算法不同，属近似值；");
        System.out.println("        精确值以服务端日志「上下文裁剪完成 / 上下文裁剪跳过」为准。");
    }

    private static void printCalibration(RegressionContext context, List<TurnRecord> records) {
        Snapshot peak = peak(records, false);
        System.out.println();
        System.out.println("=== 阈值校准 ===");
        if (peak == null) {
            System.out.println("  没有拿到任何会话状态，无法校准");
            return;
        }
        List<Integer> chars = new ArrayList<>(peak.toolResultChars());
        Collections.sort(chars);
        System.out.println("  ① tool_result 体量  条数 " + chars.size()
                + "，min " + percentile(chars, 0)
                + "，p50 " + percentile(chars, 50)
                + "，p90 " + percentile(chars, 90)
                + "，max " + percentile(chars, 100)
                + "（已清理块按占位长度计入）");
        System.out.println("  ② 上下文总量        峰值 ≈" + peak.contextChars() + " 字符 / payload "
                + peak.payloadBytes() + " 字节，当前 trigger-chars = "
                + context.config().get("agent.memory.tool-result.trigger-chars", "(未知)"));
        System.out.println("  ③ 输入 token 峰值   " + peak.maxInputTokens()
                + "，字符/输入token ≈ " + ratio(peak.contextChars(), peak.maxInputTokens())
                + "（供应商回填，权威读数）");
        System.out.println("  ④ 命中缓存峰值      " + peak.maxCachedTokens()
                + " token；清理会改写前缀，缓存命中掉下来就说明 clear-at-least-ratio 给小了");
        System.out.println("  ⑤ 工具循环          " + peak.toolCycles() + " 个循环 / "
                + peak.toolUseBlocks() + " 次调用，thinking 块 " + peak.thinkingBlocks() + " 个（永不清理）");
        int sum = 0;
        for (int value : chars) {
            sum += value;
        }
        int newest = 0;
        for (int index = chars.size() - 1; index >= 0 && index >= chars.size() - 2; index--) {
            newest += chars.get(index);
        }
        double clearRatio = context.config().getDouble("agent.memory.tool-result.clear-at-least-ratio", 0);
        System.out.println("  可回收量粗估        tool_result 合计 " + sum + " 字符，其中最大两块 " + newest
                + "；按峰值折算的下限 ≈" + (int) Math.ceil(peak.contextChars() * clearRatio)
                + " 字符，要低于「合计 - 受保护循环」才可能触发");
    }

    private static void printChecks(List<Check> checks) {
        System.out.println();
        System.out.println("=== 判定 ===");
        for (Check check : checks) {
            System.out.printf("  [%-10s] %-4s %-38s %s%n",
                    check.status().name(), check.scope(), check.name(), check.detail());
        }
        System.out.println("  PASS=通过  FAIL=回归  PENDING=该层未实现，答不出符合预期"
                + "  UNEXPECTED=未实现却命中，需复核  UNCOVERED=本次没跑到该分支");
    }

    private static void printSessions(List<TurnRecord> records) {
        System.out.println();
        System.out.println("=== 本次会话 ===");
        String main = sessionId(records, false);
        String fresh = sessionId(records, true);
        System.out.println("  主会话   " + (main == null ? "(未建立)" : main));
        System.out.println("  新会话   " + (fresh == null ? "(未建立)" : fresh));
        System.out.println("  会话不自动清理，可用 AgentMemoryProbeMain --session <会话ID> 反复复查同一条状态");
    }

    // ---------------------------------------------------------------- 工具

    private static Snapshot peak(List<TurnRecord> records, boolean fresh) {
        Snapshot result = null;
        for (TurnRecord record : records) {
            if (record.turn().fresh() != fresh || record.snapshot() == null) {
                continue;
            }
            if (result == null || record.snapshot().contextChars() > result.contextChars()) {
                result = record.snapshot();
            }
        }
        return result;
    }

    private static Snapshot last(List<TurnRecord> records, boolean fresh) {
        Snapshot result = null;
        for (TurnRecord record : records) {
            if (record.turn().fresh() == fresh && record.snapshot() != null) {
                result = record.snapshot();
            }
        }
        return result;
    }

    private static String sessionId(List<TurnRecord> records, boolean fresh) {
        for (TurnRecord record : records) {
            if (record.turn().fresh() == fresh && record.sessionId() != null) {
                return record.sessionId();
            }
        }
        return null;
    }

    private static String percentile(List<Integer> sorted, int percent) {
        if (sorted.isEmpty()) {
            return "-";
        }
        int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
        return String.valueOf(sorted.get(Math.max(0, Math.min(sorted.size() - 1, index))));
    }

    private static String ratio(int chars, int tokens) {
        return tokens <= 0 ? "-" : String.format("%.2f", chars / (double) tokens);
    }

    private static String number(int value) {
        return value < 0 ? "-" : String.valueOf(value);
    }

    private static String abbreviate(String value, int limit) {
        String single = value == null ? "" : value.replace('\n', ' ');
        return single.length() <= limit ? single : single.substring(0, limit) + "...";
    }

    private enum Status {
        PASS, FAIL, PENDING, UNEXPECTED, UNCOVERED
    }

    private record Check(String scope, String name, String tier, Status status, String detail) {
    }

    /**
     * 一轮的全部观测：回答、状态快照、命中与否；失败轮只有 failure
     */
    private record TurnRecord(Turn turn, String sessionId, AgentTurnResult result, Snapshot snapshot,
                              boolean matched, String failure) {

        static TurnRecord of(Turn turn, AgentTurnResult result, Snapshot snapshot) {
            return new TurnRecord(turn, result.conversationId(), result, snapshot,
                    turn.matched(result.answer()), null);
        }

        static TurnRecord failed(Turn turn, String failure) {
            return new TurnRecord(turn, null, null, null, false, failure);
        }

        boolean failedTurn() {
            return failure != null;
        }

        boolean calledTool(String name) {
            return result != null && result.tools().contains(name);
        }

        int answerLength() {
            return result == null || result.answer() == null ? 0 : result.answer().length();
        }

        /**
         * 拿不到状态时不算结构违规：那是落库慢，不是压缩层越界
         */
        boolean structurallySound() {
            return snapshot == null || snapshot.structurallySound();
        }

        String hitLabel() {
            if (failedTurn()) {
                return "失败";
            }
            if (turn.expectAny().isEmpty()) {
                return result.tools().isEmpty() ? "-" : String.join(",", result.tools());
            }
            return matched ? "命中" : "未命中";
        }

        String oneLine() {
            if (failedTurn()) {
                return "结果: 失败";
            }
            return "结果: 回答 " + answerLength() + " 字"
                    + (result.thinkChars() > 0 ? "（思考 " + result.thinkChars() + " 字）" : "")
                    + "，工具 " + (result.tools().isEmpty() ? "无" : String.join(",", result.tools()))
                    + (snapshot == null ? "，状态未落库"
                    : "，上下文 " + snapshot.messageCount() + " 条 / ≈" + snapshot.contextChars() + " 字符"
                    + "，已清理 " + snapshot.evictedToolResults() + " 块");
        }
    }
}
