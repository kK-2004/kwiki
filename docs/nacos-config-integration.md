# Nacos 配置中心接入说明

kwiki 以 **`spring.config.import` 方式**（Spring Boot 2.4+ 现代接入，无 `bootstrap.yml`）接入
Nacos 配置中心。仅使用配置中心能力，不引入服务注册发现（单体应用无此需求）。

## 版本组合

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 3.5.6 | 项目现状 |
| Spring Cloud Alibaba | 2025.0.0.0 | 2025.0.x 线匹配 Boot 3.5.x / Spring Cloud 2025.0.x；由 `spring-cloud-alibaba-dependencies` BOM 统一托管。**不要用 2025.1.x 线**——它面向 Spring Boot 4，与本项目不兼容 |
| Spring Cloud（commons/context） | 4.3.0 | 随 SCA starter 传递引入，仅服务配置导入 |
| nacos-client | 3.0.3 | 随 SCA BOM 传递，勿手工指定版本 |
| Nacos Server | 2.x / 3.x | 运维方提供；kwiki 自身不创建任何中间件容器 |

## 应用侧配置（已完成）

`src/main/resources/application.yml`：

```yaml
spring:
  config:
    import:
      - optional:nacos:${spring.application.name}.yaml   # Data ID: kwiki.yaml
  cloud:
    nacos:
      config:
        server-addr: ${KWIKI_NACOS_SERVER_ADDR:127.0.0.1:8848}
        file-extension: yaml
        group: ${KWIKI_NACOS_GROUP:DEFAULT_GROUP}
        namespace: ${KWIKI_NACOS_NAMESPACE:}
        username: ${KWIKI_NACOS_USERNAME:}
        password: ${KWIKI_NACOS_PASSWORD:}
        refresh-enabled: true
```

连接与认证参数一律来自 `KWIKI_NACOS_*` 环境变量（见 `.env.example`），
配置文件不落任何凭据。

### optional 前缀的语义

- **Nacos 不可达 / Data ID 不存在**：导入被跳过（记一条 ERROR 日志），应用照常启动——
  本地开发与无 Nacos 的部署不受影响。
- **完全不使用 Nacos 的环境**：导出 `SPRING_CLOUD_NACOS_CONFIG_ENABLED=false`，
  导入整体不参与解析，不会发起任何连接。
- **测试**：`src/test/resources/application.properties` 已统一置
  `spring.cloud.nacos.config.enabled=false`，所有测试上下文保持离线。

## 配置优先级

从高到低：

1. 命令行参数、JVM 系统属性、OS 环境变量（`KWIKI_*` 显式注入仍最高优）
2. Nacos 远程配置（Data ID `kwiki.yaml`）
3. 本地 `application.yml` / `application-local.yml` 默认值

即：Nacos 远程配置可以覆盖 `application.yml` 中的默认值，但**不能**覆盖
运维显式导出的环境变量。

## Nacos 控制台发布配置（运维步骤）

1. 启动并打开 Nacos 控制台：`http://<server-addr>/nacos`（默认 `nacos`/`nacos` 登录）。
2. **配置管理 → 配置列表 → + 新建配置**：
   - **Data ID**：`kwiki.yaml`（须与 `spring.application.name` + `.yaml` 一致）
   - **Group**：`DEFAULT_GROUP`
   - **配置格式**：YAML
   - **配置内容**：粘贴需要覆盖的配置片段（无需全量复制 application.yml）
3. 点击 **发布**。

多环境隔离使用 **命名空间**：在「命名空间」页签新建后，把命名空间
**ID（UUID）**——不是显示名——填入 `KWIKI_NACOS_NAMESPACE`。

远程配置示例（覆盖示例，按需裁剪）：

```yaml
kwiki:
  graph:
    enabled: true
  index:
    management:
      mutations-enabled: false
```

## 动态刷新

`refresh-enabled: true` 时，Nacos 控制台改配置并发布后，标注了
`@RefreshScope` 的 Bean 中的 `@Value` / `@ConfigurationProperties` 会在
不重启的情况下热更新。kwiki 当前未标注 `@RefreshScope`，远程配置变更
主要覆盖**启动期绑定**；需要运行时热更新的 Bean 再按需标注即可
（注意与构造器注入风格的兼容性）。

## 故障排查

| 症状 | 原因 | 处理 |
|---|---|---|
| 远程配置未生效 | Data ID 不匹配 | 须为 `kwiki.yaml`，Group 与 `KWIKI_NACOS_GROUP` 一致 |
| 命名空间不生效 | 填了显示名 | `KWIKI_NACOS_NAMESPACE` 必须填命名空间 ID（UUID） |
| 启动报连接错误但继续运行 | Nacos 不可达，optional 跳过 | 属预期行为；或置 `SPRING_CLOUD_NACOS_CONFIG_ENABLED=false` 关闭 |
| 版本冲突类 `NoSuchMethodError` | SCA / nacos-client 版本错配 | 统一走 BOM，勿手工指定 nacos-client 版本 |
