# 写代码：11 个本地工具 + 跨平台命令 + 可选工作目录 + 九轮实测

给这个系统加上"真的能写代码"的能力，而不是"能给你一段代码"。

判据只有一条：**产物在磁盘上，`exitCode=0`**。回答看起来对不对不算数——模型念一遍代码说
"应该没问题"是最典型的幻觉，同一个任务跑一次编译就能戳破。

| | 加了工具之前 | 加了 11 个工具之后 |
| --- | --- | --- |
| 产出 | 聊天记录里的一段代码 | `src/LruCache.java` + `pom.xml` + 单测文件 |
| 验证 | "应该可以运行" | `mvn test` → exitCode=0，5 个用例全绿 |
| SQL | "这段 SQL 应该能跑" | 真的在库里执行过，结果查出来核对 |
| 跨平台 | 在 cmd 里写 `ls -l`，撞墙才知道不行 | 按平台给等价写法（`dir /b`），一次就过 |
| 改存量工程 | 只能看（只读目录），改不动 | **页面上选本机目录，直接读、改、编译**，改完回显 git 增删行数 |

---

## 一、11 个工具

`com.example.agentcore.tool.code`（文件/命令）+ `com.example.agentcore.tool.db`（数据库），全部本地。

| 工具 | 作用 | 少了它会怎样 |
| --- | --- | --- |
| `list_files` | 递归列目录，跳过 `.git`/`target`/`node_modules` | 凭空造目录，覆盖已有文件 |
| `read_file` | 读文本，带行号，可按区间取（默认 200 行，上限 800）；**`around=方法名` 一次拿到整个方法体**；回传 `sha256` | 凭记忆改代码，改出来全是幻觉 |
| `read_dir` | **批量读一个目录**：glob 过滤，一次返回多个文件的内容（默认 20 个 / 每个 120 行） | 读一个包要十几次 `read_file`，轮次全花在搬文件上 |
| `write_file` | 新建 / 整体覆盖，自动建父目录；**覆盖已有文件必须带 `sha256`** | 什么都写不出来 |
| `edit_file` | **精确局部替换**，锚点必须唯一；**必须带 `sha256`**，写后回显 git 变化 | 改一行要重写整个文件，长文件必被输出上限截断 |
| `search_code` | 正则搜代码，返回 `文件:行号:内容`；**一次 60 条，用 `offset` 分页** | 只能靠猜文件名逐个读，十几次调用全耗在这 |
| `run_command` | 编译 / 测试 / 运行，返回退出码与输出；**mvn/gradle/npm 默认给 300 秒** | 只会说"应该能跑"，没有任何验证能力 |
| `db_connections` | 列出可连的库（名字、类型、是否可写） | 不知道往哪连，或猜一个连不上 |
| `db_schema` | 表清单 / 表结构（列名、类型、主键、默认值） | 列名靠猜，DAO 写完跑起来才发现对不上 |
| `db_query` | 只读查询（SELECT/WITH/SHOW/DESC/EXPLAIN） | SQL 只能"看着像对"，没法验证 |
| `db_execute` | 建表 / 插入 / 更新 / 删除 | 造不出测试数据，写不了端到端验证 |

`edit_file` 的锚点设计要求"在文件里唯一出现"，重复和未命中分别报不同的错：

- 找不到 → 让它先 `read_file` 确认原文（缩进、空行、标点都要一致），并回显片段开头；
- 出现多次 → 让它多带几行上下文。

这两条是模型改代码时最常见的两种失败，报错文案写清楚，它下一次就能自己改对。

**这两类错误走 `RetryableToolError`，不进熔断。** 见第四节最后一条——踩过才知道：
把"锚点没对上"和"工具炸了"算进同一个计数器，两次就足够让 `edit_file` 被禁掉。

## 二、安全边界

能写文件、能跑命令，等于能删文件、能联网、能提权。三道界：

1. **目录**：工作目录由**用户在页面上选**（选中即授权），没选时用 `tool.code.workspace`
   （默认 `target/code-workspace`）。所有写路径必须落在某个可写根里，字面量 `../` 和
   "目录里放一个指向 `C:\` 的软链"两种越界都被拦。
   另有 `tool.code.read-only-dirs`（只读）与 `tool.code.writable-dirs`（额外可写）两组挂载。
   **系统目录不接受为工作目录**：盘符根、用户主目录本身、`C:\Windows`、`/etc` 等在
   `/api/fs/list` 里就直接标红，`/api/run` 收到也会 400。
2. **命令白名单**（不是黑名单）：只放 `javac/java/mvn/gradle/node/npm/python/git/ls/grep`
   这几类。黑名单永远漏（`cmd /c rd /s /q`、`powershell -enc`…），白名单漏了只是少个工具。
