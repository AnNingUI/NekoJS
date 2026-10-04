import { $AutoCloseable, $Double, $Enum, $Integer, $Long, $Runnable, $String } from "java:java/lang";
import { $Comparator, $DoubleSummaryStatistics, $IntSummaryStatistics, $Iterator, $List, $LongSummaryStatistics, $Optional, $OptionalDouble, $OptionalInt, $OptionalLong, $PrimitiveIterator$OfDouble, $PrimitiveIterator$OfInt, $PrimitiveIterator$OfLong, $Set, $Spliterator, $Spliterator$OfDouble, $Spliterator$OfInt, $Spliterator$OfLong } from "java:java/util";
import { $BiConsumer, $BiFunction, $BinaryOperator, $Consumer, $DoubleBinaryOperator, $DoubleConsumer, $DoubleFunction, $DoublePredicate, $DoubleSupplier, $DoubleToIntFunction, $DoubleToLongFunction, $DoubleUnaryOperator, $Function, $IntBinaryOperator, $IntConsumer, $IntFunction, $IntPredicate, $IntSupplier, $IntToDoubleFunction, $IntToLongFunction, $IntUnaryOperator, $LongBinaryOperator, $LongConsumer, $LongFunction, $LongPredicate, $LongSupplier, $LongToDoubleFunction, $LongToIntFunction, $LongUnaryOperator, $ObjDoubleConsumer, $ObjIntConsumer, $ObjLongConsumer, $Predicate, $Supplier, $ToDoubleFunction, $ToIntFunction, $ToLongFunction, $UnaryOperator } from "java:java/util/function";

declare module "java:java/util/stream" {
    export interface $BaseStream<T, S extends $BaseStream<T, S>> extends $AutoCloseable {
        close(): void;
        isParallel(): boolean;
        iterator(): $Iterator<T>;
        onClose(arg0: (() => void) | $Runnable): S;
        parallel(): S;
        sequential(): S;
        spliterator(): $Spliterator<T>;
        unordered(): S;
    }

    export interface $Collector<T, A, R> {
        accumulator(): $BiConsumer<A, T>;
        characteristics(): $Set<$Collector$Characteristics>;
        combiner(): $BinaryOperator<A>;
        finisher(): $Function<A, R>;
        of<T, A, R>(arg0: (() => A) | $Supplier<A>, arg1: ((arg0: A, arg1: T) => void) | $BiConsumer<A, T>, arg2: ((arg0: A, arg1: A) => A) | $BinaryOperator<A>, arg3: ((arg0: A) => R) | $Function<A, R>, ...arg4: $Collector$Characteristics_[]): $Collector<T, A, R>;
        of<T, R>(arg0: (() => R) | $Supplier<R>, arg1: ((arg0: R, arg1: T) => void) | $BiConsumer<R, T>, arg2: ((arg0: R, arg1: R) => R) | $BinaryOperator<R>, ...arg3: $Collector$Characteristics_[]): $Collector<T, R, R>;
        supplier(): $Supplier<A>;
    }

    export class $Collector$Characteristics {
        static CONCURRENT: $Collector$Characteristics;
        static IDENTITY_FINISH: $Collector$Characteristics;
        static UNORDERED: $Collector$Characteristics;
        name(): string;
        ordinal(): number;
        toString(): string;
        static values(): $Collector$Characteristics[];
        static valueOf(name: string): $Collector$Characteristics;
    }

