# `@NekoProbe` —— 手写类型声明接管

> **文档地位**：本文件是 `@NekoProbe` 注解的正式设计。草案见 git 历史中的 `nekoprobe-annotation-draft.md`（已并入本文）。
>
> **标记约定**：
> - **[必须]** —— 硬性约束，实现与使用都不得违反；
> - **[惯例]** —— 推荐做法；
> - **[待定]** —— 尚未拍板，实现前需确认。

---

## 1. 要解决的问题

NekoJS 从 Java 类反射生成 `.d.ts`。反射能拿到的信息有上限：

- **泛型擦除** —— `RpcCallbacks.RpcImpl.invoke(Object arg)` 里没有 `Object` 以外的信息
- **类型只存在于运行期约定** —— 如 `schema({ msg: 'string' })` 里的 `'string'` 对反射不可见
- **键名不可见** —— `Map<String, String>` 反射出来只有 `$Map`

结果：脚本侧 `RPC.create('x').schema({msg:'string'}).fn(({msg}) => ...)` 里 `msg` 是 `any`。

### 为什么已有的三条路都不通

NekoLDLib 实测记录（`JsxTypes.java`）：

| 尝试 | 失败原因 |
|---|---|
| `typeOverride` 指向手写泛型接口 | NekoJS 无条件生成 `type <override> = $<JavaType>`，把手写名字占成别名 → 遮蔽 |
| `interface` 增强 `$RpcBuilder` | 原 class 的旧重载与新泛型重载并存时 TS 选前者 → 推导失效 |
| `@manual` 声明 | **类声明已经生成过了，TS 里无法替换已存在的 class** |

根因是同一个：**声明一旦生成就无法覆盖**。所以必须在**生成端**换掉。

---

## 2. 注解形状

```java
@NekoProbe(
    extra = """ ...辅助类型，包进自动生成的命名空间... """,
    type  = """ ...主体，替换原反射产物... """
)
```

| 属性 | 作用 |
|---|---|
| `extra` | **非类型本身**的辅助声明（`type` / `interface` / 常量）。生成时统一包进一个唯一命名空间 |
| `type` | 本类最终的 TS 声明文本，**完全取代**反射渲染结果 |

- `@Target(ElementType.TYPE)`、`@Retention(RUNTIME)`
- `type` 是**必需**的（空则退化为"不生成该类"）
- **没有 `import` 属性** —— 见 §5，导入一律由占位符产生
- **没有 `lang` 属性** —— 当前只有 TS 后端，留一个恒为 `"ts"` 的参数是投机设计

> **[必须]** 类被 `@NekoProbe` 接管后，反射 IR 只用于**识别类本身**（FQN、包路径、`renameClass` 后的名字），
> 不再用于渲染成员、也不再用于收集 import。

---

## 3. 占位符

`extra` 与 `type` 文本里，`{{ ... }}` 是生成时替换的占位符。这是本设计的核心机制——手写文本要能引用「本类名」和「外部类型」，而这两者生成前才确定。

### 3.1 `{{ classtype }}`

展开为**本类的 TS 名**（如 `$RpcBuilder`）。

> **[必须]** 不要手写 `$RpcBuilder`——类名可由 `probe.modify_type` 的 `renameClass` 改写，硬编码会与生成结果脱节。

### 3.2 `{{ extra.X }}`

展开为**本类 extra 命名空间**下的名字（如 `$RpcBuilder$$$Extra.SType`）。

命名空间名 = `$` + 类名 + `$$$Extra`。

> **[必须]** 用**三个** `$` 作分隔，不用一个。
> 类名本身用**单个** `$` 连接内部类（`tsClassName`：`RpcBuilder.Extra` → `$RpcBuilder$Extra`），
> 单 `$` 分隔会与「内部类恰好叫 `Extra`」撞名。三 `$` 在常规命名下不会撞。

### 3.3 `{{ import(a.b.C) }}`

展开为类型 `C` 的 TS 名（`$C`；嵌套类按 `$A$B` 规则），**同时登记一条 import**。

- 名字解析规则与 `TypeScriptClassRenderer#tsClassName` 一致（`:496`）
- import 语句发射到**类所在模块**顶部（`declare module "java:..."` 内）
- 路径形如 `java:com/tkisor/nekoldlib/signal`（包名点换斜杠）

> **[必须]** 这是**唯一**的 import 产生方式（§5）。

---

## 4. 生成产物形状

以 `RpcBuilder` 为例：

