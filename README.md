# 分子图结构变换规则审核台（molgraph-rule-review）

一个本地运行、无需外部网络服务的化学信息学审核系统：导入带**同位素、电荷、键阶、四面体立体**信息的反应前后分子图，定义“匹配子图 + 重写结果”规则，本地枚举候选匹配，检查原子映射、元素守恒、电荷差与未参与结构，并通过网页逐步展示一次变换。

## 1. 环境与构建

- JDK 17 及以上（开发验证使用 Temurin JDK 24），无需数据库、无需外部网络（Maven 首次构建需从 Maven Central 拉依赖，之后全部离线可用）。
- 安装（跳过测试打包）：

```bash
./mvnw -q -DskipTests package
```

- 测试并启动：

```bash
./mvnw -q test && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5227
```

- 可见页面：<http://127.0.0.1:5227>
- 也可以直接运行打好的 jar：`java -jar target/molgraph-rule-review-1.0.0.jar --server.port=5227`

## 2. 功能地图

| 需求 | 实现位置 |
| --- | --- |
| 本地枚举候选匹配（同位素/电荷/键阶） | `engine/SubgraphMatcher.java` |
| 对称等价映射归并、保留映射数量 | `engine/GraphIsomorphism.java` + `engine/TransformService.java` |
| 稳定结构指纹与规范顺序 | `engine/Canonical.java`（WL 细化 + 规范排列；SHA-256 指纹） |
| 重写：保留/删除/新增/替换原子、键 | `engine/Rewriter.java` |
| 四面体立体传递与翻转 | `engine/Rewriter.java#deriveOverrideStereo`、`engine/StereoMath.java` |
| 元素守恒、电荷差、未参与结构、悬挂外部键 | `engine/Rewriter.java`（elementDelta/chargeDelta/unparticipatedAtoms/issues） |
| 搜索上限 / 无匹配 / 规则无效三种状态 | `domain/TransformResult.java`：`OK`、`NO_MATCH`、`LIMIT_REACHED`、`INVALID_RULE`、`INVALID_INPUT` |
| 规则校验 | `engine/RuleValidator.java`（未知元素、悬空引用、断开子图、重复键等） |
| 原始证据不可原地改写、哈希链事件日志 | `store/EventStore.java`（append-only，每帧载荷哈希 + 前向链哈希） |
| 派生物带规则版本与来源指纹 | 事件载荷中的 `ruleVersion`/`ruleFingerprint`/`evidenceFingerprint`，以及映射证书 |
| 状态流转：导入→分析→锁定→确认 | `service/ReviewService.java`、`store/StoredCase.java` |
| 锁定映射后重新验证 | `POST /api/cases/{id}/revalidate` |
| 规则版本变化不重写已确认案例；迁移列出结论变化与映射漂移 | `ReviewService#migrate`，`store/MigrationReport.java`、`MappingDrift.java` |
| 两浏览器乐观并发冲突 | 所有写接口接受 `If-Match: <事件版本>`，冲突返回 409 `VERSION_CONFLICT` |
| 批量多候选、稳定默认展示 | `POST /api/batch`；候选按规范串排序，默认候选不随遍历顺序变化 |
| 两规则组合、临时编号依赖检测、失败不留半个结果 | `ReviewService#compose`（预检 `persist=false` 不落库） |
| 导出包：规则/案例/映射证书/未决原因 | `GET /api/export`（ZIP） |
| 逐步网页展示 | `src/main/resources/static/`（SVG 分子渲染：保留=绿、新增=黄、删除=红虚线、立体=紫） |

## 3. 数据格式

### 分子（Molecule）

```json
{
  "atoms": [
    { "id": "C*", "element": "C", "isotope": 0, "charge": 0,
      "stereo": "UP",
      "stereoOrder": ["Cl", "R", "H1", "H2"] }
  ],
  "bonds": [ { "a": "C*", "b": "Cl", "order": 1, "stereo": "NONE" } ]
}
```

- `stereo`：`NONE` / `UP` / `DOWN`（四面体，`stereoOrder` 给出有序配体；UP/DOWN 的楔形按 `stereoOrder[0]` 渲染）。
- `isotope=0` 表示匹配任意同位素（输入中给出真实质量数）；电荷与键阶精确匹配。
- 原子可选 `originTag`：用于规则链声明“该原子必须来自上一步的临时编号”。