    export interface $DoubleStream extends $BaseStream {
        allMatch(arg0: ((arg0: number) => boolean) | $DoublePredicate): boolean;
        anyMatch(arg0: ((arg0: number) => boolean) | $DoublePredicate): boolean;
        average(): $OptionalDouble;
        boxed(): $Stream<number>;
        builder(): $DoubleStream$Builder;
        collect<R>(arg0: (() => R) | $Supplier<R>, arg1: ((arg0: R, arg1: number) => void) | $ObjDoubleConsumer<R>, arg2: ((arg0: R, arg1: R) => void) | $BiConsumer<R, R>): R;
        concat(arg0: $DoubleStream, arg1: $DoubleStream): $DoubleStream;
        count(): number;
        distinct(): $DoubleStream;
        dropWhile(arg0: ((arg0: number) => boolean) | $DoublePredicate): $DoubleStream;
        empty(): $DoubleStream;
        filter(arg0: ((arg0: number) => boolean) | $DoublePredicate): $DoubleStream;
        findAny(): $OptionalDouble;
        findFirst(): $OptionalDouble;
        flatMap(arg0: ((arg0: number) => $DoubleStream) | $DoubleFunction<$DoubleStream>): $DoubleStream;
        forEachOrdered(arg0: ((arg0: number) => void) | $DoubleConsumer): void;
        forEach(arg0: ((arg0: number) => void) | $DoubleConsumer): void;
        generate(arg0: (() => number) | $DoubleSupplier): $DoubleStream;
        iterate(arg0: number, arg1: ((arg0: number) => boolean) | $DoublePredicate, arg2: ((arg0: number) => number) | $DoubleUnaryOperator): $DoubleStream;
        iterate(arg0: number, arg1: ((arg0: number) => number) | $DoubleUnaryOperator): $DoubleStream;
        iterator(): $PrimitiveIterator$OfDouble;
        limit(arg0: number): $DoubleStream;
        mapMulti(arg0: ((arg0: number, arg1: $DoubleConsumer) => void) | $DoubleStream$DoubleMapMultiConsumer): $DoubleStream;
        mapToInt(arg0: ((arg0: number) => number) | $DoubleToIntFunction): $IntStream;
        mapToLong(arg0: ((arg0: number) => number) | $DoubleToLongFunction): $LongStream;
        mapToObj<U>(arg0: ((arg0: number) => U) | $DoubleFunction<U>): $Stream<U>;
        map(arg0: ((arg0: number) => number) | $DoubleUnaryOperator): $DoubleStream;
        max(): $OptionalDouble;
        min(): $OptionalDouble;
        noneMatch(arg0: ((arg0: number) => boolean) | $DoublePredicate): boolean;
        of(...arg0: number[]): $DoubleStream;
        of(arg0: number): $DoubleStream;
        parallel(): $DoubleStream;
        peek(arg0: ((arg0: number) => void) | $DoubleConsumer): $DoubleStream;
        reduce(arg0: number, arg1: ((arg0: number, arg1: number) => number) | $DoubleBinaryOperator): number;
        reduce(arg0: ((arg0: number, arg1: number) => number) | $DoubleBinaryOperator): $OptionalDouble;
        sequential(): $DoubleStream;
        skip(arg0: number): $DoubleStream;
        sorted(): $DoubleStream;
        spliterator(): $Spliterator$OfDouble;
        summaryStatistics(): $DoubleSummaryStatistics;
        sum(): number;
        takeWhile(arg0: ((arg0: number) => boolean) | $DoublePredicate): $DoubleStream;
        toArray(): number[];
    }

    export interface $DoubleStream$Builder extends $DoubleConsumer {
        accept(arg0: number): void;
        add(arg0: number): $DoubleStream$Builder;
        build(): $DoubleStream;
    }

    export interface $DoubleStream$DoubleMapMultiConsumer {
        accept(arg0: number, arg1: ((arg0: number) => void) | $DoubleConsumer): void;
    }

