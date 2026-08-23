# Ragent 回归验证台

对一个**已经跑起来的**环境做行为验证：登录、按剧本串行提问、读库看状态、给判定。它不写任何业务数据，
也不删任何东西，跑完的会话留在库里，随时可以再查。

当前只有一个套件 `agent-memory`，验证 ReAct Agent 的三层记忆。

它和 `resources/initializer` 是同一套 CLI：同样的 Java 17、同样不启动 Spring、同样复用
`common/` 下的配置、HTTP 与 JDBC 客户端。区别是初始化器负责**造出确定状态**，回归台负责**验证运行时行为**。

## agent-memory 套件

### 它验证什么

| 层 | 机制 | 剧本怎么验 | 现状 |
| --- | --- | --- | --- |
| 短期 | 工具结果清理（`AgentContextCompactionMiddleware`） | 第 1 轮植入知识库里查不到的锚点，中间 6 轮把上下文撑大到触发清理，第 8 轮要求原样复述锚点 | 已实现，硬断言 |
| 中期 | 会话摘要独立资产 | 第 9 轮要求「只依据前面聊过的内容」复述已被清理掉的工具原文 | 未实现，答不出记 PENDING |
| 长期 | 跨会话用户事实 | 第 10 轮另开一条会话直接问工号 | 未实现，答不出记 PENDING |

未实现的层不判死是有意的：**P1 / P2 落地后只要把 `MemoryTurnScript.IMPLEMENTED_TIERS` 里加上
对应值，同一份剧本自动从 PENDING 转成硬断言**，这套剧本同时也是剩下两层的验收用例。

### 前置条件

- JDK 17，`java` 与 `javac` 都在 PATH；
- RagentAI、PostgreSQL 已启动，服务读的是 `bootstrap/src/main/resources/application.yaml`；
- `ragent.engine.type=agent`——`workflow` 档位走的是 RAG 编排管线，根本没有 Agent 记忆；
- **`enterprise-knowledge-base` 模板已经初始化过**。中间 6 轮撑量问题取自该模板，库里没内容就检索
  不到东西，上下文涨不起来，清理永远不触发，整场跑完只会得到一堆 UNCOVERED；
- 账号 `admin/admin` 可登录，在 `regression.properties` 里改。

### 跑一次

在项目根目录执行：

```bash
rm -rf /tmp/ragent-regression-classes && mkdir -p /tmp/ragent-regression-classes

javac -encoding UTF-8 \
  -d /tmp/ragent-regression-classes \
  resources/initializer/common/*.java \
  resources/regression/agent-memory/*.java

java -cp /tmp/ragent-regression-classes \
  com.nageoffer.ai.ragent.initializer.AgentMemoryRegressionMain \
  --suite-dir resources/regression/agent-memory
```

10 轮全是真实模型调用，通常要跑几分钟。加 `--verbose` 会顺带打印每轮回答的前 400 字——判断模型
是真记得还是蒙对了关键词，只能靠人看这一段。

看到 `[regression] SUCCESS` 才算通过。任何一条 FAIL 都会以非零状态退出。

改过 `resources/regression/**` 或 `resources/initializer/common/**` 之后，编译这一步必须重跑。

### 怎么读报告

报告有四段：

**逐轮观测**——每轮的回答字数、上下文消息数、工具循环数、≈字符数、tool_result 块数、已清理块数、
payload 字节。`≈字符` 是在 SQL 侧按 `AgentContextTrimmer.charsOf` 的口径复算的，其中 `tool_use`
的入参服务端算的是 `Map.toString()` 长度、这里算 JSON 文本长度，**所以它是近似值**。要精确数字请看
服务端日志的 `上下文裁剪完成` / `上下文裁剪跳过`，那两行打的是服务端自己算的数。

**阈值校准**——五个指标，用来回填 `application.yaml` 里那三个阈值：

| 指标 | 用途 |
| --- | --- |
| ① tool_result 体量分布 | 单块多大，配合②算出 `clear-at-least-ratio` 给到多少才可能一次回收够 |
| ② 上下文总量峰值 | 决定 `trigger-chars` 定在哪里才既不空转也不太晚 |
| ③ 输入 token 峰值 | 供应商回填的权威读数，字符/token 比例是把上面两个字符阈值折算成 token 的唯一依据 |
| ④ 命中缓存峰值 | 清理会改写前缀让缓存失效。清理之后这个数掉下来，说明 `clear-at-least-ratio` 给小了，回收的量不值那次缓存击穿 |
| ⑤ 工具循环 / thinking 块 | `keep-recent-cycles` 保几个循环才够用；thinking 块数量是它永远不被清理的证据 |

