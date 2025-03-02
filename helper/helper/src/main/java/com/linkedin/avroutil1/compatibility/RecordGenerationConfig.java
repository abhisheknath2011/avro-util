/*
 * Copyright 2021 LinkedIn Corp.
 * Licensed under the BSD 2-Clause License (the "License").
 * See License in the project root for license information.
 */

package com.linkedin.avroutil1.compatibility;

import java.util.Random;

/**
 * configuration for use with {@link RandomRecordGenerator}
 */
public class RecordGenerationConfig {
    private final long seed;
    private final Random random;
    /**
     * if true the generator will void emitting a null value whenever possible.
     * if a field is of type NULL, hence the only possible value is null, null will
     * still be emitted, but if a field is a union  [null, Anything, Else] one of the
     * other branches of the union would be selected (with equal probability)
     */
    private final boolean avoidNulls;
    /**
     * what subclass of {@link CharSequence} to use for generating strings, if
     * no other constraints are applicable. this will be used for both string
     * property values and map keys
     */
    private final StringRepresentation preferredStringRepresentation;

    private final Random randomToUse;

    public RecordGenerationConfig(
        long seed,
        Random random,
        boolean avoidNulls,
        StringRepresentation preferredStringRepresentation
        ) {
        this.seed = seed;
        this.random = random;
        this.avoidNulls = avoidNulls;
        this.preferredStringRepresentation = validate(preferredStringRepresentation, "preferredStringRepresentation");

        this.randomToUse = this.random != null ? this.random : new Random(this.seed);
    }

    public RecordGenerationConfig(RecordGenerationConfig toCopy) {
        if (toCopy == null) {
            throw new IllegalArgumentException("argument cannot be null");
        }
        this.seed = toCopy.seed;
        this.random = toCopy.random;
        this.avoidNulls = toCopy.avoidNulls;
        this.preferredStringRepresentation = toCopy.preferredStringRepresentation;

        this.randomToUse = this.random != null ? this.random : new Random(this.seed);
    }

    public Random random() {
        return randomToUse;
    }

    public StringRepresentation preferredStringRepresentation() {
        return preferredStringRepresentation;
    }

    /**
     * whether null values are avoided if/when possible (==in unions)
     * @return true if null values are avoided where possible
     */
    public boolean avoidNulls() {
        return avoidNulls;
    }

    // "builder-style" copy-setters

    public static RecordGenerationConfig newConfig() {
        //we provide seed and not Random so that copies of this config get their own random
        //instances. this is done so the defaults produce consistent behaviour even under MT use
        return new RecordGenerationConfig(System.currentTimeMillis(), null, false, StringRepresentation.Utf8);
    }

    public RecordGenerationConfig withSeed(long seed) {
        return new RecordGenerationConfig(
                seed,
                this.random,
                this.avoidNulls,
                this.preferredStringRepresentation
        );
    }

    public RecordGenerationConfig withRandom(Random random) {
        return new RecordGenerationConfig(
                this.seed,
                random,
                this.avoidNulls,
                this.preferredStringRepresentation
        );
    }

    public RecordGenerationConfig withAvoidNulls(boolean avoidNulls) {
        return new RecordGenerationConfig(
                this.seed,
                this.random,
                avoidNulls,
                this.preferredStringRepresentation
        );
    }

    public RecordGenerationConfig withPrefferedStringRepresentation(StringRepresentation preferredStringRepresentation) {
        return new RecordGenerationConfig(
            this.seed,
            this.random,
            this.avoidNulls,
            preferredStringRepresentation
        );
    }

    private StringRepresentation validate(StringRepresentation input, String propName) {
        if (input == null) {
            throw new IllegalArgumentException(propName + " is required");
        }
        switch (input) {
            case Utf8:
            case String:
                //these are fine
                break;
            default:
                throw new IllegalArgumentException(propName + " cannot be " + input);
        }
        return input;
    }
}