    export interface $IntStream extends $BaseStream {
        allMatch(arg0: ((arg0: number) => boolean) | $IntPredicate): boolean;
        anyMatch(arg0: ((arg0: number) => boolean) | $IntPredicate): boolean;
        asDoubleStream(): $DoubleStream;
        asLongStream(): $LongStream;
        average(): $OptionalDouble;
        boxed(): $Stream<number>;
        builder(): $IntStream$Builder;
        collect<R>(arg0: (() => R) | $Supplier<R>, arg1: ((arg0: R, arg1: number) => void) | $ObjIntConsumer<R>, arg2: ((arg0: R, arg1: R) => void) | $BiConsumer<R, R>): R;
        concat(arg0: $IntStream, arg1: $IntStream): $IntStream;
        count(): number;
        distinct(): $IntStream;
        dropWhile(arg0: ((arg0: number) => boolean) | $IntPredicate): $IntStream;
        empty(): $IntStream;
        filter(arg0: ((arg0: number) => boolean) | $IntPredicate): $IntStream;
        findAny(): $OptionalInt;
        findFirst(): $OptionalInt;
        flatMap(arg0: ((arg0: number) => $IntStream) | $IntFunction<$IntStream>): $IntStream;
        forEachOrdered(arg0: ((arg0: number) => void) | $IntConsumer): void;
        forEach(arg0: ((arg0: number) => void) | $IntConsumer): void;
        generate(arg0: (() => number) | $IntSupplier): $IntStream;
        iterate(arg0: number, arg1: ((arg0: number) => boolean) | $IntPredicate, arg2: ((arg0: number) => number) | $IntUnaryOperator): $IntStream;
        iterate(arg0: number, arg1: ((arg0: number) => number) | $IntUnaryOperator): $IntStream;
        iterator(): $PrimitiveIterator$OfInt;
        limit(arg0: number): $IntStream;
        mapMulti(arg0: ((arg0: number, arg1: $IntConsumer) => void) | $IntStream$IntMapMultiConsumer): $IntStream;
        mapToDouble(arg0: ((arg0: number) => number) | $IntToDoubleFunction): $DoubleStream;
        mapToLong(arg0: ((arg0: number) => number) | $IntToLongFunction): $LongStream;
        mapToObj<U>(arg0: ((arg0: number) => U) | $IntFunction<U>): $Stream<U>;
        map(arg0: ((arg0: number) => number) | $IntUnaryOperator): $IntStream;
        max(): $OptionalInt;
        min(): $OptionalInt;
        noneMatch(arg0: ((arg0: number) => boolean) | $IntPredicate): boolean;
        of(...arg0: number[]): $IntStream;
        of(arg0: number): $IntStream;
        parallel(): $IntStream;
        peek(arg0: ((arg0: number) => void) | $IntConsumer): $IntStream;
        rangeClosed(arg0: number, arg1: number): $IntStream;
        range(arg0: number, arg1: number): $IntStream;
        reduce(arg0: number, arg1: ((arg0: number, arg1: number) => number) | $IntBinaryOperator): number;
        reduce(arg0: ((arg0: number, arg1: number) => number) | $IntBinaryOperator): $OptionalInt;
        sequential(): $IntStream;
        skip(arg0: number): $IntStream;
        sorted(): $IntStream;
        spliterator(): $Spliterator$OfInt;
        summaryStatistics(): $IntSummaryStatistics;
        sum(): number;
        takeWhile(arg0: ((arg0: number) => boolean) | $IntPredicate): $IntStream;
        toArray(): number[];
    }

    export interface $IntStream$Builder extends $IntConsumer {
        accept(arg0: number): void;
        add(arg0: number): $IntStream$Builder;
        build(): $IntStream;
    }

    export interface $IntStream$IntMapMultiConsumer {
        accept(arg0: number, arg1: ((arg0: number) => void) | $IntConsumer): void;
    }