**判定**——五种状态：

- `PASS` 通过；
- `FAIL` 回归，退出码非零；
- `PENDING` 该层还没实现，答不出是当前的正确行为；
- `UNEXPECTED` 没实现却命中了，需要人复核是不是走了别的路径蒙对的；
- `UNCOVERED` 本次没跑到这个分支，不算回归，但也不能算验过。

**本次会话**——主会话与新会话的 ID，拿去做单会话排查。

### 让清理确定性地触发

生产阈值是 `trigger-chars: 20000`，单位是字符。实测一条 10 轮会话的 payload 大约两万字节量级，中文一字
三字节，折成字符还不到阈值的三分之一，**按生产阈值跑，清理很可能一次都不触发**，报告会给一条
`UNCOVERED: 工具结果清理已实际触发`。这说明的是「这次没跑到」，不是「代码坏了」——但机制没被验过
就是没被验过。

要确定性地覆盖清理逻辑，临时把阈值调到本次实测峰值以下，重启服务再跑一遍：

```yaml
agent:
  memory:
    tool-result:
      trigger-chars: 8000        # 调到报告里「上下文总量峰值」的 1/2 左右
      clear-at-least-ratio: 0.05 # 调到「tool_result 体量 p50 ÷ 上下文总量峰值」以下，回收一块就够触发
```

改完 **必须重启进程**，这几个值是 `@ConfigurationProperties` 一次性绑定的。重跑后应当看到：

- 逐轮观测里 `已清理` 从 0 变成正数，且只增不减；
- `判定` 里那条转成 PASS；
- **锚点原文始终留在上下文里** 仍然是 PASS——清理只改写工具结果，从不删消息、也不碰用户说过的话；
- **上下文里没有孤儿 tool_use / tool_result** 仍然是 PASS——P0 做的是等长原位替换，出现孤儿就是有人
  改了消息条数，那会让 DeepSeek 直接返回 400。

验完记得把阈值改回去。生产值最终该定多少，以校准段的 ①②③ 为准，不要照抄这里的调试值。

### 单会话排查

不想重跑对话、只想再看一眼某条会话的状态：

```bash
java -cp /tmp/ragent-regression-classes \
  com.nageoffer.ai.ragent.initializer.AgentMemoryProbeMain \
  --suite-dir resources/regression/agent-memory \
  --session <会话ID>
```

它只读 `t_agent_state`，不发起任何对话。会把该会话的消息数、四类内容块数量、每块 tool_result 的
体量、逐条 usage 全部摊开。锚点默认取剧本里的 `anchor`，用 `--anchor <关键词>` 可以查别的词——
拿它查线上会话「为什么忘了某件事」也是成立的。

### 改剧本

`turns.properties` 一行一个配置，加轮次只要在 `turn.refs` 里追一个 ref 再补上它的键：

| 键 | 含义 |
| --- | --- |
| `anchor` | 全场锚点，判定「记不记得」全靠它。**必须是知识库里查不到的内容**，否则模型检索一下就答出来了，验的就不是记忆 |
| `turn.<ref>.session` | `main` 复用主会话，`fresh` 另开新会话 |
| `turn.<ref>.tier` | `short-term` / `mid-term` / `long-term`，决定这轮是硬断言还是 PENDING |
| `turn.<ref>.text` | 真正发给 `/agent/v1/chat` 的问题 |
| `turn.<ref>.expect-any` | 回答里出现任意一个即算命中，`\|` 分隔；留空表示不判回答内容 |
| `turn.<ref>.expect-tool` | 期望调用到的工具。不命中记 UNCOVERED 而不是 FAIL——检不检索是模型自己的决定 |
| `turn.<ref>.purpose` | 报告里的说明文字 |

判定关键词只匹配正式回答，不匹配思考内容。模型「想到过」不算记得。

## 配置

`regression.properties` 里只有回归台自己的参数（账号、超时、重试）。服务地址、数据库账号和
`agent.memory.*` 三个阈值全部从 `application.config` 指向的那份 `application.yaml` 读，回归台不另存
一份——报告里印的「配置阈值」必须和服务端正在用的是同一个值，否则整张校准表是假的。

排查回归台自身报错时加环境变量 `RAGENT_REGRESSION_DEBUG=true`，会打印完整堆栈。
