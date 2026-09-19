# MolMap — 分子图结构变换规则审核（本地应用）

MolMap 是一个**完全本地、无需外部网络**的化学信息学审核工具，用于审阅作用在**分子图**
上的结构变换规则。它支持带**同位素、形式电荷、键阶、立体信息**的反应前/后结构，
在本地枚举匹配子图、应用重写，并对每个候选做原子映射、元素守恒、电荷差和
“未参与结构”检查。

- 后端：Java 17 / Spring Boot 3（仅 `spring-boot-starter-web`，无数据库服务器）
- 持久化：本地文件的 **append-only JSON 事件日志** + **内容寻址、只写一次的原始证据库**
- 前端：原生 HTML/CSS/JS 单页（SVG 渲染，零前端依赖、零 CDN）
- 测试：JUnit 5，全部可离线运行

## 构建 / 测试 / 启动

要求 JDK 17 或更新（开发环境为 Temurin 24）。Maven Wrapper 已提交，无需预装 Maven。

```bash
# 打包（跳过测试）
./mvnw -q -DskipTests package

# 测试，然后在 5227 端口启动
./mvnw -q test && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5227
```

可见页面：<http://127.0.0.1:5227>

数据默认写入 `./data`，可用 `-Dmolmap.data-dir=/abs/path` 覆盖。

## 数据模型（导入格式）

分子图为 JSON：`atoms[]` 与 `bonds[]`。

```json
{
  "atoms": [
    { "id": "a", "element": "C", "isotope": 13, "charge": 0, "stereo": "TH" }
  ],
  "bonds": [
    { "from": "a", "to": "b", "order": "2", "stereo": "/" }
  ]
}
```

- `element`：大写元素符号（必填）。
- `isotope`：质量数，可省略（=未指定）。
- `charge`：形式电荷，可省略（=0）。
- `stereo`（原子）：`TH` / `AL` / `U`，可省略。
- `order`（键）：`1`/`2`/`3`/`A`（芳香）。
- `stereo`（键）：`/`/`\\`/`U`，可省略。

**规则** = `lhs`（匹配子图）+ `rhs`（重写结果）。
LHS/RHS **共享原子 id** 表示该原子被保留并建立映射；LHS 独有=删除，RHS 独有=新增
（新增原子在产物里获得临时编号 `t#`）。键在保留原子间按“同端点同键阶即保留”处理，
否则删除并按 RHS 重建；与**未参与**原子相连的键始终原样保留。

## 匹配、归并与状态

- 在同一份分子上做带回溯的子图同构枚举，约束元素/同位素/电荷/原子立体/键阶。
- **对称原子**会产生多个等价映射；候选按规范指纹归并，但用 `multiplicity`
  保留等价映射的**数量**。
- 候选默认顺序由**产物规范指纹 + 轨道签名**决定，**与原始遍历顺序无关**，
  因此请求/批量条目的顺序不会改变默认展示。
- 每个候选携带稳定的输入/产物**结构指纹**（SHA-256 派生）与轨道签名。

运行结果是**显式状态**而不是“一律返回空列表”：

| 状态 | 含义 |
| --- | --- |
| `OK` | 枚举成功，0..N 个规范候选 |
| `NO_MATCH` | 匹配子图不是输入的子结构 |
| `LIMIT_REACHED` | 原始映射数超过搜索上限，**不返回半截结果** |
| `INVALID_RULE` | 规则自身未通过校验（如下），不枚举 |
| `INVALID_INPUT` | 输入结构非法，不枚举 |

规则自身的无效原因带稳定代码，例如 `RULE:ELEMENT_MUTATION`（保留原子改元素）、
`RULE:ISOTOPE_MUTATION`、`RULE:LHS_DANGLING_BOND`、`RULE:ISOLATED_LHS_ATOM` 等；
无效规则仍以**草稿**登记，但不能运行。

每个候选的诊断：`ELEMENT_NOT_CONSERVED`、`CHARGE_NOT_CONSERVED`、
`UNINVOLVED_ATOM_LOST/MUTATED`、`UNINVOLVED_BOND_LOST/ADDED`。

## 用户工作流（状态流转）

1. **定义规则**：提交 LHS/RHS，立即校验；同 id 再次提交会生成**新版本**。
2. **新建案例**：选择规则版本，粘贴反应前结构，本地枚举，查看 `未决原因`。
3. **逐步展示一次变换**：左右两个 SVG，颜色区分
   🟩保留 / 🟥删除 / 🟧新增 / 🟪立体变化，可在候选间上一步/下一步。