    export interface $LongStream extends $BaseStream {
        allMatch(arg0: ((arg0: number) => boolean) | $LongPredicate): boolean;
        anyMatch(arg0: ((arg0: number) => boolean) | $LongPredicate): boolean;
        asDoubleStream(): $DoubleStream;
        average(): $OptionalDouble;
        boxed(): $Stream<number>;
        builder(): $LongStream$Builder;
        collect<R>(arg0: (() => R) | $Supplier<R>, arg1: ((arg0: R, arg1: number) => void) | $ObjLongConsumer<R>, arg2: ((arg0: R, arg1: R) => void) | $BiConsumer<R, R>): R;
        concat(arg0: $LongStream, arg1: $LongStream): $LongStream;
        count(): number;
        distinct(): $LongStream;
        dropWhile(arg0: ((arg0: number) => boolean) | $LongPredicate): $LongStream;
        empty(): $LongStream;
        filter(arg0: ((arg0: number) => boolean) | $LongPredicate): $LongStream;
        findAny(): $OptionalLong;
        findFirst(): $OptionalLong;
        flatMap(arg0: ((arg0: number) => $LongStream) | $LongFunction<$LongStream>): $LongStream;
        forEachOrdered(arg0: ((arg0: number) => void) | $LongConsumer): void;
        forEach(arg0: ((arg0: number) => void) | $LongConsumer): void;
        generate(arg0: (() => number) | $LongSupplier): $LongStream;
        iterate(arg0: number, arg1: ((arg0: number) => boolean) | $LongPredicate, arg2: ((arg0: number) => number) | $LongUnaryOperator): $LongStream;
        iterate(arg0: number, arg1: ((arg0: number) => number) | $LongUnaryOperator): $LongStream;
        iterator(): $PrimitiveIterator$OfLong;
        limit(arg0: number): $LongStream;
        mapMulti(arg0: ((arg0: number, arg1: $LongConsumer) => void) | $LongStream$LongMapMultiConsumer): $LongStream;
        mapToDouble(arg0: ((arg0: number) => number) | $LongToDoubleFunction): $DoubleStream;
        mapToInt(arg0: ((arg0: number) => number) | $LongToIntFunction): $IntStream;
        mapToObj<U>(arg0: ((arg0: number) => U) | $LongFunction<U>): $Stream<U>;
        map(arg0: ((arg0: number) => number) | $LongUnaryOperator): $LongStream;
        max(): $OptionalLong;
        min(): $OptionalLong;
        noneMatch(arg0: ((arg0: number) => boolean) | $LongPredicate): boolean;
        of(...arg0: number[]): $LongStream;
        of(arg0: number): $LongStream;
        parallel(): $LongStream;
        peek(arg0: ((arg0: number) => void) | $LongConsumer): $LongStream;
        rangeClosed(arg0: number, arg1: number): $LongStream;
        range(arg0: number, arg1: number): $LongStream;
        reduce(arg0: ((arg0: number, arg1: number) => number) | $LongBinaryOperator): $OptionalLong;
        reduce(arg0: number, arg1: ((arg0: number, arg1: number) => number) | $LongBinaryOperator): number;
        sequential(): $LongStream;
        skip(arg0: number): $LongStream;
        sorted(): $LongStream;
        spliterator(): $Spliterator$OfLong;
        summaryStatistics(): $LongSummaryStatistics;
        sum(): number;
        takeWhile(arg0: ((arg0: number) => boolean) | $LongPredicate): $LongStream;
        toArray(): number[];
    }

    export interface $LongStream$Builder extends $LongConsumer {
        accept(arg0: number): void;
        add(arg0: number): $LongStream$Builder;
        build(): $LongStream;
    }

    export interface $LongStream$LongMapMultiConsumer {
        accept(arg0: number, arg1: ((arg0: number) => void) | $LongConsumer): void;
    }

