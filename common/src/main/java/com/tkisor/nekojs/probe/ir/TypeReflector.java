package com.tkisor.nekojs.probe.ir;

import com.tkisor.nekojs.api.JavaMemberIndex;
import com.tkisor.nekojs.api.surface.ApiParameter;
import com.tkisor.nekojs.api.surface.ApiSignature;
import com.tkisor.nekojs.api.surface.ApiSymbolId;
import com.tkisor.nekojs.api.surface.ApiTypeRef;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code Class<?>} → {@link TypeDecl} 反射器。镜像旧 {@code ClassDeclGenerator} 的成员枚举与 getter/setter 推断，
 * 保证 {@link TypeScriptClassRenderer} 渲染未编辑 IR 时与旧实现逐字一致。
 *
 * <p>每个类型槽产出 {@link TypeSlot}：{@code sourceType} 供 TS 默认渲染（TypeConverter，零回归），
 * {@code ref}（ApiTypeRef，best-effort）供 Python（Phase 3）与 modify_type 编辑使用。
 */
public final class TypeReflector {

    public TypeDecl reflect(Class<?> cls) {
        TypeDecl.Kind kind = cls.isEnum() ? TypeDecl.Kind.ENUM
                : (cls.isInterface() ? TypeDecl.Kind.INTERFACE : TypeDecl.Kind.CLASS);
        TypeDecl decl = new TypeDecl(kind, cls, cls.getName());
        // 类级 @Doc → JSDoc（注解缺省时 docs 为空，渲染零输出）
        decl.docs.addAll(AnnotatedDocs.typeDocs(cls));

        // 类级泛型
        for (TypeVariable<?> tv : cls.getTypeParameters()) {
            TypeSlot bound = null;
            Type[] bounds = tv.getBounds();
            if (bounds.length > 0 && bounds[0] != Object.class) {
                bound = TypeSlot.of(bounds[0], toRef(bounds[0]));
            }
            decl.typeParams.add(new TypeDecl.TypeParam(tv.getName(), bound));
        }

        // 父类（仅 class；interface/enum 的 extends 在旧实现里不渲染 superclass）
        if (kind == TypeDecl.Kind.CLASS) {
            Type sc = cls.getGenericSuperclass();
            Class<?> scRaw = rawClassOf(sc);
            if (scRaw != null && scRaw != Object.class) {
                decl.superType = TypeSlot.of(scRaw, toRef(sc));
            }
        }

        // 接口：用 getGenericInterfaces 才能带上实参（$Collection<E> extends $Iterable<E>）。
        // getInterfaces 只给裸 Class，实参丢失后父接口的类型变量退化成 any，
        // 子接口就继承不到 E（forEach 的 x 变成 any）。
        for (Type iface : cls.getGenericInterfaces()) {
            Class<?> ifaceRaw = rawClassOf(iface);
            if (ifaceRaw != null) {
                decl.interfaces.add(TypeSlot.of(ifaceRaw, toRef(iface)));
            }
        }

        switch (kind) {
            case CLASS -> reflectClassMembers(cls, decl);
            case INTERFACE -> reflectInterfaceMembers(cls, decl);
            case ENUM -> reflectEnumMembers(cls, decl);
        }

        // 补齐全被继承成员的重载（见 flattenShadowedOverloads）
        flattenShadowedOverloads(cls, decl);

        // 整个类型建好后统一清理：埋名/编辑过 `probe.assign_type` 的类可能残留不可达的类型变量，
        // 这一步在能看到完整型参作用域的位置兜底（见 dropUnreachableTypeVars）。
        dropUnreachableTypeVars(decl);

        // 确定性排序：JVM 规范不保证 getDeclaredMethods/getDeclaredFields 的返回顺序，
        // 跨进程运行会产生成员顺序抖动 → 按名字（+参数/返回类型）稳定排序，保证 probe 产物可复现。
        // 渲染分段（getter/静态/实例）由 renderer 按标志过滤，与列表顺序无关。
        decl.constructors.sort(Comparator.comparing(TypeReflector::constructorKey));
        decl.fields.sort(Comparator.comparing(f -> f.name));
        decl.methods.sort(Comparator.comparing(TypeReflector::methodKey));
        return decl;
    }

    private static String constructorKey(MethodDecl c) {
        return paramsKey(c);
    }

    private static String methodKey(MethodDecl m) {
        return m.name + "|" + paramsKey(m) + "→" + typeKey(m.returnType);
    }

    private static String paramsKey(MethodDecl m) {
        StringBuilder sb = new StringBuilder();
        for (MethodDecl.MethodParam p : m.params) {
            sb.append('|').append(typeKey(p.type));
            // varargs/optional 是排序键的一部分：varargs 参数在 IR 中被扁平化为组件类型，
            // 若不加标志，of(int) 与 of(int...) 的排序键相同 → 稳定排序保留反射原始序 → 跨 JVM 抖动
            if (p.varargs) sb.append("[]");
            if (p.optional) sb.append("?");
        }
        return sb.toString();
    }

    private static String typeKey(TypeSlot slot) {
        if (slot == null || slot.sourceType == null) return "";
        return slot.sourceType.getTypeName();
    }

