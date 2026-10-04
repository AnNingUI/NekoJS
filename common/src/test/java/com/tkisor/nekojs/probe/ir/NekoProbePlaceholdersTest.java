package com.tkisor.nekojs.probe.ir;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code @NekoProbe} 占位符替换的纯逻辑测试——不涉及渲染管线，只验证文本替换与错误检测。
 */
class NekoProbePlaceholdersTest {

    private static final String CLS = "RpcBuilder";
    private static final String NS = "$RpcBuilder$$$Extra";

    /** 测试用的 FQN 解析器：镜像 IndexFileGenerator 发射 import 时的命名规则。 */
    private static String resolver(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? null : "$" + fqn.substring(dot + 1);
    }

    private static NekoProbePlaceholders.Result sub(String text) {
        return NekoProbePlaceholders.substitute(text, CLS, NS, NekoProbePlaceholdersTest::resolver, "T#x");
    }

    // ---------------- 三种占位符 ----------------

    @Test
    void classtypeExpandsToDollarName() {
        assertEquals("export $RpcBuilder {", sub("export {{ classtype }} {").text());
    }

    @Test
    void classtypeIsUsableRepeatedly() {
        var r = sub("{{ classtype }}<T> extends {{ classtype }}<T>");
        assertEquals("$RpcBuilder<T> extends $RpcBuilder<T>", r.text());
    }

    @Test
    void extraExpandsToNamespaceMember() {
        var r = sub("x: {{ extra.SType }};");
        assertEquals("x: $RpcBuilder$$$Extra.SType;", r.text());
        assertTrue(r.imports().isEmpty(), "extra 不产生 import");
    }

    @Test
    void importExpandsToTsNameAndRegistersFqn() {
        var r = sub("): {{ import(com.tkisor.nekoldlib.signal.RpcCollector) }};");
        assertEquals("): $RpcCollector;", r.text());
        assertEquals(java.util.Set.of("com.tkisor.nekoldlib.signal.RpcCollector"), r.imports());
    }

    @Test
    void importHandlesNestedClassFqn() {
        // 嵌套类 FQN 的最后一段已含 `$`（RpcCollector$Entry）→ 加 `$` 前缀得 $RpcCollector$Entry
        var r = sub("{{ import(com.tkisor.nekoldlib.signal.RpcCollector$Entry) }}");
        assertEquals("$RpcCollector$Entry", r.text());
        assertEquals(java.util.Set.of("com.tkisor.nekoldlib.signal.RpcCollector$Entry"), r.imports());
    }

    @Test
    void multipleImportsAreDeduplicated() {
        var r = sub("{{ import(a.b.C) }} {{ import(a.b.C) }} {{ import(a.b.D) }}");
        assertEquals("$C $C $D", r.text());
        assertEquals(java.util.Set.of("a.b.C", "a.b.D"), r.imports());
    }

    @Test
    void whitespaceInsideBracesIsTolerated() {
        assertEquals("$RpcBuilder", sub("{{classtype}}").text());
        assertEquals("$RpcBuilder", sub("{{   classtype   }}").text());
        assertEquals("$RpcBuilder$$$Extra.X", sub("{{ extra.X }}").text());
        assertEquals("$C", sub("{{ import( a.b.C ) }}").text());
    }

    @Test
    void placeholdersSpanMultipleLines() {
        // 注解文本是多行字符串，占位符内部可能有换行（用户排版导致）
        var r = sub("{{\n  classtype\n}}");
        assertEquals("$RpcBuilder", r.text());
    }

    // ---------------- 转义 ----------------

    @Test
    void escapedBracesBecomeLiteral() {
        assertEquals("{{", sub("\\{\\{").text());
        assertEquals("}}", sub("\\}\\}").text());
        assertEquals("{{ classtype }}", sub("\\{\\{ classtype \\}\\}").text());
    }

    @Test
    void escapedBracesDoNotRegisterImports() {
        var r = sub("\\{\\{ import(a.b.C) \\}\\}");
        assertEquals("{{ import(a.b.C) }}", r.text());
        assertTrue(r.imports().isEmpty(), "转义后的文本不应被当占位符处理");
    }

    @Test
    void escapeAndPlaceholderCoexist() {
        var r = sub("\\{\\{ {{ classtype }} \\}\\}");
        assertEquals("{{ $RpcBuilder }}", r.text());
    }

    // ---------------- 错误检测 ----------------

    @Test
    void unclosedPlaceholderIsAnError() {
        var e = assertThrows(IllegalArgumentException.class, () -> sub("export {{ classtype {"));
        assertTrue(e.getMessage().contains("未闭合"), e.getMessage());
        assertTrue(e.getMessage().contains("T#x"), "错误信息应标明来源: " + e.getMessage());
    }

    @Test
    void danglingDoubleBraceAtEndIsAnError() {
        var e = assertThrows(IllegalArgumentException.class, () -> sub("foo {{"));
        assertTrue(e.getMessage().contains("未闭合"), e.getMessage());
    }

    @Test
    void unknownPlaceholderIsAnError() {
        var e = assertThrows(IllegalArgumentException.class, () -> sub("{{ nope }}"));
        assertTrue(e.getMessage().contains("无法识别"), e.getMessage());
        // 错误信息应列出可用写法，便于纠正
        assertTrue(e.getMessage().contains("classtype"), e.getMessage());
    }

    @Test
    void emptyExtraNameIsAnError() {
        var e = assertThrows(IllegalArgumentException.class, () -> sub("{{ extra. }}"));
        assertTrue(e.getMessage().contains("缺少名字"), e.getMessage());
    }

    @Test
    void emptyImportFqnIsAnError() {
        var e = assertThrows(IllegalArgumentException.class, () -> sub("{{ import() }}"));
        assertTrue(e.getMessage().contains("缺少类名"), e.getMessage());
    }

    @Test
    void unresolvableImportIsAnError() {
        var e = assertThrows(IllegalArgumentException.class, () -> sub("{{ import(NoDots) }}"));
        assertTrue(e.getMessage().contains("无法解析"), e.getMessage());
    }

    // ---------------- 边界 ----------------

    @Test
    void emptyAndNullTextYieldNothing() {
        assertEquals("", sub("").text());
        assertTrue(sub("").imports().isEmpty());
        var r = NekoProbePlaceholders.substitute(null, CLS, NS, NekoProbePlaceholdersTest::resolver, "T#x");
        assertEquals("", r.text());
    }

    @Test
    void textWithoutPlaceholdersPassesThrough() {
        String src = "export type SType = \"string\" | \"int\";\ninterface M { int: number; }";
        var r = sub(src);
        assertEquals(src, r.text());
        assertTrue(r.imports().isEmpty());
    }

    @Test
    void namespaceNameUsesThreeDollars() {
        // 三 $ 避开内部类的单 $ 连接（RpcBuilder.Extra → $RpcBuilder$Extra）
        assertEquals("$RpcBuilder$$$Extra", NekoProbePlaceholders.extraNamespace("RpcBuilder"));
        assertNotEquals(NekoProbePlaceholders.extraNamespace("RpcBuilder"), "$RpcBuilder$Extra");
    }
}
