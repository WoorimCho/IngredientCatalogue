package com.example.ingredientcatelog.Dto;

import com.example.ingredientcatelog.Model.Tag;

public record TagResponse(Long id, String name, String namespace, String description) {

    public static TagResponse from(Tag tag) {
        return new TagResponse(tag.getId(), tag.getName(), tag.getNamespace(), tag.getDescription());
    }
}