### 规则（RuleDef）

```json
{
  "name": "KETO_ENOL",
  "version": "1.0",
  "pattern": { "atoms": [...], "bonds": [...] },
  "deleteAtoms": ["lg"],
  "addAtoms": [
    { "id": "lg", "element": "O", "isotope": 0, "charge": -1,
      "stereo": "NONE", "stereoOrder": null }
  ],
  "deleteBonds": ["c|lg"],
  "addBonds": [ { "a": "lg", "b": "c", "order": 1, "stereo": "NONE" } ]
}
```

重写原语：

- `addAtoms` 的 `id` 不在 `pattern` 中 → 真正新增原子（产品 ID 为 `new:<规则名>@<版本>:<id>`，携带来源标记）。
- `addAtoms` 的 `id` 同时出现在 `deleteAtoms` → **替换原子**（离开基被新元素替换，SN2 用）。
- `addAtoms` 的 `id` 是保留的模式中心 → 覆盖该中心的属性与立体定义。
- 立体约定：模式中心的 `stereoOrder` 是输入槽位；覆盖原子的 `stereoOrder` 按位置给出输出槽位，槽位奇偶与 `UP/DOWN` 共同决定结果手性（`UP`=保留、`DOWN`=翻转，按槽位排列奇偶修正），结论与具体嵌入无关。