3. **拒绝清单**：白名单内的程序做破坏性的事也要拦——`rm -rf`、`git push`、`git reset --hard`、
   `--no-verify`、提权、读 `.ssh`。

被拒时返回的是**结果**（`ok=false, blocked=true`，附可用命令清单），不是 `ERROR`。
`ERROR` 会进熔断计数，三次就把工具禁掉；被拒是模型的错，不该连累工具。

同理，**命令失败（`exitCode != 0`）也不返回 `ERROR`**：编译报错是正常工作流，必须原样交给模型去修。

### 工作目录：从"钉死的沙箱"改成"用户自己选"

这是这一轮最大的改动，起因很直接：**需求都在真实仓库里，而工具只让在沙箱里写**。

原来的模型是"一个写死的可写根 + 若干只读根"，结果是遇到真实项目只有两条路——要么
只读分析完给一段建议代码（用户还得自己贴），要么把代码写进沙箱副本（用户还得自己合并）。
两条都不叫"处理了这个需求"。

现在：页面左栏点「工作目录」→ 弹层浏览本机目录（`GET /api/fs/list`，只列目录不读内容，
带 git / maven 标记）→ 选中后这次 run 的 `workspace` 参数带上去，选中的目录直接成为
可写根（`POST /api/run?...&workspace=D:/workspace/demo-project`）。

三条边界没有因为放开而消失：

- 模型碰不到 `workspace` 参数（它在 URL 里，由页面带上来），所以**模型没法给自己扩权**；
- 路径仍必须落在已授权根内，越界报
  `路径越界：… 不在任何已授权目录内（当前可写：[…]）`；
- 只读根仍只读，报的是"路径只读…要改就请用户在页面上把它选为工作目录"，
  不是笼统的越界——后者会让模型换个写法继续撞。

`tool.code.allow-select=false` 可以整套关掉（部署成多人共用时），此时 `workspace` 参数被忽略。

### 只读目录：读得到 ≠ 改得动

`read_dir` 回答的是"一次读完一组文件"，`tool.code.read-only-dirs` 回答的是"读哪个目录"。
两件事都得有，才能对真实工程干活：

- `read_file` / `read_dir` / `list_files` / `search_code` 走 `resolveRead`：工作目录或只读目录都行；
- `write_file` / `edit_file` 走 `resolve`：只认可写根。命中只读目录时报的是
  `路径只读：… 在只读目录 … 内，只能读不能写；要改就先复制到工作目录 … 再改（或在页面上把这个目录选为工作目录）`，
  而不是笼统的"越界"——后者会让模型以为路径写错了，然后换个写法继续撞。

只读目录的路径必须写**绝对路径**（相对路径只相对工作目录）。默认配置为空，即"什么外部目录都读不到"。

### Windows / Linux 分开处理

同一句命令在两个平台上不是一回事，所以平台是**一等参数**（`Platform`），不是散落的 `if`：

| | Windows | Linux / macOS |
| --- | --- | --- |
| 默认 shell | `cmd`（先 `chcp 65001`，否则中文报错乱码） | `sh`（有 bash 时用 `bash -lc`） |
| 看目录 | `dir /b` | `ls -l` |
| 看文件 | `type` | `cat` |
| 搜内容 | `findstr /s /n /c:"x" *` | `grep -rn "x" .` |
| 找程序 | `where` | `which` |
| 通配符 | **不展开**，`javac *.java` 会失败 | shell 展开，可直接用 |

三件事一起做：

1. **白名单按 shell 分三份**：通用（`mvn/javac/java/node/python/git`）、cmd 专有、POSIX 专有。
   跟着**本次实际用的 shell** 走而不是平台走——PowerShell 里 `ls`/`cat` 是别名，能跑；cmd 里不能。
2. **写错平台就翻译**：Windows 上写 `ls -l` 不是回一句"不允许"，而是
   `本平台的等价写法：dir /b（只看名字）或 dir（带大小和时间）`，还附一句"本机装了 bash，
   也可以传 `shell="bash"`"。模型下一轮就能走通，不用试三次。
3. **shell 可以显式指定**：`shell=auto|cmd|powershell|bash|sh`。指定了本机没有的（Linux 上要 `cmd`、
   没装 Git Bash 却要 `bash`）会在执行前拒掉，并告诉它默认 shell 是哪个。

顺带堵掉两个"首词白名单"挡不住的洞：`cmd /c ...`、`xargs rm`、`find ... -delete`、
`powershell -Command Remove-Item`——前者是套壳绕过，后三者是把白名单里的程序拼成删除动作。
所以 `cmd`/`powershell` 本身不在白名单里（要 PowerShell 语法就传 `shell` 参数），
`xargs`/`sed -i`/`find -delete`/`tee` 一并拒掉。

### 数据库的四条边界

