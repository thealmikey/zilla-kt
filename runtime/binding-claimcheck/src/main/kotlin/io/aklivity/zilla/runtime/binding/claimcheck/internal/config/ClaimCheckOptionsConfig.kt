package io.aklivity.zilla.runtime.binding.claimcheck.internal.config

import io.aklivity.zilla.runtime.binding.claimcheck.config.*
import io.aklivity.zilla.runtime.binding.http.config.HttpRequestConfig

import io.aklivity.zilla.runtime.engine.config.OptionsConfig
import io.minio.MinioClient

class ClaimCheckOptionsConfig(
    val bucket: String,
    val endpoint: String,
    val accessKey: String,
    val secretKey: String
) : OptionsConfig()