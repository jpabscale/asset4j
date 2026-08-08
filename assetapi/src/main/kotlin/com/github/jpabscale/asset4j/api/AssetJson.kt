// Copyright (c) 2026 jpabscale — original code (not part of the AssetsTools.NET port)
package com.github.jpabscale.asset4j.api

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper

internal object AssetJson {
    // Large Unity files carry multi-hundred-MB opaque base64 blobs; Jackson's default
    // 20MB string limit would reject them. Match uasset4j's permissive config.
    private val factory: JsonFactory = JsonFactory.builder()
        .streamReadConstraints(
            com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxStringLength(Int.MAX_VALUE)
                .maxNestingDepth(10_000)
                .build()
        )
        .build()

    val mapper: ObjectMapper = JsonMapper.builder(factory).build()
}