1. **只能连登记过的库**：模型给的是连接名（`sandbox` / `dm8` …），不是 jdbc url，拼不出任意地址；
2. **写操作分档**：内置沙箱永远可写（它就是用来造的），真实库要 `tool.db.allow-write=true`；
3. **查询只读**：`db_query` 只放行 SELECT/WITH/SHOW/DESC/EXPLAIN，且只允许一条语句
   （字符串字面量里的 `;` 不会被误判成第二条语句）；
4. **H2 的文件后门点名禁掉**：`RUNSCRIPT`、`ATTACH`、`CSVREAD`、`CREATE ALIAS`、`LINK_SCHEMA`
   这类 SQL 能读写本地文件、甚至执行 Java 代码，等于绕过整套命令白名单。

密码永远不出现在工具返回值里（URL 里的 `password=`/`user=` 也打码），
只用于建立连接。

## 三、六轮任务 + 两项专项验证（DeepSeek `deepseek-chat`，自动路由）

### 1. 从零写：LRU 缓存 + 自测

```
36.7s · 16 次工具调用 · 2 个文件 · 最终 PASS
list_files → write_file ×2 → javac → java(FAIL) → edit_file → 再编译 → read_file → edit_file → … → PASS
```

编译一次就过，**失败的是自测用例本身**（模型把"容量 3 插入 a,b,c,d 淘汰谁"的期望写反了）。
它没有改实现去迁就测试，而是两次修正期望值——这是想要的行为：实现是对的，测试错了就该改测试，
并且最终回答里明确写了"缓存实现未改动，只修正了测试期望"。

> 这一轮还暴露了一个真 bug，见第四节。

### 2. 从零写：Maven 工程 + JUnit

```
28.0s · 12 次工具调用 · 5 个文件 · mvn test 5 个用例全绿
list_files → java/mvn 版本探测 → write_file ×4（pom/LruCache/Main/Test）→ mvn test(编译失败) → edit_file ×2（改 pom）→ mvn test(PASS) → java demo.Main
```

模型自己决定"这不是单个文件，是个工程"，于是写了 `pom.xml`、按 `src/main/java` 布局、加 JUnit 依赖。
中途撞上 `-source 8 中不支持 '<>' 具有匿名内部类`，它读懂了报错、去改 `pom.xml` 的编译插件配置，
而不是去改源码绕过语言特性——一次定位，一次修好。

### 3. 修已有代码的 bug（最贴近日常）

预置一个 bug：`average()` 的分母写成了 `values.length + 1`，`Main` 输出 FAIL。

```
6.6s · 5 次工具调用 · 改 1 行 · 输出 PASS
list_files → read_file(Calc) → read_file(Main) → edit_file(1 处) → javac && java → PASS
```

`edit_file` 的锚点就是那一行，`Main.java` 一个字没动（提示词要求"只改实现"），
回答里给了根因（分母多加了 1，100/5=20 而不是 25）、跑过的命令、以及"编译产物在 out/，我没删"。

### 4. 数据库工具链：建表 → 插数据 → 分组统计

```
8.9s · 6 次调用 · 0 个文件
db_connections → db_schema(列表，空库) → db_execute(CREATE TABLE) →
db_execute(INSERT 5 行) → db_query(SELECT * 核对) → db_query(GROUP BY 统计)
```

任务：*在沙箱数据库里建一张 employee 表，插入 5 条中文姓名数据，按部门统计平均工资。*

结果：研发部 19750.00、市场部 13500.00、财务部 13500.00——手算一致。
路径上每一步都真的落到 H2 文件里（`target/code-workspace/.db/sandbox.mv.db`，24 KB），
不是把 SQL 写成回复。

### 5. 端到端：代码连同一个库

```
10.8s · 8 次调用 · 2 个文件 + PASS
list_files → db_connections → db_schema → dir → db_query ×2 → write_file → javac+java
```

`EmployeeAvg.java` 用 JDBC 连**同一个**沙箱文件库（`AUTO_SERVER=TRUE`，所以另一个进程也能连），
查出研发部平均工资、和 19750 比对，输出 `PASS`。产物我独立复核过：
手动 `javac -cp h2.jar` + `java -cp .;h2.jar` 也是 `PASS`。

这一步是数据库工具真正的价值：**代码里的 SQL 就是你刚验过的那条**，表名列名不是猜的。

### 6. 真实库：本机达梦 DM8

```
9.6s · 5 次调用 · 137 张表
db_connections(看到 sandbox 可写、dm8 只读) → db_schema(列表) → db_schema(表结构) → db_query ×2
```

连上 `jdbc:dm://127.0.0.1:5236`（驱动 `DmJdbcDriver18`，从 classpath 动态加载），
列出 `BUREAU_NEW` / `FXYP` / `HIFCCV` 三个业务 schema 共 137 个对象，挑了
`FXYP.TB_BASE_STATION`（64 行）取前 5 行：

