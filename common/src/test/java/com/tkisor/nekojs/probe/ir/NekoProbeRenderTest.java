package com.tkisor.nekojs.probe.ir;

import com.tkisor.nekojs.api.annotation.NekoProbe;
import com.tkisor.nekojs.probe.types.TypeAliasRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code @NekoProbe} 端到端：从真实类反射到渲染产物。
 *
 * <p>与 {@link NekoProbePlaceholdersTest}（纯文本替换）分工：
 * 这里验证接进渲染管线后的行为——接管判定、反射产物被完全取代、
 * 缩进与命名空间发射、以及 import 收集。
 */
class NekoProbeRenderTest {

    // ---------------- 测试夹具 ----------------

    /** 一个被完全接管的类：反射产物（方法、字段）不应出现在输出里。 */
    @NekoProbe(
            extra = """
                    export type SType = "string" | "int";
                    export interface STypeMap {
                        string: string;
                        int: number;
                    }
                    export type ArgsOf<T extends Record<string, SType>> = { [K in keyof T]: STypeMap[T[K]] };
                    """,
            type = """
                    export class {{ classtype }}<
                        T extends Record<string, {{ extra.SType }}> = {},
                    > {
                        schema<const S extends { [K in keyof S]: {{ extra.SType }} }>(sch: S): {{ classtype }}<S>;
                        fn(impl: (arg: {{ extra.ArgsOf }}<T>) => void): void;
                    }
                    """)
    public static final class AnnotatedBuilder {
        // 这些反射成员都不该出现在产物里
        public String reflectedMethod(int x) { return ""; }
        public static final String REFLECTED_FIELD = "x";
    }

    /** 无 extra 的接管类。 */
    @NekoProbe(type = "export class {{ classtype }} { only(): void; }")
    public static final class TypeOnly {
        public void shouldNotAppear() { }
    }

    /** 未接管的类，应走常规反射渲染。 */
    public static final class NotAnnotated {
        public String hello() { return ""; }
    }

    /** 引用外部类型的接管类，验证 import 登记。 */
    @NekoProbe(
            extra = "export interface Holder { c: {{ import(java.util.List) }}; }",
            type = "export class {{ classtype }} { get(): {{ import(java.util.List) }}; }")
    public static final class WithImport {
    }

    /** 占位符未闭合：渲染时必须报错而非静默产出坏声明。 */
    @NekoProbe(type = "export {{ classtype } { broken")
    public static final class Broken {
    }

    /** 中间含空行：作者用它分段，应保留（只剥首尾）。 */
    @NekoProbe(type = """
            export class {{ classtype }} {

                a(): void;

                b(): void;
            }
            """)
    public static final class InteriorBlankLines {
    }

    private static String render(Class<?> cls) {
        return new TypeScriptClassRenderer(new TypeAliasRegistry()).render(new TypeReflector().reflect(cls));
    }

    private static TypeScriptClassRenderer rendererFor(Class<?> cls, StringBuilder out) {
        var r = new TypeScriptClassRenderer(new TypeAliasRegistry());
        out.append(r.render(new TypeReflector().reflect(cls)));
        return r;
    }