    export interface $Stream<T> extends $BaseStream {
        allMatch(arg0: ((arg0: T) => boolean) | $Predicate<any>): boolean;
        anyMatch(arg0: ((arg0: T) => boolean) | $Predicate<any>): boolean;
        builder<T>(): $Stream$Builder<T>;
        collect<R>(arg0: (() => R) | $Supplier<R>, arg1: ((arg0: R, arg1: T) => void) | $BiConsumer<R, any>, arg2: ((arg0: R, arg1: R) => void) | $BiConsumer<R, R>): R;
        collect<R, A>(arg0: $Collector<any, A, R>): R;
        concat<T>(arg0: $Stream<T>, arg1: $Stream<T>): $Stream<T>;
        count(): number;
        distinct(): $Stream<T>;
        dropWhile(arg0: ((arg0: T) => boolean) | $Predicate<any>): $Stream<T>;
        empty<T>(): $Stream<T>;
        filter(arg0: ((arg0: T) => boolean) | $Predicate<any>): $Stream<T>;
        findAny(): $Optional<T>;
        findFirst(): $Optional<T>;
        flatMapToDouble(arg0: ((arg0: T) => $DoubleStream) | $Function<any, $DoubleStream>): $DoubleStream;
        flatMapToInt(arg0: ((arg0: T) => $IntStream) | $Function<any, $IntStream>): $IntStream;
        flatMapToLong(arg0: ((arg0: T) => $LongStream) | $Function<any, $LongStream>): $LongStream;
        flatMap<R>(arg0: ((arg0: T) => $Stream<R>) | $Function<any, $Stream<R>>): $Stream<R>;
        forEachOrdered(arg0: ((arg0: T) => void) | $Consumer<any>): void;
        forEach(arg0: ((arg0: T) => void) | $Consumer<any>): void;
        generate<T>(arg0: (() => T) | $Supplier<T>): $Stream<T>;
        iterate<T>(arg0: T, arg1: ((arg0: T) => boolean) | $Predicate<any>, arg2: ((arg0: T) => T) | $UnaryOperator<T>): $Stream<T>;
        iterate<T>(arg0: T, arg1: ((arg0: T) => T) | $UnaryOperator<T>): $Stream<T>;
        limit(arg0: number): $Stream<T>;
        mapMultiToDouble(arg0: ((arg0: T, arg1: $DoubleConsumer) => void) | $BiConsumer<any, any>): $DoubleStream;
        mapMultiToInt(arg0: ((arg0: T, arg1: $IntConsumer) => void) | $BiConsumer<any, any>): $IntStream;
        mapMultiToLong(arg0: ((arg0: T, arg1: $LongConsumer) => void) | $BiConsumer<any, any>): $LongStream;
        mapMulti<R>(arg0: ((arg0: T, arg1: $Consumer<R>) => void) | $BiConsumer<any, any>): $Stream<R>;
        mapToDouble(arg0: ((arg0: T) => number) | $ToDoubleFunction<any>): $DoubleStream;
        mapToInt(arg0: ((arg0: T) => number) | $ToIntFunction<any>): $IntStream;
        mapToLong(arg0: ((arg0: T) => number) | $ToLongFunction<any>): $LongStream;
        map<R>(arg0: ((arg0: T) => R) | $Function<any, R>): $Stream<R>;
        max(arg0: $Comparator<any>): $Optional<T>;
        min(arg0: $Comparator<any>): $Optional<T>;
        noneMatch(arg0: ((arg0: T) => boolean) | $Predicate<any>): boolean;
        ofNullable<T>(arg0: T): $Stream<T>;
        of<T>(...arg0: T[]): $Stream<T>;
        of<T>(arg0: T): $Stream<T>;
        peek(arg0: ((arg0: T) => void) | $Consumer<any>): $Stream<T>;
        reduce(arg0: T, arg1: ((arg0: T, arg1: T) => T) | $BinaryOperator<T>): T;
        reduce<U>(arg0: U, arg1: ((arg0: U, arg1: T) => U) | $BiFunction<U, any, U>, arg2: ((arg0: U, arg1: U) => U) | $BinaryOperator<U>): U;
        reduce(arg0: ((arg0: T, arg1: T) => T) | $BinaryOperator<T>): $Optional<T>;
        skip(arg0: number): $Stream<T>;
        sorted(arg0: $Comparator<any>): $Stream<T>;
        sorted(): $Stream<T>;
        takeWhile(arg0: ((arg0: T) => boolean) | $Predicate<any>): $Stream<T>;
        toArray<A>(arg0: ((arg0: number) => A) | $IntFunction<A[]>): A[];
        toArray(): any[];
        toList(): $List<T>;
    }

    export interface $Stream$Builder<T> extends $Consumer {
        accept(arg0: T): void;
        add(arg0: T): $Stream$Builder<T>;
        build(): $Stream<T>;
    }

    export type $Collector$Characteristics_ = $Collector$Characteristics | "CONCURRENT" | "IDENTITY_FINISH" | "UNORDERED";
    export type $Stream_<T> = T[];
}