| ID | STATION_NAME | STATION_CODE | LONGITUDE | LATITUDE |
| --- | --- | --- | --- | --- |
| 0A | 广西101 | GX101 | 108E13 | 22N49 |
| 0B | 甘肃合作台 | GSHZ | 102E57 | 34N58 |

顺带发现列的真实类型（经纬度是 `VARCHAR2(8)`，形如 `108E13`，不是数值列）——
这种细节正是"凭常识写 DAO"最容易错的地方。

### 7. 跨平台：写错平台时它拿到了什么

```
第 1 次 run_command("ls -l") →
{"ok":false,"blocked":true,"reason":"\"ls\" 是另一个平台的命令，在当前环境（Windows / cmd）里不可用。
 本平台的等价写法：dir /b（只看名字）或 dir（带大小和时间）",
 "suggestion":"…；本机装了 bash，也可以保留原命令并传 shell=\"bash\""}

第 2 次 run_command("dir") →
{"ok":true,"platform":"Windows","shell":"cmd","executed":"cmd /c chcp 65001>nul & dir","exitCode":0}
```

`executed` 字段把真正下发的命令行也带回去，排查"到底是哪个 shell 跑的"不用猜。

### 8. 只读目录：一次读 3 个真实文档，写一个字都进不去

只读目录配成 `D:/workspace/demo-project/docs`（96 个 `.md`），任务：读前 3 个并各概括一句，
然后往该目录写 `probe.txt`。

```
read_dir {"dir":"D:/workspace/demo-project/docs","glob":"*.md","maxFiles":3,"maxLinesPerFile":40}
← {"ok":true,"count":3,"candidates":100,"notRead":97,
   "hint":"还有 97 个匹配文件没读：用 glob 缩小范围、换子目录，或调大 maxFiles",
   "files":[{"path":"D:/workspace/demo-project/docs/admin-visits.md","lines":12,"totalLines":12,
             "truncated":false,"cutBy":"none","content":"   1| # 管理后台访客统计 …"}]}   (81ms)

write_file {"path":"D:/workspace/demo-project/docs/probe.txt","content":"probe"}
← ERROR: 路径只读：D:/workspace/demo-project/docs/probe.txt 在只读目录 … 内，只能读不能写
```

一次调用读完 3 个文件（逐个 `read_file` 要 3 次调用、3 次往返），越界写被拒且**文件没被碰过**。
`notRead` 和 `hint` 是刻意加的：不给这个数字，模型会以为"这个目录只有 3 个文件"。

## 四、实测暴露、并且已经修掉的三个问题

### 1. "改完再跑一遍"被当成重复调用挡掉

`ChatAgent` 里有一层同参重复调用拦截（原本为本地 7B 反复查同一个景点坐标而加）。
编码场景里最正常的节奏恰好是 **编译 → 报错 → 改代码 → 再编译**，第二次编译的命令**一个字都没变**，
于是被拦。模型为了绕过，给命令加了个假参数（`java -cp out Main run1`），白烧 6 次调用，
最终回答里还写了一串"工具拒绝了重复调用"。

修法：给状态记版本号。工具可以声明哪些方法**有副作用**（`MutatingTool`），
写文件/改文件成功后版本号加一；指纹里记着当时的版本号，版本变过就放行。

- 期间没改动 → 同参调用照拦（这是拦截的本意）；
- 改过文件 → 同参调用放行（结果真的会变）。

反过来，`write_file` 自己也算改动，但指纹按**新**版本号记，"同样的内容连写两次"第二遍仍被拦。

### 2. 点名到源码文件的修复请求，被路由去"讨论怎么修"

```
src/Calc.java 里的 average 算错了……改到输出 PASS
→ refine（架构师起草 + 评审人追问）· 0 次工具调用 · 一个字都没改到磁盘
```

原因：这句话只命中一个 `实现`，词表把它当成了技术议题。补了两条：

- **源码文件名本身是强信号**（`.java/.py/.ts/.go/...`）。用户已经指着文件说"改这里"，比任何关键词都硬；
- 词汇补 `修复/修好/改到/跑不起来/编译不过`。

修完之后同一句话落到 `coding`，5 次调用改完并跑通。

### 3. 数据库任务被判成"讨论方案"，0 次工具调用

```
在沙箱数据库里建一张 employee 表，插入 5 条数据，按部门统计平均工资
→ refine（架构师起草 + 评审人追问，2.3 秒）· 0 次工具调用
→ 回复里给了一段 SQL 和一句"你尚未实际执行"
```

两个原因叠在一起：

- `CODE_STRONG` 里全是"写一个/编译/单测"这类**代码**词，数据库动作（建表、插数据、查出来）
  一个都没有，`codeHits` 为空，`isCoding` 直接返回 false；
