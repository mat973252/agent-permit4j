# CGLIB 接入障碍复现（2026-10-03）

来源：[Spring AI #3485](https://github.com/spring-projects/spring-ai/issues/3485)，2025-06-09 报告代理工具发现失败；2026-10-03 GitHub API 仍为 open。它是公开场景线索，不是 AgentPermit4j 用户反馈，也不证明所有 Spring AI 版本都有同一缺陷。

维护者用公共 Maven Central 0.5.0、Spring AI 2.0.1 和 Spring `ProxyFactory` 复现：普通对象可注册，CGLIB class proxy 注册时报 `at least one public annotated tool is required`。未先安装本地 SDK；复现缓存与后续候选缓存分开保存。

原因：`GuardedToolMethods.fromAnnotated` 在生成代理类的方法上找注解，覆盖方法没有原始 `@Tool` 元数据。当前源码改为从 `ClassUtils.getUserClass(target)` 发现公开方法，仍把原代理传给实际调用器，没有提取裸 target 绕过 advice，也没有扩大到 bean 扫描或 JDK interface proxy 支持。

回归验证：使用真实 InMemoryApprovalService 与 InvocationFingerprinter，为捕获的规范化调用申请审批。未审批时 advice 和业务执行均为0；批准后通过代理执行，规范化参数与可信身份仍正确；同 key 重试返回相同结果。同审批换 orderId 返回 APPROVAL_INVOCATION_MISMATCH，换 key 返回 APPROVAL_ALREADY_CONSUMED；整个序列 advice 与实际业务均只执行1次。CGLIB 上 final 工具方法在注册时明确拒绝，普通对象的 final 方法仍可注册。拒绝回归先失败后通过，完整 Maven verify 通过。本轮修复尚未发布，公共0.5.0仍保留原行为；不能让用户误以为重新下载0.5.0即可得到修复。

另外两条公开线索暂不扩实现：[MCP ToolContext传输 #4773](https://github.com/spring-projects/spring-ai/issues/4773)属于跨传输上下文问题，传输到达不等于身份可信；[执行前审批 #6916](https://github.com/spring-projects/spring-ai/issues/6916)涉及框架批次/流式交互，现有单工具 guard 不能冒充整套 SSE 审批工作流。只修复本轮实际复现的一项接入障碍，独立开发者接入与重复使用仍未测量。