    /** SAM 检测结果缓存：probe 会遍历上千个类，每次都 getMethods() 太贵。 */
    private static final Map<Class<?>, java.util.Optional<Method>> SAM_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * **参数位置**的类型引用。
     *
     * <p>函数式接口在这里渲染成 {@code $X | ((args) => ret)}，让脚本能直接传 lambda：
     * 箭头函数没有接口的方法（{@code invoke}/{@code andThen}…），永远赋不进接口本身，
     * 只有联合才能两者都收。实测：
     * <pre>
     *   interface $Fn { apply(a): b; andThen(...): $Fn }
     *   take(f: $Fn)               → take((a) =&gt; b) 报「缺少 andThen」
     *   type L = $Fn | ((a) =&gt; b)  → take((a) =&gt; b) 通过，$Fn 实例也通过
     * </pre>
     *
     * <p><b>只在参数位置做</b>是刻意的：这不属于类型本身的属性，而是"参数位置能接受 lambda"
     * 这一位置语义。若在 {@link #toRef} 里全局生成，联合会污染 heritage 位置
     * （class 的 extends/implements、interface 的 extends）——那里 {@code isHeritageSafe}
     * 会把联合整个剔除，于是 {@code interface $Closeable extends $AutoCloseable} 退化成
     * {@code interface $Closeable}，继承关系静默消失。
     *
     * <p>与 KubeJS 配套的 ProbeJS 是同一做法（识别函数式接口后为其生成输入别名）。
     */
    private static ApiTypeRef paramRef(Type type) {
        var base = toRef(type);
        if (base == null || base.kind() != ApiTypeRef.Kind.SYMBOL) {
            return base;
        }
        Class<?> raw = rawClassOf(type);
        if (raw == null) {
            return base;
        }
        Method sam = SAM_CACHE.computeIfAbsent(raw, k -> java.util.Optional.ofNullable(singleAbstractMethod(k)))
                .orElse(null);
        if (sam == null) {
            return base;
        }
        // 形态 → 按实参还原本接口的形参 → 还原继承自父接口的形参。
        // 仍解释不了的名字留给 dropUnreachableTypeVars（它能看到整个类的型参作用域）。
        ApiSignature signature = samSignature(sam);
        signature = applySignatureSubstitution(signature, type, base);
        signature = resolveInherited(signature, type, sam.getDeclaringClass());
        return ApiTypeRef.union(List.of(base, ApiTypeRef.callback(signature)));
    }

    /**
     * 解析类型对应的**原始**类；泛型/通配符等非 Class 形态不可能是函数式接口，返回 null。
     *
     * <p>这里交出原始类即可，参数化实参由 {@link #applySignatureSubstitution} 另行绑定——
     * 只靠 {@code Class} 无法还原 {@code Predicate<String>} 的实参。
     */
    @Nullable
    private static Class<?> rawClassOf(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) return raw;
        return null;
    }

    /**
     * SAM 的 {@code (参数...) => 返回} 签名；参数/返回都按**真实泛型**渲染。
     *
     * <p>签名里的类型变量分两种，处理方式不同：
     * <ul>
     *   <li>属于**函数式接口自身**的（{@code Predicate<T>} 的 {@code T}）——在参数位置上
     *       它的实参由声明处所在作用域决定，通配符上界又会把它抹成 any。这类位置统一按
     *       {@link #applySignatureSubstitution} 回填成接口自己的形参名；</li>
     *   <li>**方法自己声明的**（{@code Function.andThen<V>} 的 {@code V}）——由调用方在调用点
     *       推断，原样保留。这里不做任何替换：替换成外层实参反而是错的。</li>
     * </ul>
     */
    private static ApiSignature samSignature(Method sam) {
        var genericParams = sam.getGenericParameterTypes();
        List<ApiParameter> params = new ArrayList<>(genericParams.length);
        boolean varArgs = sam.isVarArgs();
        for (int i = 0; i < genericParams.length; i++) {
            boolean isVarargs = varArgs && i == genericParams.length - 1;
            params.add(new ApiParameter("arg" + i,
                    toRef(varargsComponent(genericParams[i], isVarargs)), false, isVarargs));
        }
        ApiTypeRef ret = sam.getReturnType() == void.class
                ? ApiTypeRef.voidType()
                : toRef(sam.getGenericReturnType());
        return new ApiSignature(params, ret, false);
    }

    /**
     * 变参参数的**组件类型**；非变参原样返回。
     *
     * <p>反射给的是数组形态（{@code ClassDesc[]}），而 IR 约定变参存组件类型 + {@code varargs} 标志，
     * 渲染时再补回 {@code ...args: T[]}。两种数组形态都要处理：泛型数组是 {@link GenericArrayType}，
     * 而 {@code Object...} 反射给的是 {@code Class}。
     */
    private static Type varargsComponent(Type type, boolean isVarargs) {
        if (!isVarargs) {
            return type;
        }
        if (type instanceof GenericArrayType gat) {
            return gat.getGenericComponentType();
        }
        if (type instanceof Class<?> pc && pc.isArray()) {
            return pc.getComponentType();
        }
        return type;
    }

    /** 原始类的类型形参名集合（用于区分"接口自身"与"方法自身"的类型变量）。 */
    private static Set<String> ownTypeParameterNames(Type raw) {
        TypeVariable<?>[] vars = rawTypeParameters(raw);
        if (vars == null) {
            return Set.of();
        }
        Set<String> names = new HashSet<>(vars.length);
        for (TypeVariable<?> v : vars) {
            names.add(v.getName());
        }
        return names;
    }