- 更隐蔽的一个：`normalize` 会把**所有空白删掉**（`CREATE TABLE` → `createtable`），
  所以词表里写 `create table`、`insert into`、`跑 sql` 这种带空格的词**永远匹配不上**。

修法：强信号词补 `建表/建一张/创建表/插数据/插入数据/查出来/create table/insert into/数据库工具`；
`isCoding` 里让强信号**不要求先命中代码域词**（只要不输给旅游域）；`hits` 匹配时把词也压一遍空白。

顺带加了一条反向用例：`这个订单表的索引怎么设计` 仍要走 `refine`——它要的是一句判断，
不是让 agent 去建表。强信号只放"动词"，不放"表结构/索引"这类名词。

## 五、配置

```properties
# 工作目录
tool.code.workspace=target/code-workspace      # 默认工作目录；页面上选了就以页面为准
tool.code.allow-select=true                    # 允许在页面上选本机目录（关掉后 workspace 参数被忽略）
tool.code.read-only-dirs=                      # 额外能读、不能写的目录（逗号分隔，绝对路径）
tool.code.writable-dirs=                       # 额外可写目录（一次要改两个仓库时用）
tool.code.write-protect=true                   # 写已有文件必须带 sha256（先 read_file 拿）
tool.code.command-timeout-seconds=120          # 单条命令默认超时；mvn/gradle/npm/node 固定 300

# 数据库
tool.db.allow-write=false                      # 真实库默认只读；沙箱不受这个开关限制
tool.db.max-rows=50                            # 单次查询返回行数上限（模型可下调，最多 200）
tool.db.query-timeout-seconds=20
tool.db.connections=dm8                        # 登记过的真实库（逗号分隔）
tool.db.dm8.url=jdbc:dm://127.0.0.1:5236
tool.db.dm8.username=SYSDBA
tool.db.dm8.password=                           # 建议用 -D 传入，不落盘
tool.db.dm8.driver=dm.jdbc.driver.DmDriver      # 中央仓库没有的驱动（达梦）必须显式写
```

命令白名单在 `Platform` 里按 shell 分三份，**不开放配置**——放开配置等于放开删文件的能力。
平台自动识别（`os.name`），调试时可用 `-Dtool.code.platform=linux` 模拟另一套组装。

## 六、已知限制

- **没有回滚**：工具只会写，不会撤销。要它改真实项目，请指向一份拷贝，别指向正在开发的目录。
- **不是 OS 级隔离**：靠路径检查 + 命令白名单，不是容器/seccomp。能防住"模型手滑"，
  防不住"模型刻意绕过"——所以目录一定要选一个删了不心疼的地方。
- **Windows 下 shell 是 cmd**，不展开 `*` 通配符：`javac src/*.java` 会失败（提示词已说明，
  模型实测撞过一次后改成逐个列出文件）。要 POSIX 语法就传 `shell="bash"`，前提是本机装了 Git Bash。
- **单 agent、无并行**：一次只跑一个编码任务；多文件大改（改十几个文件的重构）会因为
  **40 次**工具调用上限而截断，这类活目前得人来拆。
- **不装依赖**：`mvn` 第一次跑要联网下依赖，离线环境下只能直接 `javac`。
- **只读目录没有"写入例外"**：只读就是只读。要原位改一个仓库，唯一的路径是**用户在页面上
  把它选为工作目录**（选择即授权），工具不提供"模型自己把一个目录改成可写"的开关——
  那等于把可写范围交回给模型。命令行里对应的做法是给 `run-coding.py` 传绝对路径。
- **写保护不是防篡改**：`sha256` 只保证"改的是它读过的那一版"，挡不住 `run_command` 里
  `mvn`/`java` 执行任意字节码，也挡不住 `git checkout -- .`。回滚靠 git，不靠工具。
- **`read_dir` 会吃上下文**：默认 20 个文件 × 120 行 ≈ 6 万字符，已经接近一次请求的预算上限。
  读大目录时它只回前几个 + `notRead` 提示，剩下的要靠 glob 分批。
- **数据库没有事务边界**：`db_execute` 一条语句一提交，失败不回滚；真实库的唯一保护是
  "默认只读 + allow-write 开关"，不是事务。
- **沙箱是单机文件库**：`AUTO_SERVER=TRUE` 让同机进程共享，跨机器不行；数据随工作目录删。
- **驱动要自己备**：H2 / MySQL / PostgreSQL 已声明为依赖，达梦、Oracle、SQL Server 的 jar
  需要手动放进 classpath（`target/lib` 或 `-cp`），否则会返回"找不到 JDBC 驱动"。

## 七、复现