4. **锁定映射并重新验证**：锁定时服务端用**同一 pinned 规则版本**重算并比对
   指纹，确认可复现且无违规后签发不可变**映射证书**；失败返回冲突，不会锁定。
5. **版本迁移**：规则出新版**不会重写**已确认案例；迁移报告只列出
   结论变化（状态/候选数/指纹增删）与**映射漂移**。
6. **批量**：同一输入可有多个产物候选，每个候选带稳定指纹。
7. **规则组合**：两条规则顺序执行；若第二条把映射建立在第一条产生的**临时编号**
   （`t#` 新增原子）上，返回 `RULE2_DEPENDS_ON_TEMP_IDS` 且**不留下半个结果**。
8. **导出包**：`/api/export/package.zip` 含规则、案例、映射证书、全部未决原因
   与 SHA-256 `MANIFEST.json`。

### 并发（两个浏览器基于同一旧版本提交）

案例记录带乐观锁版本号。先到的一方提交成功、版本 +1；**后到的一方收到 HTTP 409
`CASE_VERSION_CONFLICT`**，响应体同时给出 `expected` 与 `actual`，前端显示冲突横幅，
审阅者可“拉取最新内容并重新合并”后再锁定。

### 证据不可变与来源可溯

- 原始输入与原始 LHS/RHS 以内容哈希存入 `data/evidence/<sha256>.json`，
  路径即内容地址，**只写一次、永不原地改写**。
- 派生物（候选）带 `derivedFromRuleVersion` 与 `sourceFingerprint`（规则指纹）。
- 已确认案例永久 pin 在确认时的规则版本与指纹上。

## REST 摘要

```
POST   /api/rules                              # 提交/版本化规则
GET    /api/rules                              # 最新版本列表
GET    /api/rules/{id}/versions                # 所有版本
POST   /api/cases                              # 运行并创建案例
GET    /api/cases                              # 案例列表
GET    /api/cases/{id}                         # 案例详情
GET    /api/cases/{id}/input                   # 不可变原始输入
POST   /api/cases/{id}/lock                    # 锁定映射（服务端重新验证）
POST   /api/cases/{id}/unlock                  # 解除锁定
POST   /api/batch                              # 批量
POST   /api/compose                            # 两规则顺序组合
GET    /api/compose/history                    # 组合历史
POST   /api/migrate/rules/{ruleId}/to/{ver}    # 迁移报告（dry-run）
GET    /api/export/package.zip                 # 导出包
```

## 磁盘布局

```
data/
  rules.log          # append-only 规则事件（每行=一个版本的完整后状态）
  cases.log          # append-only 案例事件（每行=该案例某版本完整后状态）
  compositions.log   # append-only 组合尝试（成功与失败都记录）
  evidence/<sha>.json# 内容寻址、只写一次的原始证据
```

---

## 故障恢复演练（新维护者可复现一次）

本节演示一次“进程被强杀 / 拷贝数据目录到新机器”后的恢复。整个过程不需要外部网络。

### 1) 准备：打包并启动

```bash
./mvnw -q -DskipTests package
rm -rf data   # 从干净状态开始
./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5227 &
sleep 12
```

### 2) 制造持久化数据：规则 + 案例 + 锁定

```bash
cat > /tmp/rule.json <<'JSON'
{"id":"rule-ketoenol","name":"keto-enol",
 "lhs":{"atoms":[{"id":"a","element":"C"},{"id":"b","element":"O"},{"id":"c","element":"C"},{"id":"h","element":"H"}],
        "bonds":[{"from":"a","to":"b","order":"2"},{"from":"a","to":"c","order":"1"},{"from":"c","to":"h","order":"1"}]},
 "rhs":{"atoms":[{"id":"a","element":"C"},{"id":"b","element":"O"},{"id":"c","element":"C"},{"id":"h","element":"H"}],
        "bonds":[{"from":"a","to":"b","order":"1"},{"from":"b","to":"h","order":"1"},{"from":"a","to":"c","order":"2"}]}}
JSON
curl -s -XPOST localhost:5227/api/rules -H 'Content-Type: application/json' --data-binary @/tmp/rule.json

cat > /tmp/input.json <<'JSON'
{"atoms":[{"id":"m1","element":"C"},{"id":"m2","element":"O"},{"id":"m3","element":"C"},{"id":"m4","element":"H"},{"id":"m5","element":"H"}],
 "bonds":[{"from":"m1","to":"m2","order":"2"},{"from":"m1","to":"m3","order":"1"},{"from":"m3","to":"m4","order":"1"},{"from":"m1","to":"m5","order":"1"}]}
JSON
CID=$(curl -s -XPOST localhost:5227/api/cases -H 'Content-Type: application/json' \
  -d "{\"ruleId\":\"rule-ketoenol\",\"ruleVersion\":1,\"input\":$(cat /tmp/input.json)}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["state"]["id"])')

# 浏览器 A 在案例版本 1 上锁定
curl -s -XPOST localhost:5227/api/cases/$CID/lock -H 'Content-Type: application/json' \
  -d '{"caseVersion":1,"candidateIndex":0}'
echo "case id = $CID"
ls -R data
```

