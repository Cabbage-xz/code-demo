# API 安全配置指南

## 重要提醒
此项目之前存在 API 密钥硬编码在配置文件中的安全漏洞。该漏洞已修复，但请确保：
1. 立即在 API 提供商处重置已泄露的 API 密钥
2. 按照以下说明安全地管理 API 密钥

## 正确配置方式

### 方法一：环境变量（推荐）
在运行应用前设置环境变量：
```bash
export DEEPSEEK_API_KEY="your_actual_api_key_here"
```

### 方法二：JVM 系统属性
```bash
java -jar your-app.jar -DDEEPSEEK_API_KEY="your_actual_api_key_here"
```

### 方法三：外部配置文件
创建外部配置文件（如 `config/application.yml`），并将其放在版本控制之外：
```yaml
deepseek:
  api-key: "your_actual_api_key_here"
```

然后通过命令行指定外部配置位置：
```bash
java -jar your-app.jar --spring.config.location=file:./config/
```

## 验证配置
启动应用后，确保日志显示正确的配置加载，且不会在日志中泄露 API 密钥。

## 安全最佳实践
- 绝不要将 API 密钥提交到代码仓库
- 定期轮换 API 密钥
- 限制 API 密钥的权限范围
- 监控 API 密钥的使用情况