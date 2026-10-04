package com.tkisor.nekojs.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces the probe-generated TypeScript declaration of the annotated class with
 * hand-written text.
 *
 * <p>Reflection cannot express everything: generics are erased, and type information
 * that lives only in runtime conventions (such as a builder whose argument types are
 * carried by string literals) is invisible to {@code java.lang.reflect}. The generated
 * {@code .d.ts} then loses that information and script authors get {@code any}.
 *
 * <p>Once a declaration is emitted there is no way to override it from TypeScript —
 * a class cannot be redeclared, and interface augmentation loses to the original
 * overloads. This annotation therefore swaps the declaration at the generation site.
 *
 * <h2>Placeholders</h2>
 *
 * Both {@link #extra()} and {@link #type()} may contain {@code {{ ... }}} placeholders:
 *
 * <ul>
 *   <li>{@code {{ classtype }}} — this class's TS name (e.g. {@code $RpcBuilder})</li>
 *   <li>{@code {{ extra.Name }}} — {@code Name} inside this class's extra namespace</li>
 *   <li>{@code {{ import(a.b.C) }}} — the TS name of {@code C}, and registers an import</li>
 * </ul>
 *
 * <p>{@code {{ import(...) }}} is the <b>only</b> way to produce an import for an
 * annotated class: reflection-based import collection is skipped for such classes
 * (it can neither see type-level references nor avoid emitting dead ones).
 *
 * <p>Literal {@code {{} / {@code }}} are written {@code \{\{} / {@code \}\}}.
 * An unclosed {@code {{} is an error rather than being treated as plain text.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @NekoProbe(
 *     extra = """
 *         export type SType = "string" | "block" | "int";
 *         export interface STypeMap { string: string; block: {{ import(net.minecraft.world.level.block.Block) }}; int: number; }
 *         export type ArgsOf<T extends Record<string, SType>> = { [K in keyof T]: STypeMap[T[K]] };
 *         """,
 *     type = """
 *         export class {{ classtype }}<
 *             T extends Record<string, {{ extra.SType }}> = {},
 *         > {
 *             schema<const S extends { [K in keyof S]: {{ extra.SType }} }>(sch: S): {{ classtype }}<S>;
 *             fn(impl: (arg: {{ extra.ArgsOf }}<T>) => void): {{ import(a.b.Entry) }};
 *         }
 *         """)
 * public final class RpcBuilder { ... }
 * }</pre>
 *
 * @see com.tkisor.nekojs.api.annotation.Doc
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface NekoProbe {

    /**
     * Helper declarations belonging to this type but not the type itself
     * ({@code type} / {@code interface} / constants).
     *
     * <p>Emitted inside a namespace derived from the class name
     * ({@code $ClassName$$$Extra}) and referenced via {@code {{ extra.X }}}.
     * Empty (the default) emits no namespace.
     */
    String extra() default "";

    /**
     * The class's final TypeScript declaration, replacing the reflection output entirely.
     *
     * <p>Nothing else is rendered for this class — no members, no heritage clause,
     * no JSDoc. Write everything the declaration needs.
     */
    String type();
}