```bash
# 后端（DeepSeek）
MODEL_PROVIDER=deepseek MODEL_API_KEY=sk-xxx mvn -q -o exec:java

# 自动路由 + 从零写
python tools/run-coding.py auto
# 自动路由 + 修 bug（先按上面第 3 节预置 src/Calc.java 与 src/Main.java）
python tools/run-coding.py "src/Calc.java 里的 average 算错了……改到输出 PASS" auto
# 直接把本地目录当工作目录（可写），改真实仓库——建议先用副本
CODE_WORKSPACE=D:/workspace/_tmp_agent_ws/demo_probe \
  python tools/run-coding.py "给 until() 补 javadoc，只改这一个方法" auto
# 只读分析一个大文件里的某个方法（around 按符号定位）
python tools/run-coding.py "用 read_file around=applyUnqualifiedPercentForToday 读 D:/workspace/demo-project/**/TransCalculateService.java，解释分母口径" auto
# 数据库工具链
python tools/run-coding.py "在沙箱数据库里建一张 employee 表，插入 5 条数据，按部门统计平均工资" auto
# 批量读真实目录（要先用 -Dtool.code.read-only-dirs 或 agent.properties 挂上）
python tools/run-coding.py "读 D:/workspace/xxx/docs 下所有 *.md（maxFiles=3），各概括一句" auto
# 真实库（需要驱动在 classpath，且 agent.properties 里登记了连接）
java -cp "target/classes;target/lib/*;D:/path/DmJdbcDriver18.jar" \
     -Dtool.db.connections=dm8 -Dtool.db.dm8.url=jdbc:dm://127.0.0.1:5236 \
     -Dtool.db.dm8.username=SYSDBA -Dtool.db.dm8.password=*** com.example.agentcore.Main
```

测试：`mvn -o test` → **145/145**（`CodeToolsTest` 21 条、`CodeWorkspaceRootsTest` 6 条、
`CodeFilesToolGitTest` 1 条、`RetryableToolFailureTest` 1 条、`ToolRegistrySubsetTest` 4 条、
`CodeCommandToolPlatformTest` 7 条、`PlatformTest` 6 条、`DbToolTest` 8 条、
`MutatingToolRetryTest` 2 条、编码/数据库路由 6 条，其余是原有场景）。

---

## 八、能改真实工程了（第三轮：工作目录可选 + 可控写入 + 容量）

### 8.1 上一轮为什么"处理不了这个需求"

拿 demo-project（688 个 Java、137 个 mapper XML、6.8 MB 源码，最大单文件 4536 行 / 单方法 558 行）
去试，它一个字都改不了。四条墙，按撞上的先后：

| # | 墙 | 表现 |
| --- | --- | --- |
| 1 | 配置 | `read-only-dirs` 默认空 → 第一句 `read_file D:/workspace/demo-project/...` 就报"路径越界" |
| 2 | 产品定位 | 只读根不能写 → `edit_file` 永远报"要改就先复制到工作目录"。**工具只支持"沙箱从零写"，没有"改存量仓库"的通道** |
| 3 | 容量 | 30 次工具配额 + `read_file` 800 行/次；一个 4119 行的 Service 读两段就没了 |
| 4 | 验证 | `exitCode=0` 是唯一硬信号，而真实工程零 JUnit、6 个数据源全指内网，编译一次 61 秒 |

第 2 条是根因：不是"哪里没配好"，是这套工具的权限模型里根本没有这条路。

### 8.2 三个改动

**① 工作目录由用户选**（`CodeWorkspace` 从"单根 + 只读"改成"多根，每个根可写或只读"）

- `GET /api/fs/list?path=` 列本机目录（只列目录、不读内容、带 git / maven 标记、系统目录标红）；
- 页面左栏「工作目录」→ 弹层浏览 → 选中后存 localStorage；
- `POST /api/run?...&workspace=D:/workspace/demo-project` → 选中的目录成为**主根（可写）**，
  `list_files` / `read_dir` / `search_code` 不带参数就默认从它开始；
- 提示词里那句"工作目录是 X"跟着选择走——否则用户选了 demo-project，模型还以为自己在默认沙箱里。

**② 可控写入**（不是"放开写"，是"可追溯地写"）

- `read_file` 回传 `sha256`；`edit_file` 和"覆盖已有文件"的 `write_file` **必须带回来**，
  否则拒绝并告诉它"先 read_file 拿哈希"。文件在"读完之后被别人改过"会被当场拦下。
- 写后自动回显 git：`{"repo":..., "status":"M src/...", "added":"23", "deleted":"1"}`，
  模型第一次能看到"我以为改了一行，其实删了 300 行"。
- 提示词要求开工先跑 `git status --short`：有别人的在途改动就先说明，别混在一起。

**③ 容量**（让"读一个真实文件"变得可行）

- `read_file` 新增 `around=方法名`：按符号定位，大括号配对到方法结尾，**同名重载一并给出**
  （实测 `circuitQuality` 有两个重载，只给第一个会让模型找错地方）；按符号读不再谎报 `truncated`。
