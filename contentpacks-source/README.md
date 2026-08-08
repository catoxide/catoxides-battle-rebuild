# ContentPack 模板与工具

本目录是内容包（ContentPack）的**源码源**。每个子目录是一个包的源（含 MANIFEST + JSON + 资源），
入口类 Java 代码在主 mod 源码的 `example.contentpack.*` 包下（编译产物由打包脚本收集）。

## 目录

| 目录 | 说明 |
|------|------|
| `template-pack/` | **标准模板**：复制它创建新包 |
| `test-mob-pack/` | 测试包（JSON 数据驱动实体验证） |
| `build-pack.ps1` | 打包脚本（模板脚本） |

## 创建新内容包（3 步）

1. **复制模板**：`template-pack/` → `my-pack/`，改 `MANIFEST.MF` 的 `Module-Name`/`Module-Entry`
2. **加内容**：`entities/*.json` 定义生物；`spark_models/` `spark_animations/` `assets/` 放资源；
   需要代码时在主 mod 源码写入口类（实现 `ContentPack` 接口）
3. **打包**：
   ```powershell
   gradlew compileJava   # 编译（入口类在主 mod 源码时）
   powershell -File contentpacks-source/build-pack.ps1 -SourceDir contentpacks-source/my-pack -OutJar contentpacks/my-pack.jar
   ```

## 打包脚本 build-pack.ps1

自动完成：解析 MANIFEST `Module-Entry` → 收集入口包全部 `.class`（从主 mod 编译产物）
→ 复制 MANIFEST/资源/JSON → `jar cfm` 打包 → 校验内容。

**注意**：改 jar 内代码后必须重新打包（运行时加载的是 jar 里的类，不是主 mod 编译产物）。

## 格式规范

完整格式规范见 `design/CONTENTPACK_FORMAT.md`（MANIFEST 属性 / 实体 JSON schema / 行为组件 / 加载流程）。
