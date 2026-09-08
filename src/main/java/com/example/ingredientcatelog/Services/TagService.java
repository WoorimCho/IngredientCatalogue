package com.example.ingredientcatelog.Services;

import com.example.ingredientcatelog.Dto.MergeRequest;
import com.example.ingredientcatelog.Dto.TagRequest;
import com.example.ingredientcatelog.Dto.TagResponse;
import com.example.ingredientcatelog.Model.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

public interface TagService {

    Page<TagResponse> list(String prefix, String namespace, Pageable pageable);

    TagResponse get(long id);

    TagResponse create(TagRequest request);

    TagResponse update(long id, TagRequest request);

    void delete(long id);

    TagResponse merge(MergeRequest request);

    /**
     * Normalise the given names and return managed {@link Tag} entities,
     * creating any that don't exist yet. Call this from within a transaction so
     * the returned entities stay attached.
     */
    Set<Tag> resolve(Collection<String> names);

    /** The single place tag names are canonicalised: trimmed, lower-cased. */
    static String normalise(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }
}