- `search_code` 新增 `offset` 分页：一次 60 条，返回 `nextOffset`，不用靠换关键词绕上限（会漏）。
- `run_command` 超时按程序分类：`mvn/gradle/npm/node` 默认 300 秒，其余 120。
- `maxToolIterations` 30 → 40（改存量工程时读文件占大头）。

另外把**参数级失败**从 `ERROR` 里拆出来（`RetryableToolError` → `{"ok":false,"retryable":true}`）：
锚点没对上、sha256 过期，这类是模型自己能修好的，不该进熔断计数。

### 8.3 实测（DeepSeek，工作目录 = demo-project 的副本仓库）

| 轮次 | 任务 | 耗时 | 工具调用 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | 只读分析：`around=applyUnqualifiedPercentForToday` 并解释分母口径 | 10.3 s | 3 | 一次命中方法体（95–97 行），13 处引用全部核对，结论与代码一致 |
| 2 | 修 bug：`circuitQuality` 的 `until()` 同日得 0 天 | 39.4 s | 12 | 改成 `between()+1`；`mvn -o -q compile` exitCode=0（22.2 s）；`git diff --stat` 2+/1- |
| 3 | 补 javadoc（**CRLF 修复前**） | 95.1 s | 22 | `edit_file` 连续 2 次锚点失配 → 熔断 → 改用 python 脚本按字节改，留了 2 个删不掉的临时文件 |
| 4 | 同一个 javadoc 任务（**修复后**） | 38.1 s | 7 | `edit_file` 一次成功；`git.added=23 / deleted=1` 与 `git diff --stat` 完全一致；无残留文件 |

第 3、4 轮是同一任务的对照：**95.1 s / 22 次 → 38.1 s / 7 次**。

第 4 轮模型还主动报告了一处它没动的东西："第 32 行仍是 `until()`，没有 +1，注释描述的是预期口径，
两者目前不一致；那是逻辑改动，没有你的确认我没有动。"——这正是"改存量工程"该有的行为。

> 实测全程在 `D:/workspace/_tmp_agent_ws/demo_probe`（`git init` 后的副本）里做，
> 原仓库 `demo-project` 的 `git status` 与开工前完全一致，一个字没动。

### 8.4 这一轮踩的坑

1. **`git -C <文件所在目录> diff -- <仓库相对路径>` 会拼出重复路径**，git 吐
   `could not open directory` 但**退出码仍是 0**，于是工具把警告当成 `status` 回给了模型。
   `-C` 必须是仓库根。
2. **Windows 的 `autocrlf` 警告走 stderr**，合并进 stdout 后把 `1\t1\tA.java` 顶到第二行，
   `--numstat` 解析出空值。读 git 输出必须过滤 `warning:` 行。
3. **`edit_file` 对 CRLF 完全不宽容**：文件是 CRLF、模型用 `\n` 拼锚点，逐字符 `indexOf` 必然失败。
   这个失败率在 Windows 项目上接近 100%，而它是"改存量工程"的第一个动作。修法：匹配前把锚点的
   换行符调成文件风格，写回时也保持一致（否则 git diff 整段变色）。
4. **锚点失配把工具熔断掉了**：连续 2 次 `old_text` 对不上 → `edit_file` 被禁 → 模型转而去写
   python 脚本改文件，还留下两个 `del` 不掉的临时脚本。参数错 ≠ 工具坏，必须分开。
5. **弹层 HTML 放在 `<script>` 之后 → `$('wsModal')` 是 null**，`init()` 在第一行就抛错，
   整页什么都不渲染（连场景卡片都没有）。DOM 里被脚本绑定的元素必须排在脚本前面。
6. **`around` 只返回第一个重载**会让模型改错方法：`circuitQuality` 有两个重载，第一个只是转发。
   现在按声明顺序收集所有重载并合并区间。
7. **`around` 复用了 `truncated` 字段**：文件后面还有行也报 `truncated: true`，模型以为方法体不全，
   又补读一次全文。按符号读时"截断"只应指"这个方法体超过 800 行"。

---

## 九、分析类问题为什么读不到文件（第四轮：只读子集）

### 9.1 现象：最难看的回答不是改错代码

用户已经在页面上选好了 `D:/workspace/demo-project`，然后问：

> **分析一下这个项目**

回答是：

> 你没有贴出项目内容——代码、README、目录结构、需求文档，什么都没有。我无法分析一个看不见的东西。
> ……**如果是我读本地路径**：我这次没有文件系统和工具权限，读不到，需要你把内容贴进来。

一个工具都没调。用户把目录交出来了，模型说"我没有权限"。

### 9.2 三重原因，缺一不成立

