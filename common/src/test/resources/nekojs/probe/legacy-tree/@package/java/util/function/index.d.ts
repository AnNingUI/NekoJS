import { $Comparator } from "java:java/util";

declare module "java:java/util/function" {
    export interface $BiConsumer<T, U> {
        accept(arg0: T, arg1: U): void;
        andThen(arg0: ((arg0: T, arg1: U) => void) | $BiConsumer<any, any>): $BiConsumer<T, U>;
    }

    export interface $BiFunction<T, U, R> {
        andThen<V>(arg0: ((arg0: R) => V) | $Function<any, V>): $BiFunction<T, U, V>;
        apply(arg0: T, arg1: U): R;
    }

    export interface $BinaryOperator<T> extends $BiFunction<T, T, T> {
        maxBy<T>(arg0: $Comparator<any>): $BinaryOperator<T>;
        minBy<T>(arg0: $Comparator<any>): $BinaryOperator<T>;
    }

    export interface $Consumer<T> {
        accept(arg0: T): void;
        andThen(arg0: ((arg0: T) => void) | $Consumer<any>): $Consumer<T>;
    }

    export interface $DoubleBinaryOperator {
        applyAsDouble(arg0: number, arg1: number): number;
    }

    export interface $DoubleConsumer {
        accept(arg0: number): void;
        andThen(arg0: ((arg0: number) => void) | $DoubleConsumer): $DoubleConsumer;
    }

    export interface $DoubleFunction<R> {
        apply(arg0: number): R;
    }

    export interface $DoublePredicate {
        and(arg0: ((arg0: number) => boolean) | $DoublePredicate): $DoublePredicate;
        negate(): $DoublePredicate;
        or(arg0: ((arg0: number) => boolean) | $DoublePredicate): $DoublePredicate;
        test(arg0: number): boolean;
    }

    export interface $DoubleSupplier {
        getAsDouble(): number;
    }

    export interface $DoubleToIntFunction {
        applyAsInt(arg0: number): number;
    }

    export interface $DoubleToLongFunction {
        applyAsLong(arg0: number): number;
    }

    export interface $DoubleUnaryOperator {
        andThen(arg0: ((arg0: number) => number) | $DoubleUnaryOperator): $DoubleUnaryOperator;
        applyAsDouble(arg0: number): number;
        compose(arg0: ((arg0: number) => number) | $DoubleUnaryOperator): $DoubleUnaryOperator;
        identity(): $DoubleUnaryOperator;
    }

    export interface $Function<T, R> {
        andThen<V>(arg0: ((arg0: R) => V) | $Function<any, V>): $Function<T, V>;
        apply(arg0: T): R;
        compose<V>(arg0: ((arg0: V) => T) | $Function<any, T>): $Function<V, R>;
        identity<T>(): $Function<T, T>;
    }

    export interface $IntBinaryOperator {
        applyAsInt(arg0: number, arg1: number): number;
    }

    export interface $IntConsumer {
        accept(arg0: number): void;
        andThen(arg0: ((arg0: number) => void) | $IntConsumer): $IntConsumer;
    }

    export interface $IntFunction<R> {
        apply(arg0: number): R;
    }

    export interface $IntPredicate {
        and(arg0: ((arg0: number) => boolean) | $IntPredicate): $IntPredicate;
        negate(): $IntPredicate;
        or(arg0: ((arg0: number) => boolean) | $IntPredicate): $IntPredicate;
        test(arg0: number): boolean;
    }

    export interface $IntSupplier {
        getAsInt(): number;
    }

    export interface $IntToDoubleFunction {
        applyAsDouble(arg0: number): number;
    }

    export interface $IntToLongFunction {
        applyAsLong(arg0: number): number;
    }

    export interface $IntUnaryOperator {
        andThen(arg0: ((arg0: number) => number) | $IntUnaryOperator): $IntUnaryOperator;
        applyAsInt(arg0: number): number;
        compose(arg0: ((arg0: number) => number) | $IntUnaryOperator): $IntUnaryOperator;
        identity(): $IntUnaryOperator;
    }

    export interface $LongBinaryOperator {
        applyAsLong(arg0: number, arg1: number): number;
    }

    export interface $LongConsumer {
        accept(arg0: number): void;
        andThen(arg0: ((arg0: number) => void) | $LongConsumer): $LongConsumer;
    }

    export interface $LongFunction<R> {
        apply(arg0: number): R;
    }

    export interface $LongPredicate {
        and(arg0: ((arg0: number) => boolean) | $LongPredicate): $LongPredicate;
        negate(): $LongPredicate;
        or(arg0: ((arg0: number) => boolean) | $LongPredicate): $LongPredicate;
        test(arg0: number): boolean;
    }

    export interface $LongSupplier {
        getAsLong(): number;
    }

    export interface $LongToDoubleFunction {
        applyAsDouble(arg0: number): number;
    }

    export interface $LongToIntFunction {
        applyAsInt(arg0: number): number;
    }

    export interface $LongUnaryOperator {
        andThen(arg0: ((arg0: number) => number) | $LongUnaryOperator): $LongUnaryOperator;
        applyAsLong(arg0: number): number;
        compose(arg0: ((arg0: number) => number) | $LongUnaryOperator): $LongUnaryOperator;
        identity(): $LongUnaryOperator;
    }

    export interface $ObjDoubleConsumer<T> {
        accept(arg0: T, arg1: number): void;
    }

    export interface $ObjIntConsumer<T> {
        accept(arg0: T, arg1: number): void;
    }

    export interface $ObjLongConsumer<T> {
        accept(arg0: T, arg1: number): void;
    }

    export interface $Predicate<T> {
        and(arg0: ((arg0: T) => boolean) | $Predicate<any>): $Predicate<T>;
        isEqual<T>(arg0: any): $Predicate<T>;
        negate(): $Predicate<T>;
        not<T>(arg0: ((arg0: T) => boolean) | $Predicate<any>): $Predicate<T>;
        or(arg0: ((arg0: T) => boolean) | $Predicate<any>): $Predicate<T>;
        test(arg0: T): boolean;
    }

    export interface $Supplier<T> {
        get(): T;
    }

    export interface $ToDoubleFunction<T> {
        applyAsDouble(arg0: T): number;
    }

    export interface $ToIntFunction<T> {
        applyAsInt(arg0: T): number;
    }

    export interface $ToLongFunction<T> {
        applyAsLong(arg0: T): number;
    }

    export interface $UnaryOperator<T> extends $Function<T, T> {
        identity<T>(): $UnaryOperator<T>;
    }

    export type $BiConsumer_<T, U> = (T, U) => void;
    export type $BiFunction_<T, U, R> = (T, U) => any;
    export type $BinaryOperator_<T> = (T, T) => T;
    export type $Consumer_<T> = (T) => void;
    export type $Function_<T, R> = (T) => R;
    export type $Predicate_<T> = (T) => boolean;
    export type $Supplier_<T> = () => T;
    export type $UnaryOperator_<T> = (T) => {1};
}