    /**
     * 取接口的单一抽象方法；不是函数式接口时返回 null。
     *
     * <p>{@code @FunctionalInterface} 注解本身不是必须的（它只在编译期做校验），
     * 所以按 JDK 的 SAM 规则判定：接口 + 恰好一个抽象方法。
     */
    @Nullable
    private static Method singleAbstractMethod(Class<?> cls) {
        if (!cls.isInterface()) {
            return null;
        }
        Method found = null;
        for (Method m : cls.getMethods()) {
            if (m.isDefault() || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            if (found != null) {
                return null;   // 多于一个抽象方法 → 不是函数式接口
            }
            found = m;
        }
        return found;
    }

    /**
     * 补齐**被自身重声明遮蔽掉的继承重载**。
     *
     * <p>Java 允许子接口只重声明父接口的一部分重载（{@code Set} 只重声明了
     * {@code toArray()} 与 {@code toArray(T[])}，没重声明 {@code toArray(IntFunction)}）。
     * 渲染成 TS 后，子接口的同名成员只剩自己那几条。这对**单向继承**无害
     * （TS 用可赋值性检查），但一旦某个接口**同时继承**两个同名成员条数不同的父接口，
     * TS 就要求两边 identical，直接报 TS2320：
     * <pre>
     *   interface $SequencedSet&lt;E&gt; extends $SequencedCollection&lt;E&gt;, $Set&lt;E&gt;
     *   → Named property 'toArray' of types ... are not identical.
     * </pre>
     *
     * <p>这里把父接口有、自己缺的那些签名补进自己，使重声明后的集合与父接口一致。
     * 补的是**父接口的签名**（经泛型实参改写，如 {@code Collection<E>} 用在 {@code $Set<E>}
     * 下仍是 {@code E}），不是并集之外的新东西——所以子接口能力不变，只是不再遮蔽。
     */
    private void flattenShadowedOverloads(Class<?> cls, TypeDecl decl) {
        for (TypeSlot slot : decl.interfaces) {
            Class<?> parent = rawClassOf(slot.sourceType);
            if (parent == null || parent == cls) {
                continue;
            }
            Map<String, ApiTypeRef> remap = supertypeArgumentMap(cls, parent);
            for (MethodDecl inherited : parentInstanceMethods(parent)) {
                // 只处理**被自身重声明遮蔽掉**的名字：没重声明的整个靠继承，不必补
                if (decl.methods.stream().noneMatch(m -> !m.hidden && m.name.equals(inherited.name))) {
                    continue;
                }
                MethodDecl rebound = rebindMethod(inherited, remap);
                // 该签名若已能从自身或更远的父链拿到（如 $PrimitiveIterator$OfDouble 的直接父
                // $PrimitiveIterator 已被补齐过），就不必再补——否则会逐层重复。
                if (inheritedFromAnySupertype(cls, rebound)) {
                    continue;
                }
                decl.methods.add(rebound);
            }
        }
    }

    /**
     * 该签名是否已能从 {@code cls} 的**任意**父类型拿到（含自身声明的）。
     *
     * <p>父类自身的声明也要查：{@code Integer extends Number implements Comparable<Integer>}
     * 里 {@code compareTo(Integer)} 属 {@code Comparable}，但 {@code Integer} 自己声明了它——
     * 只查接口会把已经有的那条又补一遍（{@code extends} 与 {@code implements} 两条路径各补一次）。
     *
     * <p>只按参数列表比对，不比返回类型——协变覆盖允许同参不同返回。
     */
    private static boolean inheritedFromAnySupertype(Class<?> cls, MethodDecl candidate) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var method : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) continue;
                if (method.getName().equals(candidate.name)
                        && method.getParameterCount() == candidate.params.size()
                        && declaresMatching(c, candidate, new HashSet<>())) {
                    return true;
                }
            }
            for (Class<?> iface : c.getInterfaces()) {
                if (declaresMatching(iface, candidate, new HashSet<>())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 接口自身声明的公开实例方法（跳过 static/bridge/synthetic，与 reflectInterfaceMembers 同口径）。 */
    private static List<MethodDecl> parentInstanceMethods(Class<?> iface) {
        List<MethodDecl> out = new ArrayList<>();
        var reflector = new TypeReflector();
        for (var method : iface.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (method.isSynthetic() || method.isBridge()) continue;
            out.add(reflector.reflectMethod(method));
        }
        return out;
    }

    /**
     * 求「{@code parent} 的形参名 → {@code cls} 作用域下的类型引用」
     * （如 {@code Collection<E>} 用在 {@code Set<E>} 上时 {@code E → E}）。
     */
    private static Map<String, ApiTypeRef> supertypeArgumentMap(Class<?> cls, Class<?> parent) {
        Map<String, ApiTypeRef> bindings = searchSupertype(cls, parent, rootSubstitution(cls), new HashSet<>());
        return bindings == null ? Map.of() : bindings;
    }

    /** 把方法签名里的类型变量按映射改写（父接口形参名 → 本接口作用域下的引用）。 */
    private static MethodDecl rebindMethod(MethodDecl src, Map<String, ApiTypeRef> remap) {
        MethodDecl out = new MethodDecl(src.name);
        out.isStatic = src.isStatic;
        out.isGetter = src.isGetter;
        out.isSetter = src.isSetter;
        out.renameTo = src.renameTo;
        out.typeParams.addAll(src.typeParams);
        for (MethodDecl.MethodParam p : src.params) {
            ApiTypeRef type = p.type == null ? null : substituteTypeVariables(p.type.ref, remap);
            out.params.add(new MethodDecl.MethodParam(p.name, TypeSlot.of(p.type == null ? null : p.type.sourceType, type), p.varargs));
        }
        if (src.returnType != null) {
            out.returnType = TypeSlot.of(src.returnType.sourceType,
                    substituteTypeVariables(src.returnType.ref, remap));
        }
        return out;
    }

    /**
     * 该接口（沿其父链）是否声明了与 {@code candidate} 同参数列表的方法。
     *
     * <p>**只比参数列表，不比返回类型**：Java 允许协变覆盖（{@code Spec.value(): Object} 被
     * {@code ConcreteSpec.value(): String} 覆盖），两者参数相同、返回不同，属同一条重载。
     * 把返回类型算进去会把这种覆盖误判成"缺失的重载"补进来，产出同参不同返回的非法重载
     * （正是 bridge 过滤要避免的）。
     *
     * <p>反射参数的**变参要拆成组件类型**再比：IR 里变参被扁平化为组件类型（{@code ClassDesc...}
     * → 类型 {@code ClassDesc} + {@code varargs}），而 {@code getGenericParameterTypes()} 给的是
     * {@code ClassDesc[]}。不拆就会把同一条重载判成不同（{@code MethodTypeDesc} 自己声明的
     * {@code insertParameterTypes} 会被再补一遍）。
     */
    private static boolean declaresMatching(Class<?> iface, MethodDecl candidate, Set<String> visited) {
        if (!visited.add(iface.getName())) {
            return false;
        }
        for (var method : iface.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterCount() != candidate.params.size()) continue;
            if (!method.getName().equals(candidate.name)) continue;
            boolean same = true;
            boolean varArgs = method.isVarArgs();
            Type[] types = method.getGenericParameterTypes();
            for (int i = 0; i < types.length && same; i++) {
                boolean isVarargs = varArgs && i == types.length - 1;
                same = toRef(varargsComponent(types[i], isVarargs)).compatibilityKey()
                        .equals(typeRefKey(candidate.params.get(i).type));
            }
            if (same) {
                return true;
            }
        }
        for (Class<?> parent : iface.getInterfaces()) {
            if (declaresMatching(parent, candidate, visited)) {
                return true;
            }
        }
        return false;
    }

    private static String typeRefKey(TypeSlot slot) {
        return slot == null || slot.ref == null ? "" : slot.ref.compatibilityKey();
    }

    private void reflectClassMembers(Class<?> cls, TypeDecl decl) {
        // 构造器
        for (var ctor : cls.getDeclaredConstructors()) {
            if (Modifier.isPublic(ctor.getModifiers())) {
                decl.constructors.add(reflectConstructor(ctor));
            }
        }
        // 字段
        for (var field : cls.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers())) continue;
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            FieldDecl f = new FieldDecl(field.getName(), TypeSlot.of(field.getGenericType(), toRef(field.getGenericType())));
            f.isStatic = isStatic;
            f.isFinal = Modifier.isFinal(field.getModifiers());
            f.docs.addAll(AnnotatedDocs.fieldDocs(field));
            decl.fields.add(f);
        }
        // 方法 + getter/setter 推断（与 ClassDeclGenerator 对齐）
        reflectMethodsLikeClassDecl(cls, decl);
    }

    private void reflectInterfaceMembers(Class<?> cls, TypeDecl decl) {
        for (var method : cls.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            // bridge/synthetic 是 JVM 协变覆盖的实现细节（如 LevelExtension.neko$data()
            // 覆盖 LevelSpec 的 Object 哨兵时 javac 生成 Object bridge），对 JS/Python 侧
            // 无意义且会产生「同参不同返回」的非法重载，过滤掉
            if (method.isSynthetic() || method.isBridge()) continue;
            decl.methods.add(reflectMethod(method));
        }
        for (var field : cls.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())) {
                FieldDecl f = new FieldDecl(field.getName(), TypeSlot.of(field.getGenericType(), toRef(field.getGenericType())));
                f.isStatic = true;
                f.isFinal = true;
                f.docs.addAll(AnnotatedDocs.fieldDocs(field));
                decl.fields.add(f);
            }
        }
    }

    private void reflectEnumMembers(Class<?> cls, TypeDecl decl) {
        for (var field : cls.getDeclaredFields()) {
            if (field.isEnumConstant()) {
                FieldDecl f = new FieldDecl(field.getName(), TypeSlot.of(cls, toRef(cls)));
                f.isStatic = true;
                f.isEnumConstant = true;
                f.docs.addAll(AnnotatedDocs.fieldDocs(field));
                decl.fields.add(f);
            } else if (Modifier.isPublic(field.getModifiers())) {
                // 非常量公开字段：renderer 的 renderEnum 只发射 isEnumConstant，这里仅供
                // import 收集镜像旧 collectImports（旧实现对枚举的公开字段类型也收集 import）
                FieldDecl f = new FieldDecl(field.getName(), TypeSlot.of(field.getGenericType(), toRef(field.getGenericType())));
                f.isStatic = Modifier.isStatic(field.getModifiers());
                f.isFinal = Modifier.isFinal(field.getModifiers());
                decl.fields.add(f);
            }
        }
        // 枚举的公开方法：renderEnum 不发射（固定骨架），仅用于 import 收集镜像旧行为
        reflectMethodsLikeClassDecl(cls, decl);
    }

    /**
     * 镜像 ClassDeclGenerator.generateClass 的方法枚举：
     * 非静态 getXxx/isXxx(0 参) → getter；其配对 setXxx(1 参) → setter（isSetter 标志）。
     * 其余方法按原样收集，由 renderer 按标志分段。
     *
     * <p>同一属性可能存在多个 getter 候选（协变覆盖 vs 其 bridge 方法、getFoo()/isFoo() 并存），
     * JVM 规范不保证 getDeclaredMethods 的返回顺序——first-seen 去重会跨 JVM 漂移，且 bridge 胜出时
     * 渲染出错误的（超类型）返回类型，事后排序无法修复选择。故先对候选做确定性排序：
     * 非 synthetic/bridge 优先（协变覆盖总是胜出 bridge），同优先级按签名键字典序（getFoo 先于 isFoo）。
     * 排序只影响候选选择；输出列表在 {@link #reflect} 末尾仍按 methodKey 全量排序，
     * bridge 方法本身保留在输出中（legacy ProbeJS parity，如 {@code append(arg0: string): $Appendable}）。
     */
    private void reflectMethodsLikeClassDecl(Class<?> cls, TypeDecl decl) {
        Method[] declared = cls.getDeclaredMethods();
        Arrays.sort(declared, TypeReflector::compareCandidates);
        Set<String> processedProperties = new HashSet<>();
        for (var method : declared) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            // bridge/synthetic 是 JVM 协变覆盖的实现细节（如宿主类上 mixin 注入接口的
            // 协变返回覆盖会生成 Object bridge），对 JS/Python 侧无意义且产生
            // 「同参不同返回」的冗余重载——与接口收集（reflectInterfaceMembers）一致地过滤
            if (method.isSynthetic() || method.isBridge()) continue;
            boolean isStatic = Modifier.isStatic(method.getModifiers());
            // JS 侧方法名：@Remap/@RemapByPrefix 重映射；@HideFromJS → null（跳过）
            String jsName = jsName(method);
            if (jsName == null) continue;

            // 非静态 getXxx/isXxx(0 参) → getter（按属性名去重，首个出现者胜出；重复者整体跳过，
            // 镜像旧实现：不双发射原方法名）。getter 判定基于 JS 名：neko$getId remap 为 getId
            // 后与运行时 Graal getter 属性语义一致（脚本访问 .id）
            if (!isStatic && isGetterName(jsName) && method.getParameterCount() == 0) {
                String propName = getPropertyName(jsName);
                if (propName != null && processedProperties.add(propName)) {
                    MethodDecl getter = reflectMethod(method);
                    getter.isGetter = true;
                    getter.property = propName;
                    getter.setterParamType = findSetterParamSlot(cls, propName);
                    decl.methods.add(getter);
                }
                continue;
            }

            MethodDecl m = reflectMethod(method);
            // 非静态 setXxx(1 参) → isSetter（renderer 实例方法段据此排除）
            if (!isStatic && isSetterName(jsName) && method.getParameterCount() == 1) {
                m.isSetter = true;
            }
            decl.methods.add(m);
        }
    }

    /**
     * getter/setter 候选的确定性排序：非 synthetic/bridge 的声明优先（协变覆盖胜出其 bridge），
     * 同优先级按「名 + 参数类型 + 泛型返回类型」字典序。排序结果与 JVM 返回顺序无关。
     */
    private static int compareCandidates(Method a, Method b) {
        boolean syntheticA = a.isSynthetic() || a.isBridge();
        boolean syntheticB = b.isSynthetic() || b.isBridge();
        if (syntheticA != syntheticB) return syntheticA ? 1 : -1;
        return candidateKey(a).compareTo(candidateKey(b));
    }

    private static String candidateKey(Method m) {
        StringBuilder sb = new StringBuilder(m.getName());
        for (Type p : m.getGenericParameterTypes()) sb.append('|').append(p.getTypeName());
        sb.append("→").append(m.getGenericReturnType().getTypeName());
        return sb.toString();
    }

    private MethodDecl reflectConstructor(java.lang.reflect.Constructor<?> ctor) {
        MethodDecl m = new MethodDecl(ctor.getName());
        m.isConstructor = true;
        reflectParamsInto(m, ctor);
        m.docs.addAll(AnnotatedDocs.executableDocs(ctor));
        return m;
    }

    private MethodDecl reflectMethod(java.lang.reflect.Method method) {
        MethodDecl m = new MethodDecl(method.getName());
        // JS 侧方法名（@Remap/@RemapByPrefix）：与运行时 Graal remapper 语义一致，声明/提示
        // 用 remap 名；Java 原名保留在 name（排序/编辑语义），renameTo 为空时渲染回退原名
        String jsName = jsName(method);
        if (jsName == null) {
            m.hidden = true; // @HideFromJS
            return m;
        }
        if (!jsName.equals(method.getName())) {
            m.renameTo = jsName;
        }
        m.isStatic = Modifier.isStatic(method.getModifiers());
        m.returnType = TypeSlot.of(method.getGenericReturnType(), toRef(method.getGenericReturnType()));
        for (TypeVariable<?> tv : method.getTypeParameters()) {
            m.typeParams.add(tv.getName());
        }
        reflectParamsInto(m, method);
        m.docs.addAll(AnnotatedDocs.executableDocs(method));
        return m;
    }

    /**
     * JS 侧成员名：委托 {@link JavaMemberIndex#remapName}（hideMarker 传 null = 命中
     * {@code @HideFromJS} 返回 null，调用方跳过）。未命中 remap 返回原名。
     */
    private static String jsName(java.lang.reflect.Method method) {
        return JavaMemberIndex.remapName(method, null, method.getName());
    }

    private void reflectParamsInto(MethodDecl m, java.lang.reflect.Executable exec) {
        boolean varArgs = exec.isVarArgs();
        var params = exec.getParameters();
        for (int i = 0; i < params.length; i++) {
            var p = params[i];
            Type sourceType;
            if (varArgs && i == params.length - 1 && p.getType().isArray()) {
                // varargs 必须走泛型路径：p.getType() 只给 raw Class 数组（component 的泛型实参
                // 丢失，如 MemoryModuleType<?>... → raw MemoryModuleType）；getParameterizedType()
                // 返回 GenericArrayType（component = MemoryModuleType<?>）保留下界
                Type generic = p.getParameterizedType();
                sourceType = generic instanceof GenericArrayType gat ? gat.getGenericComponentType()
                        : p.getType().getComponentType();
            } else {
                sourceType = p.getParameterizedType();
            }
            MethodDecl.MethodParam mp = new MethodDecl.MethodParam(
                    p.isNamePresent() ? p.getName() : "arg" + i,
                    // 参数位置用 paramRef：函数式接口在这里额外接受脚本传的 lambda。
                    // 非参数位置（字段/父类/接口/返回类型）仍用 toRef——那些位置不该出现联合。
                    TypeSlot.of(sourceType, paramRef(sourceType)),
                    varArgs && i == params.length - 1);
            m.params.add(mp);
        }
    }

    /**
     * 配对 setter 的入参槽：同名 setXxx(1 参) 的公开重载里确定性取一个——非 synthetic/bridge 优先，
     * 同优先级按泛型参数类型字典序（首个匹配胜出的旧实现依赖 getDeclaredMethods 顺序，跨 JVM 会漂移）。
     */
    private TypeSlot findSetterParamSlot(Class<?> cls, String propName) {
        String setterName = "set" + propName.substring(0, 1).toUpperCase(Locale.ROOT) + propName.substring(1);
        Method best = null;
        for (Method method : cls.getDeclaredMethods()) {
            String jsName = jsName(method);
            if (jsName == null || !jsName.equals(setterName) || method.getParameterCount() != 1
                    || !Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (best == null || compareCandidates(method, best) < 0) {
                best = method;
            }
        }
        if (best == null) return null;
        Type t = best.getGenericParameterTypes()[0];
        return TypeSlot.of(t, toRef(t));
    }

    /** getter 名判定基于 JS 名（remap 后）：neko$getId → getId 即 getter 形态。 */
    private static boolean isGetterName(String name) {
        return (name.startsWith("get") && name.length() > 3) || (name.startsWith("is") && name.length() > 2);
    }

    private static boolean isSetterName(String name) {
        return name.startsWith("set") && name.length() > 3;
    }

    /** 属性名：getFoo→foo、isFoo→foo。大小写归一显式用 {@link Locale#ROOT}，避免默认区域（如 tr）漂移。 */
    private static String getPropertyName(String name) {
        if (name.startsWith("get") && name.length() > 3) {
            return name.substring(3, 4).toLowerCase(Locale.ROOT) + name.substring(4);
        }
        if (name.startsWith("is") && name.length() > 2) {
            return name.substring(2, 3).toLowerCase(Locale.ROOT) + name.substring(3);
        }
        return null;
    }

    // ---- Java Type → ApiTypeRef（唯一类型映射：无损承载，语言糖由各渲染器决定）----

    /**
     * Java 反射类型 → {@link ApiTypeRef}（probe 的唯一类型映射，TS/Python 渲染均以此为准）。
     *
     * <p>保真约定（与旧 {@code TypeConverter} 的 TS 语义逐项对齐，保证双轨合并后产物零回归）：
     * <ul>
     *   <li>参数化类型 → SYMBOL(raw) **携带完整实参**（{@code Map<K,V>} 保留两个实参；
     *       语法糖——TS 的 {@code $Map<$K, $V>}、Python 的 {@code list[X]}——由各语言渲染器决定）</li>
     *   <li>有界通配符 → 上界；无界通配符 → {@code any}（对齐 TypeConverter 的 "any"，非 object）</li>
     *   <li>raw 非 Class 的参数化类型 / 未知形态 → {@code any}</li>
     * </ul>
     */
    public static ApiTypeRef toRef(Type type) {
        if (type == null || type == void.class || type == Void.class) return ApiTypeRef.voidType();
        if (type instanceof Class<?> cls) return classToRef(cls);
        if (type instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw instanceof Class<?> rawCls) {
                Type[] args = pt.getActualTypeArguments();
                List<ApiTypeRef> argRefs = new ArrayList<>(args.length);
                for (Type arg : args) {
                    argRefs.add(toRef(arg));
                }
                return ApiTypeRef.symbol(new ApiSymbolId("java", rawCls.getName()), argRefs);
            }
            return ApiTypeRef.primitive("any");
        }
        if (type instanceof GenericArrayType gat) return ApiTypeRef.array(toRef(gat.getGenericComponentType()));
        if (type instanceof TypeVariable<?> tv) return ApiTypeRef.typeVariable(tv.getName());
        if (type instanceof WildcardType wt) {
            // 通配符按上界渲染。上界恰好是 Object（`?` 与 `? super X`）时无类型信息可取，
            // 只能落到 any —— 这正是 `Predicate<? super T>` 的形态。
            Type[] upper = wt.getUpperBounds();
            if (upper.length > 0 && upper[0] != Object.class) return toRef(upper[0]);
            return ApiTypeRef.primitive("any");
        }
        return ApiTypeRef.primitive("any");
    }

    /**
     * 把 SAM 形态里**属于本接口自身形参**的名字，换成声明处该写的类型。
     *
     * <p>{@link #samSignature} 按接口自身形态渲染，形态里出现的是**接口形参名**
     * （{@code Supplier<T>.get()} → {@code () => T}）。这个 {@code T} 在声明处是否可直接引用，
     * 取决于实参怎么写：实参已确定（{@code Supplier<String>}）就该写 {@code string}；
     * 实参是调用方的类型变量（{@code Supplier<A>}）就该写 {@code A}——形态给的 {@code T}
     * 在调用处根本不存在，正是"无中生有"的来源；实参被通配符抹掉时没有可用信息，
     * 只能保留接口形参名。
     */
    private static ApiSignature applySignatureSubstitution(ApiSignature signature, Type declared, ApiTypeRef declaredRef) {
        return applySignatureSubstitution(signature, staticParameterSubstitution(declared, declaredRef));
    }

    /** 按映射替换签名里的类型变量（参数 + 返回）；映射为空时原样返回。 */
    private static ApiSignature applySignatureSubstitution(ApiSignature signature, Map<String, ApiTypeRef> mapping) {
        if (mapping.isEmpty()) {
            return signature;
        }
        List<ApiParameter> params = new ArrayList<>(signature.parameters().size());
        for (ApiParameter p : signature.parameters()) {
            params.add(new ApiParameter(p.name(), substituteTypeVariables(p.type(), mapping), p.optional(), p.varargs()));
        }
        return new ApiSignature(params, substituteTypeVariables(signature.returnType(), mapping), signature.isConstructor());
    }

    /**
     * 本接口形参名 → 声明处该写的类型（见 {@link #applySignatureSubstitution} 的说明）。
     */
    private static Map<String, ApiTypeRef> staticParameterSubstitution(Type declared, ApiTypeRef declaredRef) {
        if (!(declared instanceof ParameterizedType pt) || declaredRef == null) {
            return Map.of();
        }
        Type raw = pt.getRawType();
        List<ApiTypeRef> refArgs = declaredRef.arguments();
        TypeVariable<?>[] vars = rawTypeParameters(raw);
        Type[] args = pt.getActualTypeArguments();
        if (vars == null || args == null || args.length != vars.length || refArgs.size() != vars.length) {
            return Map.of();
        }
        Set<String> ownVarNames = ownTypeParameterNames(raw);
        Map<String, ApiTypeRef> fill = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            // 形态里出现的是**接口形参名**（Supplier<T>.get() 渲染成 `() => T`），
            // 所以键是 vars[i] 的名字，值才是声明处该写的东西。
            String paramName = vars[i].getName();
            if (!ownVarNames.contains(paramName)) {
                continue;
            }
            ApiTypeRef target = argumentType(args[i]);
            if (target != null) {
                fill.put(paramName, target);
            }
        }
        return fill;
    }

    /** 原始类的类型形参；拿不到时返回 null。 */
    @Nullable
    private static TypeVariable<?>[] rawTypeParameters(Type raw) {
        return raw instanceof Class<?> c ? c.getTypeParameters() : null;
    }

    /**
     * 求 SAM 里那些**属于其声明者**的类型变量在声明处该写成什么。
     *
     * <p>SAM 常是继承来的：{@code UnaryOperator<T> extends Function<T, T>} 的抽象方法是
     * {@code Function.apply(T): R}，而 {@code T}/{@code R} 都是 {@code Function} 的形参，
     * 在只声明了一个形参的 {@code UnaryOperator} 里并不存在，光看实参也无从回填
     * （该接口只接一个实参，而 {@code Function} 要两个）。沿**泛型父接口链**把
     * {@code Function} 的形参与 {@code UnaryOperator<T>} 的实参对上，才能把形态
     * {@code (T) => R} 正确还原成 {@code (E) => E}。
     */
    private static ApiSignature resolveInherited(ApiSignature signature, Type declared, Class<?> samOwner) {
        Map<String, ApiTypeRef> bindings = samOwnerBindings(declared, samOwner);
        if (bindings.isEmpty()) {
            return signature;
        }
        // SAM 就声明在本接口上时，它的形参归 applySignatureSubstitution 管（按实参还原）。
        // 这里只补**声明者自己**的形参——继承来的 SAM 才需要它们，且它们不是本接口的形参名。
        Set<String> ownedBySamDeclarer = ownTypeParameterNames(samOwner);
        Set<String> ownedByInterface = declared instanceof Class<?> c
                ? ownTypeParameterNames(c) : ownTypeParameterNames(rawClassOf(declared));
        Set<String> usable = new HashSet<>();
        for (String name : ownedBySamDeclarer) {
            if (!ownedByInterface.contains(name)) {
                usable.add(name);
            }
        }
        if (usable.isEmpty()) {
            return signature;
        }
        Map<String, ApiTypeRef> mapping = new HashMap<>();
        for (Map.Entry<String, ApiTypeRef> e : bindings.entrySet()) {
            if (usable.contains(e.getKey()) && e.getValue() != null) {
                mapping.put(e.getKey(), e.getValue());
            }
        }
        return applySignatureSubstitution(signature, mapping);
    }

    /** SAM 声明者的形参名 → 它在 {@code declared} 上的类型引用；未覆盖到的名字值为 null。 */
    private static Map<String, ApiTypeRef> samOwnerBindings(Type declared, Class<?> samOwner) {
        Class<?> raw = rawClassOf(declared);
        if (raw == null) {
            return Map.of();
        }
        return searchSupertype(raw, samOwner, rootSubstitution(declared), new HashSet<>());
    }

    /** {@code declared} 的实参列表，按 raw 的形参名建表；非参数化类型返回空表。 */
    private static Map<String, ApiTypeRef> rootSubstitution(Type declared) {
        if (!(declared instanceof ParameterizedType pt) || !(pt.getRawType() instanceof Class<?> raw)) {
            return Map.of();
        }
        TypeVariable<?>[] vars = raw.getTypeParameters();
        Type[] args = pt.getActualTypeArguments();
        Map<String, ApiTypeRef> subst = new HashMap<>();
        for (int i = 0; i < vars.length && i < args.length; i++) {
            subst.put(vars[i].getName(), toRef(args[i]));
        }
        return subst;
    }

    /** 深度优先找 {@code target}；找到时返回它那一层的形参绑定表。 */
    @Nullable
    private static Map<String, ApiTypeRef> searchSupertype(Class<?> raw, Class<?> target,
                                                          Map<String, ApiTypeRef> subst, Set<String> visited) {
        if (raw == target) {
            return subst;
        }
        if (!visited.add(raw.getName())) {
            return null;
        }
        List<Type> parents = new ArrayList<>(Arrays.asList(raw.getGenericInterfaces()));
        Type superclass = raw.getGenericSuperclass();
        if (superclass != null) {
            parents.add(superclass);
        }
        for (Type parent : parents) {
            Class<?> parentRaw = rawClassOf(parent);
            if (parentRaw == null || parentRaw == Object.class) {
                continue;
            }
            Map<String, ApiTypeRef> next = parentSubstitution(parent, parentRaw, subst);
            Map<String, ApiTypeRef> found = searchSupertype(parentRaw, target, next, visited);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 父类型的形参名 → 用当前 subst 展开后的实参（把父实参里的变量换成 {@code declared} 上的引用）。 */
    private static Map<String, ApiTypeRef> parentSubstitution(Type parent, Class<?> parentRaw,
                                                             Map<String, ApiTypeRef> subst) {
        TypeVariable<?>[] vars = parentRaw.getTypeParameters();
        Type[] args = parent instanceof ParameterizedType pt ? pt.getActualTypeArguments() : new Type[0];
        Map<String, ApiTypeRef> nonNull = new HashMap<>();
        for (Map.Entry<String, ApiTypeRef> e : subst.entrySet()) {
            if (e.getValue() != null) {
                nonNull.put(e.getKey(), e.getValue());
            }
        }
        Map<String, ApiTypeRef> next = new HashMap<>();
        for (int i = 0; i < vars.length; i++) {
            next.put(vars[i].getName(),
                    i < args.length ? substituteTypeVariables(toRef(args[i]), nonNull) : null);
        }
        return next;
    }

    /**
     * 最后一道兜底，在**整个类型**构建完之后跑一次：把仍未解释、且在任何位置都不可达的
     * 类型变量写成 {@code any}——宁可宽松，也不留一个未绑定的名字（那会让编辑器把整行标红）。
     *
     * <p>可达集合按**成员所属的类**（泛型父链）与**成员自身**的形参名收集，而不是按使用点。
     * 类型变量的作用域是「声明它的类或方法」，由反射拿到的 {@code $UnaryOperator<E>}
     * 无法表达这一点：其泛型父 {@code $Function} 同样声明 {@code T}/{@code R}，
     * 而反射拿不到父接口的实参。按类的父链收集能把这类名字正确认定为可达。
     */
    private void dropUnreachableTypeVars(TypeDecl decl) {
        if (decl.sourceClass == null) {
            return;
        }
        Set<String> reachable = new HashSet<>(ownTypeParameterNames(decl.sourceClass));
        for (Class<?> c = decl.sourceClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> iface : c.getInterfaces()) {
                collectTypeParameterNames(iface, reachable);
            }
        }
        for (MethodDecl m : decl.methods) {
            Set<String> names = new HashSet<>();
            collectSignatureTypeVars(m, names);
            Map<String, ApiTypeRef> anyMap = new HashMap<>();
            for (String name : names) {
                if (!m.typeParams.contains(name) && !reachable.contains(name)) {
                    anyMap.put(name, ApiTypeRef.primitive("any"));
                }
            }
            applySignatureSubstitution(m, anyMap);
        }
        for (MethodDecl ctor : decl.constructors) {
            Set<String> names = new HashSet<>();
            collectSignatureTypeVars(ctor, names);
            Map<String, ApiTypeRef> anyMap = new HashMap<>();
            for (String name : names) {
                if (!reachable.contains(name)) {
                    anyMap.put(name, ApiTypeRef.primitive("any"));
                }
            }
            applySignatureSubstitution(ctor, anyMap);
        }
    }

    /** 递归收集类（含泛型父链）声明的全部类型形参名。 */
    private static void collectTypeParameterNames(@Nullable Class<?> cls, Set<String> out) {
        if (cls == null || cls == Object.class) {
            return;
        }
        for (TypeVariable<?> v : cls.getTypeParameters()) {
            out.add(v.getName());
        }
        for (Class<?> iface : cls.getInterfaces()) {
            collectTypeParameterNames(iface, out);
        }
        collectTypeParameterNames(cls.getSuperclass(), out);
    }

    /** 收集方法签名（参数 + 返回）里出现的全部类型变量名。 */
    private static void collectSignatureTypeVars(MethodDecl m, Set<String> out) {
        for (MethodDecl.MethodParam p : m.params) {
            if (p.type != null) {
                collectTypeVariableNames(p.type.ref, out);
            }
        }
        if (m.returnType != null) {
            collectTypeVariableNames(m.returnType.ref, out);
        }
    }

    /** 按映射替换方法签名里的类型变量（参数与返回，原地改写 TypeSlot.ref）。 */
    private static void applySignatureSubstitution(MethodDecl m, Map<String, ApiTypeRef> mapping) {
        if (mapping.isEmpty()) {
            return;
        }
        for (MethodDecl.MethodParam p : m.params) {
            if (p.type != null && p.type.ref != null) {
                p.type.ref = substituteTypeVariables(p.type.ref, mapping);
            }
        }
        if (m.returnType != null && m.returnType.ref != null) {
            m.returnType.ref = substituteTypeVariables(m.returnType.ref, mapping);
        }
    }

    /** 收集引用里出现的全部类型变量名（递归进入泛型实参与**回调签名**）。 */
    private static void collectTypeVariableNames(@Nullable ApiTypeRef ref, Set<String> out) {
        if (ref == null) {
            return;
        }
        if (ref.kind() == ApiTypeRef.Kind.TYPE_VARIABLE) {
            out.add(ref.name());
            return;
        }
        for (ApiTypeRef arg : ref.arguments()) {
            collectTypeVariableNames(arg, out);
        }
        // 回调签名里的类型变量同样要参与可达性判定，否则漏网的会被整行标红
        ApiSignature signature = ref.callbackSignature();
        if (signature != null) {
            for (ApiParameter p : signature.parameters()) {
                collectTypeVariableNames(p.type(), out);
            }
            collectTypeVariableNames(signature.returnType(), out);
        }
    }

    /**
     * 实参位置上真正能交给函数式接口的东西。
     *
     * <p>通配符取**下界**（{@code ? super X} → {@code X}）：{@code Predicate<? super E>} 的参数
     * 位置接受任何能消费 {@code E} 的谓词，脚本写 lambda 时按 {@code E} 写才与调用方一致，
     * 也与同声明里 {@code test(arg0: E)} 的写法对得上。X 本身是类型变量时同样照取——
     * 在 {@code $Collection<E>} 里它就是 {@code E}。
     *
     * <p>{@code ? extends X} 取不到下界，退回上界 X；无界 {@code ?} 两边都没有信息，返回 null
     * 表示**不做替换**：此时反射已还原不出实参（{@code Iterable.forEach(Consumer<? super T>)}
     * 的 T 属于 Iterable、{@code BiConsumer.andThen(BiConsumer<? super T, ? super U>)} 的 T/U
     * 属于 BiConsumer），而接口形参名未必在调用处可达，猜一个名字不如交给
     * {@link #dropUnreachableTypeVars} 兜底。
     *
     * <p>实参类型是数组时按**组件类型**处理：接在接口形参上的数组对应
     * {@code interface X<T> { accept(T) }} 的用法（如 {@code Consumer<String[]>} 对应
     * {@code accept(String[])}），而 rest 渲染会在组件类型后补 {@code []}。
     */
    @Nullable
    private static ApiTypeRef argumentType(Type arg) {
        if (arg instanceof WildcardType wt) {
            Type[] lower = wt.getLowerBounds();
            if (lower.length > 0) {
                return arrayElement(toRef(lower[0]));
            }
            Type[] upper = wt.getUpperBounds();
            if (upper.length > 0 && upper[0] != Object.class) {
                return arrayElement(toRef(upper[0]));
            }
            return null;
        }
        return arrayElement(toRef(arg));
    }

    /** 数组引用取其组件类型，供接在函数式接口形参上时使用；非数组原样返回。 */
    @Nullable
    private static ApiTypeRef arrayElement(@Nullable ApiTypeRef ref) {
        if (ref != null && ref.kind() == ApiTypeRef.Kind.ARRAY && !ref.arguments().isEmpty()) {
            return ref.arguments().getFirst();
        }
        return ref;
    }

    /**
     * 用给定映射替换引用中的类型变量（递归进入泛型实参）。
     * 映射里没有的名字原样保留。实参未变时直接返回原引用。
     */
    private static ApiTypeRef substituteTypeVariables(ApiTypeRef ref, Map<String, ApiTypeRef> mapping) {
        if (ref == null || mapping.isEmpty()) {
            return ref;
        }
        if (ref.kind() == ApiTypeRef.Kind.TYPE_VARIABLE) {
            return mapping.getOrDefault(ref.name(), ref);
        }
        // CALLBACK 的类型变量挂在**签名**上而非 arguments 上，单独处理
        if (ref.kind() == ApiTypeRef.Kind.CALLBACK) {
            ApiSignature signature = ref.callbackSignature();
            if (signature == null) {
                return ref;
            }
            List<ApiParameter> params = new ArrayList<>(signature.parameters().size());
            boolean changed = false;
            for (ApiParameter p : signature.parameters()) {
                ApiTypeRef sub = substituteTypeVariables(p.type(), mapping);
                changed |= sub != p.type();
                params.add(new ApiParameter(p.name(), sub, p.optional(), p.varargs()));
            }
            ApiTypeRef ret = substituteTypeVariables(signature.returnType(), mapping);
            changed |= ret != signature.returnType();
            return changed
                    ? ApiTypeRef.callback(new ApiSignature(params, ret, signature.isConstructor()))
                    : ref;
        }
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        List<ApiTypeRef> replaced = new ArrayList<>(ref.arguments().size());
        boolean changed = false;
        for (ApiTypeRef arg : ref.arguments()) {
            ApiTypeRef sub = substituteTypeVariables(arg, mapping);
            changed |= sub != arg;
            replaced.add(sub);
        }
        if (!changed) {
            return ref;
        }
        // 实参改变了就必须重建：ApiTypeRef 是不可变记录，泛型实参挂在 arguments 上。
        return switch (ref.kind()) {
            case SYMBOL -> ApiTypeRef.symbol(ApiSymbolId.parse(ref.name()), replaced);
            case UNION -> ApiTypeRef.union(replaced);
            case ARRAY -> ApiTypeRef.array(replaced.getFirst());
            default -> ref;
        };
    }

    private static ApiTypeRef classToRef(Class<?> cls) {
        if (cls == void.class || cls == Void.class) return ApiTypeRef.voidType();
        if (cls == String.class || cls == char.class) return ApiTypeRef.primitive("string");
        if (cls == boolean.class || cls == Boolean.class) return ApiTypeRef.primitive("boolean");
        if (cls == float.class || cls == Float.class || cls == double.class || cls == Double.class) {
            return ApiTypeRef.primitive("float");
        }
        if (cls.isPrimitive() || Number.class.isAssignableFrom(cls)) return ApiTypeRef.primitive("int");
        if (cls == Object.class) return ApiTypeRef.primitive("object");
        if (cls.isArray()) return ApiTypeRef.array(classToRef(cls.getComponentType()));
        return ApiTypeRef.symbol(new ApiSymbolId("java", cls.getName()));
    }
}
