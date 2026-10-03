# CGLIB 接入障碍复现（2026-10-03）

来源：[Spring AI #3485](https://github.com/spring-projects/spring-ai/issues/3485)，2025-06-09 报告代理工具发现失败；2026-10-03 GitHub API 仍为 open。它是公开场景线索，不是 AgentPermit4j 用户反馈，也不证明所有 Spring AI 版本都有同一缺陷。

维护者用公共 Maven Central 0.5.0、Spring AI 2.0.1 和 Spring `ProxyFactory` 复现：普通对象可注册，CGLIB class proxy 注册时报 `at least one public annotated tool is required`。未先安装本地 SDK；复现缓存与后续候选缓存分开保存。

原因：`GuardedToolMethods.fromAnnotated` 在生成代理类的方法上找注解，覆盖方法没有原始 `@Tool` 元数据。当前源码改为从 `ClassUtils.getUserClass(target)` 发现公开方法，仍把原代理传给实际调用器，没有提取裸 target 绕过 advice，也没有扩大到 bean 扫描或 JDK interface proxy 支持。

回归验证：使用真实 InMemoryApprovalService 与 InvocationFingerprinter，为捕获的规范化调用申请审批。未审批时 advice 和业务执行均为0；批准后通过代理执行，规范化参数与可信身份仍正确；同 key 重试返回相同结果。同审批换 orderId 返回 APPROVAL_INVOCATION_MISMATCH，换 key 返回 APPROVAL_ALREADY_CONSUMED；整个序列 advice 与实际业务均只执行1次。CGLIB 上 final 工具方法在注册时明确拒绝，普通对象的 final 方法仍可注册。拒绝回归先失败后通过，完整 Maven verify 通过。本轮修复尚未发布，公共0.5.0仍保留原行为；不能让用户误以为重新下载0.5.0即可得到修复。

另外两条公开线索暂不扩实现：[MCP ToolContext传输 #4773](https://github.com/spring-projects/spring-ai/issues/4773)属于跨传输上下文问题，传输到达不等于身份可信；[执行前审批 #6916](https://github.com/spring-projects/spring-ai/issues/6916)涉及框架批次/流式交互，现有单工具 guard 不能冒充整套 SSE 审批工作流。只修复本轮实际复现的一项接入障碍，独立开发者接入与重复使用仍未测量。

## 从未发布候选复现

修复基线为本地提交 `c113515bad05919aab18b1da496cfac86a42bad9`，尚未推送。先取得维护者提供的包含此提交的源码副本；不能假设公共仓库已包含它。在副本根目录用PowerShell执行：

```powershell
$candidateCache = Join-Path ([IO.Path]::GetTempPath()) ('agentpermit-candidate-' + [guid]::NewGuid().ToString('N'))
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=$candidateCache" -pl agent-permit-spring-ai -am '-Dtest=GuardedToolMethodsAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
if ($LASTEXITCODE -ne 0) { throw 'Proxy acceptance failed' }
.\scripts\verify-adoption.ps1 -MavenRepository $candidateCache
```

上述脚本验收的是随附库存示例。接入自己的独立工程时，保留同一个候选缓存，并从候选源码根目录调用 wrapper；将下面路径替换为你的实际 POM 绝对路径：

```powershell
.\mvnw.cmd -B -ntp "-Dmaven.repo.local=$candidateCache" -f 'D:/your-project/pom.xml' verify
if ($LASTEXITCODE -ne 0) { throw 'Application integration failed' }
```

自己的工程通过 Maven 坐标引用候选，不复制 SDK 源码到业务工程。每次构建都显式传入此缓存；遗漏参数会回到默认缓存，不能据此认定测试了候选。若要比较公开 0.5.0，另建全新 `$publicCache`，仅执行消费者 `verify`，不在其中安装候选；具体命令见[独立消费示例](../../examples/spring-ai-adoption/README.md)。

Git检出可另执行`git rev-parse HEAD`；无`.git`的源码ZIP无需执行它，改用`Get-FileHash source.zip -Algorithm SHA256`核对维护者提供的“源码提交↔ZIP SHA256”映射。ZIP哈希本身不能证明提交身份，制品SHA256SUMS也不能代替源码身份；没有可信映射则记录来源未核验。进入展开后的项目根目录再运行上述命令。

首段执行4项方法/代理验收，之后复用候选脚本构建41个POM/JAR/source/Javadoc文件、SHA256清单，并在独立消费者目录online/offline验证库存示例。需要Java21与首次下载第三方依赖的网络；脚本不向Central发布，不配置签名。不同源码副本应使用不同的新缓存。

坐标仍是0.5.0，仅代表本地候选，不能混入默认`.m2`或公共制品复现缓存，也不能把这些JAR当作Central下载物。源码SHA、SHA256SUMS和消费者输出应共同留存。只有独立开发者实际完成接入才填写采用记录；维护者执行上述命令仍是工程验收。正式版本命名、真实Redis验收、签名和公共消费步骤继续遵循[发布说明](../releasing.md)，不由本说明自动触发。