| # | 位置 | 实际发生了什么 |
| --- | --- | --- |
| 1 | 路由 | "分析一下这个项目"不含任何技术域词（`项目` 不在词表里），`decide()` 判成 `general` |
| 2 | 场景 | `general` 的 `toolset` 是 `none`——它一个工具都没有，不是"有但被拒" |
| 3 | 提示词 | `general` 的 system 里写着"你这次没有联网、没有地图、**没有工具**"，模型只是照着念 |

第 2 条是根因，但只修第 2 条不够：`review`（需求评审）、`refine`（方案打磨）同样不挂工具，
用户问"评审一下这个项目的架构"会撞同一堵墙。所以这次改的是**规则**，不是一处配置。

### 9.3 三个改动

**① 工具裁到方法级**（`ToolRegistry.subset` + `ChatAgent.Builder.toolRegistry`）

```java
ToolRegistry.of(new CodeFilesTool(workspace, false))
            .subset("list_files", "read_dir", "read_file", "search_code");
```

关键是**写工具不进 schema**。挂上去再拒绝没用——模型看见 `write_file` 就会去写，
被拒一次还会换个写法再试，把轮次耗在注定失败的动作上。schema 就是能力边界。

**② 能力按"这一轮有没有选目录"叠加**，不写死在场景里

`general` / `review` / `refine` / `pick` 的 `toolset` 仍然是 `none`（`/api/scenarios` 里
`tools` 依旧为 `false`）；用户在页面上选了目录时，`AgentWebServer.buildTeam` 才给这些成员：
挂 4 个只读工具、追加只读提示词、`maxToolIterations = 20`（读一个真实项目十几个文件是常态）。
没选目录时走另一条分支：**不挂工具，但把话说准**。

**③ 提示词分清"没权限"和"没指定"**

- 选了目录 → `CodePrompt.readOnlyHint()`：四个工具怎么用、分析顺序（先构建文件再源码）、
  每条结论必须指到文件行号、没读过的文件不许写进结论；
- 没选目录 → `CodePrompt.noWorkspaceHint()`：**"不要说自己没有文件系统权限——你有工具，
  只是这一轮用户还没告诉你看哪里"**，并指到左栏「工作目录」。

### 9.4 实测（同一句话、同一个目录）

| | 修复前 | 修复后 |
| --- | --- | --- |
| 路由 | general | general（没变） |
| 工具数 | 0 | 4（只读） |
| 工具调用 | 0 次 | **17 次**：`list_files`×6、`read_file`×5、`read_dir`×2、`search_code`×2 … |
| 耗时 | 2.4 s | 29.2 s |
| 回答 | "请补充材料" | 模块划分、7 个数据源↔Mapper 包对应表、巨型类实测行数与最大方法区间、
"`mysql2` 实际连的是达梦"、"`testController` 会被 Swagger 扫到"、硬编码 `E:/picture/` 等 |

第 17 次调用是被"同参重复调用拦截"挡掉的（它想再 `list_files .` 一遍），提示"结果不会变"，
于是直接收尾——这条老机制在只读场景里正好用得上。

回答末尾它自己写了："以上全部来自静态阅读，我**没有编译也没有运行**，所以不下'能正常工作'的结论"，
并主动标出"`plyc` 没有 `@MapperScan`，但我不排除它在别处被 `JdbcTemplate` 用掉"——这是想要的边界感。

### 9.5 这一轮踩的坑

1. **`toolset` 不该为"运行时能力"新增枚举值**。第一版给 `general` 加了 `readonly`，结果
   `/api/scenarios` 把它报成"这个场景有工具"，而实际上没选目录时它一个都没有——同一个字段
   既被当"场景固有属性"又被当"本次运行的能力"，页面和测试都会说错话。改成运行时叠加后，
   `toolset=none` 的含义重新变得干净。
2. **只补 `general` 会漏掉 `review`**。"评审这个项目的架构"命中技术域词，走的是三角色评审，
   照样答"我看不到"。所以判断条件写成"所有本来没工具的成员"，而不是"id 等于 general"。
3. **页面文案会反向误导**。左栏原来写"默认沙箱（由服务端配置）"，用户没选目录时会以为
   "已经有目录了"，然后收到一句"看不到你的项目"。现在写"未指定（点这里选本机目录）"。

测试：`ToolRegistrySubsetTest` 4 条（schema 里只剩读方法 / 子集外的名字调不动 / 子集仍能读 /
未知名忽略）、`WebApiTest` 3 条（general+目录拿到 4 读工具且无写工具、无目录时改问目录、
review 场景同样拿到只读工具）。全量 `mvn -o test` → **145/145**。

页面证据见 `docs/console-analysis-readonly.png`（DeepSeek 限流期间用本机
`ollama · qwen2.5:7b` 复跑同一句话截的，所以次数是 22 不是上表的 17——路由、只读工具集、
"零写工具"的结论一致；本地 7b 模型跑了约 6 分钟，回答 1049 字）。