## 4. REST 接口摘要

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/health` | 版本号、链头哈希、是否只读恢复模式 |
| `POST /api/rules/validate` | 只校验不入库，返回问题列表与规则指纹 |
| `POST /api/rules?ruleId=...` | 规则入库（同版本内容必须一致，不同版本必须新版本号） |
| `POST /api/cases?title=...` | 接收原始证据（ID 冲突拒绝，绝不原地改写） |
| `POST /api/cases/{id}/analyze?ruleId=...` | 分析，返回完整 `TransformResult` |
| `POST /api/cases/{id}/lock?candidateFingerprint=...` | 锁定一个候选的代表映射，生成映射证书 |
| `POST /api/cases/{id}/revalidate?ruleId=...` | 锁定后重新验证 |
| `POST /api/cases/{id}/confirm` | 确认案例 |
| `POST /api/cases/{id}/migrate?newRuleId=...` | 迁移到新规则版本：旧案例不动，生成新案例 + 漂移报告 |
| `POST /api/batch` | 批量分析多个案例 |
| `POST /api/compose?persist=false|true` | 两规则组合预检/落库 |
| `GET /api/export` | 下载审核包 ZIP |

所有写接口可带 `If-Match: <事件版本>` 做乐观并发控制。

### 状态语义（不会统一返回空列表）

- `INVALID_INPUT`：输入结构本身非法（未知元素、重复 ID、悬空键引用等）。
- `INVALID_RULE`：规则无法执行（问题码在 `issues[]` 中逐条给出）。
- `NO_MATCH`：结构合法但子图无任何嵌入。
- `LIMIT_REACHED`：嵌入数达到 `molgraph.search.limit`（默认 1024），保留已枚举出的归并候选并明确标注不完整。
- `OK`：正常，`candidates[]` 中每个候选带 `mappingCount`（归并掉对称等价嵌入后的映射数量）、`fingerprint`、`canonical`、差异列表与守恒诊断。

## 5. 本地持久化与并发

- 数据目录：`data/`（可用 `molgraph.data.dir` 覆盖）。全部状态保存在 `data/events.log`，每行一个 JSON 帧：
  `seq, type, payload, payloadHash=SHA256(payload), prevHash, chainHash=SHA256(prevHash|seq|type|payloadHash)`。
- 启动时全量重放构建内存投影；证据（`CaseReceived`）只追加不修改，任何更正都是新事件。
- 派生物（分析不落地为事件；**锁定证书**、确认、迁移、批量、组合落事件）都带规则版本、规则指纹和证据指纹。
- 事件版本即全局追加序号。浏览器在页面加载时看到的版本通过 `If-Match` 回传；另一浏览器先提交后，后到提交收到 409，响应体含 `expectedVersion`/`actualVersion`，重新加载即可看到冲突内容并再次合并（网页“并发冲突演示”页可完整走一遍）。

## 6. 故障恢复演练（新维护者必读）

1. 启动一次应用，让 `data/events.log` 产生若干帧，然后停止。
2. 模拟“原地改写证据”（哈希失配）：

   ```bash
   # 把第 1 帧 payload 中的某个字符改掉，例如把 first/任意标题文字改掉
   python3 - <<'PY'
   import pathlib
   p = pathlib.Path("data/events.log")
   lines = p.read_text().splitlines()
   lines[0] = lines[0].replace("丙酮", "被篡改", 1)
   p.write_text("\n".join(lines) + "\n")
   PY
   ```

   （想演练“尾部半截写入”，就在文件末尾追加一行 `NOT-A-JSON-FRAME`。）
3. 重新启动应用。恢复器逐帧校验字段、序号、载荷哈希与哈希链：
   - 完整日志被复制到 `data/quarantine/events-<时间戳>.corrupt.log`；
   - `events.log` 只保留最后一个有效前缀；
   - 服务进入**只读模式**：`GET /api/health` 返回 `readOnly=true`、`recoveryError` 与隔离原因，页面顶部出现红色徽标，写操作返回 503。
4. 处置：
   - 接受有效前缀：把 `data/quarantine/*.corrupt.log` 另存归档后，删除其中损坏帧并手工核对，或直接在只读状态下继续查看；
   - 从隔离文件修复：复制隔离文件，修正/删除坏帧（保证其后每帧的 `prevHash` 重新链接，最简单的办法是保留坏帧之前的全部帧、删除其后帧），覆盖回 `data/events.log`，重启后自动解除只读。
5. 验证：`GET /api/health` 中 `readOnly=false`，`version` 等于保留帧数。

自动化覆盖见 `src/test/java/chem/molgraph/EventStoreRecoveryTest.java`（重启重放、篡改后隔离与只读、尾部垃圾截断）。

## 7. 测试（全部离线）

```bash
./mvnw -q test
```

- `StereoMathTest` / `CanonicalTest`：立体奇偶、重命名不变性、同位素/电荷/键阶/对映体指纹差异。
- `TransformEngineTest`：对称归并并保留映射数量、NO_MATCH/INVALID_RULE/INVALID_INPUT/LIMIT_REACHED 状态分离、SN2 翻转、守恒诊断、多候选规范排序、悬挂外部键、同位素强制。
- `EventStoreRecoveryTest`：哈希链重放、篡改隔离只读、尾部截断。
- `ApplicationIntegrationTest`：分析→锁定→确认→规则变更转 STALE→迁移漂移报告、乐观并发冲突、批量默认候选、组合失败显式落库且无半个结果、来源依赖检测、证据不可覆盖。

## 8. 目录结构

```
src/main/java/chem/molgraph/
  domain/      分子、规则、变换结果等不可变记录
  engine/      校验、子图枚举、重写、立体数学、规范指纹、同构归并
  store/       追加事件日志、重放投影、冲突异常
  service/     审核流转、迁移、批量、组合、ZIP 导出
  api/         REST 控制器与统一错误处理
  seed/        内置规则与案例（仅在空日志时播种）
src/main/resources/static/  单页前端（无构建步骤、无 CDN）
src/test/java/chem/molgraph/  JUnit 5 测试
```

## 9. 内置示例

- `KETO_ENOL 1.0`：对称羰基底物（两个等价 α-C–H），一次分析得到 1 个候选、映射数量 2，演示对称归并。
- `KETO_ENOL 2.0`：仅匹配 18O 羰基；对同一证据迁移时结论从 OK 变为 NO_MATCH，漂移报告给出 `DISAPPEARED`。
- `SN2_INVERSION 1.0`：手性氯代烷 → O⁻ 取代，四面体中心 UP→DOWN（页面紫色描边），报告元素差 `O=+1, Cl=-1` 与电荷差 -1。
- 乙醇案例演示 `NO_MATCH`；`BROKEN_RULE` 含未知元素，演示规则被拒绝入库（网页可粘贴后看到 `INVALID_RULE` 全套问题码）。