    /**
     * 把所有夹具的渲染产物写一份到 {@code build/neko-probe-actual/}，便于人工检查与贴 issue。
     *
     * <p>写的是**未经断言**的原始产物，包含完整上下文（未注解类的反射产物可作对照）。
     * 只写 build/ 目录，不碰源码树；不设断言，因此产物有问题时本用例仍会通过，
     * 真正的问题由其它用例的断言暴露。
     */
    @Test
    void dumpActualOutputForInspection() throws java.io.IOException {
        var dir = java.nio.file.Path.of("build", "neko-probe-actual");
        java.nio.file.Files.createDirectories(dir);

        record Sample(String name, Class<?> cls, String note) { }
        var samples = java.util.List.of(
                new Sample("annotated-builder", AnnotatedBuilder.class, "extra + type，含三种占位符"),
                new Sample("type-only", TypeOnly.class, "只有 type，无 extra"),
                new Sample("with-import", WithImport.class, "{{ import(java.util.List) }} 跨包引用"),
                new Sample("not-annotated", NotAnnotated.class, "未注解 → 走反射，可作对照"));

        var summary = new StringBuilder();
        summary.append("本目录由 NekoProbeRenderTest#dumpActualOutputForInspection 生成，仅供人工检查。\n")
                .append("注意：这些是 render() 的原始产物（模块体层级，4 空格缩进），\n")
                .append("外层还会被 IndexFileGenerator 包进 declare module \"...\" { }。\n\n");

        for (var s : samples) {
            String out = render(s.cls());
            java.nio.file.Files.writeString(dir.resolve(s.name() + ".d.ts"), out,
                    java.nio.charset.StandardCharsets.UTF_8);
            summary.append("=== ").append(s.name()).append(".d.ts —— ").append(s.note()).append(" ===\n")
                    .append(out).append("\n");
        }
        java.nio.file.Files.writeString(dir.resolve("README.txt"), summary.toString(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------------- 接管行为 ----------------

    @Test
    void annotatedClassRendersHandWrittenTypeInsteadOfReflection() {
        String out = render(AnnotatedBuilder.class);

        assertTrue(out.contains("export class $NekoProbeRenderTest$AnnotatedBuilder<"), out);
        assertTrue(out.contains("schema<const S extends { [K in keyof S]: $NekoProbeRenderTest$AnnotatedBuilder$$$Extra.SType }>"), out);
        // 反射成员必须完全消失
        assertFalse(out.contains("reflectedMethod"), "反射方法不应出现:\n" + out);
        assertFalse(out.contains("REFLECTED_FIELD"), "反射字段不应出现:\n" + out);
    }

    @Test
    void extraGoesIntoDerivedNamespace() {
        String out = render(AnnotatedBuilder.class);

        assertTrue(out.contains("export namespace $NekoProbeRenderTest$AnnotatedBuilder$$$Extra {"), out);
        // extra 的成员在命名空间内
        assertTrue(out.contains("export type SType = \"string\" | \"int\";"), out);
        assertTrue(out.contains("export type ArgsOf<T extends Record<string, SType>>"), out);
    }

    @Test
    void extraNamespaceUsesThreeDollarsToAvoidNestedClassCollision() {
        String out = render(AnnotatedBuilder.class);
        String clsName = "$NekoProbeRenderTest$AnnotatedBuilder";
        // 命名空间名 = 类名 + $$$Extra（单 $ 会与「内部类恰好叫 Extra」撞名）
        assertTrue(out.contains("namespace " + clsName + "$$$Extra"), out);
        assertFalse(out.contains("namespace " + clsName + "$Extra "), out);
    }

    @Test
    void typeOnlyClassEmitsNoNamespace() {
        String out = render(TypeOnly.class);

        assertTrue(out.contains("export class $NekoProbeRenderTest$TypeOnly { only(): void; }"), out);
        assertFalse(out.contains("$$$Extra"), "extra 为空时不应发射命名空间:\n" + out);
        assertFalse(out.contains("shouldNotAppear"), out);
    }

    @Test
    void unannotatedClassStillUsesReflection() {
        String out = render(NotAnnotated.class);

        assertTrue(out.contains("export class $NekoProbeRenderTest$NotAnnotated"), out);
        assertTrue(out.contains("hello()"), "未接管的类应走反射渲染:\n" + out);
    }

    // ---------------- import 登记 ----------------

    @Test
    void importPlaceholderRegistersFqnAndUsesTsName() {
        var sb = new StringBuilder();
        var renderer = rendererFor(WithImport.class, sb);

        assertEquals(java.util.Set.of("java.util.List"),
                renderer.getNekoProbeImports("com.tkisor.nekojs.probe.ir.NekoProbeRenderTest$WithImport"),
                "占位符引用的 FQN 应被登记供 import 使用");
        assertTrue(sb.toString().contains("$List"), sb.toString());
    }

    @Test
    void importsAreDeduplicatedAcrossExtraAndType() {
        var sb = new StringBuilder();
        var renderer = rendererFor(WithImport.class, sb);

        assertEquals(1, renderer.getNekoProbeImports(
                "com.tkisor.nekojs.probe.ir.NekoProbeRenderTest$WithImport").size(),
                "extra 与 type 引用同一类型时只登记一次");
    }

    @Test
    void unannotatedClassHasNoProbeImports() {
        var sb = new StringBuilder();
        var renderer = rendererFor(NotAnnotated.class, sb);

        assertTrue(renderer.getNekoProbeImports("com.tkisor.nekojs.probe.ir.NekoProbeRenderTest$NotAnnotated")
                .isEmpty());
    }

    // ---------------- 错误传播 ----------------

    @Test
    void unclosedPlaceholderFailsLoudly() {
        var e = assertThrows(IllegalArgumentException.class, () -> render(Broken.class));
        assertTrue(e.getMessage().contains("未闭合"), e.getMessage());
        assertTrue(e.getMessage().contains("NekoProbe"), "应标明来源: " + e.getMessage());
    }

    // ---------------- 缩进 ----------------

    @Test
    void outputIsIndentedForModuleBody() {
        String out = render(TypeOnly.class);

        // 声明与其它类同层级（4 空格），与 renderClass 一致
        assertTrue(out.startsWith("    export class"), out);
        // 尾部换行，便于拼接
        assertTrue(out.endsWith("\n"), out);
    }

    @Test
    void extraNamespaceBodyIsIndentedDeeper() {
        String out = render(AnnotatedBuilder.class);

        assertTrue(out.contains("    export namespace $NekoProbeRenderTest$AnnotatedBuilder$$$Extra {\n"), out);
        assertTrue(out.contains("        export type SType"), "extra 内容应深一级:\n" + out);
    }

    @Test
    void blankEdgesFromTextBlocksAreStripped() {
        // 注解用 Java text block 书写，首尾各带一个换行是常态，不该在产物里留空白行
        String out = render(AnnotatedBuilder.class);
        assertFalse(out.contains("\n\n\n"), "不应出现连续空行:\n" + out);
        assertFalse(out.contains("\n\n    }"), "命名空间闭合前不应有空行:\n" + out);
    }

    @Test
    void interiorBlankLinesArePreserved() {
        String out = render(InteriorBlankLines.class);
        // 作者用空行分段是有意的，只剥首尾
        assertTrue(out.contains("a(): void;\n\n        b(): void;"), "中间空行应保留:\n" + out);
    }
}