此时 `data/rules.log`、`data/cases.log` 各有记录，`data/evidence/` 至少有三个
内容寻址文件（输入、LHS、RHS）。

### 3) 注入故障：强杀进程（模拟崩溃 / 断电）

```bash
pkill -f spring-boot:run
# 如有残留 java 进程占用 5227，也一并结束
lsof -ti :5227 | xargs -r kill -9
```

### 4) 恢复：直接重启（事件溯源回放）

存储层在启动时构造 Bean 即回放全部日志，因此无需任何“修复命令”：

```bash
./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5227 &
sleep 12

curl -s localhost:5227/api/cases/$ID   # 用第 2 步打印的 case id
```

预期：案例仍为 `"status":"CONFIRMED"`、`"version":2`，`certificate` 完整存在；
规则列表仍含 `rule-ketoenol v1`。这说明崩溃前已 append+fsync 的记录全部恢复。

### 5) 演练并发冲突恢复（两个浏览器）

- 浏览器 A、B 同时打开同一案例（都看到 `version=2`）。
- A 先锁定/解锁成功，版本变为 3。
- B 仍用 `caseVersion=2` 提交 → 得到：

```json
{ "code":"CASE_VERSION_CONFLICT",
  "context":{ "expected":2, "actual":3 } }
```

B 在界面上点击“拉取最新内容并重新合并”（或重新 `GET /api/cases/{id}`），
基于版本 3 再提交即可。没有任何一方会静默覆盖另一方。

### 6) 灾难迁移：把 data/ 拷到另一台机器

```bash
tar czf molmap-data.tgz data
# 在另一台有 JDK17+ 的机器上解包到项目根目录，执行同样的启动命令即可
```

由于所有状态都来自日志回放 + 内容寻址证据，且指纹是对**规范化内容**计算的
SHA-256（与时间戳、id 命名无关），迁移后规则指纹、候选指纹、证书哈希保持不变。

### 常见故障与判读

- **端口被占用**：`lsof -i :5227` 找到旧进程后结束，或换
  `--server.port=....`（页面端口随之变化）。
- **怀疑某条证据损坏**：`data/evidence/<sha>.json` 的文件名即其内容 SHA-256；
  `shasum -a 256` 校验，不一致即被外部篡改。应用从不覆盖该文件。
- **日志被截断（写到一半崩溃）**：回放按行解析；最后一条不完整行会导致该行读取
  失败。可把损坏的最后一行移出 `*.log` 另行保存后重启；此前完整记录全部可恢复。
- **规则跑不出来**：先看案例的 `status`（`INVALID_RULE` / `NO_MATCH` /
  `LIMIT_REACHED` 含义不同）和 `unresolvedReasons`，不要把它们都当成“无匹配”。

## 项目结构

```
src/main/java/com/molmap/
  model/      Atom, Bond, MolGraph（同位素/电荷/键阶/立体）
  rule/       Canonicalizer, MatchEngine, RuleIntegrity, GraphValidator,
              Fingerprints, Rule, Candidate, RunResult, Violation
  store/      JsonlStore, EvidenceStore, *Repository（回放+乐观锁）, 状态记录
  service/    RuleService, CaseService, MigrationService, BatchService,
              CompositionService, ExportService
  web/        REST 控制器 + 全局错误映射
  AppConfig, MolMapApplication
src/main/resources/static/  index.html, styles.css, app.js（离线单页）
src/test/java/com/molmap/   18 个离线 JUnit 测试
```
