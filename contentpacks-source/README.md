# ContentPack 模板与工具

本目录是内容包（ContentPack）的**源码源**。每个子目录是一个包的完整源：
入口类 Java 代码在 `<包>/java/` 目录（**独立编译**，不再依赖主 mod 源码），
资源/JSON/MANIFEST 在包目录下。

## 目录

| 目录 | 说明 |
|------|------|
| `template-pack/` | **标准模板**：复制它创建新包 |
| `test-mob-pack/` | 测试包（JSON 数据驱动实体验证，含独立 Java 源码） |
| `build-pack.ps1` | 打包脚本（模板脚本，支持独立编译） |

## 创建新内容包（3 步）

1. **复制模板**：`template-pack/` → `my-pack/`，改 `MANIFEST.MF` 的 `Module-Name`/`Module-Entry`
2. **加内容**：
   - `entities/*.json` 定义生物；`spark_models/` `spark_animations/` `assets/` 放资源
   - 需要代码时：入口类放在 `<包>/java/` 目录（包结构对应 `Module-Entry`），**独立编译**
3. **打包**：
   ```powershell
   powershell -File contentpacks-source/build-pack.ps1 -SourceDir contentpacks-source/my-pack -OutJar contentpacks/my-pack.jar
   ```

## 打包脚本 build-pack.ps1

自动完成：
1. 解析 MANIFEST `Module-Entry` → 包路径
2. **独立编译**（包 `java/` 源码存在时）：`javac` + 主 mod classpath
   （`build/runtime-classpath.txt`，由 `gradlew writeRuntimeClasspath --no-configuration-cache` 生成）
3. 复制 MANIFEST/资源/JSON → `jar cfm` 打包 → 校验内容

**注意**：
- 改包内 Java 代码后重新打包即可（无需重编主 mod）——**独立并行开发**
- 修改主 mod API 后需重跑 `gradlew writeRuntimeClasspath`（classpath 刷新）
- 运行时加载的是 jar 里的类（ChildFirstClassLoader 子优先）

## 格式规范

完整格式规范见 `design/CONTENTPACK_FORMAT.md`（MANIFEST 属性 / 实体 JSON schema / 行为组件 / 加载流程）。