```ts
// signal/index.d.ts
declare module "java:com/tkisor/nekoldlib/signal" {
    import { $Block } from "java:net/minecraft/world/level/block";       // ← 占位符登记
    import { $RpcCollector$Entry } from "java:com/tkisor/nekoldlib/signal";

    // extra → 唯一命名空间（三 $ 分隔，避开内部类的单 $ 连接）
    export namespace $RpcBuilder$$$Extra {
        export type SType = "string" | "block" | "int" | "boolean";
        export interface STypeMap { string: string; block: $Block; int: number; boolean: boolean; }
        export type Of<S> = S extends SType ? STypeMap[S] : void;
        export type ArgsOf<T extends Record<string, SType>> = { [K in keyof T]: Of<T[K]> };
    }

    export class $RpcCollector$Entry { /* 未注解 → 照旧反射 */ }

    // type → 替换原 $RpcBuilder
    export class $RpcBuilder<
        T extends Record<string, $RpcBuilder$$$Extra.SType> = {},
        R extends $RpcBuilder$$$Extra.SType | undefined = undefined,
    > {
        schema<const S extends { [K in keyof S]: $RpcBuilder$$$Extra.SType }>(this: $RpcBuilder, sch: S): $RpcBuilder<S>;
        returns<Ret extends $RpcBuilder$$$Extra.SType>(this: $RpcBuilder<T, undefined>, ret: Ret): $RpcBuilder<T, Ret>;
        fn(
            this: R extends $RpcBuilder$$$Extra.SType ? $RpcBuilder<T, R> : never,
            impl: (arg: $RpcBuilder$$$Extra.ArgsOf<T>) => $RpcBuilder$$$Extra.Of<R>,
        ): $RpcCollector$Entry;
    }
}
```

**[必须]** 只替换被注解的类。同模块其他类（`$Rpc`、`$RpcCollector$Entry`）照旧反射生成。

---

## 5. 导入从哪来

用户问过：「实际实现不需要 `Block`/`Entity`/`Item`/`AABB`，但 `.d.ts` 需要，怎么办？」

答案是**只能靠占位符**，而且原因是对称的两面：

### 5.1 声明需要、实现不需要 → 占位符是唯一途径

`STypeMap.block: $Block` 里 `$Block` 在**类型层**是真实需要的——脚本写
`.schema({b:'block'}).fn(({b}) => b.id())` 时 `b` 必须是 `$Block` 才能补全 `id()`。

而 `RpcBuilder` 的 Java 字段只有 `Map<String,String>`，**一处也没引用 `Block`**。
反射 IR 扫描因此**永远扫不出 `$Block`**。

### 5.2 反射能扫出、`type` 文本不再引用 → 扫了也是死的

原反射产物可能引用了别的类；`type` 替换后那些成员不再渲染，
自动扫出来的 import 全成死 import。

### 5.3 结论

> **[必须]** 被 `@NekoProbe` 接管的类**完全不扫反射 IR 收集 import**，
> 只用 `{{ import(...) }}` 登记的。

5.1 说明自动扫描**做不到**该做的事，5.2 说明它**做出来的全是垃圾**——
两头都不成立，所以注解不提供 `import` 属性，`auto` 这种模式也没有存在理由。

---

## 6. 已验证的修正：`Of<S>` 的约束

用户伪代码里 `{{ extra.Of }}<R>` 配 `export type Of<S extends SType>` 会**编译报错**：

```
TS2344: Type 'R' does not satisfy the constraint 'SType'.
  Type 'SType | undefined' is not assignable to type 'SType'.
```

因为 `R` 声明为 `SType | undefined`（无返回时是 `undefined`），而 `Of<S extends SType>` 不接受 `undefined`。

> **[必须]** `Of` 放宽为条件类型：
> ```diff
> - export type Of<S extends SType> = STypeMap[S];
> + export type Of<S> = S extends SType ? STypeMap[S] : void;
> ```
> 实测改后 `tsc` 退出码 0。副作用是正面的：无返回 RPC 的 `fn` 返回类型成为 `void`，语义正确。

---

## 7. 待定

1. **`extra` 是否允许引用另一个类的 `extra`** —— 目前不支持
2. **注解文本的缩进** —— 当前由写注解的人自行缩进，生成器不做处理
3. **`extra` 为空时是否仍发射空命名空间** —— 倾向不发（避免无意义产物）

## 7.1 转义与错误检测

占位符是纯文本替换，但**不是**「见 `{{` 就替换」：

> **[必须]** 只有能**正常闭合**的 `{{ ... }}` 才当占位符处理。
> 走到文本结尾仍未遇到 `}}` 的 `{{`，**报错**（不是静默当普通文本），
> 因为漏写闭合几乎总是笔误，静默会让占位符以原文形式出现在 `.d.ts` 里。

> **[必须]** 字面量 `{{` / `}}` 写作 `\{\{` / `\}\}`，生成时还原为 `{{` / `}}`。
> 这是唯一转义方式。

**[待定]** `{{` 与 `}}` 之外的其余反斜杠转义是否透传（倾向：只识别 `\{\{` / `\}\}`，其余 `\x` 原样保留，避免与 TS 自身的转义打架）。

## 8. 实现要点（给实现者）

- 注解定义放 `common-api/src/main/java/com/tkisor/nekojs/api/annotation/NekoProbe.java`（与 `@Doc` 同目录）
- 读取走 `AnnotatedDocs` 同款反射路径；渲染入口在 `TypeScriptClassRenderer.render(TypeDecl)`
- 接管判定要**早于** `predeclareClass`（`IndexFileGenerator:377`），因为它同时写 `declCache` 与 `importCache`
- `{{ import(...) }}` 收集到的 FQN 走 `predeclareClass` 的 `extraImportFqns` 参数并入 importCache
- 占位符替换是**纯文本**操作，不解析 TS 语法；转义与未闭合的处理见 §7.1
