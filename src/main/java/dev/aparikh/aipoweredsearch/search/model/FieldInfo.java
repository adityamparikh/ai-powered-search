package dev.aparikh.aipoweredsearch.search.model;

public record FieldInfo(
        String name,
        String type,
        boolean multiValued,
        boolean stored,
        boolean docValues,
        boolean indexed
) { }
